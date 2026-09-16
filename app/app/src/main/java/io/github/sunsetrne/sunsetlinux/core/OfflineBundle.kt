package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 内置离线包（容器格式 `sunsetlinux-bundle` v1，layout `layers-split-v1`）。
 *
 * ## 这是什么
 *
 * 四个内置组合（minimal / ubuntu / ubuntu-proot / ubuntu-proot-dsh）各自的 APK 里，
 * 都带一份 `assets/offline-bundle.bin` —— 装完**不用联网**就能把环境铺起来。
 * 格式与打包工具见 `tools/offline-bundle/README.md`：
 *
 * ```
 * 偏移 0    4 字节   魔数 "SLB1"
 * 偏移 4    4 字节   头长度 N（小端 uint32）
 * 偏移 8    N 字节   头 JSON（UTF-8）：各部件 off/len/sha256 + 版本 + 变体名
 * 偏移 8+N  载荷      各部件字节按头里顺序原样拼接（off 相对载荷起点）
 * ```
 *
 * ## 为什么解析写在这个文件里、而且尽量纯
 *
 * [parse] 是纯函数（只吃字节数组），所以能在 JVM 单测里对着**合成容器**和**真产物**跑；
 * 而"读 assets / 落盘"这类 Android 相关的部分只有薄薄一层。
 */
object OfflineBundle {

    /** assets 里的固定文件名（组合由 `BuildConfig.EMBED_VARIANT` 决定，文件名不带版本）。 */
    const val ASSET_NAME = "offline-bundle.bin"

    private const val MAGIC = "SLB1"
    private const val HEADER_OFFSET = 8
    private const val FORMAT = "sunsetlinux-bundle"

    /** 一个部件：层（base/runtime/dsh）或 proot 运行时。 */
    data class Part(
        val kind: String,
        /** 层才有：`base` / `runtime` / `dsh`。 */
        val id: String?,
        /** 层才有：语义版本（`24.04.3-l1` / `1.0.0` / `0.1.5-rc.2`）。 */
        val version: String?,
        /** 层才有：`zstd` / `gzip`（决定用哪条解压路径）。 */
        val transport: String?,
        val file: String,
        val size: Long,
        val sha256: String,
        /** 层才有：解压后裸镜像的 sha256/size（App 解压完**当场核对**，不必等频道清单）。 */
        val sha256Raw: String?,
        val sizeRaw: Long?,
        /** 载荷内偏移（相对载荷起点）。 */
        val off: Long,
        val len: Long,
    ) {
        val isLayer: Boolean get() = kind == "layer"
        val human: String
            get() = when {
                isLayer -> "层 ${id} ${version}（${file}）"
                else -> "proot 运行时（$file）"
            }
    }

    data class Bundle(
        val variant: String,
        val layout: String,
        val baseVersion: String?,
        val runtimeVersion: String?,
        val dshVersion: String?,
        val parts: List<Part>,
        /** 整个容器的字节数。 */
        val size: Long,
        /** 载荷起点（= 8 + 头长度）；解包时按 `payloadStart + part.off` 取字节。 */
        val payloadStart: Long,
        /** 头里的生成时间（排障用）。 */
        val builtAt: String?,
    ) {
        val layers: List<Part> get() = parts.filter { it.isLayer }
        val hasProot: Boolean get() = parts.any { !it.isLayer }
    }

    class BundleFormatException(message: String) : IOException(message)

