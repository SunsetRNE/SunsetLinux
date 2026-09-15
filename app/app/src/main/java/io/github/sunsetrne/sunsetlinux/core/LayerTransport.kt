package io.github.sunsetrne.sunsetlinux.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import io.airlift.compress.zstd.ZstdInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * 层产物的**传输格式**（docs/architecture.md §5.2）。
 *
 * 注意：压缩产物只是"传输层"，`linuxctl update` 只接受**解压后的裸镜像**——
 * 它做 magic 检测（erofs=`0xE0F5E1E2`），拿到 `.zst`/`.gz` 必然报
 * "既不是 erofs 也不是 squashfs"。
 */
enum class LayerTransport(val wire: String, val suffix: String) {
    ZSTD("zstd", ".zst"),
    GZIP("gzip", ".gz"),
    /** 无后缀 / 已是裸镜像：原样拷贝。 */
    RAW("raw", "");

    companion object {
        /** 清单里的 `transport` 字段；缺失时按 URL 后缀推断（兼容早期清单）。 */
        fun from(wire: String?, url: String?): LayerTransport {
            when (wire?.trim()?.lowercase()) {
                "zstd", "zst" -> return ZSTD
                "gzip", "gz" -> return GZIP
                "raw", "none", "erofs" -> return RAW
            }
            val u = url?.lowercase() ?: return RAW
            return when {
                u.endsWith(".zst") -> ZSTD
                u.endsWith(".gz") -> GZIP
                else -> RAW
            }
        }
    }
}

/** 一次传输产物的完整描述（url/sha256/size 都是**压缩文件本身**的）。 */
data class LayerArtifact(
    val transport: LayerTransport,
    val url: String,
    val sha256: String?,
    val size: Long?,
) {
    /** 界面/日志里的一句话，例如 `zstd · 31.2 MiB`。 */
    val label: String get() = "${transport.wire} · ${formatBytes(size)}"
}

/** 解压结果。 */
data class DecompressOutcome(
    /** 解压后镜像的 sha256（流式计算，不额外读一遍文件）。 */
    val sha256: String,
    val size: Long,
)

/** 解压失败。`stage` 用于把"卡在哪一步"写进更新日志。 */
class LayerDecompressException(val stage: String, message: String, cause: Throwable? = null) :
    IOException("$stage：$message", cause)

/**
 * 把传输产物解压成裸镜像。**纯 Java，不引用任何 Android API**，
 * 因此可以直接在 JVM 单测里拿真实产物验证（见 `src/test/.../LayerDecompressorTest.kt`）。
 *
 * 两条路**同构**：都是从 `InputStream` 流式读、边读边写 `FileOutputStream`，
 * 只用一个 1 MiB 的堆内数组 —— **常量内存**，200 MB 级镜像也没有堆峰值。
 *
 * ★ 为什么 zstd 也必须是流式（真机事故，2026-09-16）：
 *   原先走 `ZstdDecompressor().decompress(ByteBuffer, ByteBuffer)` + mmap direct buffer，
 *   那条路在 Android 上会 **SIGSEGV**（crash dump：`art::Unsafe_getInt` ←
 *   `ZstdFrameDecompressor.verifyMagic`）—— aircompressor 用
 *   `UnsafeUtil.getAddress(buffer)` 取裸地址再 `Unsafe.getInt`，Android 上拿到的地址不可用。
 *   SIGSEGV 杀的是整个进程，`try/catch` 拦不住：`LauncherViewModel.init` 里的能力探测
 *   直接把 App 变成"一打开就闪退"。**桌面 JVM 上这条路径是好的**，所以只有真机会暴露。
 *   守住它的：`LayerTransportUnsafePathTest`（源码级契约，禁止 direct buffer / mmap 回来）。
 */
object LayerDecompressor {

    private const val COPY_BUFFER = 1 shl 20 // 1 MiB

