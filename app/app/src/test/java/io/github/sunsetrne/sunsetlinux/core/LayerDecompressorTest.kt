package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **用真实层产物**验证传输层解压链路（不是 mock，也不是合成样本）。
 *
 * 覆盖的是 §5.2 消费方规则 3~4 的核心不变量：
 * `.erofs.zst` 与 `.erofs.gz` 两种传输产物解压后**必须得到同一个裸 erofs 镜像**，
 * 且该镜像的 sha256 等于清单里的 `sha256_raw`。
 *
 * 产物由发布侧放在 `dist/`，可用 `-Dsunsetlinux.dist.dir=<dir>` 覆盖。
 * 产物不在时整类**跳过**（assumeTrue），这样在别的机器上跑测试不会误报失败。
 *
 * 注意：这里的 zstd 走的是与 App 运行时**完全相同**的代码路径
 * （[LayerDecompressor] → aircompressor + mmap direct ByteBuffer）。
 */
class LayerDecompressorTest {

    // 产物目录同样由 TestPaths 解析（默认 <仓库根>/dist），不写死开发机绝对路径。
    private val distDir = io.github.sunsetrne.sunsetlinux.TestPaths.distDir
    private val zstFile = File(distDir, "dsh-0.1.5-rc.2.erofs.zst")
    private val gzFile = File(distDir, "dsh-0.1.5-rc.2.erofs.gz")

