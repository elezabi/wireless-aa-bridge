package com.ezabi.wirelessaabridge.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.ezabi.wirelessaabridge.logging.LogManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Minimal HFP hands-free endpoint.
 *
 * Why this exists: Android Auto on the phone side refuses to start wireless projection
 * unless the head unit has HFP connected ("WIRELESS_SETUP_FAILED_TO_START_NO_HFP_FROM_
 * HU_PRESENCE"). In HFP the car is the HF and the phone is the AG, so a phone acting as
 * a head unit must present the HF role. Android's own stack only implements AG, so we
 * publish the HF/HS SDP records ourselves and answer the AT command sequence the phone's
 * AG uses to bring up a Service Level Connection.
 *
 * Once the SLC completes, the phone's BluetoothHeadset profile reports state CONNECTED
 * for this device and AA's head-unit presence check is satisfied.
 */
class HfpHandsfreeServer(private val context: Context) {

    companion object {
        val UUID_HF: UUID = UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB") // Handsfree
        val UUID_HS: UUID = UUID.fromString("00001108-0000-1000-8000-00805F9B34FB") // Headset
        val UUID_AG: UUID = UUID.fromString("00001112-0000-1000-8000-00805F9B34FB") // Handsfree AG (phone side)
        const val TAG = "HfpHF"
        private const val SDP_HF = "Android Auto HF"
        private const val SDP_HS = "Android Auto HS"
    }

    private val log = LogManager.getInstance(context)

    @Volatile private var running = false

    /**
     * One dialler at a time. Every replug calls onCarReady() again, and without this guard
     * a stack of retry threads piles up — each one fighting the others for the phone's AG
     * channel and logging contradictory attempts.
     */
    @Volatile private var outboundActive = false
    @Volatile private var outboundGen = 0
    private val sockets = mutableListOf<BluetoothServerSocket>()
    private val threads = mutableListOf<Thread>()

    var onScl: ((String) -> Unit)? = null

    /** Fired when an HFP session ends, so the UI can show BT as disconnected. */
    var onSessionEnd: ((String) -> Unit)? = null

    fun start(): Boolean {
        if (running) return true
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            log.e(TAG, "No Bluetooth adapter")
            return false
        }
        if (!adapter.isEnabled) {
            log.e(TAG, "Bluetooth disabled; cannot publish HF profile")
            return false
        }
        // running must be true BEFORE the accept threads start, or they see false and
        // exit immediately while the SDP record stays published — connections then hang.
        running = true
        val hfOk = listen(adapter, UUID_HF, SDP_HF)
        val hsOk = listen(adapter, UUID_HS, SDP_HS)
        if (!hfOk && !hsOk) running = false
        log.i(TAG, "HFP HF endpoint ${if (running) "listening" else "FAILED"} (HF=$hfOk HS=$hsOk)")
        return running
    }

    /**
     * The head unit must not wait to be noticed. In the car run of 2026-10-28 nothing
     * happened on the phone for 3 min 12 s until Bluetooth was nudged by hand; once the
     * phone did start, HFP -> WPP -> TCP took 2 s. The reference dongle initiates the
     * connection itself (BluetoothHandler::connectWithRetry), and the car's patience is
     * only 21-105 s, so waiting for the phone to poll can never work.
     */
    fun connectToPairedAg(maxAttempts: Int = 40, gapMs: Long = 7_000) {
        if (outboundActive) {
            log.i(TAG, "Outbound HFP: dialler already running — not starting another")
            return
        }
        outboundActive = true
        val gen = outboundGen
        Thread({
          try {
            // Cold start ordering: the accessory is opened the instant it attaches, but the
            // bridge (and therefore this HFP server) starts ~1.2 s later. Return here and
            // the dialler dies before Bluetooth is up, so wait for the server first.
            var waited = 0L
            while (!running && waited < 30_000) {
                Thread.sleep(500)
                waited += 500
            }
            if (!running) {
                log.w(TAG, "Outbound HFP: HFP server never started — not dialling")
                return@Thread
            }
            val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (e: Exception) { null }
            if (adapter == null) { log.w(TAG, "Outbound HFP: no Bluetooth adapter"); return@Thread }
            for (attempt in 1..maxAttempts) {
                if (!running || gen != outboundGen) return@Thread
                val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { null }
                if (bonded.isNullOrEmpty()) {
                    log.w(TAG, "Outbound HFP: no bonded devices")
                } else {
                    for (dev in bonded) {
                        if (!running || gen != outboundGen) return@Thread
                        val name = safeName(dev.name)
                        // The S9+ is also bonded to the car itself (Toyota Touch). Never
                        // ring the car's own AG.
                        if (name.contains("toyota", ignoreCase = true)) continue
                        try {
                            if (attempt == 1 || attempt % 5 == 0) {
                                log.i(TAG, "Outbound HFP: dialling ${dev.address} ($name) attempt $attempt/$maxAttempts")
                            }
                            val socket = dev.createRfcommSocketToServiceRecord(UUID_AG)
                            socket.connect()
                            log.i(TAG, "Outbound HFP: connected to ${dev.address} — running HF session")
                            runSession(socket, dev.address)
                            return@Thread
                        } catch (e: Exception) {
                            // Expected while the phone's Bluetooth is off: keep dialling,
                            // the client may come back at any time inside the car's patience.
                            if (attempt == 1 || attempt % 5 == 0) {
                                log.w(TAG, "Outbound HFP to ${dev.address} failed: ${e.javaClass.simpleName}: ${e.message}")
                            }
                        }
                    }
                }
                if (attempt < maxAttempts) Thread.sleep(gapMs)
            }
            log.i(TAG, "Outbound HFP: gave up after $maxAttempts attempts")
          } finally {
            // Always release the guard, or one early exit disables dialling forever.
            outboundActive = false
          }
        }, "HfOutbound").apply { isDaemon = true; start() }
    }

    /**
     * Release the dialler so the next accessory attach can dial again. Without this a single
     * 40-attempt dialler (about 4.7 minutes) starved every later attach — measured: the
     * replug at 13:55:50 did nothing but log "dialler already running".
     */
    fun resetOutbound() {
        outboundGen++
        outboundActive = false
        log.i(TAG, "Outbound HFP: dialler reset — the next attach will dial again")
    }

    private fun listen(adapter: BluetoothAdapter, uuid: UUID, name: String): Boolean {
        return try {
            val ss = adapter.listenUsingRfcommWithServiceRecord(name, uuid)
            sockets.add(ss)
            log.i(TAG, "HF listener up: name=\"$name\", uuid=$uuid (SDP published)")
            Thread({ acceptLoop(ss, uuid) }, "HfAccept-${uuid.toString().take(8)}").also {
                threads.add(it)
                it.start()
            }
            true
        } catch (e: SecurityException) {
            log.e(TAG, "Missing permission for HF listener $uuid: ${e.message}")
            false
        } catch (e: IOException) {
            log.e(TAG, "Failed to publish HF listener $uuid: ${e.message}")
            false
        }
    }

    private fun acceptLoop(ss: BluetoothServerSocket, uuid: UUID) {
        while (running) {
            val sock = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) log.w(TAG, "accept failed on $uuid: ${e.message}")
                null
            } ?: continue
            val dev = sock.remoteDevice
            log.i(TAG, "HFP connection from name=${safeName(dev?.name)} address=${dev?.address} on $uuid")
            Thread({ runSession(sock, dev?.address ?: "?") }, "HfSession").start()
        }
        log.i(TAG, "HF accept loop ended for $uuid")
    }

    private fun safeName(n: String?): String = try { n ?: "unknown" } catch (e: SecurityException) { "unknown" }

    /**
     * In HFP the HF side (us) opens the SLC: it sends AT+BRSF first, then drives
     * CIND=? / CIND? / CMER. A passive responder gets a 10-second silence and the AG
     * hangs up, which also tears down the AA session that was already handshaking.
     */
    private fun runSession(socket: BluetoothSocket, addr: String) {
        try {
            val inp: InputStream = socket.inputStream
            val out: OutputStream = socket.outputStream

            fun send(cmd: String) {
                out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
                out.flush()
                log.i(TAG, "HF->AG[$addr]: $cmd")
            }

            send("AT+BRSF=893")
            var state = 1
            var sclDone = false
            val sb = StringBuilder()
            val buf = ByteArray(512)

            while (running) {
                val n = inp.read(buf)
                if (n < 0) {
                    log.i(TAG, "AG[$addr] closed the socket")
                    break
                }
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                while (true) {
                    val cr = sb.indexOf("\r")
                    val lf = sb.indexOf("\n")
                    val end = listOf(cr, lf).filter { it >= 0 }.minOrNull() ?: break
                    val line = sb.substring(0, end).trim()
                    sb.delete(0, end + 1)
                    if (line.isEmpty()) continue
                    log.i(TAG, "AG[$addr]: $line")

                    if (line.equals("OK", ignoreCase = true)) {
                        when (state) {
                            1 -> { send("AT+CIND=?"); state = 2 }
                            2 -> { send("AT+CIND?"); state = 3 }
                            3 -> { send("AT+CMER=3,0,0,1"); state = 4 }
                            4 -> {
                                state = 5
                                sclDone = true
                                log.i(TAG, "HFP SLC established with $addr — phone should now report HFP connected")
                                onScl?.invoke(addr)
                            }
                            else -> { /* acknowledgements for AG-initiated commands */ }
                        }
                    } else if (line.uppercase().startsWith("AT")) {
                        // AG-initiated command (CLIP, VGS, CHLD, ...)
                        out.write(handle(line).toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                }
            }
            if (!sclDone) log.w(TAG, "HFP session with $addr ended before SLC completed")
        } catch (e: Exception) {
            log.w(TAG, "HFP session error with $addr: ${e.javaClass.simpleName}: ${e.message}")
            onSessionEnd?.invoke(addr)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handle(cmd: String): String {
        val c = cmd.trim().uppercase()
        return when {
            c.startsWith("AT+BRSF") ->
                "+BRSF: 893\r\n\r\nOK\r\n"
            c.startsWith("AT+CIND=?") ->
                "+CIND: (\"service\",(0-1)),(\"call\",(0-1)),(\"callsetup\",(0-3))," +
                    "(\"battchg\",(0-5)),(\"signal\",(0-5)),(\"roam\",(0-1)),(\"callheld\",(0-2))\r\n\r\nOK\r\n"
            c.startsWith("AT+CIND?") ->
                "+CIND: 1,0,0,5,5,0,0\r\n\r\nOK\r\n"
            c.startsWith("AT+CMER") -> "\r\nOK\r\n"
            c.startsWith("AT+CHLD=?") -> "+CHLD: (0,1,2,3)\r\n\r\nOK\r\n"
            c.startsWith("AT+CLIP") -> "\r\nOK\r\n"
            c.startsWith("AT+COPS") -> "+COPS: 0,0,\"Network\"\r\n\r\nOK\r\n"
            c.startsWith("AT+VGS") -> "\r\nOK\r\n"
            c.startsWith("AT+VGM") -> "\r\nOK\r\n"
            c.startsWith("AT+BIA") -> "\r\nOK\r\n"
            c.startsWith("AT+NREC") -> "\r\nOK\r\n"
            c.startsWith("AT+XAPL") || c.startsWith("AT+IPHONEACCEV") -> "\r\nOK\r\n"
            c.startsWith("AT+CREG") -> "+CREG: 0,1\r\n\r\nOK\r\n"
            c == "AT" || c.startsWith("AT+") || c.startsWith("ATA") || c.startsWith("ATD") ->
                "\r\nOK\r\n"
            else -> "\r\nOK\r\n"
        }
    }

    fun stop() {
        running = false
        for (s in sockets) try { s.close() } catch (_: Exception) {}
        sockets.clear()
        for (t in threads) t.interrupt()
        threads.clear()
        log.i(TAG, "HFP HF server stopped")
    }
}
