package io.github.sunsetrne.sunsetlinux.core

import java.math.BigInteger
import java.security.MessageDigest

/**
 * 自带（不依赖平台 provider）的 Ed25519 验签 —— **只验签，不签名**。
 *
 * ## 为什么必须有它（真机 2026-09-19，OnePlus PJD110 / Android 16 / App 0.3.18）
 *
 * App 报「签名校验失败，已拒绝该频道」，理由里的异常是
 * `InvalidKeySpecException：To generate a key pair in Android Keystore, use KeyPairGenerator
 * initialized with android.security.keystore.KeyGenParameterSpec`。逐层查下来：
 *
 * 1. 那句文本来自 AOSP `AndroidKeyStoreKeyFactorySpi.engineGeneratePublic()` —— 它**无条件抛**
 *    （源码里就是一行 throw）：AndroidKeyStore 只给自己生成的密钥当 KeyFactory。
 * 2. API 36 的 `AndroidKeyStoreProvider` 注册了 `KeyFactory.ED25519`（只注册 KeyPairGenerator +
 *    KeyFactory，没有 Signature），而 `SignatureVerifier` 原来用 `KeyFactory.getInstance("Ed25519")`
 *    + `generatePublic(SPKI)` 造公钥 —— 于是撞上它。
 * 3. 平台**本来有**失败转移（JDK 9 起的 delayed provider selection：`KeyFactory` 会挨个 provider
 *    试，`nextSpi`/`serviceIterator` 这两个符号在设备 `/apex/com.android.art/javalib/core-oj.jar`
 *    里都在）。既然转移过后**仍然**把它抛了出来，说明这台机器上**没有任何 provider** 能用
 *    X.509/SPKI 造出 Ed25519 公钥：Conscrypt 的 `KeyFactory.EdDSA` 是 2025 年才加的新类
 *    （`OpenSslEdDsaKeyFactory`），该机上的那份还没有。
 *
 * ⇒ 结论：**频道验签不能建立在"平台一定给得出 Ed25519 公钥"这个假设上**。这里用纯 Kotlin
 * 实现一份（只依赖 `MessageDigest("SHA-512")`，任何 Android 都有），作为最后的兜底 ——
 * 平台 provider 能用就用它（快、走硬件），不能用就自己算。
 *
 * 算法按 RFC 8032 §5.1.7（验证那一半）实现，参数与 §6.1 一致：
 *   p = 2^255-19、L = 2^252+27742317777372353535851937790883648493、d = -121665/121666、
 *   基点 B 用 RFC 里给的那两个常数。单测里钉了 RFC 8032 §7.1 的官方测试向量、
 *   项目自己发布的真实频道清单，以及"改一个字节必须拒绝"。
 *
 * ⚠️ 这是**安全敏感**代码，改动必须让 `Ed25519Test` 全绿；那边同时钉住反例
 * （S ≥ L、非规范编码、伪造签名必须全部拒绝）。
 */
internal object Ed25519 {

    private val TWO = BigInteger.valueOf(2)

    /** p = 2^255 - 19 */
    private val P = TWO.pow(255) - BigInteger.valueOf(19)

    /** 群阶 L = 2^252 + 27742317777372353535851937790883648493 */
    private val L = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")

    /** d = -121665/121666 mod p */
    private val D = BigInteger.valueOf(-121665).mod(P)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    /** sqrt(-1) = 2^((p-1)/4) mod p（开方修正用） */
    private val SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    /** RFC 8032 §6.1 给的基点 B。 */
    private val BX = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val BY = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
    private val B = Point(BX, BY)

    /** 曲线上的仿射点（x, y）。恒等元（零点）用 `null` 表示。 */
    private data class Point(val x: BigInteger, val y: BigInteger)

