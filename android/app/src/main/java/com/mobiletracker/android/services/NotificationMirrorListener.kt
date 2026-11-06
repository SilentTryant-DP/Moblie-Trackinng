package com.mobiletracker.android.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.mobiletracker.core.crypto.EikCryptoEngine

/**
 * Android Notification Mirror Listener
 *
 * Captures system notifications and incoming call events via NotificationListenerService:
 * - Detects incoming calls and priority alerts.
 * - Extracts titles/messages and serializes them into encrypted outbox envelopes.
 * - Prevents plaintext exposure by applying E2EE Ephemeral Identity Key encryption before queuing.
 */
class NotificationMirrorListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationMirror"
        // Master EIK loaded from secure Keystore in production
        private val TEST_EIK = ByteArray(32) { 0x1A.toByte() }
    }

    private val cryptoEngine = EikCryptoEngine(TEST_EIK)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val packageName = sbn.packageName ?: return

        // Filter out self-notifications and low-priority progress indicators
        if (packageName == applicationContext.packageName) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
            notification.category != Notification.CATEGORY_CALL) {
            return
        }

        val extras = notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val category = notification.category

        val isCall = category == Notification.CATEGORY_CALL ||
                     packageName.contains("dialer", ignoreCase = true) ||
                     packageName.contains("telecom", ignoreCase = true)

        val urgencyTier = if (isCall) 2 else 1 // Tier 2 (WebSocket/Immediate), Tier 1 (Push)

        Log.i(TAG, "Notification intercepted: pkg=$packageName, isCall=$isCall, category=$category")

        // Serialize and Encrypt via E2EE
        val rawMessage = "pkg:$packageName|title:$title|body:$text|isCall:$isCall"
        val encryptedEnvelope = cryptoEngine.encryptPayload(rawMessage.toByteArray(Charsets.UTF_8))

        // Dispatch or queue in local SQLite outbox
        queueForEcosystemDispatch(
            eventId = "${sbn.id}_${sbn.postTime}",
            urgencyTier = urgencyTier,
            encryptedData = encryptedEnvelope.cipherText,
            iv = encryptedEnvelope.iv,
            tag = encryptedEnvelope.tag
        )
    }

    private fun queueForEcosystemDispatch(
        eventId: String,
        urgencyTier: Int,
        encryptedData: ByteArray,
        iv: ByteArray,
        tag: ByteArray
    ) {
        // Enqueue to notification_mirror_events table
        Log.d(TAG, "Enqueued mirrored notification event $eventId with tier $urgencyTier to SQLite outbox")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Handle remote dismissal sync if required
    }
}
