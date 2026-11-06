package com.mobiletracker.core.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Compact, Cryptographically Authenticated SMS Payload Engine
 *
 * Designed to fit within the 140-byte binary SMS (Type 0 / GSM 7-bit / 8-bit PDU) payload envelope:
 * Header: [Version (1B)] [Nonce (8B)] [Command/Type (1B)] [Payload Length (1B)]
 * Encrypted Body: AES-128-GCM or AES-256-GCM ciphertext + 12B/16B Auth Tag
 * Base64 URL-safe encoding for standard SMS text delivery.
 */
object SmsPayloadCrypto {

    private const val PROTOCOL_VERSION: Byte = 0x01
    private const val HMAC_ALGO = "HmacSHA256"
    private const val GCM_TAG_LENGTH = 128 // 16 bytes

    enum class SmsCommand(val code: Byte) {
        POLL_LOCATION(0x10),
        RING_ALARM(0x20),
        LOCK_DEVICE(0x30),
        LOCATION_RESPONSE(0x11),
        ACK(0x01);

        companion object {
            fun fromCode(code: Byte) = entries.firstOrNull { it.code == code }
        }
    }

    data class LocationSmsData(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val batteryPct: Byte,
        val timestampSeconds: Int
    )

    /**
     * Serializes and encrypts a location response into an obfuscated SMS string.
     */
    fun packEncryptedLocation(
        location: LocationSmsData,
        presharedSecret: ByteArray
    ): String {
        // Binary coordinate packing: 8B Lat + 8B Lon + 4B Acc + 1B Bat + 4B Time = 25 Bytes
        val rawBuffer = ByteBuffer.allocate(25)
        rawBuffer.putDouble(location.latitude)
        rawBuffer.putDouble(location.longitude)
        rawBuffer.putFloat(location.accuracyMeters)
        rawBuffer.put(location.batteryPct)
        rawBuffer.putInt(location.timestampSeconds)
        val plaintext = rawBuffer.array()

        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(presharedSecret.copyOf(32), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.updateAAD(byteArrayOf(PROTOCOL_VERSION, SmsCommand.LOCATION_RESPONSE.code))
        val ciphertext = cipher.doFinal(plaintext)

        // Envelope: [Version 1B] [Command 1B] [IV 12B] [Ciphertext + Tag]
        val packet = ByteBuffer.allocate(2 + 12 + ciphertext.size)
        packet.put(PROTOCOL_VERSION)
        packet.put(SmsCommand.LOCATION_RESPONSE.code)
        packet.put(iv)
        packet.put(ciphertext)

        return "MT#" + Base64.getUrlEncoder().withoutPadding().encodeToString(packet.array())
    }

    /**
     * Parses and authenticates incoming remote control command SMS.
     */
    fun parseAndVerifyCommand(
        smsBody: String,
        presharedSecret: ByteArray
    ): Pair<SmsCommand, ByteArray?>? {
        if (!smsBody.startsWith("MT#")) return null
        val payloadBase64 = smsBody.substring(3)

        val rawBytes = try {
            Base64.getUrlDecoder().decode(payloadBase64)
        } catch (e: Exception) {
            return null
        }

        if (rawBytes.size < 14) return null // Version (1) + Cmd (1) + IV (12) minimum

        val buffer = ByteBuffer.wrap(rawBytes)
        val version = buffer.get()
        if (version != PROTOCOL_VERSION) return null

        val commandCode = buffer.get()
        val command = SmsCommand.fromCode(commandCode) ?: return null

        val iv = ByteArray(12)
        buffer.get(iv)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(presharedSecret.copyOf(32), "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher.updateAAD(byteArrayOf(version, commandCode))
            val decrypted = cipher.doFinal(ciphertext)
            Pair(command, decrypted)
        } catch (e: Exception) {
            null // Cryptographic authentication or HMAC failure
        }
    }
}
