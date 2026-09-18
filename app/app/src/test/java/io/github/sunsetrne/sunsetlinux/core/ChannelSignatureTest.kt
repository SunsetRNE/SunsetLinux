package io.github.sunsetrne.sunsetlinux.core

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * **信任根的行为级回归**：内置公钥到底能不能验真实发布过的清单。
 *
 * ## 为什么需要它（此前是零覆盖）
 *
 * 在 2026-09-17 的真机上，App 报过「签名校验失败，已拒绝该频道」。这条链路此前
 * **一行测试都没有** —— `OfficialChannelContractTest` 只比对"Prefs.kt 里的公钥文本
 * 与 HANDOFF 里写的那行是否一致"（**文本**契约），而"这把钥匙真的能验那份清单"
 * （**行为**契约）没人验。文本一致但解出来不是 32 字节、或指纹对不上、或签名文件
 * 其实是 404 页面，都照样能过文本契约。
 *
 * 所以这里放一份**真实发布过的**清单快照（`testdata/channel/`，与 gh-pages 上的
 * `/channel/` 逐字节一致），用真代码去验它。夹具是快照，签名对固定字节永远有效，
 * 不会随下一次发布失效（下次发布只会新增一份，不必替换它）。
 *
 * ⚠️ 反过来也成立：**改内置公钥 / 改指纹写法的任何一个字节，这里必须红**。
 */
class ChannelSignatureTest {

    private val fixtureDir = File(TestPaths.repoRoot, "testdata/channel")
    private val manifest: ByteArray = File(fixtureDir, "channel.json").readBytes()
    private val signature: String = File(fixtureDir, "channel.json.sig").readText().trim()

    /** 文档里公开的那行指纹（docs/HANDOFF.md §〇「内置官方频道的公开指纹」）。 */
    private val documentedFingerprint = "ed25519:06:d0:c4:4d:29:1c:ef:66"

    @Test
    fun `内置公钥能验真实的已发布清单`() {
        assertTrue("夹具本身要先像那么回事（这是个 JSON 清单）", manifest.size > 200)
        assertTrue(
            "内置公钥验不过真实发布的清单 —— 要么公钥被改，要么 .sig 取错了文件",
            SignatureVerifier.verify(Channel.OFFICIAL.pubkey, manifest, signature),
        )
    }

    @Test
    fun `内置公钥的指纹与文档公开的那行一致`() {
        val raw = Base64.getDecoder().decode(Channel.OFFICIAL.pubkey)
        assertEquals("公钥必须是 32 字节（ed25519）", 32, raw.size)
        assertEquals(documentedFingerprint, SignatureVerifier.fingerprint(raw))
    }

    @Test
    fun `清单被改一个字节就必须拒绝，且理由里带得出可核对的证据`() {
        val tampered = manifest.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(
            "改了清单还能验过 = 验签根本没生效",
            SignatureVerifier.verify(Channel.OFFICIAL.pubkey, tampered, signature),
        )
        val reason = SignatureVerifier.diagnose(Channel.OFFICIAL.pubkey, tampered, signature)
        // 指纹：用户拿它跟文档对照（对得上 ⇒ 是清单/签名这一份的问题；对不上 ⇒ 换了钥匙）
        assertTrue("理由里必须带公钥指纹（照文档的写法）：$reason", reason.contains(documentedFingerprint))
        // 尺寸与摘要：让"清单被改过/取到别的版本"看得出来
        assertTrue("理由里必须带清单字节数：$reason", reason.contains("清单 ${tampered.size} B"))
        assertTrue("理由里必须带清单摘要：$reason", reason.contains("sha256="))
        assertTrue("签名本身正常时要明说（否则会误判成签名坏了）：$reason", reason.contains("签名 64 字节正常"))
    }

    @Test
    fun `签名文件根本不是签名时要直说，而不是含糊的不匹配`() {
        val html = "<html><body>404 Not Found</body></html>"
        assertFalse(SignatureVerifier.verify(Channel.OFFICIAL.pubkey, manifest, html))
        val reason = SignatureVerifier.diagnose(Channel.OFFICIAL.pubkey, manifest, html)
        assertTrue("必须指出取到的不是签名（404 页面/被代理改写）：$reason", reason.contains("签名文件解不出来"))
    }

