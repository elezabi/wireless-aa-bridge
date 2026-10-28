package com.ezabi.wirelessaabridge.boot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.ezabi.wirelessaabridge.bridge.BridgeHolder
import com.ezabi.wirelessaabridge.ui.MainActivity

/**
 * Headless owner of the bridge, so the car side (Bluetooth RFCOMM/HFP + the Wi-Fi Direct
 * AP + TCP) is up after a reboot without an activity. Android 10 refuses background
 * activity starts from a boot broadcast, so a foreground service is the only way.
 *
 * The USB accessory still arrives through the normal attach intent, which launches the
 * activity; that path holds the accessory until a phone connects.
 */
class BridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bridge = BridgeHolder.get(this)
        if (!bridge.isRunning()) {
            bridge.start()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Wireless AA Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Head unit side of the wireless Android Auto bridge" }
            nm.createNotificationChannel(ch)
        }
        val open = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Wireless AA Bridge")
            .setContentText("Head unit side running — waiting for a phone")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "waab_bridge"
        private const val NOTIF_ID = 41

        fun start(context: Context) {
            val i = Intent(context, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