    /**
     * 验签。`publicKey` 32 字节裸公钥、`signature` 64 字节签名（都是 RFC 8032 的编码）。
     *
     * 任何不规范输入（长度不对、y ≥ p、解不出点、S ≥ L）一律返回 false —— 不做"尽力而为"。
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false

        val a = decodePoint(publicKey) ?: return false
        val r = decodePoint(signature.copyOfRange(0, 32)) ?: return false

        val s = littleEndian(signature, 32, 32)
        if (s >= L) return false

        // k = SHA-512(R || A || M) mod L
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(signature, 0, 32)
        digest.update(publicKey)
        digest.update(message)
        val k = littleEndian(digest.digest(), 0, 64).mod(L)

        // 判据：[S]B == R + [k]A
        val left = scalarMult(s, B)
        val right = add(r, scalarMult(k, a))
        return when {
            left == null && right == null -> true
            left == null || right == null -> false
            else -> left.x == right.x && left.y == right.y
        }
    }

    /** 点解码：末字节最高位是 x 的符号位，其余 255 位是 y（小端）。 */
    private fun decodePoint(encoded: ByteArray): Point? {
        if (encoded.size != 32) return null
        val yBytes = encoded.copyOf()
        val sign = (yBytes[31].toInt() and 0x80) != 0
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = littleEndian(yBytes, 0, 32)
        if (y >= P) return null                       // 非规范编码：直接拒
        val x = recoverX(y, sign) ?: return null
        return Point(x, y)
    }

    /**
     * 由 y 与符号位还原 x：x² = (y²-1)/(d·y²+1)。
     *
     * p ≡ 5 (mod 8)，用 RFC 8032 附录里的那个开方技巧：
     * `x = u·v³·(u·v⁷)^((p-5)/8)`，再按 `v·x²` 是否等于 `u` / `-u` 判定与修正。
     */
    private fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)

        val v3 = v.multiply(v).mod(P).multiply(v).mod(P)
        val v7 = v3.multiply(v3).mod(P).multiply(v).mod(P)
        var x = u.multiply(v3).mod(P)
            .multiply(u.multiply(v7).mod(P).modPow(P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8)), P))
            .mod(P)

        val vx2 = v.multiply(x).mod(P).multiply(x).mod(P)
        when {
            vx2 == u -> Unit
            vx2 == P.subtract(u).mod(P) -> x = x.multiply(SQRT_M1).mod(P)
            else -> return null                        // 不在曲线上
        }
        if (x.testBit(0) != sign) x = P.subtract(x).mod(P)
        // x == 0 且符号位为 1 是非规范编码（RFC 8032 §5.1.3）
        if (x.signum() == 0 && sign) return null
        return x
    }

    /** 扭曲爱德华兹曲线（a = -1）的加法：完备公式，不会有除零。 */
    private fun add(p: Point?, q: Point?): Point? {
        if (p == null) return q
        if (q == null) return p
        val xy = D.multiply(p.x).mod(P).multiply(q.x).mod(P).multiply(p.y).mod(P).multiply(q.y).mod(P)
        val x3 = p.x.multiply(q.y).add(q.x.multiply(p.y)).mod(P)
            .multiply(BigInteger.ONE.add(xy).mod(P).modInverse(P)).mod(P)
        val y3 = p.y.multiply(q.y).add(p.x.multiply(q.x)).mod(P)
            .multiply(BigInteger.ONE.subtract(xy).mod(P).modInverse(P)).mod(P)
        return Point(x3, y3)
    }

    /** 倍点（就是自己加自己，单独写出来省一次乘法）。 */
    private fun dbl(p: Point?): Point? {
        if (p == null) return null
        val xy = D.multiply(p.x).mod(P).multiply(p.x).mod(P).multiply(p.y).mod(P).multiply(p.y).mod(P)
        val x3 = TWO.multiply(p.x).mod(P).multiply(p.y).mod(P)
            .multiply(BigInteger.ONE.add(xy).mod(P).modInverse(P)).mod(P)
        val y3 = p.y.multiply(p.y).add(p.x.multiply(p.x)).mod(P)
            .multiply(BigInteger.ONE.subtract(xy).mod(P).modInverse(P)).mod(P)
        return Point(x3, y3)
    }

    /** 标量乘（double-and-add，从低位到高位；恒等元用 null 表示）。 */
    private fun scalarMult(k: BigInteger, p: Point): Point? {
        var acc: Point? = null
        var base: Point? = p
        var i = 0
        while (i < k.bitLength()) {
            if (k.testBit(i)) acc = add(acc, base)
            base = dbl(base)
            i++
        }
        return acc
    }

    /** 小端字节 → 正整数。 */
    private fun littleEndian(bytes: ByteArray, offset: Int, length: Int): BigInteger {
        val be = ByteArray(length)
        for (i in 0 until length) be[length - 1 - i] = bytes[offset + i]
        return BigInteger(1, be)
    }
}
