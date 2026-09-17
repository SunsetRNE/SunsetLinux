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
}
