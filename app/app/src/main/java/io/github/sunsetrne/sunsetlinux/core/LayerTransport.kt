package io.github.sunsetrne.sunsetlinux.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
 * 两条路的内存策略：
 * - gzip：`GZIPInputStream` 流式边读边写，**常量内存**。
 * - zstd：aircompressor（纯 Java 解码器）+ **输入/输出都用 mmap 的 direct ByteBuffer**，
 *   解码器只写堆外内存，堆内只有约 128 KB 工作区 —— 200 MB 级镜像不会造成堆峰值。
 */
object LayerDecompressor {

    private const val COPY_BUFFER = 1 shl 20 // 1 MiB

    /**
     * @param expectRaw 期望的解压后大小；null/≤0 时尝试从 zstd 帧头读取。
     * @return 解压后镜像的 sha256 与实际大小（由调用方与 `sha256_raw` 比对）
     */
    fun decompress(
        src: File,
        dst: File,
        transport: LayerTransport,
        expectRaw: Long?,
    ): DecompressOutcome = when (transport) {
        LayerTransport.GZIP -> decompressGzip(src, dst)
        LayerTransport.ZSTD -> decompressZstd(src, dst, expectRaw)
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
     * zstd 解码走 aircompressor（`io.airlift:aircompressor`，纯 Java，无 native）。
     *
     * 为什么不用 `com.github.luben:zstd-jni`：它的 jar 里只有 aix/darwin/freebsd/linux/win
     * 的原生库，**没有任何 Android ABI**；它的 `linux/aarch64/libzstd-jni.so` 是 glibc 链接的，
     * 在 Android 的 bionic 上无法 dlopen —— 能编译、能在桌面 JVM 上跑通，但到设备上必
     * `UnsatisfiedLinkError`。纯 Java 解码器没有这个问题，代价是窗口上限 8 MiB（见下）。
     *
     * 已知限制：aircompressor 的 `MAX_WINDOW_SIZE = 1 << 23`（8 MiB）。`zstd` CLI 默认
     * level≤19 的 windowLog 就是 23，所以默认产出的产物可用；若发布侧用了 `--long`/`-22`
     * 等更大窗口，这里会抛异常 —— 调用方会**自动退回 gzip 产物**并把原因写进日志。
     */
    private fun decompressZstd(src: File, dst: File, expectRaw: Long?): DecompressOutcome {
        // **产物自己的帧头是权威尺寸来源**：清单里的 size_raw 只是提示，可能是笔误
        // （实测 dist 里那份产物的真实解压大小是 211259392，而清单样例写的是 211288064）。
        // 因此先按帧头尺寸分配输出，再让调用方去比对 size_raw —— 绝不能反过来，
        // 否则一条笔误就会让 zstd 路径在解码中途失败。
        val frameSize = readHeaderBytes(src, 64)?.let { header ->
            try {
                io.airlift.compress.zstd.ZstdDecompressor.getDecompressedSize(header, 0, header.size)
            } catch (_: Throwable) {
                -1L
            }
        } ?: -1L

        val declaredSize = expectRaw?.takeIf { it > 0 } ?: -1L
        // 分配用两者较大值（防止清单偏小时把解码器的输出写穿），
        // 但**校验用帧头值**：帧头才是产物自身的真相。
        val allocSize = maxOf(frameSize, declaredSize)
        val expectedSize = if (frameSize > 0) frameSize else declaredSize
        if (allocSize <= 0 || expectedSize <= 0) {
            throw LayerDecompressException("解压", "无法确定解压后镜像大小（帧头无 content size 且清单缺 size_raw）")
        }

        var inChannel: FileChannel? = null
        var outRaf: RandomAccessFile? = null
        try {
            inChannel = FileChannel.open(src.toPath(), java.nio.file.StandardOpenOption.READ)
            val input: ByteBuffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, src.length())

            // 先把输出文件撑到目标大小，再 mmap：解码器直接写堆外内存
            outRaf = RandomAccessFile(dst, "rw").apply {
                setLength(0)
                setLength(allocSize)
            }
            val output: java.nio.MappedByteBuffer = outRaf.channel.map(FileChannel.MapMode.READ_WRITE, 0, allocSize)

            io.airlift.compress.zstd.ZstdDecompressor().decompress(input, output)

            val written = output.position().toLong()
            output.force()
            if (written != expectedSize) {
                throw LayerDecompressException(
                    "解压",
                    "解压后大小与产物帧头不符：实际 $written 字节，帧头声明 $expectedSize 字节" +
                        if (declaredSize > 0 && declaredSize != expectedSize) {
                            "（清单 size_raw=$declaredSize 与帧头不一致，已按帧头为准）"
                        } else {
                            ""
                        },
                )
            }
            // 文件实际长度按真实解码结果收敛，避免比镜像长的尾巴
            outRaf.setLength(written)
        } catch (t: LayerDecompressException) {
            dst.delete()
            throw t
        } catch (t: Throwable) {
            dst.delete()
            throw LayerDecompressException(
                "解压",
                "zstd 解压失败（${t.javaClass.simpleName}: ${t.message ?: "无详情"}）",
                t,
            )
        } finally {
            runCatching { inChannel?.close() }
            runCatching { outRaf?.close() }
        }

        // 解压成功后再读一遍算 sha256：这一步是顺序读，代价可控，换来内存零峰值
        return DecompressOutcome(Digest.sha256Hex(dst), dst.length())
    }

    /** 只读文件头若干字节（避免为了拿帧头把 30 MB 全读进内存）。 */
    private fun readHeaderBytes(src: File, n: Int): ByteArray? = try {
        FileInputStream(src).use { input ->
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            if (off == 0) null else buf.copyOf(off)
        }
    } catch (_: Throwable) {
        null
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
 * **必须探测而不是"编译通过就认为可用"**：纯 Java 解码器依赖 `sun.misc.Unsafe` 去取
 * direct ByteBuffer 的裸地址，这在 Android 上属于"平台有但不在公开 API 里"的灰色地带；
 * 一旦设备上不可用，我们要**当场知道**并改用 gzip 产物，而不是等用户更新到一半崩掉。
 *
 * 探测方式：把一段真实的 zstd 帧（内嵌 base64，由 `zstd -19` 生成）走**完整解码路径**
 * （含 mmap + direct ByteBuffer）解出来比对原文。任何 Throwable 都视为不可用。
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
