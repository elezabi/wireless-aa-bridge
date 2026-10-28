package com.ezabi.wirelessaabridge.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.ezabi.wirelessaabridge.logging.LogManager
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth RFCOMM server advertising the Android Auto Wireless service UUID.
 *
 * The phone is the RFCOMM client; we (the head-unit side) are the server. The
 * reference dongle registers exactly this UUID as a Bluez profile, so the SDP
 * record created by listenUsingRfcommWithServiceRecord() is the equivalent.
 *
 * NOTE: the client phone must be paired with this device first. A real head unit
 * triggers pairing itself; a phone acting as head unit cannot, so pairing is a
 * manual step (documented in the README).
 */
class BtRfcommServer(private val context: Context) {

    companion object {
        val AA_WIRELESS_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        const val TAG = "BtRfcomm"
        private const val SDP_NAME = "Android Auto"
    }

    private val log = LogManager.getInstance(context)

    @Volatile private var running = false
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

    /** Called on the accept thread when a phone completes the RFCOMM connection. */
    var onConnection: ((BluetoothSocket) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun isRunning(): Boolean = running

    fun start(): Boolean {
        if (running) return true
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            onError?.invoke("Device has no Bluetooth adapter")
            return false
        }
        if (!adapter.isEnabled) {
            onError?.invoke("Bluetooth is disabled")
            return false
        }
        return try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, AA_WIRELESS_UUID)
            running = true
            log.i(
                TAG,
                "RFCOMM server listening: SDP name=\"$SDP_NAME\", UUID=$AA_WIRELESS_UUID, " +
                    "adapter=${adapter.name}, local address=${adapter.address}, " +
                    "discoverable=${adapter.scanMode}"
            )
            acceptThread = Thread({ acceptLoop() }, "BtRfcommAccept").also { it.start() }
            true
        } catch (e: SecurityException) {
            log.e(TAG, "Missing BLUETOOTH permission for RFCOMM listen", e)
            onError?.invoke("Bluetooth permission denied: ${e.message}")
            false
        } catch (e: IOException) {
            log.e(TAG, "Failed to open RFCOMM server socket", e)
            onError?.invoke("RFCOMM listen failed: ${e.message}")
            false
        }
    }

    private fun acceptLoop() {
        while (running) {
            val sock = try {
                serverSocket?.accept()
            } catch (e: IOException) {
                if (running) {
                    log.e(TAG, "accept() failed", e)
                    onError?.invoke("RFCOMM accept failed: ${e.message}")
                }
                null
            }
            if (sock == null) {
                if (running) continue else break
            } else {
                val dev = sock.remoteDevice
                log.i(
                    TAG,
                    "Phone connected over RFCOMM: name=${safeName(dev?.name)}, " +
                        "address=${dev?.address}, socket=${sock.connectionType}"
                )
                try {
                    onConnection?.invoke(sock)
                } catch (e: Exception) {
                    log.e(TAG, "Connection handler threw", e)
                    try { sock.close() } catch (_: Exception) {}
                }
            }
        }
        log.i(TAG, "RFCOMM accept loop ended")
    }

    private fun safeName(name: String?): String = try {
        name ?: "unknown"
    } catch (e: SecurityException) {
        "unknown(no permission)"
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { log.w(TAG, "close server socket: ${e.message}") }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        log.i(TAG, "RFCOMM server stopped")
    }
}