    /**
     * @param expectRaw 清单里声明的解压后大小，**仅供参考**：它是**不会**用来预分配缓冲的
     *   （实测清单出现过笔误，拿它预分配会让解码在中途失败），真正的完整性判据是
     *   解压后镜像的 sha256，由调用方与 `sha256_raw` 比对。
     * @return 解压后镜像的 sha256 与实际大小
     */
    fun decompress(
        src: File,
        dst: File,
        transport: LayerTransport,
        expectRaw: Long?,
    ): DecompressOutcome = when (transport) {
        LayerTransport.GZIP -> decompressGzip(src, dst)
        LayerTransport.ZSTD -> decompressZstd(src, dst)
        LayerTransport.RAW -> copyRaw(src, dst)
    }

    // ---------------------------------------------------------------- gzip

    private fun decompressGzip(src: File, dst: File): DecompressOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            GZIPInputStream(FileInputStream(src).buffered(COPY_BUFFER), COPY_BUFFER).use { input ->
                FileOutputStream(dst).buffered(COPY_BUFFER).use { output ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        written += n
                    }
                }
            }
        } catch (t: Throwable) {
            dst.delete()
            throw LayerDecompressException("解压", "gzip 解压失败：${t.message ?: t.javaClass.simpleName}", t)
        }
        return DecompressOutcome(hex(digest.digest()), written)
    }

    // ---------------------------------------------------------------- zstd

    /**
     * zstd 解析走 aircompressor 的**流式**解码器（`ZstdInputStream`），与 gzip 路径完全同构：
     * 堆内 1 MiB 缓冲、常量内存、不碰 direct buffer（原因见 [LayerDecompressor] 的说明）。
     *
     * 为什么以前要用 mmap + direct buffer：想避免 200 MB 级镜像进堆。现在改成流式，
     * 内存更低（1 MiB）而且没有 direct buffer 那条会 SIGSEGV 的路。
     *
     * aircompressor 的窗口上限 `MAX_WINDOW_SIZE = 1 << 23`（8 MiB）：`zstd` CLI 默认
     * level≤19 的 windowLog 就是 23，所以默认产物可用；若发布侧用了 `--long`/`-22`
     * 等更大窗口，这里会抛异常 —— 调用方会自动退回 gzip 产物并把原因写进日志。
     */
    private fun decompressZstd(src: File, dst: File): DecompressOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            ZstdInputStream(FileInputStream(src).buffered(COPY_BUFFER)).use { input ->
                FileOutputStream(dst).buffered(COPY_BUFFER).use { output ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        written += n
                    }
                }
            }
        } catch (t: Throwable) {
            dst.delete()
            throw LayerDecompressException("解压", "zstd 解压失败：${t.message ?: t.javaClass.simpleName}", t)
        }
        return DecompressOutcome(hex(digest.digest()), written)
    }

    // ---------------------------------------------------------------- raw

    private fun copyRaw(src: File, dst: File): DecompressOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            FileInputStream(src).buffered(COPY_BUFFER).use { input ->
                FileOutputStream(dst).buffered(COPY_BUFFER).use { output ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        written += n
                    }
                }
            }
        } catch (t: Throwable) {
            dst.delete()
            throw LayerDecompressException("解压", "拷贝失败：${t.message ?: t.javaClass.simpleName}", t)
        }
        return DecompressOutcome(hex(digest.digest()), written)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * zstd 能力探测。
 *
 * **必须探测而不是"编译通过就认为可用"**：aircompressor 是纯 Java，但它的
 * direct-ByteBuffer 快路径依赖 `sun.misc.Unsafe` 取裸地址 —— 而那条路在 Android 上
 * 会 **SIGSEGV**（真机事故，见 [LayerDecompressor] 的说明）。所以：
 *
 * 1. 解码路径已经改成**只用堆内数组的流式解码**（`ZstdInputStream`），不碰 direct buffer；
 * 2. 这里仍然**探测**一遍，且走的是与生产**完全相同**的那条路 —— 将来若某台设备
 *    连堆内路径都不可用（比如窗口上限），我们要**当场知道**并改用 gzip 产物，
 *    而不是等用户更新到一半崩掉；
 * 3. 任何 Throwable 都视为不可用。⚠️ 但 SIGSEGV **不是 Throwable**：进程直接死，
 *    `catch` 拦不住 —— 所以真正的防线是"不碰那条路" + 源码级契约测试，
 *    这也是为什么 `runProbe` 必须一直跟生产路径同构。
 *
 * 探测方式：把一段真实的 zstd 帧（内嵌 base64，由 `zstd -19` 生成）走完整解码路径解出来比对原文。
 */
