package io.github.sunsetrne.sunsetlinux.core

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 内置离线包的读取回归。
 *
 * 离线包是"装完不联网就能起"的那份东西，读错一个字段的后果是**把半个环境铺下去**
 * 或者解出一个坏镜像 —— 所以这里既测合成容器（能构造各种畸形输入），
 * 也测**真产物**（`dist/bundles/ 下的 *.bin`，有就跑）。
 */
class OfflineBundleTest {

    // ─────────────────────────────────────── 合成容器

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { "%02x".format(it) }

    /** 手搓一个合法容器：格式见 tools/offline-bundle/README.md。 */
    private fun makeBundle(
        parts: List<Triple<String, String, ByteArray>>, // (kind/id 描述, file, payload)
        variant: String = "ubuntu-proot-dsh",
        magic: String = "SLB1",
    ): ByteArray {
        var off = 0L
        val jsonParts = parts.map { (desc, file, payload) ->
            val id = desc.substringBefore(':')
            val version = desc.substringAfter(':', "")
            val o = org.json.JSONObject()
                .put("kind", "layer")
                .put("id", id)
                .put("version", version)
                .put("transport", "zstd")
                .put("file", file)
                .put("size", payload.size.toLong())
                .put("sha256", sha(payload))
                .put("sha256_raw", "f".repeat(64))
                .put("size_raw", 123456L)
                .put("off", off)
                .put("len", payload.size.toLong())
            off += payload.size
            o
        }
        val header = org.json.JSONObject()
            .put("format", "sunsetlinux-bundle")
            .put("schema", 1)
            .put("layout", "layers-split-v1")
            .put("variant", variant)
            .put("built_at", "2026-09-16T00:00:00Z")
            .put("base_version", "24.04.3-l1")
            .put("runtime_version", "1.0.0")
            .put("dsh_version", "0.1.5-rc.2")
            .put("parts", org.json.JSONArray(jsonParts.map { it }))
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(magic.toByteArray(Charsets.US_ASCII))
        out.write(
            byteArrayOf(
                (headerBytes.size and 0xff).toByte(),
                ((headerBytes.size shr 8) and 0xff).toByte(),
                ((headerBytes.size shr 16) and 0xff).toByte(),
                ((headerBytes.size shr 24) and 0xff).toByte(),
            ),
        )
        out.write(headerBytes)
        parts.forEach { out.write(it.third) }
        return out.toByteArray()
    }

    private val p1 = "base-payload".repeat(40).toByteArray()
    private val p2 = "dsh-payload".repeat(20).toByteArray()

    @Test
    fun `合法容器：变体、版本、部件偏移与 sha 都对`() {
        val bytes = makeBundle(
            listOf(
                Triple("base:24.04.3-l1", "base-24.04.3-l1.erofs.zst", p1),
                Triple("dsh:0.1.5-rc.2", "dsh-0.1.5-rc.2.erofs.zst", p2),
            ),
        )
        val b = OfflineBundle.parse(bytes)
        assertEquals("ubuntu-proot-dsh", b.variant)
        assertEquals("layers-split-v1", b.layout)
        assertEquals("24.04.3-l1", b.baseVersion)
        assertEquals("1.0.5-rc.2".replace("1.0.5", "0.1.5"), b.dshVersion)
        assertEquals(2, b.parts.size)
        assertEquals(2, b.layers.size)
        assertFalse("这份里没有 proot", b.hasProot)

        val base = b.parts[0]
        assertEquals("base", base.id)
        assertEquals("zstd", base.transport)
        assertEquals(p1.size.toLong(), base.len)
        assertEquals("f".repeat(64), base.sha256Raw)
        assertEquals(123456L, base.sizeRaw)
        // 载荷能按范围取出来，且 sha 校验通过
        val got = bytes.copyOfRange((b.payloadStart + base.off).toInt(), (b.payloadStart + base.off + base.len).toInt())
        assertTrue(OfflineBundle.verifyPart(base, got))
        assertEquals(sha(p1), OfflineBundle.sha256Hex(got))
        // 第二个部件的偏移紧跟上第一个
        assertEquals(base.len, b.parts[1].off)
    }

    @Test
    fun `魔数不对要明确失败`() {
        val bytes = makeBundle(listOf(Triple("base:1", "b.erofs.zst", p1)), magic = "XXXX")
        val e = runCatching { OfflineBundle.parse(bytes) }.exceptionOrNull()
        assertTrue("应当是 BundleFormatException：$e", e is OfflineBundle.BundleFormatException)
        assertTrue("错误里要说清魔数不对：${e?.message}", e!!.message!!.contains("魔数"))
    }

