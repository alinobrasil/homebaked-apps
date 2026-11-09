package app.shell

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 key agreement (RFC 7748), used once per link so that the key the phone and computer
 * end up sharing never appears in the QR code.
 *
 * Written out with BigInteger because the platform only offers X25519 from Android 13, and the
 * desktop side (Python standard library) has none at all; both sides use the same ladder. It
 * is not constant-time. That is acceptable here: each key pair lives for one link, on your
 * own LAN, for a few seconds.
 */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun newPrivateKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

    /** The shared secret. Throws if the other side's key is degenerate (all-zero result). */
    fun sharedSecret(privateKey: ByteArray, theirPublic: ByteArray): ByteArray {
        val s = scalarMult(privateKey, theirPublic)
        if (s.all { it.toInt() == 0 }) throw IllegalArgumentException("bad public key")
        return s
    }

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        require(k.size == 32 && u.size == 32)
        val kk = k.copyOf()
        kk[0] = (kk[0].toInt() and 248).toByte()
        kk[31] = ((kk[31].toInt() and 127) or 64).toByte()
        val scalar = fromLittleEndian(kk)
        val uu = u.copyOf()
        uu[31] = (uu[31].toInt() and 127).toByte()
        val x1 = fromLittleEndian(uu).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = scalar.testBit(t)
            if (swap != bit) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = bit
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val sum = da.add(cb).mod(P)
            val diff = da.subtract(cb).mod(P)
            x3 = sum.multiply(sum).mod(P)
            z3 = x1.multiply(diff.multiply(diff)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap) {
            x2 = x3.also { x3 = x2 }
            z2 = z3.also { z3 = z2 }
        }
        return toLittleEndian(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    private fun fromLittleEndian(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun toLittleEndian(n: BigInteger): ByteArray {
        val be = n.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }
}