object TransportSupport {

    /**
     * 38 字节的 zstd 帧，解压后**必须**等于 [PROBE_TEXT]。
     *
     * ⚠️ 这两个常量是一对：改 [PROBE_TEXT] 就必须重新生成这里 —
     * `printf '%s' '<新文本>' | zstd -19 -c | base64 -w0`。
     * 只改文本会让探测永远报「解压大小不符」，从而在**能**跑 zstd 的设备上误退回
     * gzip（安全，但白等一倍下载量）。`LayerDecompressorTest` 有回归用例盯着这一对。
     */
    private const val PROBE_ZSTD_B64 = "KLUv/QRoyQAAc3Vuc2V0bGludXgtenN0ZC1wcm9iZS12MYdnOZI="
    private const val PROBE_TEXT = "sunsetlinux-zstd-probe-v1"

    @Volatile
    private var cached: String? = null

    @Volatile
    private var probed = false

    /**
     * zstd 是否可用。
     * @return null = 可用；否则是**给用户看的中文原因**（可写进更新日志）
     */
    fun zstdUnavailableReason(): String? {
        if (probed) return cached
        synchronized(this) {
            if (probed) return cached
            cached = runProbe()
            probed = true
            return cached
        }
    }

    fun zstdUsable(): Boolean = zstdUnavailableReason() == null

    /** 仅供测试/诊断：重置探测缓存。 */
    fun resetForTest() {
        synchronized(this) {
            probed = false
            cached = null
        }
    }

    /**
     * 真正跑探测。
     *
     * ⚠️ 两个容易踩的点，都记在这里免得被"顺手优化"掉：
     * 1. **`java.io.tmpdir` 在 Android 上可用**：libcore 里那个 `/tmp` 只是"给宿主用的兜底默认值"，
     *    App 进程启动时 `ActivityThread` 会把 `java.io.tmpdir` 指到**本应用自己的临时目录**
     *    （AOSP 原话："On Android, each app gets its own temporary directory"）。所以这里
     *    不需要额外传 `cacheDir`，也不该写死某个绝对路径。
     * 2. **这是磁盘 IO，不要在构造器/组合期调用**：调用方要用协程放到 IO 线程
     *    （`LauncherViewModel.init`、`UpdatePaneState.check` 就是这么做的）。
     *    结果会缓存，重复调用几乎零成本。
     */
    private fun runProbe(): String? {
        val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "sunsetlinux-zstd-probe")
        val src = File(dir, "probe.zst")
        val dst = File(dir, "probe.raw")
        return try {
            dir.mkdirs()
            val payload = java.util.Base64.getDecoder().decode(PROBE_ZSTD_B64)
            src.writeBytes(payload)
            val out = LayerDecompressor.decompress(src, dst, LayerTransport.ZSTD, PROBE_TEXT.length.toLong())
            when {
                out.size != PROBE_TEXT.length.toLong() ->
                    "zstd 探测失败：解压大小不符（${out.size}）"
                dst.readBytes().toString(Charsets.UTF_8) != PROBE_TEXT ->
                    "zstd 探测失败：内容不符"
                else -> null
            }
        } catch (t: Throwable) {
            "本机不支持 zstd 解压（${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}），将使用 gzip 产物"
        } finally {
            runCatching { src.delete() }
            runCatching { dst.delete() }
            runCatching { dir.delete() }
        }
    }
}
