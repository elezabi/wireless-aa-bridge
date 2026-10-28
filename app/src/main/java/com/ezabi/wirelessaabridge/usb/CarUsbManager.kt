package com.ezabi.wirelessaabridge.usb

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.ezabi.wirelessaabridge.logging.LogManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Handles USB Accessory (AOA) connection from the car.
 * The car acts as USB host and sends the AOA request with accessory strings.
 * We register as the accessory handler, open the file descriptor, and read/write bulk data.
 */
class CarUsbManager(private val activity: Activity) {

    private val TAG = "CarUsbManager"
    private val log = LogManager.getInstance(activity)

    private val usbManager: UsbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private var accessory: UsbAccessory? = null
    private val prePhoneBuffer = java.io.ByteArrayOutputStream()
    private val MAX_PRE_PHONE_BUFFER = 64 * 1024
    private val OPEN_ATTEMPTS = 10
    private val OPEN_RETRY_MS = 500L

    /**
     * The phone's exact VERSION_RESPONSE (captured from the real device): version 1.7,
     * status OK. Sent to the car once the phone is on TCP (from the relay path), NOT the
     * instant the car asks: answering immediately makes the car send its ClientHello right
     * away, but the phone needs ~15 s to arrive, and the car abandons the TLS handshake
     * after ~10 s — the exact "Android disconnected" cycle. Holding it until the phone is
     * connected makes the car's ClientHello land on an already-connected phone, so the
     * phone's ServerHello is instant. A fallback timer still answers inside the car's
     * ~18 s version timeout if the phone is slow.
     */
    private val SYNTHETIC_VERSION_RESPONSE = byteArrayOf(
        0x00, 0x03, 0x00, 0x08, 0x00, 0x02, 0x00, 0x01, 0x00, 0x07, 0x00, 0x00
    )
    private val SYNTHETIC_FALLBACK_MS = 14_000L
    @Volatile var syntheticResponseSent: Boolean = false
        private set
    @Volatile private var syntheticPending: Boolean = false
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var readThread: Thread? = null
    private var isReading = false

    // Callbacks
    var onAccessoryAttached: ((UsbAccessory) -> Unit)? = null
    var onAccessoryDetached: (() -> Unit)? = null
    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onProtocolVersion: ((Int) -> Unit)? = null
    var onAccessoryStrings: ((Map<String, String>) -> Unit)? = null

