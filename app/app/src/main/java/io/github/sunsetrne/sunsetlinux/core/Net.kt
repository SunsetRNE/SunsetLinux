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
 * ★ 2026-09-19：**平台这条路在 Android 上根本走不通**（真机 + AOSP 源码双重确认）。
 *
 * 现场：真机（OnePlus PJD110 / Android 16 / App 0.3.18）报「签名校验失败，已拒绝该频道」，
 * 理由是 `InvalidKeySpecException：To generate a key pair in Android Keystore, use
 * KeyPairGenerator initialized with android.security.keystore.KeyGenParameterSpec`。
 * 逐层查下来是**原生 Android 的空缺，与厂商魔改无关**：
 *
 *   ① 那句文本来自 AOSP `AndroidKeyStoreKeyFactorySpi.engineGeneratePublic()` —— 源码里
 *      就是一句无条件 `throw`：AndroidKeyStore 只给自己生成的密钥当 KeyFactory。
 *   ② API 36 的 `AndroidKeyStoreProvider` 注册了 `KeyFactory.ED25519`（只注册
 *      KeyPairGenerator + KeyFactory，**没有** Signature）。
 *   ③ AOSP 的 Conscrypt（`OpenSSLProvider`）只注册 **RSA / EC / XDH** 三个 KeyFactory ——
 *      **没有任何 Ed25519 KeyFactory**；`OpenSslEdDsaKeyFactory` 只存在于上游
 *      google/conscrypt（2025 年新增），还没进 AOSP。设备上的 conscrypt.jar 里
 *      `eddsa` 出现 0 次（对照 `25519` 12 次）—— 与 AOSP 一致。
 *   ④ Android 自带的 BouncyCastle 是被裁剪过的：设备 `bouncycastle.jar` 里 `ed25519`
 *      0 次。
 *   ⑤ 平台的失败转移（JDK 9 的 delayed provider selection）确实在：`nextSpi` /
 *      `serviceIterator` 两个符号在设备 `/apex/com.android.art/javalib/core-oj.jar` 里都有。
 *      但转移完**仍然**抛出 AndroidKeyStore 那句 ⇒ 这台机器上没有任何 provider 能用
 *      SPKI 造出 Ed25519 公钥。
 *
 * ⇒ 结论：`KeyFactory.getInstance("Ed25519") + generatePublic(SPKI)` 在**任何 Android
 * 设备**（原生未改也一样）上都必然失败。而后果极重：频道**永远验签失败** ⇒
 * `UpdateChecker.merge()` 收不到任何 OK ⇒ 更新页落回「已是最新」—— 用户以为"没有更新"，
 * 其实"根本没查成"，这就是"用户自己更新不了"的根因。
 *
 * ⇒ 所以这里**三层**来做，且每一层都要**用公开测试向量真验一遍**才算数：
 *   ① 逐个点名候选 provider（Conscrypt / AndroidOpenSSL / 平台默认）—— 有的机型/将来的
 *      AOSP 会有能用的 EdDSA KeyFactory，能用就用它（快，走原生实现）；
 *   ② 都不行 ⇒ 用**随包的纯 Kotlin Ed25519**（[Ed25519]，只依赖 `MessageDigest("SHA-512")`，
 *      任何 Android 都有）—— 这一层保证"一定能验"；
 *   ③ 连自带实现都过不了自检 ⇒ 才算"本机没有可用实现"（那时是代码 bug，如实报出来）。
 *
 * 挑中的实现缓存起来，[verify] 与 [availability] 用**同一份**，不会再出现"探测说能用、
 * 验签时又抛"的错位；[describe] 会把实际用的是哪一层写给用户看（更新页显示）。
 */
object SignatureVerifier {

    /** SPKI 头（固定前缀 + 32 字节裸公钥）。 */
    private const val ED25519_SPKI_PREFIX_HEX = "302a300506032b6570032100"

    /**
     * 候选 provider，**按顺序试**；`null` = 让平台按默认顺序挑（放最后兜底）。
     *
     * Conscrypt 优先：Android 上将来若有 Ed25519 KeyFactory，最可能出现在它身上
     * （上游 `OpenSslEdDsaKeyFactory`）；`AndroidOpenSSL` 是它在老版本上的名字。
     */
    private val KEY_FACTORY_PROVIDERS: List<String?> = listOf("Conscrypt", "AndroidOpenSSL", null)
    private val SIGNATURE_PROVIDERS: List<String?> = listOf("Conscrypt", "AndroidOpenSSL", null)

    /**
     * 公开测试向量（自造，非机密）：只有它**真验通过**，才认为这一层可用。
     * 公钥/消息/签名都是十六进制。
     */
    private const val TV_PUB_HEX = "c2b74822f0c7ec5e8b064b8e5411453811648cd3b0452ba70717ca6113cdfd91"
    private const val TV_MSG_HEX = "73756e7365746c696e75782d656432353531392d70726f6265"
    private const val TV_SIG_HEX =
        "4cdb7cae5b3c7380777d0ed872717d6e6aed065f63fd0a078a29b0490035d0b901d31a349bb637f4f698075f42c19955e6a31a69ebd384997e6284fed4911d02"

    /** 一层能用的验签实现。 */
    internal interface Impl {
        /** 给用户看的一行（更新页"验签实现："那行）。 */
        val label: String

        /** 裸公钥（32 B）+ 消息 + 裸签名（64 B）。 */
        fun verify(rawPub: ByteArray, message: ByteArray, rawSig: ByteArray): Boolean
    }

