package com.ezabi.wirelessaabridge

import android.app.Application
import com.ezabi.wirelessaabridge.logging.LogManager

class WirelessAABridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize logging early
        LogManager.getInstance(this)
    }
}