    private val accessoryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
                    acc?.let { handleAccessoryAttached(it) }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
                    // Do NOT compare UsbAccessory objects here: equals() calls getSerial(),
                    // and the accessory permission is already revoked at detach, so it
                    // throws SecurityException and kills the process on every unplug.
                    val sameDevice = try {
                        acc != null && accessory != null &&
                            acc.manufacturer == accessory?.manufacturer &&
                            acc.model == accessory?.model
                    } catch (e: Exception) {
                        true
                    }
                    if (sameDevice) {
                        handleAccessoryDetached()
                    } else {
                        log.w(TAG, "Detached event for a different accessory; ignoring")
                    }
                }
            }
        }
    }

    fun startListening() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        activity.registerReceiver(accessoryReceiver, filter)

        // Check if already connected
        val accessoryList = usbManager.accessoryList
        if (accessoryList != null && accessoryList.isNotEmpty()) {
            handleAccessoryAttached(accessoryList[0])
        } else {
            log.i(TAG, "No accessory currently attached. Waiting for car...")
            updateStatus("Waiting for car…")
        }
    }

    fun stopListening() {
        stopReading()
        closeConnection()
        try {
            activity.unregisterReceiver(accessoryReceiver)
        } catch (e: Exception) {
            log.e(TAG, "Receiver already unregistered", e)
        }
        updateStatus("Disconnected")
    }

    /**
     * Open an accessory handed to us directly by the USB_ACCESSORY_ATTACHED activity intent.
     * The broadcast receiver is not reliably delivered when the activity is already running,
     * which left the car unopened while the phone sat waiting ("Relay pending: car USB not
     * open yet"). Idempotent: an already-open descriptor is left alone.
     */
    fun openFromIntent(acc: UsbAccessory) {
        if (fileDescriptor != null) {
            log.i(TAG, "Accessory already open; ignoring attach intent")
            return
        }
        handleAccessoryAttached(acc)
    }

    /**
     * Fallback for when neither the receiver nor the intent fires: scan what the system
     * already reports as attached. Called after the bridge starts.
     */
    fun openAttachedAccessory(): Boolean {
        if (fileDescriptor != null) return true
        return try {
            val list = usbManager.accessoryList
            if (list.isNullOrEmpty()) {
                log.i(TAG, "No accessory currently attached")
                return false
            }
            val acc = list.firstOrNull { it.manufacturer == "Android" } ?: list[0]
            log.i(TAG, "Found attached accessory via accessoryList: ${acc.manufacturer} / ${acc.model}")
            handleAccessoryAttached(acc)
            true
        } catch (e: Exception) {
            log.w(TAG, "accessoryList scan failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- state for the bridge

    /** True when the head unit has stopped answering on this descriptor (needs a replug). */
    @Volatile private var sessionDead = false
    fun isSessionDead(): Boolean = sessionDead

    /** True once the accessory descriptor is live (car is really connected). */
    fun isOpen(): Boolean = fileDescriptor != null

    /** True when the car is attached but the descriptor is not open. */
    fun isAttachedOnly(): Boolean = accessory != null && fileDescriptor == null

    /** Millis since the accessory was opened (0 = not open). */
    @Volatile private var openedAtMs: Long = 0L

    fun openAgeMs(): Long = if (openedAtMs == 0L) 0L else System.currentTimeMillis() - openedAtMs

    /** Set by the bridge when a phone is on TCP, so reads stop buffering. */
    @Volatile var phoneAttached: Boolean = false

    /** When a phone is on TCP, car bytes are forwarded straight here by the read loop. */
    @Volatile var phoneOutput: java.io.OutputStream? = null

    /** Car bytes captured before a phone was present, for replay on relay start. */
    fun takeBufferedCarBytes(): ByteArray? = synchronized(prePhoneBuffer) {
        if (prePhoneBuffer.size() == 0) return null
        val out = prePhoneBuffer.toByteArray()
        prePhoneBuffer.reset()
        log.i(TAG, "Replaying ${out.size} buffered car bytes to the phone")
        out
    }

    private fun handleAccessoryAttached(acc: UsbAccessory) {
        log.i(TAG, "Accessory attached: ${acc.manufacturer} / ${acc.model} / ${acc.version}")
        accessory = acc

        // Log the AOA strings the car sent
                val strings: Map<String, String> = mapOf(
                    "manufacturer" to (acc.manufacturer ?: "null"),
                    "model" to (acc.model ?: "null"),
                    "description" to (acc.description ?: "null"),
                    "version" to (acc.version ?: "null"),
                    "uri" to (acc.uri ?: "null"),
                    "serial" to (acc.serial ?: "null")
                )
                strings.forEach { entry -> log.i(TAG, "AOA String [${entry.key}]: ${entry.value}") }
                onAccessoryStrings?.invoke(strings)

        // Request permission if needed
        if (!usbManager.hasPermission(acc)) {
            log.i(TAG, "Requesting USB accessory permission")
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                activity, 0, Intent("com.ezabi.wirelessaabridge.USB_PERMISSION"),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(acc, pendingIntent)
            updateStatus("Permission requested…")
            return
        }

        openAccessory(acc)
    }

    private fun handleAccessoryDetached() {
        log.i(TAG, "Accessory detached")
        openedAtMs = 0L
        syntheticResponseSent = false
        syntheticPending = false
        phoneAttached = false
        phoneOutput = null
        synchronized(prePhoneBuffer) { prePhoneBuffer.reset() }
        stopReading()
        closeConnection()
        accessory = null
        onAccessoryDetached?.invoke()
        updateStatus("Disconnected")
    }

    private fun openAccessory(acc: UsbAccessory, attempt: Int = 1) {
        log.i(TAG, "Opening accessory file descriptor... (attempt $attempt/$OPEN_ATTEMPTS)")
        fileDescriptor = usbManager.openAccessory(acc)
        if (fileDescriptor == null) {
            // The descriptor is frequently not ready at the instant of the attach intent.
            // Giving up here cost us a run where the phone had connected 15 ms earlier.
            if (attempt < OPEN_ATTEMPTS) {
                log.w(TAG, "open returned null fd — retrying in ${OPEN_RETRY_MS}ms")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (fileDescriptor == null) openAccessory(acc, attempt + 1)
                }, OPEN_RETRY_MS)
                return
            }
            log.e(TAG, "Failed to open accessory (null fd after $OPEN_ATTEMPTS attempts)")
            onError?.invoke("Failed to open accessory file descriptor")
            updateStatus("Error: open failed")
            return
        }

        val fd = fileDescriptor!!.fileDescriptor
        inputStream = FileInputStream(fd)
        outputStream = FileOutputStream(fd)

        openedAtMs = System.currentTimeMillis()
        sessionDead = false
        log.i(TAG, "Accessory opened successfully. Starting read loop.")
        startReading()
        onAccessoryAttached?.invoke(acc)
        updateStatus("Car connected")
    }

    private fun startReading() {
        isReading = true
        readThread = Thread({ readLoop() }, "CarUsbReadThread").apply { start() }
    }

    private fun stopReading() {
        isReading = false
        readThread?.interrupt()
        readThread?.join(1000)
        readThread = null
    }

    private fun readLoop() {
        val buffer = ByteArray(16384)
        while (isReading && !Thread.currentThread().isInterrupted) {
            try {
                val bytesRead = inputStream?.read(buffer)
                if (bytesRead == null || bytesRead <= 0) {
                    if (bytesRead == -1) {
                        log.w(TAG, "EOF reached - car disconnected")
                        activity.runOnUiThread { handleAccessoryDetached() }
                    }
                    break
                }

                val data = buffer.copyOf(bytesRead)
                log.d(TAG, "Read ${bytesRead} bytes: ${bytesToHex(data)}")
                // The head unit writes its opening frame (the version request) once. Answer
                // it only when the phone is on TCP (sendSyntheticResponse, from the relay
                // path) or on the fallback timer — answering immediately would make the car
                // send its ClientHello before the phone arrives and abandon the TLS handshake.
                if (!phoneAttached && !syntheticResponseSent && !syntheticPending) {
                    if (isVersionRequest(data)) {
                        syntheticPending = true
                        Thread({
                            Thread.sleep(SYNTHETIC_FALLBACK_MS)
                            sendSyntheticResponse()
                        }, "SyntheticVersionFallback").apply { isDaemon = true; start() }
                    }
                }
                // The head unit writes its opening frame once, immediately. If no phone is
                // attached yet these bytes would otherwise be lost forever, and the car
                // would sit waiting for a reply that can never come.
                if (phoneAttached) {
                    // Relay is up: the read loop is the SOLE reader of the accessory, so car
                    // bytes go straight to the phone. A second reader on the same bulk endpoint
                    // returns EIO — which is exactly why a relay-side car->phone pump must not
                    // exist while the read loop is alive.
                    val sink = phoneOutput
                    if (sink != null) {
                        try {
                            sink.write(data)
                            sink.flush()
                        } catch (e: Exception) {
                            log.w(TAG, "Forward to phone failed: ${e.message}")
                        }
                    }
                } else {
                    synchronized(prePhoneBuffer) {
                        if (prePhoneBuffer.size() + data.size <= MAX_PRE_PHONE_BUFFER) {
                            prePhoneBuffer.write(data)
                            log.i(TAG, "Buffered ${data.size} car bytes until a phone connects " +
                                "(${prePhoneBuffer.size()} held)")
                        }
                    }
                }
                onDataReceived?.invoke(data)

                // First read - log as "first bytes" and try to parse protocol version
                if (bytesRead >= 2) {
                    // AOA protocol version is typically in first bytes
                    // For Android Auto, the first frame often starts with version info
                    // We'll log it and let the UI display
                }
            } catch (e: Exception) {
                if (isReading) {
                    sessionDead = true
                    log.e(TAG, "Read error — head unit has stopped answering; a physical replug is required", e)
                    onError?.invoke("Read error: ${e.message}")
                }
                break
            }
        }
        log.i(TAG, "Read loop ended")
    }

    /**
     * Answer the car's VERSION_REQUEST once the phone is on TCP (or on the fallback timer).
     * Deferred from the moment the request arrives: see the SYNTHETIC_VERSION_RESPONSE doc.
     */
    fun sendSyntheticResponse() {
        if (!syntheticPending || syntheticResponseSent) return
        syntheticPending = false
        try {
            outputStream?.write(SYNTHETIC_VERSION_RESPONSE)
            outputStream?.flush()
            syntheticResponseSent = true
            log.i(TAG, "Sent synthetic VERSION_RESPONSE (phone on TCP or fallback timer)")
        } catch (e: Exception) {
            log.w(TAG, "Synthetic version response failed: ${e.message}")
        }
    }

    fun write(data: ByteArray): Boolean {
        return try {
            outputStream?.write(data)
            outputStream?.flush()
            log.d(TAG, "Wrote ${data.size} bytes: ${bytesToHex(data)}")
            true
        } catch (e: Exception) {
            log.e(TAG, "Write error", e)
            onError?.invoke("Write error: ${e.message}")
            false
        }
    }

    private fun closeConnection() {
        try { inputStream?.close() } catch (e: Exception) { log.e(TAG, "Close input error", e) }
        try { outputStream?.close() } catch (e: Exception) { log.e(TAG, "Close output error", e) }
        try { fileDescriptor?.close() } catch (e: Exception) { log.e(TAG, "Close fd error", e) }
        inputStream = null
        outputStream = null
        fileDescriptor = null
    }

    private fun isVersionRequest(d: ByteArray): Boolean =
        d.size >= 6 && d[0].toInt() == 0x00 && (d[4].toInt() and 0xFF) == 0x00 && (d[5].toInt() and 0xFF) == 0x01

    private fun updateStatus(msg: String) {
        activity.runOnUiThread {
            // UI update will be handled by activity via callbacks
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun isConnected(): Boolean = fileDescriptor != null && isReading

    /** The accessory write stream, for phone->car relay writes. Read stays with the loop. */
    fun getCarOutput(): java.io.OutputStream? = outputStream

    fun getAccessoryInfo(): String? {
        return accessory?.let { "Mfg: ${it.manufacturer}, Model: ${it.model}, Ver: ${it.version}" }
    }
}