package com.ezabi.wirelessaabridge.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The S9+ lives in the car and loses power every time the ignition does, so the bridge
 * has to come back without anyone touching the phone. This receiver relaunches the
 * activity after boot; the activity then starts the bridge (BT/RFCOMM/HFP + Wi-Fi Direct
 * AP) so the client can connect as soon as the car is powered.
 *
 * The USB side is separate: the car's accessory attach launches the activity itself, and
 * the accessory stays closed until a phone is on TCP.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        Log.i("WAAB/Boot", "Boot completed ($action) — launching bridge activity")
        // Android 10 blocks activity starts from a boot broadcast
        // (ActivityTaskManager: Background activity start ... isBgStartWhitelisted: false),
        // so the bridge is owned by a foreground service instead. The notification it posts
        // opens the UI when wanted.
        try {
            BridgeService.start(context)
        } catch (e: Exception) {
            Log.w("WAAB/Boot", "Could not start bridge service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