    /** 平台 provider 组合（KeyFactory + Signature）。 */
    private class ProviderImpl(
        private val keyFactory: java.security.KeyFactory,
        private val signatureProvider: String?,
        override val label: String,
    ) : Impl {
        override fun verify(rawPub: ByteArray, message: ByteArray, rawSig: ByteArray): Boolean = try {
            val spki = hexToBytes(ED25519_SPKI_PREFIX_HEX) + rawPub
            val publicKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(spki))
            // `Signature` 不是线程安全的，每次验签都新建一个
            val verifier = if (signatureProvider == null) {
                java.security.Signature.getInstance("Ed25519")
            } else {
                java.security.Signature.getInstance("Ed25519", signatureProvider)
            }
            verifier.initVerify(publicKey)
            verifier.update(message)
            verifier.verify(rawSig)
        } catch (_: Throwable) {
            false
        }
    }

    /** 随包的纯 Kotlin 实现 —— 平台给不出 Ed25519 公钥时的兜底（见类注释）。 */
    private object BundledImpl : Impl {
        override val label = "内置 Ed25519（纯 Kotlin，不依赖平台 provider）"
        override fun verify(rawPub: ByteArray, message: ByteArray, rawSig: ByteArray): Boolean =
            Ed25519.verify(rawPub, message, rawSig)
    }

    private val picked: Result<Impl> by lazy {
        pick(KEY_FACTORY_PROVIDERS, SIGNATURE_PROVIDERS)
    }

    /**
     * 按"平台 provider → 自带实现"的顺序挑第一层能真的验过测试向量的；全都不行时失败
     * 信息里带**每一层为什么不行**（用户贴出来就能定位，不用猜）。
     *
     * `internal` 是给单测留的口子：坏 provider 那种形状要靠注入名字来复现，
     * 不能靠 JVM 的默认顺序碰运气；`bundledFallback=false` 用来单独测"平台全挂"那支。
     */
    internal fun pick(
        keyFactoryProviders: List<String?>,
        signatureProviders: List<String?>,
        bundledFallback: Boolean = true,
    ): Result<Impl> {
        val notes = mutableListOf<String>()
        val testSpki = hexToBytes(ED25519_SPKI_PREFIX_HEX + TV_PUB_HEX)
        val testMsg = hexToBytes(TV_MSG_HEX)
        val testSig = hexToBytes(TV_SIG_HEX)

        for (kfName in keyFactoryProviders) {
            val kf = try {
                if (kfName == null) {
                    java.security.KeyFactory.getInstance("Ed25519")
                } else {
                    java.security.KeyFactory.getInstance("Ed25519", kfName)
                }
            } catch (t: Throwable) {
                notes += "KeyFactory(${kfName ?: "默认"})：${t.javaClass.simpleName}"
                continue
            }
            val kfLabel = kf.provider.name + if (kfName == null) "(默认)" else ""
            val pub = try {
                kf.generatePublic(java.security.spec.X509EncodedKeySpec(testSpki))
            } catch (t: Throwable) {
                // 真机（以及任何原生 Android）就是死在这里：provider 取得到，但解不了 SPKI
                notes += "解公钥($kfLabel)：${t.javaClass.simpleName}"
                continue
            }
            // 与 KeyFactory 同名的 provider 先试（同一家实现最稳），其余按候选顺序
            val sigOrder = signatureProviders.sortedBy { if (it != null && it == kf.provider.name) 0 else 1 }
            for (sgName in sigOrder) {
                val sg = try {
                    if (sgName == null) {
                        java.security.Signature.getInstance("Ed25519")
                    } else {
                        java.security.Signature.getInstance("Ed25519", sgName)
                    }
                } catch (t: Throwable) {
                    notes += "Signature(${sgName ?: "默认"})：${t.javaClass.simpleName}"
                    continue
                }
                val label = "KeyFactory=$kfLabel / Signature=${sg.provider.name}" +
                    if (sgName == null) "(默认)" else ""
                val impl = ProviderImpl(kf, sgName, label)
                if (impl.verify(hexToBytes(TV_PUB_HEX), testMsg, testSig)) return Result.success(impl)
                notes += "验签($label)：测试向量没通过"
            }
        }

        // 平台这条路在 Android 上本来就走不通 ⇒ 兜底：随包的纯 Kotlin 实现（自己也要过自检）
        if (bundledFallback) {
            if (BundledImpl.verify(hexToBytes(TV_PUB_HEX), testMsg, testSig)) {
                return Result.success(BundledImpl)
            }
            notes += "内置纯 Kotlin 实现：测试向量没通过（这是代码 bug）"
        }
        return Result.failure(IllegalStateException(notes.joinToString("；")))
    }

    /** null = 本机可用；否则是给用户看的中文原因。 */
    fun availability(): String? = picked.exceptionOrNull()?.let { failure ->
        "本机没有可用的 Ed25519 验签实现，已按契约拒绝该频道" +
            "（逐层都不通：${failure.message}）"
    }

    /**
     * 实际用的是哪一层 —— 排障用（更新页会显示）。
     *
     * 为什么要有：真机上"验签失败"曾经只给一句结论，谁也判断不了是本机实现的问题
     * 还是清单的问题；把这一行写出来，下一次真机反馈就能一眼定性。
     */
    fun describe(): String = picked.fold(
        onSuccess = { it.label },
        onFailure = { "无可用实现（${it.message}）" },
    )

    fun verify(pubkeyEncoded: String, data: ByteArray, signatureEncoded: String): Boolean {
        val rawPub = decodeKeyLike(pubkeyEncoded) ?: return false
        val rawSig = decodeKeyLike(signatureEncoded) ?: return false
        if (rawPub.size != 32) return false
        if (rawSig.size != 64) return false

        val impl = picked.getOrNull() ?: return false
        return impl.verify(rawPub, data, rawSig)
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
