package com.mobiletracker.android.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.mobiletracker.core.crypto.SmsPayloadCrypto

/**
 * Encrypted SMS Command Receiver & Silent Responder
 *
 * Intercepts incoming SMS commands before delivery to user messaging inboxes:
 * - Decrypts and authenticates AES-256-GCM + HMAC payload using pre-shared secret.
 * - Suppresses user alert via abortBroadcast().
 * - Dispatches silent response via SmsManager with encrypted coordinates.
 */
class EncryptedSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EncryptedSmsReceiver"
        // In production, retrieved dynamically from Android Keystore / Database
        val PRESHARED_SMS_SECRET = ByteArray(32) { 0x42.toByte() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        for (sms in messages) {
            val body = sms.displayMessageBody ?: continue
            val senderAddress = sms.displayOriginatingAddress ?: continue

            // Verify if incoming message matches our encrypted protocol prefix
            val verifiedCommand = SmsPayloadCrypto.parseAndVerifyCommand(body, PRESHARED_SMS_SECRET)

            if (verifiedCommand != null) {
                val (command, _) = verifiedCommand
                Log.w(TAG, "Cryptographically verified SMS command received: $command from $senderAddress")

                // Abort standard broadcast so message does not appear in user SMS inbox
                abortBroadcast()

                handleSilentCommand(context, command, senderAddress)
                break
            }
        }
    }

    private fun handleSilentCommand(
        context: Context,
        command: SmsPayloadCrypto.SmsCommand,
        senderNumber: String
    ) {
        when (command) {
            SmsPayloadCrypto.SmsCommand.POLL_LOCATION -> {
                Log.i(TAG, "Triggering passive cellular triangulation for SMS emergency response")
                // Simulate resolution or query last recorded telemetry from SQLite
                val lastLat = 37.7749
                val lastLon = -122.4194
                val accuracy = 150.0f
                val batteryPct = 85.toByte()
                val timestamp = (System.currentTimeMillis() / 1000L).toInt()

                val responsePayload = SmsPayloadCrypto.packEncryptedLocation(
                    location = SmsPayloadCrypto.LocationSmsData(
                        latitude = lastLat,
                        longitude = lastLon,
                        accuracyMeters = accuracy,
                        batteryPct = batteryPct,
                        timestampSeconds = timestamp
                    ),
                    presharedSecret = PRESHARED_SMS_SECRET
                )

                // Dispatch silent encrypted SMS reply
                val smsManager = context.getSystemService(SmsManager::class.java)
                smsManager.sendTextMessage(senderNumber, null, responsePayload, null, null)
                Log.i(TAG, "Encrypted coordinate payload dispatched to $senderNumber")
            }
            SmsPayloadCrypto.SmsCommand.RING_ALARM -> {
                Log.i(TAG, "Triggering high-volume alarm via AudioService")
                // Trigger local alert audio player
            }
            else -> Log.d(TAG, "Unhandled command: $command")
        }
    }
}
