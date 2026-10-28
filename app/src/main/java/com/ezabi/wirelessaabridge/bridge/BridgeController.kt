package com.ezabi.wirelessaabridge.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.ezabi.wirelessaabridge.bluetooth.BtRfcommServer
import com.ezabi.wirelessaabridge.bluetooth.HfpHandsfreeServer
import com.ezabi.wirelessaabridge.bluetooth.WppProtocol
import com.ezabi.wirelessaabridge.logging.LogManager
import com.ezabi.wirelessaabridge.relay.RelayEngine
import com.ezabi.wirelessaabridge.relay.TcpPhoneServer
import com.ezabi.wirelessaabridge.usb.CarUsbManager
import com.ezabi.wirelessaabridge.wifi.HotspotInfo
import com.ezabi.wirelessaabridge.wifi.HotspotManager
import java.io.EOFException
import java.net.Socket

/**
 * Runs the whole bridge: hotspot + RFCOMM (WPP handshake) + TLS listener + relay.
 *
 * Stage 2 = hotspot, RFCOMM server, WPP exchange, TCP endpoint reachable.
 * Stage 3 = TLS termination plus the transparent relay to the car's USB link.
 *
 * WPP wire order is taken from the reference dongle's AAWirelessLauncher::launch():
 * the head-unit side speaks first with WifiStartRequest, answers WifiInfoRequest
 * with WifiInfoResponse, then the link goes quiet. Version request/response (ids
 * 4/5) are not part of that exchange.
 *
 * The relay is deliberately protocol-agnostic: the car is the only head unit and
 * the phone is the only phone, so their AAP conversation is passed through
 * unmodified in both directions.
 */
class BridgeController(private val context: Context) {

    /** Desk mode: replace the car's USB streams with a scripted fake head unit. */
    @Volatile var deskMode: Boolean = false
    private val desk = com.ezabi.wirelessaabridge.desk.DeskHeadUnit(context)
    private var deskSocket: java.net.Socket? = null

    companion object {
        const val TAG = "Bridge"
        const val PORT = 5288
    }

    private val log = LogManager.getInstance(context)
    private val hotspot = HotspotManager(context.applicationContext)
    private val rfcomm = BtRfcommServer(context.applicationContext)
    private val hfp = HfpHandsfreeServer(context.applicationContext)
    private val tcp = TcpPhoneServer(context.applicationContext, PORT)
    private val relay = RelayEngine(context.applicationContext)

    @Volatile private var info: HotspotInfo? = null
    @Volatile private var running = false
    @Volatile private var pendingPhone: Socket? = null
    private var carUsb: CarUsbManager? = null

    /** One row per phone that has ever reached the TCP listener this session. */
    data class ClientRecord(
        val ip: String,
        var name: String,
        val connectedAt: Long,
        var active: Boolean
    )

    private val clientRecords = java.util.Collections.synchronizedList(mutableListOf<ClientRecord>())
    private val relayStartLock = Any()

