package io.github.sunsetrne.sunsetlinux.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** HTTPS 取数与下载。频道清单必须验签，因此这里只负责拿到**原始字节**。 */
object Http {

    private const val UA = "sunsetlinux-launcher/0.1 (Android)"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    class HttpFailure(message: String) : IOException(message)

    private fun open(url: String): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            useCaches = false
        }
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            conn.disconnect()
            throw HttpFailure("无法连接 ${shorten(url)}：${e.message ?: "网络不可达"}")
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw HttpFailure("${shorten(url)} 返回 HTTP $code${if (code == 404) "（文件不存在）" else ""}")
        }
        return conn
    }

    fun getBytes(url: String, maxBytes: Long = 8L * 1024 * 1024): ByteArray {
        val conn = open(url)
        return try {
            val len = conn.contentLengthLong
            if (len > maxBytes) throw HttpFailure("${shorten(url)} 超过大小上限（${formatBytes(len)}）")
            val buf = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw HttpFailure("${shorten(url)} 超过大小上限")
                    buf.write(chunk, 0, n)
                }
            }
            buf.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    fun getText(url: String, maxBytes: Long = 8L * 1024 * 1024): String =
        String(getBytes(url, maxBytes), Charsets.UTF_8)

    /**
     * 下载到 [dest]，边下边回调 (已下载, 总大小)。返回**流式计算出的 sha256 十六进制**，
     * 调用方负责与频道清单里的 sha256 比对（§5.4 第 3 步）。
     */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val conn = open(url)
        try {
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            dest.parentFile?.mkdirs()
            var done = 0L
            conn.inputStream.use { input ->
                dest.outputStream().buffered().use { output ->
                    val chunk = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        digest.update(chunk, 0, n)
                        output.write(chunk, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            dest.delete()
            throw HttpFailure("下载失败：${e.message ?: "连接中断"}")
        } finally {
            conn.disconnect()
        }
    }

    private fun shorten(url: String): String = url.removePrefix("https://").removePrefix("http://")
}

/** 摘要工具。 */
object Digest {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                digest.update(chunk, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * 频道清单签名校验（ed25519，见 §5.1）。
 *
 * 现实约束：Android 平台内置 ed25519 从 Android 13（API 33）才有；API 26~32 上
 * `KeyFactory.getInstance("Ed25519")` 会抛 NoSuchAlgorithmException。
 * 此时**不能静默放行**——[availability] 返回原因，调用方据此拒绝该频道并提示用户。
 */
object SignatureVerifier {

    /** SPKI 头（固定前缀 + 32 字节裸公钥）。 */
    private const val ED25519_SPKI_PREFIX_HEX = "302a300506032b6570032100"

    /** null = 本机可用；否则是给用户看的中文原因。 */
    fun availability(): String? = try {
        java.security.KeyFactory.getInstance("Ed25519")
        null
    } catch (_: Throwable) {
        "本机 Android 版本不支持 Ed25519 验签（需要 Android 13+），已按契约拒绝该频道"
    }

    fun verify(pubkeyEncoded: String, data: ByteArray, signatureEncoded: String): Boolean {
        val rawPub = decodeKeyLike(pubkeyEncoded) ?: return false
        val rawSig = decodeKeyLike(signatureEncoded) ?: return false
        if (rawPub.size != 32) return false
        if (rawSig.size != 64) return false

        val spki = hexToBytes(ED25519_SPKI_PREFIX_HEX) + rawPub
        val keyFactory = java.security.KeyFactory.getInstance("Ed25519")
        val publicKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(spki))
        val verifier = java.security.Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(data)
        return verifier.verify(rawSig)
    }

    /**
     * 验签**失败时**给出的一行"可核对证据"（进拒绝理由，显示给用户）。
     *
     * 为什么必须有：原先界面只有一句「签名校验失败」，用户把它贴过来谁也判断不了是
     * ① 清单与签名不配套（内容被改/缓存不同步）、② 签名文件根本不是签名（取到的是
     * 404 页面或被代理改写）、还是 ③ 公钥不是内嵌的那把。三种原因的处置完全不同。
     *
     * 指纹写法与 `docs/HANDOFF.md` 公开的那行**完全一致**（`ed25519:` + 公钥前 8 字节
     * 十六进制），所以用户可以直接拿它跟文档对照 —— 对不上就是"频道换了钥匙"，
     * 对得上就是"这次取到的清单/签名有问题"。
     */
    fun diagnose(pubkeyEncoded: String, data: ByteArray, signatureEncoded: String): String {
        val rawPub = decodeKeyLike(pubkeyEncoded)
        val rawSig = decodeKeyLike(signatureEncoded)
        val pubNote = when {
            rawPub == null -> "公钥解不出来（${pubkeyEncoded.trim().length} 字符，应为 32 字节的 base64/hex）"
            rawPub.size != 32 -> "公钥解出来是 ${rawPub.size} 字节（ed25519 应为 32）"
            else -> "公钥指纹 ${fingerprint(rawPub)}"
        }
        val sigNote = when {
            rawSig == null ->
                "签名文件解不出来（${signatureEncoded.trim().length} 字符：取到的可能不是 .sig，而是错误页面/被代理改写）"
            rawSig.size != 64 -> "签名解出来是 ${rawSig.size} 字节（ed25519 应为 64）"
            else -> "签名 64 字节正常"
        }
        return "清单 ${data.size} B sha256=${Digest.sha256Hex(data).take(16)}…；$pubNote；$sigNote；" +
            "签名与该公钥 + 该清单不匹配"
    }

    /**
     * 公钥指纹，写法与**发布工具**（`tools/channel/common.mjs` 的 `publicKeyFingerprint`）
     * 以及文档里公开的那行**完全一致**：`ed25519:` + `sha256(公钥 32 字节)` 的**前 8 字节**十六进制。
     * 例：`ed25519:06:d0:c4:4d:29:1c:ef:66`（内置官方频道那把）。
     *
     * ⚠️ 它**不是**"公钥的前 8 字节"。第一版就是这么写的，写完立刻被自己的单测抓住：
     * 那样算出来是 `ed25519:61:7a:00:73:…`，跟文档/发布工具对不上 —— 用户拿这个"证据"去核对
     * 只会更迷惑（比不给证据更糟）。
     */
    fun fingerprint(rawPub: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(rawPub)
        return "ed25519:" + digest.take(8).joinToString(":") { "%02x".format(it) }
    }

    /** 兼容 base64（标准/URL 变体）与 hex 两种公钥/签名写法。 */
    private fun decodeKeyLike(text: String): ByteArray? {        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.length >= 64 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } && t.length % 2 == 0) {
            return runCatching { hexToBytes(t) }.getOrNull()
        }
        return runCatching { java.util.Base64.getDecoder().decode(t) }.getOrNull()
            ?: runCatching { java.util.Base64.getUrlDecoder().decode(t) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }
}
