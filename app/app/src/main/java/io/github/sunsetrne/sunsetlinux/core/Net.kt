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

    /** 兼容 base64（标准/URL 变体）与 hex 两种公钥/签名写法。 */
    private fun decodeKeyLike(text: String): ByteArray? {
        val t = text.trim()
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
