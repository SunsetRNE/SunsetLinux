package io.github.sunsetrne.sunsetlinux.core

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.Base64

/**
 * 随包的 Ed25519 验签（[Ed25519]，纯 Kotlin）的回归 —— **安全敏感代码，这里必须全绿**。
 *
 * ## 为什么有这份实现（真机 2026-09-19）
 *
 * 平台那条路在 Android 上走不通：AOSP 的 Conscrypt 只注册 RSA/EC/XDH 三个 KeyFactory，
 * 唯一叫 `KeyFactory.Ed25519` 的是 AndroidKeyStore 的**只读桩**（源码里一句无条件 throw），
 * Android 自带的 BouncyCastle 又是裁剪过的（没有 ed25519）。于是频道**永远验签失败**，
 * 更新页显示"已是最新"→ 用户自己更新不了。详见 [SignatureVerifier] 的注释。
 *
 * ## 这份测试钉什么
 *
 * 1. **RFC 8032 §7.1 的官方测试向量**（权威，不是自家生成的）：验过；
 * 2. **项目真实发布过的频道清单**（`testdata/channel/`）：验过 —— 这才是真正要保证的那条链；
 * 3. **反面**：改消息/改签名/换公钥/S≥L/长度不对/非规范编码，一律拒绝。
 *    （正面用例只证明"能过"，反面用例才证明"不是永远返回 true"。）
 */
class Ed25519Test {

    private fun hx(s: String): ByteArray {
        val t = s.filterNot { it.isWhitespace() }
        return ByteArray(t.length / 2) {
            ((Character.digit(t[it * 2], 16) shl 4) + Character.digit(t[it * 2 + 1], 16)).toByte()
        }
    }

    /** 群阶 L（RFC 8032 §6.1）—— 反例里要用它构造 S ≥ L。 */
    private val l = BigInteger.valueOf(2).pow(252) + BigInteger("27742317777372353535851937790883648493")

    // ─────────────────────────── ① RFC 8032 §7.1 官方测试向量

    private data class Vector(val name: String, val pub: String, val msg: String, val sig: String)

    private val rfc8032 = listOf(
        Vector(
            "TEST 1（空消息）",
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46b" +
                "d25bf5f0595bbe24655141438e7a100b",
        ),
        Vector(
            "TEST 2（1 字节）",
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c" +
                "387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Vector(
            "TEST 3（2 字节）",
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc659" +
                "4a7c15e9716ed28dc027beceea1ec40a",
        ),
        Vector(
            "TEST SHA(abc)（64 字节）",
            "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf",
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            "dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b58909351fc9ac90b3ecfdfbc7c66431e030" +
                "3dca179c138ac17ad9bef1177331a704",
        ),
    )

    @Test
    fun `RFC 8032 官方向量必须全部验过`() {
        rfc8032.forEach { v ->
            assertTrue(
                "${v.name}：RFC 8032 §7.1 的官方向量验不过 —— 实现有 bug",
                Ed25519.verify(hx(v.pub), hx(v.msg), hx(v.sig)),
            )
        }
    }

    @Test
    fun `官方向量的反面：改消息、改签名、换公钥都必须拒绝`() {
        rfc8032.forEach { v ->
            val pub = hx(v.pub)
            val msg = hx(v.msg)
            val sig = hx(v.sig)

            val otherMsg = msg.copyOf().let { if (it.isEmpty()) byteArrayOf(1) else it.also { m -> m[0] = (m[0] + 1).toByte() } }
            assertFalse("${v.name}：消息变了还能验过", Ed25519.verify(pub, otherMsg, sig))

            val badSig = sig.copyOf().also { it[0] = (it[0] + 1).toByte() }
            assertFalse("${v.name}：签名改一个字节还能验过", Ed25519.verify(pub, msg, badSig))

            val badPub = pub.copyOf().also { it[0] = (it[0] + 1).toByte() }
            assertFalse("${v.name}：公钥改一个字节还能验过", Ed25519.verify(badPub, msg, sig))
        }
    }

    @Test
    fun `S 不小于群阶 L 时必须拒绝（可塑性签名）`() {
        val v = rfc8032[1]
        val sig = hx(v.sig)
        // S = L（既不小于 L，也确实是"另一个能通过朴素实现的输入"）
        val sEqualsL = sig.copyOf().also {
            val bytes = l.toByteArray().let { b -> if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b }
            for (i in 0 until 32) it[32 + i] = 0
            for (i in bytes.indices) it[32 + i] = bytes[bytes.size - 1 - i]   // 小端写入
        }
        assertFalse("S == L 还能验过 = 漏了可塑性检查", Ed25519.verify(hx(v.pub), hx(v.msg), sEqualsL))
    }

    @Test
    fun `长度不对一律拒绝，不许"尽力而为"`() {
        val v = rfc8032[1]
        assertFalse("公钥 31 字节", Ed25519.verify(hx(v.pub).copyOf(31), hx(v.msg), hx(v.sig)))
        assertFalse("公钥 33 字节", Ed25519.verify(hx(v.pub) + byteArrayOf(0), hx(v.msg), hx(v.sig)))
        assertFalse("签名 63 字节", Ed25519.verify(hx(v.pub), hx(v.msg), hx(v.sig).copyOf(63)))
        assertFalse("空签名", Ed25519.verify(hx(v.pub), hx(v.msg), ByteArray(0)))
    }

    @Test
    fun `非规范的 y（≥ p）必须拒绝`() {
        // y = p（= 2^255-19）不是合法编码
        val pBytes = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19)).toByteArray()
        val enc = ByteArray(32)
        val be = pBytes.let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        for (i in be.indices) enc[i] = be[be.size - 1 - i]
        assertFalse("y ≥ p 还能解出点", Ed25519.verify(enc, ByteArray(0), ByteArray(64)))
    }

    // ─────────────────────────── ② 项目自己的信任根

    @Test
    fun `真实发布过的频道清单必须验过（这才是要保证的那条链）`() {
        val dir = File(TestPaths.repoRoot, "testdata/channel")
        val manifest = File(dir, "channel.json").readBytes()
        val sig = Base64.getDecoder().decode(File(dir, "channel.json.sig").readText().trim())
        val pub = Base64.getDecoder().decode(Channel.OFFICIAL.pubkey)
        assertTrue("内置实现验不过项目自己发布的清单 —— 这条链就是坏的", Ed25519.verify(pub, manifest, sig))
    }

    // ─────────────────────────── ③ 性能（别在手机上卡住界面）

    @Test
    fun `一次验签要在可接受的时间内完成`() {
        val v = rfc8032[3]
        val pub = hx(v.pub)
        val msg = hx(v.msg)
        val sig = hx(v.sig)
        Ed25519.verify(pub, msg, sig)   // 预热
        val t0 = System.nanoTime()
        repeat(3) { assertTrue(Ed25519.verify(pub, msg, sig)) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        // 手机上会比 JVM 慢，但一次频道检查只验 1~3 次；给足余量，只拦"病态慢"
        assertTrue("3 次验签用了 ${ms}ms（>3000ms 说明实现有性能问题）", ms < 3000)
    }

    @Test
    fun `同一份实现的判断必须稳定可重复`() {
        val v = rfc8032[0]
        val pub = hx(v.pub)
        val sig = hx(v.sig)
        assertEquals(
            "同一输入连续 5 次结果必须一致",
            List(5) { Ed25519.verify(pub, ByteArray(0), sig) },
            List(5) { true },
        )
    }
}
