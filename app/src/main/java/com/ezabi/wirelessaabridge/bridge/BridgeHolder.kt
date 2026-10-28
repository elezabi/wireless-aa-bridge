package com.ezabi.wirelessaabridge.bridge

import android.content.Context

/**
 * Process-wide bridge instance.
 *
 * Android 10 blocks starting an activity from a BOOT_COMPLETED broadcast, so the bridge
 * has to be started by a foreground service after a reboot. A service and an activity
 * cannot each own a BridgeController — the second one would fail to bind TCP 5288 and
 * would try to create a second Wi-Fi Direct group — so both go through this holder.
 */
object BridgeHolder {
    @Volatile private var instance: BridgeController? = null

    fun get(context: Context): BridgeController =
        instance ?: synchronized(this) {
            instance ?: BridgeController(context.applicationContext).also { instance = it }
        }

    fun peek(): BridgeController? = instance
}