    /**
     * Relay start touches sockets (desk-mode connect, replay of buffered car bytes), and on
     * the USB-attach path it is reached from the UI thread. That threw
     * NetworkOnMainThreadException and the 10 buffered car bytes were never delivered to the
     * phone — with the phone already on TCP and the car freshly attached, i.e. the best case
     * we have. Every start therefore hops to one worker thread.
     */
    private val relayStarter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "RelayStarter").apply { isDaemon = true }
    }
    private val STALE_SESSION_MS = 20_000L
    private val RELAY_SILENCE_MS = 15_000L
    private val MAX_RELAY_CYCLES = 3
    private val WPP_WAIT_MS = 10_000L

    @Volatile private var supervising = false
    private var supervisor: Thread? = null
    @Volatile private var lastPhoneIp: String? = null

    /** Bluetooth side of the link, tracked separately from the WiFi/TCP side. */
    @Volatile private var btPeerAddress: String? = null
    @Volatile private var btPeerName: String? = null

    fun btPeer(): String? = btPeerAddress?.let { btPeerName ?: it }

    fun isBtConnected(): Boolean = btPeerAddress != null

    var onState: ((String) -> Unit)? = null

    /** Fired when a phone is on TCP, i.e. the car's accessory may now be opened. */
    var onPhoneReady: ((String) -> Unit)? = null

    /**
     * "AA session" must mean a live session, not a socket object left over from a client
     * that has since dropped. A stale accepted socket otherwise reads as an active session
     * forever, so verify it is still open *and* that the peer is still on the AP.
     */
    fun hasPhone(): Boolean {
        val s = pendingPhone ?: return isRelaying()
        if (s.isClosed || !s.isConnected) {
            clearPendingPhone("socket closed by peer", s)
            return isRelaying()
        }
        val ip = lastPhoneIp
        if (ip != null && !arpContains(ip)) {
            clearPendingPhone("client $ip no longer on the AP", s)
            return isRelaying()
        }
        return true
    }

    private fun clearPendingPhone(why: String, expected: Socket? = pendingPhone) {
        if (expected == null || pendingPhone !== expected) return
        pendingPhone = null
        try { expected.close() } catch (_: Exception) {}
        synchronized(clientRecords) {
            clientRecords.forEach { if (it.active) it.active = false }
        }
        carUsb?.phoneAttached = false
        log.i(TAG, "Dropping stale phone session: $why")
    }

    private fun arpContains(ip: String): Boolean = try {
        java.io.File("/proc/net/arp").readLines().drop(1).any { line ->
            val c = line.trim().split(Regex("\\s+"))
            c.size >= 5 && c[0] == ip && c[3] != "00:00:00:00:00:00"
        }
    } catch (e: Exception) {
        true // cannot read: do not invent a disconnect
    }

    fun isRelaying(): Boolean = relay.isRunning()

    /** Number of devices associated with our AP (P2P group clients). */
    fun apClientCount(): Int = hotspot.clientCount()

    fun refreshApClients() = hotspot.refreshClientCount()

    /**
     * Desk/no-car liveness: a phone that walks away leaves the socket half-open and its
     * APR entry intact, so the only honest check is application-level — send the HU
     * version request and require an answer. Two silent rounds drop the session.
     */
    private var heartbeatMisses = 0
    private var watchdog: Thread? = null

    private fun startSessionWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = Thread({
            val versionRequest = byteArrayOf(
                0x00, 0x03, 0x00, 0x06, 0x00, 0x01, 0x00, 0x01, 0x00, 0x05
            )
            while (running) {
                try {
                    Thread.sleep(12_000)
                    val phone = pendingPhone ?: continue
                    if (relay.isRunning() || carUsb?.isConnected() == true) continue
                    phone.outputStream.write(versionRequest)
                    phone.outputStream.flush()
                    phone.soTimeout = 5_000
                    val buf = ByteArray(512)
                    val n = phone.inputStream.read(buf)
                    if (n <= 0) {
                        heartbeatMisses = 0
                        clearPendingPhone("heartbeat: peer closed", phone)
                    } else {
                        heartbeatMisses = 0
                        log.d(TAG, "Heartbeat OK (${n} bytes)")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    heartbeatMisses++
                    log.i(TAG, "Heartbeat missed ($heartbeatMisses/2)")
                    if (heartbeatMisses >= 2) {
                        heartbeatMisses = 0
                        clearPendingPhone("heartbeat lost — phone no longer answering")
                    }
                } catch (e: Exception) {
                    heartbeatMisses = 0
                    if (running) clearPendingPhone("heartbeat failed: ${e.javaClass.simpleName}")
                }
            }
        }, "PhoneWatchdog").apply { isDaemon = true; start() }
    }

    fun clients(): List<ClientRecord> = synchronized(clientRecords) { clientRecords.toList() }

    fun relayBytes(): Pair<Long, Long> = Pair(relay.bytesToCar(), relay.bytesToPhone())

    fun relayFrames(): Triple<Long, Long, String> =
        Triple(relay.framesToCar(), relay.framesFromCar(), relay.lastFrameSummary())

    /** Fired once the hotspot exists, with the credentials the phone must use. */
    var onHotspot: ((HotspotInfo) -> Unit)? = null

    fun isRunning(): Boolean = running
    fun hotspotInfo(): HotspotInfo? = info

    fun setCarUsb(usb: CarUsbManager) {
        carUsb = usb
    }

    /** Called when the accessory detaches: the next attach must be able to dial again. */
    fun onCarDetached() {
        hfp.resetOutbound()
        // A desk-test leftover (loopback fake phone) or a stale phone must never hold the
        // slot across a detach: it would make the next car attach relay to a dead peer.
        relay.stop()
        try { pendingPhone?.close() } catch (_: Exception) {}
        pendingPhone = null
        carUsb?.phoneAttached = false
        carUsb?.phoneOutput = null
    }

    fun start() {
        if (running) {
            log.w(TAG, "Bridge already running")
            return
        }
        running = true
        log.i(TAG, "=== Bridge starting (Stage 2 + 3) ===")

        if (deskMode) {
            desk.start()
            // Desk mode has no USB attach, so nothing would trigger onCarReady; dial the
            // phone here so the harness runs unattended, exactly as the car would.
            log.i(TAG, "Desk mode — dialling the paired phone")
            hfp.connectToPairedAg()
        }
        rfcomm.onConnection = { sock -> handlePhone(sock) }
        rfcomm.onError = { msg -> log.e(TAG, "RFCOMM: $msg"); onState?.invoke("RFCOMM: $msg") }

        tcp.onPhoneConnected = { sock ->
            val ip = sock.inetAddress?.hostAddress ?: "unknown"
            // A connection from our own addresses is us (diagnostics), not a client. The
            // desk harness's loopback fake phone (127.0.0.1) must also be rejected — it
            // hijacked a car test's phone slot once. Reject loopback and the AP's own IP.
            if (ip == info?.apIp || ip == "127.0.0.1" || ip == "::1" || ip == "localhost") {
                log.i(TAG, "Ignoring self-connection from $ip")
                try { sock.close() } catch (_: Exception) {}
            } else {
            val previous = pendingPhone
            pendingPhone = sock
            if (previous != null && previous !== sock) {
                try { previous.close() } catch (_: Exception) {}
            }
            lastPhoneIp = ip
            val existing = synchronized(clientRecords) { clientRecords.firstOrNull { it.ip == ip } }
            if (existing == null) {
                clientRecords.add(ClientRecord(ip, "phone", System.currentTimeMillis(), true))
            } else {
                existing.active = true
            }
            log.i(TAG, "Phone on TCP from $ip — the car accessory can now be opened")
            onState?.invoke("Phone $ip connected")
            onPhoneReady?.invoke(ip)
            maybeStartRelay()
            probeIfNoCar(sock)
            }
        }
        tcp.onError = { msg ->
            log.e(TAG, "TCP: $msg")
            onState?.invoke("TCP: $msg")
        }

        forceCarBluetoothClass()
        val btUp = rfcomm.start()
        log.i(TAG, "Bluetooth RFCOMM server: ${if (btUp) "UP" else "FAILED"}")
        if (!btUp) onState?.invoke("Bluetooth server failed")

        val hfUp = hfp.start()
        hfp.onScl = { addr ->
            btPeerAddress = addr
            log.i(TAG, "HFP link up with $addr — the phone should now consider this device a head unit")
            onState?.invoke("HFP connected — waiting for AA")
        }
        hfp.onSessionEnd = { addr ->
            if (btPeerAddress == addr) btPeerAddress = null
            log.i(TAG, "HFP session with $addr ended — BT side now idle")
            // Do NOT touch the phone session here. Android Auto uses HFP only during
            // setup and routinely closes it seconds later while the AAP session over
            // WiFi carries on (observed: HFP ended 20:04:24 with TCP still live).
            // The heartbeat watchdog owns WiFi-side liveness.
        }
        log.i(TAG, "HFP hands-free endpoint: ${if (hfUp) "UP" else "FAILED"}")
        if (!hfUp) onState?.invoke("HFP endpoint failed")

        val tlsUp = tcp.start()
        log.i(TAG, "TCP server: ${if (tlsUp) "UP on port $PORT" else "FAILED"}")
        if (!tlsUp) onState?.invoke("TCP server failed")
        startSessionWatchdog()

        hotspot.start(
            onReady = { h ->
                info = h
                log.i(
                    TAG,
                    "Bridge ready. On the client phone: pair Bluetooth with this device, then start " +
                        "Android Auto wireless. It should join \"${h.ssid}\"."
                )
                onState?.invoke("Waiting for phone — join \"${h.ssid}\"")
                onHotspot?.invoke(h)
            },
            onError = { msg ->
                log.e(TAG, msg)
                onState?.invoke(msg)
            }
        )
    }

    /** Called by the UI once the car's USB accessory is open and streams exist. */
    fun onCarReady() {
        log.i(TAG, "Car side ready (USB accessory open)")
        // A real accessory always wins over the desk harness. Leaving desk mode on across a
        // car trip would relay to the fake HU and look exactly like a dead car.
        if (deskMode) {
            deskMode = false
            desk.stop()
            log.w(TAG, "Desk mode auto-disabled — a real car accessory attached")
        }
        // A desk session leaves a dialler (and possibly a relay) alive. The next real attach
        // must be able to start from a clean slate.
        hfp.resetOutbound()
        relay.stop()
        // The bridge IS the head unit to the client phone. It dials the phone's handsfree to
        // wake Android Auto, then serves it WiFi credentials (WPP) and the TCP relay, so the
        // client negotiates AAP with the bridge and the bridge proxies it to the car over USB
        // as a cable-connected phone. Without the dial the phone only connects late/inbound.
        log.i(TAG, "Car present — ringing the paired phone (bridge is the head unit)")
        hfp.connectToPairedAg()
        // The Denso head unit also demands a handsfree link to the device it sees over USB.
        // Force the S9's own BluetoothHeadset (AG) to connect to the car now, instead of
        // waiting for Android's auto-connect — measured: the native connect only landed after
        // the AAP session had already been killed.
        connectCarHandsfree()
        maybeStartRelay()
    }

    /**
     * Connect the S9's native handsfree (BluetoothHeadset, AG role) to the car, so the car
     * sees a complete "phone": the USB accessory and a Bluetooth handsfree behind it. The car
     * is the bonded device that is not a client phone (prefer audio/video class).
     */
    private val carHandsfreeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var carHandsfreeRetries = 0

    /**
     * The S9 advertises as a phone (0x5a020c), so the client's Android Auto treats it as a
     * peer and re-asks "allow connection access" every session instead of remembering it as
     * a car. Reclassify it as a hands-free car kit (Audio service + Audio/Video major +
     * Hands-free minor). setBluetoothClass is @hide; Samsung ignores the
     * bluetooth_class_of_device setting, so go through reflection.
     */
    private fun forceCarBluetoothClass() {
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (e: Exception) { null } ?: return
        try {
            // BluetoothClass(int) is package-private; build one by reflection, then call the
            // @hide setBluetoothClass(BluetoothClass). Samsung ignores the
            // bluetooth_class_of_device setting, so reflection is the only route.
            val ctor = BluetoothClass::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            val target = ctor.newInstance(0x200414)
            val m = BluetoothAdapter::class.java.getDeclaredMethod("setBluetoothClass", BluetoothClass::class.java)
            m.isAccessible = true
            val ok = m.invoke(adapter, target)
            log.i(TAG, "Set Bluetooth class to hands-free car kit via reflection (result=$ok)")
        } catch (e: Exception) {
            log.w(TAG, "setBluetoothClass reflection failed: ${e.message}")
        }
    }

    private fun connectCarHandsfree() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val bonded = try { adapter.bondedDevices ?: return } catch (e: Exception) { return }
        val knownClient = "24:D3:37:64:BC:45" // the client phone (Xiaomi)
        val car = bonded.firstOrNull {
            it.address != knownClient &&
                it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } ?: bonded.firstOrNull { it.address != knownClient } ?: return
        log.i(TAG, "Forcing handsfree connect to the car ${car.name} (${car.address})")
        carHandsfreeRetries = 0
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                val headset = proxy as? BluetoothHeadset ?: return
                attemptCarConnect(headset, car)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    /**
     * The car's Bluetooth boots slower than its USB, so a single connect() at attach fails with
     * result=false on a fresh power-on. Retry every 3 s until the headset reports CONNECTED,
     * so a car-off → car-on reconnect recovers without a physical replug.
     */
    private fun attemptCarConnect(headset: BluetoothHeadset, car: BluetoothDevice) {
        val state = try { headset.getConnectionState(car) } catch (e: Exception) { -1 }
        log.i(TAG, "Car handsfree state=$state (attempt ${carHandsfreeRetries + 1})")
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                log.i(TAG, "Car handsfree connected")
                carHandsfreeRetries = 0
            }
            BluetoothProfile.STATE_CONNECTING -> scheduleCarRetry(headset, car)
            else -> {
                // Disconnect first to clear a stale half-connected state (the profile can still
                // think it is connected after the car has dropped its ACL link).
                try {
                    val d = BluetoothHeadset::class.java.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
                    d.isAccessible = true
                    d.invoke(headset, car)
                } catch (e: Exception) { /* best effort */ }
                try {
                    val m = BluetoothHeadset::class.java.getDeclaredMethod("connect", BluetoothDevice::class.java)
                    m.isAccessible = true
                    val ok = m.invoke(headset, car)
                    log.i(TAG, "Car handsfree connect issued (result=$ok)")
                } catch (e: Exception) {
                    log.w(TAG, "Car handsfree connect (reflection) failed: ${e.message}")
                }
                scheduleCarRetry(headset, car)
            }
        }
    }

    private fun scheduleCarRetry(headset: BluetoothHeadset, car: BluetoothDevice) {
        if (carHandsfreeRetries++ >= 10) {
            log.w(TAG, "Car handsfree connect gave up after 10 attempts")
            return
        }
        carHandsfreeHandler.postDelayed({ attemptCarConnect(headset, car) }, 3000L)
    }

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }

    /**
     * Desk diagnostic: with no car attached the phone connects and then waits for the
     * head unit to speak. Send the exact frame the car sent in Stage 1 and log the reply.
     * If the phone answers, the phone↔HU direction is confirmed and only the USB side is
     * left to prove.
     */
    private fun probeIfNoCar(phone: Socket) {
        Thread({
            try {
                Thread.sleep(2500)
                if (relay.isRunning()) return@Thread
                // Only probe when the car is genuinely absent; if it is attached it speaks
                // for itself and a duplicate version request would confuse the phone.
                if (carUsb?.isConnected() == true) return@Thread
                if (carUsb?.getCarOutput() != null) return@Thread
                val probe = byteArrayOf(
                    0x00, 0x03, 0x00, 0x06, 0x00, 0x01, 0x00, 0x01, 0x00, 0x05
                )
                log.i(TAG, "No car attached — sending HU probe frame: ${hex(probe)}")
                phone.outputStream.write(probe)
                phone.outputStream.flush()
                phone.soTimeout = 6000
                val buf = ByteArray(512)
                val n = phone.inputStream.read(buf)
                if (n > 0) {
                    log.i(TAG, "PROBE REPLY: $n bytes: ${hex(buf.copyOf(n))}")
                    onState?.invoke("Phone answered the HU probe ✓")
                } else {
                    log.i(TAG, "PROBE: phone closed without replying")
                    clearPendingPhone("probe saw EOF", phone)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Not a fault: a phone past the version exchange simply has nothing to say.
                log.i(TAG, "Probe: no reply within 6s — session kept")
            } catch (e: Exception) {
                log.w(TAG, "Probe failed: ${e.javaClass.simpleName}: ${e.message}")
                clearPendingPhone("probe failed: ${e.javaClass.simpleName}", phone)
            }
        }, "NoCarProbe").start()
    }

    private fun maybeStartRelay() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            relayStarter.execute { maybeStartRelay() }
            return
        }
        synchronized(relayStartLock) {
        if (relay.isRunning()) return
        val phone = pendingPhone
        if (phone == null) {
            log.i(TAG, "Relay pending: no phone on TCP yet")
            return
        }
        val usb = carUsb
        if (usb == null) {
            log.w(TAG, "Relay pending: no CarUsbManager registered")
            return
        }
        // NEVER reset a live session on an age timer. Measured: closing and reopening the
        // descriptor does not make the HU restart AAP — every later read returns EIO. A run
        // where the phone arrived 22 s after attach was turned into three instant EIO cycles
        // by a 20 s stale-session reset. Age is logged for diagnostics only.
        log.i(TAG, "Car side has been open ${usb.openAgeMs()}ms; relaying on it as-is")
        if (deskMode) {
            // Desk harness: real BT + WPP + AP + TCP path, scripted car on loopback.
            val s = try { java.net.Socket("127.0.0.1", 5289) } catch (e: Exception) {
                log.e(TAG, "Desk mode: fake HU unreachable: ${e.message}")
                return
            }
            deskSocket = s
            log.i(TAG, "Desk mode — relaying the phone to the fake head unit")
            relay.start(phone, s.getOutputStream(), s.getInputStream())
            onState?.invoke("Relaying phone ↔ fake HU (desk)")
            startRelaySupervisor()
            return
        }
        val carOut = usb.getCarOutput()
        if (carOut == null) {
            log.i(TAG, "Relay pending: car USB not open yet")
            return
        }
        log.i(TAG, "Both sides present — starting relay (read loop forwards car->phone)")
        // Answer the car's held version request NOW that the phone is on TCP, so the car's
        // ClientHello lands on a connected phone and its ServerHello is instant.
        usb.sendSyntheticResponse()
        usb.phoneAttached = true
        usb.phoneOutput = phone.getOutputStream()
        val dropFirst = usb.syntheticResponseSent
        if (dropFirst) log.i(TAG, "Will drop the phone's duplicate VERSION_RESPONSE (synthetic already sent)")
        relay.start(phone, carOut, carIn = null, dropFirstPhoneFrame = dropFirst)
        // Replay whatever the car said while we were still waiting for the phone; the
        // head unit only speaks first once, at attach.
        usb.takeBufferedCarBytes()?.let { buffered ->
            try {
                phone.outputStream.write(buffered)
                phone.outputStream.flush()
                log.i(TAG, "Replayed ${buffered.size} buffered car bytes to the phone")
            } catch (e: Exception) {
                log.w(TAG, "Replay of buffered car bytes failed: ${e.javaClass.simpleName}")
            }
        }
        onState?.invoke("Relaying phone ↔ car")
        startRelaySupervisor()
        }
    }

    /**
     * The reference dongle does not nurse a single session: on any failure it disables the
     * gadget, sleeps 2 s and starts a fresh cycle (aawgd.cpp: disableGadget(); sleep(2)).
     * We cannot disable the gadget, so the equivalent is closing and reopening the accessory
     * descriptor and letting the head unit start over. Bounded, so a broken car or phone
     * cannot spin forever.
     */
    private fun startRelaySupervisor() {
        if (supervising) return
        supervising = true
        supervisor = Thread({
            var cycles = 0
            var lastToCar = -1L
            var lastToPhone = -1L
            var lastChangeAt = System.currentTimeMillis()
            try {
                while (running && supervising) {
                    Thread.sleep(1_000)
                    if (!running || !supervising) break
                    val phone = pendingPhone
                    if (phone == null || phone.isClosed) {
                        log.i(TAG, "Supervisor: no phone on TCP — cycle owner finished")
                        break
                    }
                    if (relay.isRunning()) {
                        val toCar = relay.bytesToCar()
                        val toPhone = relay.bytesToPhone()
                        if (toCar != lastToCar || toPhone != lastToPhone) {
                            lastToCar = toCar
                            lastToPhone = toPhone
                            lastChangeAt = System.currentTimeMillis()
                            cycles = 0
                        } else if (System.currentTimeMillis() - lastChangeAt > RELAY_SILENCE_MS) {
                            // Log only. Resetting here is what killed a healthy session on an
                            // age timer: closing the descriptor does not make the HU restart
                            // AAP, it makes every later read return EIO.
                            log.w(TAG, "Supervisor: no relay traffic for ${RELAY_SILENCE_MS}ms (watching, not resetting)")
                            lastChangeAt = System.currentTimeMillis()
                        }
                        continue
                    }
                    cycles++
                    if (cycles > MAX_RELAY_CYCLES) {
                        log.e(TAG, "Supervisor: relay dead and the car side will not revive — a physical replug is required")
                        break
                    }
                    log.w(
                        TAG,
                        "Supervisor: relay down with a phone attached — one re-handoff attempt $cycles/$MAX_RELAY_CYCLES " +
                            "(no descriptor reset: it does not work, measured)"
                    )
                    Thread.sleep(2_000)
                    if (!running || !supervising) break
                    if (carUsb?.isOpen() == false) carUsb?.openAttachedAccessory()
                    maybeStartRelay()
                    lastToCar = -1L
                    lastToPhone = -1L
                    lastChangeAt = System.currentTimeMillis()
                }
            } catch (e: InterruptedException) {
                log.i(TAG, "Supervisor interrupted")
            } finally {
                supervising = false
                log.i(TAG, "Supervisor stopped")
            }
        }, "RelaySupervisor").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        desk.stop()
        try { deskSocket?.close() } catch (_: Exception) {}
        deskSocket = null
        running = false
        supervising = false
        supervisor?.interrupt()
        supervisor = null
        log.i(TAG, "=== Bridge stopping ===")
        relay.stop()
        try { pendingPhone?.close() } catch (_: Exception) {}
        pendingPhone = null
        btPeerAddress = null
        tcp.stop()
        rfcomm.stop()
        hfp.stop()
        hotspot.stop()
        info = null
        onState?.invoke("Stopped")
    }

    private fun handlePhone(socket: BluetoothSocket) {
        Thread({
            try {
                // Give the newest client row its Bluetooth name once it appears on RFCOMM.
                try {
                    val nm = socket.remoteDevice?.name
                    val ip = lastPhoneIp
                    if (!nm.isNullOrBlank()) btPeerName = nm
                    if (!nm.isNullOrBlank() && ip != null) {
                        synchronized(clientRecords) {
                            clientRecords.firstOrNull { it.ip == ip }?.name = nm
                        }
                    }
                } catch (e: Exception) {
                    log.w(TAG, "Could not read remote device name: ${e.message}")
                }
                val inp = socket.inputStream
                val out = socket.outputStream
                // The phone can reach the WPP RFCOMM before the P2P group finishes coming up.
                // Wait for the hotspot credentials instead of failing: the phone retries on its
                // own, but failing here spams the log and adds a full retry round-trip to the
                // connect. Measured: the AP takes ~6 s and the phone arrives ~4 s in.
                if (info == null) {
                    log.i(TAG, "Phone on WPP before the AP is up — waiting for hotspot credentials")
                    var waited = 0L
                    while (info == null && waited < WPP_WAIT_MS) {
                        try { Thread.sleep(200) } catch (e: InterruptedException) {
                            socket.close(); return@Thread
                        }
                        waited += 200
                    }
                    if (info == null) {
                        log.e(TAG, "Hotspot never came up within ${WPP_WAIT_MS}ms — giving up on this WPP session")
                        socket.close()
                        return@Thread
                    }
                }
                val h = info ?: return@Thread

                log.i(TAG, "WPP: sending WifiStartRequest (ip=${h.apIp}, port=$PORT)")
                WppProtocol.write(
                    out,
                    WppProtocol.MSG_WIFI_START_REQUEST,
                    WppProtocol.wifiStartRequest(h.apIp, PORT)
                )

                val m1 = WppProtocol.read(inp)
                log.i(TAG, "WPP: received ${m1.idName()} (${m1.payload.size} bytes)${payloadPreview(m1.payload)}")
                if (m1.id != WppProtocol.MSG_WIFI_INFO_REQUEST) {
                    log.w(TAG, "WPP: expected WifiInfoRequest(2), got ${m1.idName()} — continuing anyway")
                }

                log.i(
                    TAG,
                    "WPP: sending WifiInfoResponse (ssid=\"${h.ssid}\", bssid=${h.bssid}, " +
                        "securityMode=${h.securityMode}, apType=${WppProtocol.AP_TYPE_DYNAMIC})"
                )
                WppProtocol.write(
                    out,
                    WppProtocol.MSG_WIFI_INFO_RESPONSE,
                    WppProtocol.wifiInfoResponse(
                        h.ssid, h.password, h.bssid,
                        h.securityMode, WppProtocol.AP_TYPE_DYNAMIC
                    )
                )

                log.i(TAG, "WPP exchange complete; RFCOMM held open, logging further messages")
                while (running) {
                    val m = WppProtocol.read(inp)
                    log.i(TAG, "WPP: received ${m.idName()} (${m.payload.size} bytes)${payloadPreview(m.payload)}")
                }
            } catch (e: EOFException) {
                log.i(TAG, "Phone closed RFCOMM link")
            } catch (e: Exception) {
                log.e(TAG, "WPP exchange failed", e)
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }, "WppSession").start()
    }

    private fun payloadPreview(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val hex = payload.take(48).joinToString(" ") { "%02X".format(it) }
        val ascii = payload.take(48).map { if (it in 32..126) it.toChar() else '.' }.joinToString("")
        return " [$hex | $ascii]"
    }
}