    @Test
    fun `被截断的头与越界的部件都要失败`() {
        val bytes = makeBundle(listOf(Triple("base:1", "b.erofs.zst", p1)))
        // 砍掉后半段 → 部件越界
        val cut = bytes.copyOfRange(0, bytes.size - 10)
        val e1 = runCatching { OfflineBundle.parse(cut) }.exceptionOrNull()
        assertTrue("截断应当失败：$e1", e1 is OfflineBundle.BundleFormatException)
        // 只有 4 字节 → 太小
        assertTrue(runCatching { OfflineBundle.parse(bytes.copyOfRange(0, 4)) }.exceptionOrNull() is OfflineBundle.BundleFormatException)
    }

    @Test
    fun `部件 sha256 字段不合法要失败（宁可不装也不铺半个环境）`() {
        val bytes = makeBundle(listOf(Triple("base:1", "b.erofs.zst", p1)))
        val text = String(bytes, Charsets.ISO_8859_1)
        val broken = text.replace("\"sha256\":\"", "\"sha256\":\"x").toByteArray(Charsets.ISO_8859_1)
        val e = runCatching { OfflineBundle.parse(broken) }.exceptionOrNull()
        assertTrue("sha256 不合法应当失败：$e", e is OfflineBundle.BundleFormatException)
    }

    @Test
    fun `校验函数对错字节说不`() {
        val bytes = makeBundle(listOf(Triple("base:1", "b.erofs.zst", p1)))
        val b = OfflineBundle.parse(bytes)
        assertTrue(OfflineBundle.verifyPart(b.parts[0], p1))
        val tampered = p1.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse("改了一个字节就不该通过", OfflineBundle.verifyPart(b.parts[0], tampered))
    }

    // ─────────────────────────────────────── 真产物

    @Test
    fun `真离线包（dist-bundles 里有时）能被解析且部件校验通过`() {
        val dir = File(TestPaths.repoRoot, "dist/bundles")
        val bins = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.name } ?: emptyList()
        if (bins.isEmpty()) {
            println("（dist/bundles 里没有离线包，跳过 —— 先跑 tools/offline-bundle/mk-bundle.mjs --all）")
            return
        }
        for (bin in bins) {
            val bytes = bin.readBytes()
            val b = OfflineBundle.parse(bytes)
            assertTrue("${bin.name} 应当至少有一个部件", b.parts.isNotEmpty())
            for (p in b.parts) {
                val from = (b.payloadStart + p.off).toInt()
                val got = bytes.copyOfRange(from, from + p.len.toInt())
                assertTrue("${bin.name} 的 ${p.file} 内容与头里的 sha256 不符", OfflineBundle.verifyPart(p, got))
            }
            println("✓ ${bin.name}：变体 ${b.variant}，${b.parts.size} 个部件，${bytes.size / 1048576} MiB")
        }
    }

    @Test
    fun `只读头也能拿到变体与部件清单（真·headerOnly 路径）`() {
        // ★ 这条测试以前**名不副实**：它叫"只读头"，传进去的却是完整字节（makeBundle 的整包），
        //   所以 readHeaderOnly 真正走的那条路（只喂头部那几 KB）**一次都没被覆盖** ——
        //   真机事故（2026-09-19）就出在这里：readHeaderOnly 拿只有头部的字节去 parse，
        //   被"部件越界"判死，App 于是认为所有内嵌包档位都"没有内嵌离线包"。
        val full = makeBundle(listOf(Triple("base:1", "b.erofs.zst", p1), Triple("proot:", "p.zst", p2)))
        val head = full.copyOfRange(0, OfflineBundle.parse(full).payloadStart.toInt())

        // ① 整包语义下，只给头部**必须**判越界（这条防线保留：防止铺半个环境）
        val strict = runCatching { OfflineBundle.parse(head) }.exceptionOrNull()
        assertTrue("整包语义下只有头部应当判越界，实际：$strict", strict is OfflineBundle.BundleFormatException)

        // ② headerOnly 语义下，同样的字节要能正常解析出部件清单
        val b = OfflineBundle.parse(head, headerOnly = true)
        assertEquals("部件数量应当解析出来", 2, b.parts.size)
        assertEquals("base", b.parts[0].id)
        assertNotNull("builtAt 也要在", b.builtAt)
    }
}
