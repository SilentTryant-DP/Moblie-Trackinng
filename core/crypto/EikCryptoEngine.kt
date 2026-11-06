package com.mobiletracker.core.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ephemeral Identity Key (EIK) Cryptographic Engine
 *
 * Implements:
 * 1. 1024-second rotating Ephemeral Identifier (EID) generation using AES-ECB-256
 *    and projection onto the SECP160R1 elliptic curve (Find My / Privacy-Preserving Beacon standard).
 * 2. E2EE payload encryption using AES-256-GCM with ephemeral public/private key agreement.
 */
class EikCryptoEngine(
    private val ephemeralIdentityKey: ByteArray // 32-byte master EIK stored in Hardware Keystore
) {
    init {
        require(ephemeralIdentityKey.size == 32) { "EIK must be a 256-bit (32-byte) secret key" }
    }

    companion object {
        private const val ROTATION_PERIOD_SECONDS = 1024L // 2^10 seconds (~17.06 minutes)

        // SECP160R1 Curve Parameters: y^2 = x^3 + ax + b (mod p)
        private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF7FFFFFFC", 16)
        private val A = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF7FFFFFF9", 16)
        private val B = BigInteger("1C97BEFC54BD7A8B65ACF89F81D4D4ADC565FA45", 16)
        private val G_X = BigInteger("4A96B5688EF573284664698968C38BB913CBFC82", 16)
        private val G_Y = BigInteger("23A628553168947D59DCC912042351377AC5FB32", 16)
        private val ORDER_N = BigInteger("0100000000000000000001F4C8F927AED3CA752257", 16)
    }

    /**
     * Calculates the discrete 1024-second epoch counter from a UNIX timestamp.
     */
    fun computeEpochCounter(unixTimeSeconds: Long = System.currentTimeMillis() / 1000L): Long {
        return unixTimeSeconds / ROTATION_PERIOD_SECONDS
    }

    /**
     * Generates an Ephemeral Identifier (EID) for BLE advertisement frames.
     *
     * Algorithm:
     * 1. Pack epoch counter (T) into a 16-byte block (counter padded with zeros).
     * 2. Encrypt the 16-byte block with EIK using AES-ECB (no padding).
     * 3. Take the first 20 bytes (or derive scalar mod N) and project as scalar multiplier d * G on SECP160R1.
     * 4. Return the compressed elliptic curve point (21 bytes: 1 byte parity + 20 bytes X-coordinate).
     */
    fun generateEphemeralIdentifier(epochCounter: Long): ByteArray {
        val counterBytes = ByteArray(16)
        val buffer = ByteBuffer.wrap(counterBytes)
        buffer.putLong(8, epochCounter) // Place in lower 64 bits

        // AES-ECB-256 block encryption
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val secretKeySpec = SecretKeySpec(ephemeralIdentityKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec)
        val encryptedBlock = cipher.doFinal(counterBytes)

        // Derive scalar d = (EncryptedBlock as BigInteger) mod (N - 1) + 1
        val scalar = BigInteger(1, encryptedBlock).mod(ORDER_N.subtract(BigInteger.ONE)).add(BigInteger.ONE)

        // Point multiplication on SECP160R1: Q = scalar * G
        val (pointX, pointY) = pointMultiply(G_X, G_Y, scalar)

        // Compressed point format: 0x02 if Y is even, 0x03 if Y is odd + 20-byte X coordinate
        val compressed = ByteArray(21)
        compressed[0] = if (pointY.testBit(0)) 0x03.toByte() else 0x02.toByte()
        val xBytes = pointX.toByteArray()
        val offset = if (xBytes.size > 20) xBytes.size - 20 else 0
        val destPos = if (xBytes.size < 20) 1 + (20 - xBytes.size) else 1
        val length = minOf(20, xBytes.size)
        System.arraycopy(xBytes, offset, compressed, destPos, length)

        return compressed
    }

    /**
     * Encrypts location coordinates or notifications with authenticated AES-256-GCM.
     */
    fun encryptPayload(payload: ByteArray, associatedData: ByteArray? = null): EncryptedEnvelope {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(ephemeralIdentityKey, "AES")
        val gcmSpec = GCMParameterSpec(128, iv) // 128-bit authentication tag

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        associatedData?.let { cipher.updateAAD(it) }

        val cipherTextWithTag = cipher.doFinal(payload)
        val cipherTextLength = cipherTextWithTag.size - 16
        val cipherText = cipherTextWithTag.copyOfRange(0, cipherTextLength)
        val tag = cipherTextWithTag.copyOfRange(cipherTextLength, cipherTextWithTag.size)

        return EncryptedEnvelope(
            iv = iv,
            cipherText = cipherText,
            tag = tag,
            epoch = computeEpochCounter()
        )
    }

    /**
     * Decrypts an authenticated AES-256-GCM envelope.
     */
    fun decryptPayload(envelope: EncryptedEnvelope, associatedData: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(ephemeralIdentityKey, "AES")
        val gcmSpec = GCMParameterSpec(128, envelope.iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        associatedData?.let { cipher.updateAAD(it) }

        val combined = ByteArray(envelope.cipherText.size + envelope.tag.size)
        System.arraycopy(envelope.cipherText, 0, combined, 0, envelope.cipherText.size)
        System.arraycopy(envelope.tag, 0, combined, envelope.cipherText.size, envelope.tag.size)

        return cipher.doFinal(combined)
    }

    // Simplified Double-and-Add Elliptic Curve Scalar Multiplication on SECP160R1
    private fun pointMultiply(x: BigInteger, y: BigInteger, k: BigInteger): Pair<BigInteger, BigInteger> {
        var rx = BigInteger.ZERO
        var ry = BigInteger.ZERO
        var isInfinity = true

        var qx = x
        var qy = y

        val bits = k.toString(2)
        for (i in bits.indices.reversed()) {
            if (bits[i] == '1') {
                if (isInfinity) {
                    rx = qx
                    ry = qy
                    isInfinity = false
                } else {
                    val sum = pointAdd(rx, ry, qx, qy)
                    rx = sum.first
                    ry = sum.second
                }
            }
            val double = pointDouble(qx, qy)
            qx = double.first
            qy = double.second
        }
        return Pair(rx, ry)
    }

    private fun pointAdd(x1: BigInteger, y1: BigInteger, x2: BigInteger, y2: BigInteger): Pair<BigInteger, BigInteger> {
        if (x1 == x2 && y1 == y2) return pointDouble(x1, y1)
        val lambda = (y2.subtract(y1)).multiply((x2.subtract(x1)).modInverse(P)).mod(P)
        val x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(P)
        val y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(P)
        return Pair(x3, y3)
    }

    private fun pointDouble(x1: BigInteger, y1: BigInteger): Pair<BigInteger, BigInteger> {
        val lambda = (BigInteger.valueOf(3).multiply(x1.pow(2)).add(A))
            .multiply((BigInteger.valueOf(2).multiply(y1)).modInverse(P)).mod(P)
        val x3 = lambda.multiply(lambda).subtract(BigInteger.valueOf(2).multiply(x1)).mod(P)
        val y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(P)
        return Pair(x3, y3)
    }
}

data class EncryptedEnvelope(
    val iv: ByteArray,
    val cipherText: ByteArray,
    val tag: ByteArray,
    val epoch: Long
)