    /**
     * 解析容器头。**纯函数**：不碰 Android、不落盘。
     *
     * 容错策略：宁可在装之前明确报错，也不要把半个环境铺下去 —— 所以这里对
     * 魔数/长度/部件 sha256 形状都做硬校验（部件**内容**的校验留给 [verifyPart]）。
     */
    fun parse(bytes: ByteArray): Bundle {
        if (bytes.size < HEADER_OFFSET) throw BundleFormatException("离线包太小（${bytes.size} 字节），不是 bundle")
        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        if (magic != MAGIC) throw BundleFormatException("离线包魔数不对（读到 $magic，期望 $MAGIC）")
        val headerLen = readUInt32LE(bytes, 4)
        if (headerLen < 0 || HEADER_OFFSET + headerLen > bytes.size) {
            throw BundleFormatException("离线包头长度 $headerLen 超出文件大小（${bytes.size}）")
        }
        val headerText = String(bytes, HEADER_OFFSET, headerLen.toInt(), Charsets.UTF_8)
        val json = try {
            org.json.JSONObject(headerText)
        } catch (t: Throwable) {
            throw BundleFormatException("离线包头不是合法 JSON：${t.message}")
        }
        val format = json.optString("format")
        if (format != FORMAT) throw BundleFormatException("离线包 format=$format，期望 $FORMAT")

        val arr = json.optJSONArray("parts") ?: throw BundleFormatException("离线包头里没有 parts")
        val parts = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val file = o.optString("file").trim()
                val sha = o.optString("sha256").trim()
                val len = o.optLong("len", -1)
                val off = o.optLong("off", -1)
                if (file.isEmpty() || sha.length != 64 || len < 0 || off < 0) {
                    throw BundleFormatException("离线包第 ${i + 1} 个部件字段不全（file=$file len=$len off=$off）")
                }
                if (HEADER_OFFSET + headerLen + off + len > bytes.size) {
                    throw BundleFormatException("离线包部件 $file 越界（off=$off len=$len）")
                }
                add(
                    Part(
                        kind = o.optString("kind", "layer"),
                        id = o.optString("id").ifBlank { null },
                        version = o.optString("version").ifBlank { null },
                        transport = o.optString("transport").ifBlank { null },
                        file = file,
                        size = o.optLong("size", len),
                        sha256 = sha,
                        sha256Raw = o.optString("sha256_raw").ifBlank { null },
                        sizeRaw = if (o.has("size_raw") && !o.isNull("size_raw")) o.optLong("size_raw") else null,
                        off = off,
                        len = len,
                    ),
                )
            }
        }
        if (parts.isEmpty()) throw BundleFormatException("离线包里一个部件都没有")
        return Bundle(
            variant = json.optString("variant"),
            layout = json.optString("layout"),
            baseVersion = json.optString("base_version").ifBlank { null },
            runtimeVersion = json.optString("runtime_version").ifBlank { null },
            dshVersion = json.optString("dsh_version").ifBlank { null },
            parts = parts,
            size = bytes.size.toLong(),
            payloadStart = HEADER_OFFSET + headerLen,
            builtAt = json.optString("built_at").ifBlank { null },
        )
    }

    /** 只读头（不把整个包读进内存）：给"我这个包内嵌了什么"这类展示用。 */
    fun readHeaderOnly(context: Context): Bundle? = try {
        context.assets.open(ASSET_NAME).use { input ->
            val head = input.readNBytes(HEADER_OFFSET)
            if (head.size < HEADER_OFFSET) null else {
                val headerLen = readUInt32LE(head, 4)
                val rest = input.readNBytes(headerLen.toInt())
                parse(head + rest)
            }
        }
    } catch (_: IOException) {
        null
    }

    /** 把某个部件的字节**按范围**取出来（不整包读进内存，层动辄几十 MB）。 */
    fun readPart(context: Context, bundle: Bundle, part: Part): ByteArray =
        context.assets.open(ASSET_NAME).use { input ->
            val from = bundle.payloadStart + part.off
            skipFully(input, from)
            val buf = ByteArray(part.len.toInt())
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) throw BundleFormatException("离线包在读 ${part.file} 时提前结束（读到 $read/${buf.size}）")
                read += n
            }
            buf
        }

    /**
     * 把某个部件**流式**写到文件，返回写入内容的 sha256 十六进制。
     *
     * 为什么不是 [readPart] + [writePart]：层部件 200 MB+（dsh 层 189 MB、runtime 层 596 MB），
     * 整块进 `ByteArray` 在低端机上直接 OOM。这里 64 KiB 一块边读边写边算摘要，
     * 峰值内存与部件大小无关。
     *
     * @param onProgress 每块回调一次（done/total 都是字节），供界面显示"读内嵌包"进度。
     */
    fun copyPart(
        context: Context,
        bundle: Bundle,
        part: Part,
        dst: File,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ): String {
        dst.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        context.assets.open(ASSET_NAME).use { input ->
            skipFully(input, bundle.payloadStart + part.off)
            var left = part.len
            dst.outputStream().use { out ->
                while (left > 0) {
                    val want = minOf(left, buf.size.toLong()).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) {
                        throw BundleFormatException(
                            "离线包在读 ${part.file} 时提前结束（还差 $left 字节）",
                        )
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    left -= n
                    onProgress?.invoke(part.len - left, part.len)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 校验部件内容的 sha256（**装之前必须过这一关**）。 */
    fun verifyPart(part: Part, bytes: ByteArray): Boolean =
        part.sha256.lowercase() == sha256Hex(bytes)

    /** 把部件写到文件（调用方负责先 [verifyPart]）。 */
    fun writePart(part: Part, bytes: ByteArray, dst: File) {
        dst.parentFile?.mkdirs()
        dst.writeBytes(bytes)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readUInt32LE(bytes: ByteArray, at: Int): Long {
        if (at + 4 > bytes.size) throw BundleFormatException("离线包头被截断（读 $at 处的 4 字节）")
        return ((bytes[at].toLong() and 0xff)) or
            ((bytes[at + 1].toLong() and 0xff) shl 8) or
            ((bytes[at + 2].toLong() and 0xff) shl 16) or
            ((bytes[at + 3].toLong() and 0xff) shl 24)
    }

    private fun skipFully(input: java.io.InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                // skip 可能返回 0（例如流不支持）→ 退化成读一个字节
                if (input.read() < 0) throw BundleFormatException("离线包在读偏移 $n 时提前结束")
                left--
            } else {
                left -= skipped
            }
        }
    }
}
