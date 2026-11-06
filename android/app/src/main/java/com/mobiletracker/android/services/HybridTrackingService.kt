package com.mobiletracker.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Android 15 (API 35+) Resilient Hybrid Tracking & Sync Foreground Service
 *
 * Implements strict compliance with Android 15 foreground service limits:
 * - Handles dataSync 6-hour execution limit within rolling 24 hours.
 * - Implements Service.onTimeout(startId, fgsType) callback to safely persist state
 *   to SQLite and gracefully terminate, avoiding fatal RemoteServiceException / ANR.
 * - Employs high-priority heads-up notifications compliant with Android 14+ background launch bans.
 */
class HybridTrackingService : Service() {

    companion object {
        private const val TAG = "HybridTrackingService"
        private const val NOTIFICATION_CHANNEL_ID = "channel_hybrid_tracking_v1"
        private const val NOTIFICATION_ID = 4096
    }

    private var sqLiteDatabase: SQLiteDatabase? = null
    private var isTrackingActive = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate - Initializing database handle and notification channel")
        initDatabase()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand invoked with startId: $startId")

        val notification = buildTrackingNotification(
            title = "Ecosystem Sync Active",
            content = "Monitoring hybrid cellular-BLE mesh and alert synchronization"
        )

        // Specify foreground service type for Android 14+ (API 34+) and Android 15 (API 35)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isTrackingActive = true
        return START_STICKY
    }

    /**
     * CRITICAL Android 15 (API level 35) Callback:
     * Triggered by the OS when foreground service timeout is reached (e.g. 6-hour rolling dataSync limit).
     * If stopSelf() is not invoked, OS throws fatal RemoteServiceException resulting in ANR.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Android 15 Foreground Service Timeout reached for type: $fgsType, startId: $startId")
        try {
            // 1. Immediately checkpoint SQLite WAL to prevent data corruption
            persistPendingContextToDatabase()
            
            // 2. Broadcast degradation intent to KMP mesh engine
            val fallbackIntent = Intent("com.mobiletracker.ACTION_SERVICE_DEGRADED").apply {
                putExtra("reason", "ANDROID_15_TIMEOUT")
            }
            sendBroadcast(fallbackIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing graceful shutdown during onTimeout", e)
        } finally {
            isTrackingActive = false
            // 3. Gracefully terminate self to honor OS contract
            stopSelf(startId)
        }
    }

    private fun persistPendingContextToDatabase() {
        sqLiteDatabase?.let { db ->
            if (db.isOpen) {
                try {
                    db.beginTransaction()
                    // Force WAL checkpoint to persist all pending telemetry frames to disk
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            Log.d(TAG, "WAL Checkpoint completed with status: ${cursor.getInt(0)}")
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    private fun buildTrackingNotification(title: String, content: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Hybrid Mesh Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent synchronization of cellular-BLE tracking telemetry"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun initDatabase() {
        // Open local SQLite handle configured with WAL
        sqLiteDatabase = openOrCreateDatabase("ecosystem_tracker.db", Context.MODE_PRIVATE, null)
        sqLiteDatabase?.execSQL("PRAGMA journal_mode = WAL;")
        sqLiteDatabase?.execSQL("PRAGMA synchronous = NORMAL;")
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy - Flusing pending buffers and releasing DB")
        persistPendingContextToDatabase()
        sqLiteDatabase?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
