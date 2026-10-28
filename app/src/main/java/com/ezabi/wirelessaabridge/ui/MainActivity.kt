package com.ezabi.wirelessaabridge.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ezabi.wirelessaabridge.R
import com.ezabi.wirelessaabridge.bridge.BridgeController
import com.ezabi.wirelessaabridge.databinding.ActivityMainBinding
import com.ezabi.wirelessaabridge.logging.LogManager
import com.ezabi.wirelessaabridge.usb.CarUsbManager

/**
 * Wireless AA Bridge — operation screen.
 *
 * Desired behaviour in the car: the S9+ is powered by the car, the app comes up, the
 * hotspot and Bluetooth endpoints are ready, a phone joins by itself, and only then is the
 * car's USB accessory opened so the car never starts its own ~95 s timeout before the
 * phone exists.
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val log by lazy { LogManager.getInstance(this) }
    private lateinit var usbManager: CarUsbManager
    private lateinit var bridge: BridgeController
    private lateinit var binding: ActivityMainBinding

    private val ui = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private val LOG_VIEW_LINES = 60
    private lateinit var logManager: com.ezabi.wirelessaabridge.logging.LogManager

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // Distinguish "waiting for a first connection" from "was connected, now gone".
    // From the head-unit side those are different facts, and showing both as "waiting"
    // made a client with Bluetooth switched off look identical to one that never arrived.
    private var btSeenThisRun = false
    private var wifiSeenThisRun = false

    private var tickCount = 0L
    private val uiTick = object : Runnable {
        override fun run() {
            // AP client count comes from an async P2P query, so poll it gently.
            if (tickCount++ % 5L == 0L) bridge.refreshApClients()
            refreshStatus()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        usbManager = CarUsbManager(this)
        logManager = com.ezabi.wirelessaabridge.logging.LogManager.getInstance(this)
        bridge = com.ezabi.wirelessaabridge.bridge.BridgeHolder.get(this)
        bridge.setCarUsb(usbManager)

        bridge.onState = { msg ->
            runOnUiThread { appendLog("STATE: $msg") }
        }
        bridge.onHotspot = { info ->
            runOnUiThread {
                appendLog("Hotspot ${info.ssid} ready (${info.apIp})")
                showJoinQr(info)
            }
        }
        // The phone is on TCP: the car's accessory may now be opened. Opening it earlier
        // guarantees losing the race, because the car speaks immediately and gives up long
        // before a phone can arrive over Bluetooth + WiFi.
        bridge.onPhoneReady = { ip ->
            if (!usbManager.isOpen()) {
                appendLog("Phone $ip is up — opening the car's accessory")
                usbManager.openAttachedAccessory()
            }
        }

        setupCallbacks()
        setupButtons()
        // Register for attach/detach broadcasts: the attach may arrive as an activity
        // intent, but detach only ever arrives as a broadcast.
        usbManager.startListening()
        checkBluetoothLocationPermission()
        checkStoragePermission()

        handleIntent(intent)
        ui.post(uiTick)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        // Boot path: the receiver launches this activity after a reboot so the head unit
        // side (BT/RFCOMM/HFP/Wi-Fi AP) is ready before anyone gets in the car.
        if (intent?.getBooleanExtra("autostart", false) == true) {
            appendLog("Booted — auto-starting bridge")
            binding.root.postDelayed({
                if (!bridge.isRunning()) toggleBridge()
            }, 1500)
        }
        if (intent?.action != "android.hardware.usb.action.USB_ACCESSORY_ATTACHED") return
        val accessory = intent.getParcelableExtra<android.hardware.usb.UsbAccessory>(
            android.hardware.usb.UsbManager.EXTRA_ACCESSORY
        )
        accessory?.let {
            log.i(TAG, "Launched via USB_ACCESSORY_ATTACHED: ${it.manufacturer}")
            // Hold it unless a phone is already waiting; the attach broadcast is not
            // reliably delivered when this activity is already running, so act on the
            // intent itself.
            // Always open immediately. Holding lost the car's opening frame (it speaks
            // once, at attach) and the head unit then waited for a reply that never came.
            // Frames are buffered by CarUsbManager and replayed when the phone connects.
            usbManager.openFromIntent(it)
            appendLog("Car attached — opening accessory now (frames buffered until phone)")
        }
        // The car run must not depend on someone tapping a button.
        binding.root.postDelayed({
            if (!bridge.isRunning()) {
                appendLog("Car attached — auto-starting bridge")
                toggleBridge()
            }
        }, 1200)
    }

    private fun setupCallbacks() {
        usbManager.onAccessoryAttached = { accessory ->
            runOnUiThread {
                appendLog("Car accessory open: ${usbManager.getAccessoryInfo() ?: "connected"}")
            }
            bridge.onCarReady()
        }

        usbManager.onAccessoryDetached = {
            bridge.onCarDetached()
            runOnUiThread { appendLog("Car accessory detached") }
        }

        usbManager.onAccessoryStrings = { strings ->
            log.i(TAG, "=== AOA Strings Received ===")
            strings.forEach { (k, v) -> log.i(TAG, "  $k: $v") }
            runOnUiThread {
                appendLog("Car says: ${strings["manufacturer"]} / ${strings["model"]} / ${strings["version"]}")
            }
        }

        usbManager.onDataReceived = { data ->
            val hex = data.take(32).joinToString(" ") { "%02X".format(it) }
            log.i(TAG, "DATA [${data.size} bytes]: $hex")
            runOnUiThread { appendLog("CAR DATA [${data.size}]: $hex") }
        }

        usbManager.onError = { error ->
            log.e(TAG, "USB Error: $error")
            runOnUiThread { appendLog("USB ERROR: $error") }
        }
    }

    private fun setupButtons() {
        binding.btnStartBridge.setOnClickListener { toggleBridge() }
        binding.btnExportLogs.setOnClickListener { exportLogs() }
        binding.btnClearLogs.setOnClickListener { clearLogs() }
    }

    // ---------------------------------------------------------------- status screen

    private fun refreshStatus() {
        renderLiveLog()
        // Report what the USB stack actually has: a stale "held" flag was shown even
        // after the accessory had been opened and the relay was running.
        val relaying = bridge.isRelaying()
        val carOpen = usbManager.isOpen()
        val carAttached = usbManager.isAttachedOnly()
        val carDead = usbManager.isSessionDead()
        binding.tvCar.text = when {
            carDead -> "REPLUG"
            relaying -> "relaying"
            carOpen -> getString(R.string.value_open)
            carAttached -> "attached"
            else -> getString(R.string.value_none)
        }
        binding.tvCar.setTextColor(
            color(
                when {
                    carDead -> R.color.red
                    relaying || carOpen -> R.color.green
                    carAttached -> R.color.amber
                    bridge.isRunning() -> R.color.amber
                    else -> R.color.text_secondary
                }
            )
        )
        binding.tvCarSub.text = when {
            carDead -> "head unit gave up — unplug and replug the cable"
            relaying -> "AAP flowing to phone"
            carOpen -> if (bridge.hasPhone()) "ready — phone on TCP" else "open, buffering for phone"
            carAttached -> "opening accessory…"
            else -> "not plugged into car"
        }

        // Bluetooth and WiFi are independent legs of the link; showing them as one
        // "PHONE" card was misleading, since BT is up long before AA joins the AP.
        val btUp = bridge.isBtConnected()
        if (btUp) btSeenThisRun = true
        binding.tvBt.text = when {
            btUp -> getString(R.string.value_connected)
            btSeenThisRun -> "disconnected"
            else -> getString(R.string.value_waiting)
        }
        binding.tvBt.setTextColor(
            color(
                when {
                    btUp -> R.color.green
                    btSeenThisRun -> R.color.gray_dark
                    bridge.isRunning() -> R.color.amber
                    else -> R.color.text_secondary
                }
            )
        )
        binding.tvBtSub.text = bridge.btPeer() ?: if (btSeenThisRun) "HFP link closed" else "no HFP link"

        val h = bridge.hotspotInfo()
        val wifiEnabled = (getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
        val apClients = bridge.apClientCount()
        val aaSession = bridge.hasPhone()
        if (aaSession) wifiSeenThisRun = true
        binding.tvWifi.text = when {
            !wifiEnabled -> "Wi-Fi OFF"
            aaSession -> "AA session"
            apClients > 0 -> "on AP"
            wifiSeenThisRun && bridge.isRunning() -> "disconnected"
            else -> getString(R.string.value_waiting)
        }
        binding.tvWifi.setTextColor(
            color(
                when {
                    !wifiEnabled -> R.color.red
                    aaSession -> R.color.green
                    apClients > 0 -> R.color.amber
                    wifiSeenThisRun -> R.color.gray_dark
                    bridge.isRunning() -> R.color.amber
                    else -> R.color.text_secondary
                }
            )
        )
        val active = bridge.clients().lastOrNull { it.active }
        binding.tvWifiSub.text = when {
            !wifiEnabled -> "enable Wi-Fi — fixed AP needs it"
            aaSession -> active?.ip ?: "TCP session open"
            apClients > 0 -> "$apClients device(s) on ${h?.ssid ?: "AP"}"
            else -> h?.let { "AP ${it.ssid}" } ?: ""
        }

        val (toCar, toPhone) = bridge.relayBytes()
        binding.tvRelay.text = if (relaying) getString(R.string.value_running) else getString(R.string.value_idle)
        binding.tvRelay.setTextColor(color(if (relaying) R.color.green else if (bridge.isRunning()) R.color.amber else R.color.text_secondary))
        val (framesToCar, framesFromCar, lastFrame) = bridge.relayFrames()
        binding.tvRelaySub.text =
            "→car ${fmt(toCar)}  →phone ${fmt(toPhone)}\n" +
                "frames $framesToCar out / $framesFromCar in\n" +
                "last: " + lastFrame.take(80)

        val pill = when {
            relaying -> R.string.pill_relaying
            bridge.isRunning() -> R.string.pill_running
            else -> R.string.pill_stopped
        }
        binding.tvPill.setText(pill)
        binding.tvPill.setTextColor(color(if (bridge.isRunning()) R.color.green else R.color.amber))

        renderClients()
    }

    private fun renderClients() {
        val clients = bridge.clients()
        binding.tvNoClients.visibility = if (clients.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutClients.removeAllViews()
        clients.takeLast(6).forEach { c ->
            val row = TextView(this).apply {
                text = buildString {
                    append(if (c.active) "● " else "○ ")
                    append(c.name)
                    append("  ")
                    append(c.ip)
                    if (!c.active) append("  (idle)")
                }
                textSize = 12f
                setTextColor(color(if (c.active) R.color.green else R.color.amber))
                setPadding(0, 2, 0, 2)
            }
            binding.layoutClients.addView(row)
        }
    }

    private fun fmt(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)


    /** Render the AP credentials as a scannable WiFi QR. */
    private fun showJoinQr(info: com.ezabi.wirelessaabridge.wifi.HotspotInfo) {
        try {
            val payload = "WIFI:T:WPA;S:${info.ssid};P:${info.password};;"
            val size = 384
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until size) for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            binding.ivQr.setImageBitmap(bmp)
            binding.tvQrCaption.text = "SSID  ${info.ssid}\nKEY   ${info.password}\nIP    ${info.apIp}"
            binding.layoutQr.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            binding.tvQrCaption.text = "SSID ${info.ssid}\nKEY  ${info.password}"
            binding.layoutQr.visibility = android.view.View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- bridge control

    private fun toggleBridge() {
        if (bridge.isRunning()) {
            bridge.stop()
            try { stopService(android.content.Intent(this, com.ezabi.wirelessaabridge.boot.BridgeService::class.java)) } catch (_: Exception) {}
            releaseRunLocks()
            binding.btnStartBridge.text = getString(R.string.btn_start_bridge)
            binding.layoutQr.visibility = android.view.View.GONE
            appendLog("=== Bridge stopped ===")
        } else {
            acquireRunLocks()
            requestDiscoverable()
            appendLog("=== Starting bridge ===")
            bridge.start()
            // Keep the process foreground so Samsung cannot freeze the bridge when the
            // screen goes off; the service is a no-op if the bridge is already running.
            try { com.ezabi.wirelessaabridge.boot.BridgeService.start(this) } catch (_: Exception) {}
            btSeenThisRun = false
            wifiSeenThisRun = false
            binding.btnStartBridge.text = getString(R.string.btn_stop_bridge)
            // If a phone is already connected and the car is held, open it now.
            binding.root.postDelayed({
                if (bridge.hasPhone() && !usbManager.isOpen()) {
                    usbManager.openAttachedAccessory()
                }
            }, 1500)
        }
        refreshStatus()
    }

    private fun acquireRunLocks() {
        try {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (wakeLock == null) {
                val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "WirelessAABridge:run"
                ).apply { acquire(60 * 60 * 1000L) }
            }
        } catch (e: Exception) {
            appendLog("Could not acquire wake lock: ${e.message}")
        }
    }

    private fun releaseRunLocks() {
        try {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun requestDiscoverable() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            appendLog("No Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            appendLog("Bluetooth is OFF")
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_LONG).show()
            return
        }
        if (adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            appendLog("Already discoverable")
            return
        }
        try {
            startActivity(
                android.content.Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 3600)
                }
            )
        } catch (e: Exception) {
            appendLog("Discoverable request failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- log + export

    private fun appendLog(msg: String) {
        // Single funnel: everything the UI says also goes to the session log, so the screen
        // and the exported file never disagree about what happened in the car.
        logManager.i("UI", msg)
        // This is called from USB, TCP and Bluetooth threads. Touching views there throws
        // ("Only the original thread that created a view hierarchy can touch its views"),
        // which used to kill the TCP handler before the relay could start.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            binding.root.post { renderLiveLog() }
        } else {
            renderLiveLog()
        }
    }

    /** Mirror the live LogManager queue on screen so the car can be debugged without adb. */
    private fun renderLiveLog() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            binding.root.post { renderLiveLog() }
            return
        }
        val tail = logManager.getLogs().takeLast(LOG_VIEW_LINES).joinToString("\n")
        binding.tvLogViewer.text = tail
        binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun exportLogs() {
        val file = LogManager.getInstance(this).exportToFile()
        val msg = if (file != null) getString(R.string.export_success, file.absolutePath)
        else getString(R.string.export_failed, "check permissions")
        appendLog(msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun clearLogs() {
        logLines.clear()
        LogManager.getInstance(this).clear()
        binding.tvLogViewer.text = getString(R.string.log_empty)
    }

    // ---------------------------------------------------------------- permissions

    private fun checkBluetoothLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1002)
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M until Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("UNUSED_EXPRESSION") Environment.isExternalStorageManager()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(uiTick)
        releaseRunLocks()
        if (bridge.isRunning()) {
            appendLog("Activity destroyed — stopping bridge")
            bridge.stop()
        }
        usbManager.stopListening()
    }
}