    /**
     * `dist/` 里的产物**正被发布侧持续重建**（实测 14:27 被同名重写、inode 变化）。
     * mmap 一个正在被替换的文件会读到损坏内容，所以测试先在私有目录固化一份副本，
     * 并校对副本的 sha256/size —— 这样并发重建不会造成假失败；
     * 而如果产物内容**真的**变了，这里会明确报错（而不是悄悄跳过）。
     */
    private fun stage(src: File, expectSha: String, expectSize: Long): File {
        val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "sunsetlinux-layer-decompress-test")
        dir.mkdirs()
        val dst = File(dir, "staged-${src.name}")
        repeat(3) {
            src.copyTo(dst, overwrite = true)
            if (dst.length() == expectSize && Digest.sha256Hex(dst) == expectSha) return dst
            Thread.sleep(300)
        }
        throw AssertionError(
            "产物 ${src.path} 与清单常量不一致：size=${dst.length()}（期望 $expectSize），" +
                "sha256=${Digest.sha256Hex(dst)}（期望 $expectSha）"
        )
    }

    private val zst: File by lazy { stage(zstFile, ZST_SHA256, ZST_SIZE) }
    private val gz: File by lazy { stage(gzFile, GZ_SHA256, GZ_SIZE) }

    /** 解压产物每个 201 MB，用完立刻删，避免多份同时在盘上。 */
    private fun <T> withTempOutput(name: String, block: (File) -> T): T {
        val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "sunsetlinux-layer-decompress-test")
        dir.mkdirs()
        val out = File(dir, name)
        try {
            return block(out)
        } finally {
            out.delete()
        }
    }

    // ─────────────────────────────────────────────── 真实产物验收

    @Test
    fun `gzip 产物解压得到清单声明的裸 erofs 镜像`() {
        assumeTrue("缺少真实产物 ${gzFile.path}，跳过", gzFile.isFile)

        assertEquals("压缩产物大小", GZ_SIZE, gz.length())
        assertEquals("压缩产物 sha256（= 清单 sha256_gz）", GZ_SHA256, Digest.sha256Hex(gz))

        withTempOutput("from-gzip.erofs") { out ->
            val result = LayerDecompressor.decompress(gz, out, LayerTransport.GZIP, RAW_SIZE)
            assertEquals("解压后大小（= 清单 size_raw）", RAW_SIZE, result.size)
            assertEquals("解压后 sha256（= 清单 sha256_raw）", RAW_SHA256, result.sha256)
        }
    }

    @Test
    fun `zstd 产物解压得到同一个裸 erofs 镜像`() {
        assumeTrue("缺少真实产物 ${zstFile.path}，跳过", zstFile.isFile)

        assertEquals("压缩产物大小", ZST_SIZE, zst.length())
        assertEquals("压缩产物 sha256（= 清单 sha256）", ZST_SHA256, Digest.sha256Hex(zst))

        withTempOutput("from-zstd.erofs") { out ->
            val result = LayerDecompressor.decompress(zst, out, LayerTransport.ZSTD, RAW_SIZE)
            assertEquals("解压后大小（= 清单 size_raw）", RAW_SIZE, result.size)
            assertEquals("解压后 sha256（= 清单 sha256_raw）", RAW_SHA256, result.sha256)
        }
    }

    /**
     * 最强的一条：两种传输产物**逐字节相同**。
     * 只比 sha256 也有可能同时错，所以这里再对内容做一次全量比对。
     */
    @Test
    fun `两种传输产物解压后逐字节一致`() {
        assumeTrue("缺少真实产物", zstFile.isFile && gzFile.isFile)

        withTempOutput("cmp-zstd.erofs") { fromZstd ->
            withTempOutput("cmp-gzip.erofs") { fromGzip ->
                LayerDecompressor.decompress(zst, fromZstd, LayerTransport.ZSTD, RAW_SIZE)
                LayerDecompressor.decompress(gz, fromGzip, LayerTransport.GZIP, RAW_SIZE)
                assertEquals("两条路径产出的镜像大小", fromGzip.length(), fromZstd.length())
                assertTrue("两条路径产出的镜像应当逐字节一致", filesEqual(fromZstd, fromGzip))
            }
        }
    }

    /** 无后缀 = 裸镜像：必须原样拷贝（且不改内容）。 */
    @Test
    fun `raw 传输按原样拷贝并计算 sha256`() {
        assumeTrue("缺少真实产物", gzFile.isFile)
        // 拿 gzip 文件当"裸镜像"来验证拷贝分支只做搬运
        withTempOutput("raw-copy.bin") { out ->
            val result = LayerDecompressor.decompress(gz, out, LayerTransport.RAW, gz.length())
            assertEquals(gz.length(), result.size)
            assertEquals(Digest.sha256Hex(gz), result.sha256)
            assertEquals(GZ_SHA256, result.sha256)
        }
    }

    /** 喂错传输格式必须**明确失败**，不能悄悄产出半截文件。 */
    @Test
    fun `用 gzip 解压 zstd 产物必须失败`() {
        assumeTrue("缺少真实产物", zstFile.isFile)
        withTempOutput("must-fail.erofs") { out ->
            val error = runCatching {
                LayerDecompressor.decompress(zst, out, LayerTransport.GZIP, RAW_SIZE)
            }.exceptionOrNull()
            assertNotNull("把 .zst 当 gzip 解压应当抛异常", error)
            assertTrue("失败时不应留下半截产物", !out.exists())
        }
    }

    /**
     * 回归：**清单的 `size_raw` 写错时，zstd 路径仍必须能解出正确镜像**。
     *
     * 背景：实测 `dist/` 那份产物的真实解压大小是 211259392，而需求/MANIFEST 样例里写的是
     * 211288064（201.5 MiB，像是估算值）。如果拿 `size_raw` 去预分配输出缓冲，一条元数据
     * 笔误就会让解压在写到一半时失败。所以输出尺寸必须以**产物自身帧头**为准。
     */
    @Test
    fun `size_raw 写错也不会让 zstd 解压失败`() {
        assumeTrue("缺少真实产物 ${zstFile.path}，跳过", zstFile.isFile)

        withTempOutput("wrong-size-raw.erofs") { out ->
            val wrongSizeRaw = 211288064L // 比真实值多 28672 字节
            val result = LayerDecompressor.decompress(zst, out, LayerTransport.ZSTD, wrongSizeRaw)
            assertEquals("仍应解出正确的真实大小", RAW_SIZE, result.size)
            assertEquals("仍应解出正确的 sha256_raw", RAW_SHA256, result.sha256)
        }
    }

    // ─────────────────────────────────────────────── 选产物规则（§5.2 规则 1）

    @Test
    fun `支持 zstd 时选主产物`() {
        val choice = sampleUpdate().choose(zstdUsable = true)
        assertEquals(LayerTransport.ZSTD, choice?.artifact?.transport)
        assertNull("选了主产物就不该有回退说明", choice?.note)
    }

    @Test
    fun `不支持 zstd 时选 gzip 回退产物并给出说明`() {
        val choice = sampleUpdate().choose(zstdUsable = false)
        assertEquals(LayerTransport.GZIP, choice?.artifact?.transport)
        assertEquals("未启用 zstd，将使用 gzip 产物", choice?.note)
    }

    @Test
    fun `不支持 zstd 且无 gzip 回退时仍然尝试主产物但明确告警`() {
        val choice = sampleUpdate(fallback = null).choose(zstdUsable = false)
        assertEquals(LayerTransport.ZSTD, choice?.artifact?.transport)
        assertNotNull("必须说明这是降级尝试", choice?.note)
    }

    @Test
    fun `两种产物都没有时明确返回 null 而不是静默跳过`() {
        assertNull(sampleUpdate(primary = null, fallback = null).choose(zstdUsable = true))
    }

    @Test
    fun `只有 gzip 回退产物时也能更新`() {
        val choice = sampleUpdate(primary = null).choose(zstdUsable = true)
        assertEquals(LayerTransport.GZIP, choice?.artifact?.transport)
        assertNotNull(choice?.note)
    }

    // ─────────────────────────────────────────────── 传输格式推断

    @Test
    fun `transport 字段缺失时按后缀推断`() {
        assertEquals(LayerTransport.ZSTD, LayerTransport.from(null, "dsh-1.0.erofs.zst"))
        assertEquals(LayerTransport.GZIP, LayerTransport.from(null, "dsh-1.0.erofs.gz"))
        assertEquals(LayerTransport.RAW, LayerTransport.from(null, "dsh-1.0.erofs"))
        assertEquals(LayerTransport.ZSTD, LayerTransport.from("zstd", "x"))
        assertEquals(LayerTransport.GZIP, LayerTransport.from("gzip", "x"))
        assertEquals(LayerTransport.RAW, LayerTransport.from("none", "x"))
    }

    // ─────────────────────────────────────────────── zstd 能力探测

    /**
     * 桌面 JVM 上探测必须通过（否则说明代码路径本身坏了）。
     * ⚠️ 这**不能**证明 Android 上可用 —— 设备侧能否用由运行时探测决定，
     * 不可用时 [UpdateApplier] 会自动改用 gzip 产物。
     */
    @Test
    fun `zstd 探测在桌面 JVM 上通过`() {
        TransportSupport.resetForTest()
        val reason = TransportSupport.zstdUnavailableReason()
        assertNull("桌面 JVM 上 zstd 探测失败：$reason", reason)
        assertTrue(TransportSupport.zstdUsable())
    }

    // ─────────────────────────────────────────────── helpers

    /** 分块比对两个大文件，避免把 2×201 MB 读进堆。 */
    private fun filesEqual(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        java.io.BufferedInputStream(java.io.FileInputStream(a), 1 shl 20).use { ia ->
            java.io.BufferedInputStream(java.io.FileInputStream(b), 1 shl 20).use { ib ->
                val bufA = ByteArray(1 shl 20)
                val bufB = ByteArray(1 shl 20)
                while (true) {
                    val na = ia.readNBytes(bufA, 0, bufA.size)
                    val nb = ib.readNBytes(bufB, 0, bufB.size)
                    if (na != nb) return false
                    if (na <= 0) return true
                    if (!bufA.copyOf(na).contentEquals(bufB.copyOf(nb))) return false
                }
            }
        }
    }

    private fun sampleUpdate(
        primary: LayerArtifact? = LayerArtifact(LayerTransport.ZSTD, "https://x/dsh.zst", "aa", ZST_SIZE),
        fallback: LayerArtifact? = LayerArtifact(LayerTransport.GZIP, "https://x/dsh.gz", "bb", GZ_SIZE),
    ) = LayerUpdate(
        id = "dsh",
        fromVersion = "0.1.5-rc.1",
        toVersion = "0.1.5-rc.2",
        sha256Raw = RAW_SHA256,
        sizeRaw = RAW_SIZE,
        primary = primary,
        fallback = fallback,
    )

    private companion object {
        const val ZST_SHA256 = "04ddce5f5edee710fc7466d46db40c70b06b11cbdc3d4bad0e2b9d8b9e25e820"
        const val ZST_SIZE = 32753059L
        const val GZ_SHA256 = "30333242466252e5b0099bf1e14a34003b1d58fb93c8541b20fd18e4c247c1d9"
        const val GZ_SIZE = 50146411L
        const val RAW_SHA256 = "d9674df77923341e39759750c36f57bb53e481296129dd4e1cb80864faa60c29"
        // 实测值（zstd -dc | wc -c 与 gzip -dc | wc -c 都是这个数）。
        // 注：需求描述里给的 211288064 = 201.5 MiB 是估算值，比真实大小多 28672 字节，
        // 这条差异已单独回报；这里以**产物本身**为准。
        const val RAW_SIZE = 211259392L
    }
}
