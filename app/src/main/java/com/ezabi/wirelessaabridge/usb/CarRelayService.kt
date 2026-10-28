package com.ezabi.wirelessaabridge.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.ezabi.wirelessaabridge.R
import com.ezabi.wirelessaabridge.logging.LogManager

/**
 * Foreground service for Stage 3 - keeps the relay running in background.
 * Not used in Stage 1.
 */
class CarRelayService : Service() {

    private val TAG = "CarRelayService"
    private val log = LogManager.getInstance(this)
    private val CHANNEL_ID = "wireless_aa_bridge_relay"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.i(TAG, "CarRelayService started")
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        log.i(TAG, "CarRelayService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wireless AA Bridge Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the car-phone relay running in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless AA Bridge")
            .setContentText("Car relay active - Stage 3")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}