    @Test
    fun `公钥长度不对要被指出来`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(3))
        assertFalse(SignatureVerifier.verify(short, manifest, signature))
        val reason = SignatureVerifier.diagnose(short, manifest, signature)
        assertTrue("必须指出公钥字节数不对：$reason", reason.contains("公钥解出来是 3 字节"))
    }

    @Test
    fun `换一把公钥时指纹必须跟着变（指纹不是写死的字符串）`() {
        val other = SignatureVerifier.fingerprint(ByteArray(32))
        assertFalse(SignatureVerifier.verify(Base64.getEncoder().encodeToString(ByteArray(32)), manifest, signature))
        assertTrue("全零公钥的指纹不该等于官方指纹", other != documentedFingerprint)
        assertTrue(
            "指纹形状应是 ed25519: + 8 组十六进制（与发布工具同口径）：$other",
            Regex("^ed25519(:[0-9a-f]{2}){8}$").matches(other),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2026-09-19 真机（Android 16）回归：默认顺序取到的 Ed25519 实现**用不了**
    //
    // 现场：`KeyFactory.getInstance("Ed25519")` 取得到，但 `generatePublic(SPKI)` 抛
    // InvalidKeySpecException，消息就是平台 AndroidKeyStore 那句（文本可在设备
    // /system/framework/framework.jar 里搜到）⇒ 频道永远验签失败 ⇒ 更新页显示"已是最新"。
    //
    // 下面用一个**模拟 AndroidKeyStore 的坏 provider**把那个形状搬进单测：它注册了
    // "Ed25519"，但解不了 SPKI。验签器必须跳过它、退到能用的实现，而不是整体失败。
    // ─────────────────────────────────────────────────────────────────────────

    /** 只在测试里注册一次（进程级）。 */
    private val brokenProvider = BrokenKeyStoreProvider()

    private fun brokenProviderName(): String {
        val name = brokenProvider.name
        if (java.security.Security.getProvider(name) == null) {
            java.security.Security.addProvider(brokenProvider)
        }
        return name
    }

    /** 能用的那个（把 JVM 上的 SunEC 包一层，扮演真机上的 Conscrypt）。 */
    private fun goodProviderName(): String {
        val name = "SunsetGoodKS"
        if (java.security.Security.getProvider(name) == null) {
            java.security.Security.addProvider(GoodProvider())
        }
        return name
    }

    /**
     * 坏 provider 排在候选**最前面**时必须被跳过、挑中后面那个能用的，且能验过真实清单。
     *
     * ⚠️ 这里**不**断言"默认顺序的写法会挂"：JVM 的 `KeyFactory` 自带失败转移
     * （JDK 9 的 delayed provider selection，设备上也有 —— `nextSpi`/`serviceIterator`
     * 两个符号在 `/apex/com.android.art/javalib/core-oj.jar` 里），坏 provider 后面还有
     * SunEC 顶着，所以"默认顺序"在 JVM 上永远不会失败。真机上之所以挂，是因为
     * **一个能用的 provider 都没有**（AOSP 只注册 RSA/EC/XDH 三个 KeyFactory）——
     * 那个形状由下面"平台一层都不可用"那条用例覆盖。
     */
    @Test
    fun `坏 provider 排在最前面时，点名候选必须跳过它挑中能用的那个`() {
        // 先坐实进程级缓存，免得这段临时改动影响别的用例（缓存的是一次性的 lazy）
        assertEquals(null, SignatureVerifier.availability())
        val broken = brokenProviderName()
        val good = goodProviderName()
        // ⚠️ `insertProviderAt` 对**已安装**的 provider 会直接返回 -1（不动位置）——
        //    所以必须先摘掉再插到第一位，否则夹具根本没生效。
        java.security.Security.removeProvider(broken)
        assertEquals("坏 provider 必须真的被插到第一位", 1, java.security.Security.insertProviderAt(brokenProvider, 1))
        try {
            val impl = SignatureVerifier.pick(listOf(broken, good, null), listOf(broken, good, null)).getOrElse {
                throw AssertionError("点名候选后仍然挑不出可用实现：${it.message}")
            }
            assertFalse("坏 provider 不该被选中：${impl.label}", impl.label.contains(broken))
            assertTrue("应当挑中能用的那个：${impl.label}", impl.label.contains(good))
            assertTrue("挑中的实现必须能验真实清单", verifyWith(impl, Channel.OFFICIAL.pubkey, manifest, signature))
            assertFalse(
                "改一个字节就该验不过（否则等于没验）",
                verifyWith(impl, Channel.OFFICIAL.pubkey, manifest.copyOf().also { it[0] = (it[0] + 1).toByte() }, signature),
            )
        } finally {
            java.security.Security.removeProvider(broken)
        }
    }

    @Test
    fun `平台一层都不可用时，必须退到随包的纯 Kotlin 实现并照样验过真实清单`() {
        val broken = brokenProviderName()
        // 只给坏 provider、且关掉兜底 ⇒ 这就是"平台全挂"（= 真机上真实发生的事）
        assertTrue(
            "平台全挂时不该假装成功",
            SignatureVerifier.pick(listOf(broken), listOf(broken), bundledFallback = false).isFailure,
        )
        // 打开兜底（= 真机上的实际配置）：必须挑到内置实现，而且真的能验过线上那份清单
        val impl = SignatureVerifier.pick(listOf(broken), listOf(broken)).getOrElse {
            throw AssertionError("兜底也没顶上：${it.message}")
        }
        assertTrue("应当退到内置实现：${impl.label}", impl.label.contains("内置 Ed25519"))
        assertTrue("内置实现必须能验真实清单", verifyWith(impl, Channel.OFFICIAL.pubkey, manifest, signature))
        assertFalse(
            "改一个字节就该验不过（否则等于没验）",
            verifyWith(impl, Channel.OFFICIAL.pubkey, manifest.copyOf().also { it[0] = (it[0] + 1).toByte() }, signature),
        )
    }

    @Test
    fun `所有候选都不能用时必须说清楚，而不是含糊地失败`() {
        val broken = brokenProviderName()
        val result = SignatureVerifier.pick(listOf(broken), listOf(broken), bundledFallback = false)
        assertTrue("全都不可用就该是失败", result.isFailure)
        val note = result.exceptionOrNull()!!.message ?: ""
        assertTrue("理由里要点出是哪个 provider、哪一步不行：$note", note.contains(broken) && note.contains("解公钥"))
    }

    @Test
    fun `本机的验签实现必须自证可用，并说得出用的是谁`() {
        assertEquals("验签必须可用（平台给不出 Ed25519 公钥时就该退到内置实现）", null, SignatureVerifier.availability())
        val label = SignatureVerifier.describe()
        assertTrue("要能说出实际用的实现（真机排障就靠这一行）：$label", label.isNotBlank())
    }

    /** 用挑中的那一层验一遍（与 [SignatureVerifier.verify] 同一条路径，只是绕过进程级缓存）。 */
    private fun verifyWith(impl: SignatureVerifier.Impl, pubkey: String, data: ByteArray, sig: String): Boolean =
        impl.verify(Base64.getDecoder().decode(pubkey), data, Base64.getDecoder().decode(sig))

    /** 模拟平台的 AndroidKeyStore：名字答得上来，密钥解不出来。 */
    class BrokenKeyStoreProvider : java.security.Provider("SunsetBrokenKS", 1.0, "模拟 AndroidKeyStore 的 Ed25519 行为") {
        init {
            put("KeyFactory.Ed25519", BrokenKeyFactorySpi::class.java.name)
        }
    }

    class BrokenKeyFactorySpi : java.security.KeyFactorySpi() {
        override fun engineGeneratePublic(keySpec: java.security.spec.KeySpec): java.security.PublicKey =
            throw java.security.spec.InvalidKeySpecException(
                "To generate a key pair in Android Keystore, use KeyPairGenerator initialized with " +
                    "android.security.keystore.KeyGenParameterSpec",
            )

        override fun engineGeneratePrivate(keySpec: java.security.spec.KeySpec): java.security.PrivateKey =
            throw java.security.spec.InvalidKeySpecException("not supported")

        override fun <T : java.security.spec.KeySpec?> engineGetKeySpec(
            key: java.security.Key?,
            keySpec: Class<T>?,
        ): T = throw java.security.spec.InvalidKeySpecException("not supported")

        override fun engineTranslateKey(key: java.security.Key?): java.security.Key =
            throw java.security.spec.InvalidKeySpecException("not supported")
    }

    /**
     * 扮演真机上那个**能用的** provider（Conscrypt）：把 JVM 的 SunEC 包一层。
     *
     * 显式点名 "SunEC" 而不是走默认顺序 —— 默认顺序在测试里被坏 provider 占着，
     * 不点名就变成自己调自己。
     */
    class GoodProvider : java.security.Provider("SunsetGoodKS", 1.0, "模拟 Conscrypt：真正能解 SPKI、能验签") {
        init {
            put("KeyFactory.Ed25519", GoodKeyFactorySpi::class.java.name)
        }
    }

    class GoodKeyFactorySpi : java.security.KeyFactorySpi() {
        private fun delegate(): java.security.KeyFactory = java.security.KeyFactory.getInstance("Ed25519", "SunEC")

        override fun engineGeneratePublic(keySpec: java.security.spec.KeySpec): java.security.PublicKey =
            delegate().generatePublic(keySpec)

        override fun engineGeneratePrivate(keySpec: java.security.spec.KeySpec): java.security.PrivateKey =
            delegate().generatePrivate(keySpec)

        override fun <T : java.security.spec.KeySpec?> engineGetKeySpec(
            key: java.security.Key?,
            keySpec: Class<T>?,
        ): T = delegate().getKeySpec(key, keySpec)

        override fun engineTranslateKey(key: java.security.Key?): java.security.Key = delegate().translateKey(key)
    }
}
