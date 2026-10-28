package com.ezabi.wirelessaabridge.relay

import android.content.Context
import com.ezabi.wirelessaabridge.logging.LogManager
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Plain TCP listener on the port advertised in WifiStartRequest.
 *
 * Deliberately NOT TLS. The reference dongle (WirelessAndroidAutoDongle) is a byte pipe:
 * proxyHandler.cpp does accept() and forwards, and its Makefile links no TLS library. The
 * phone does not send a ClientHello — it connects and waits for the head unit to speak
 * first. Terminating TLS here stalls the session until the phone gives up (~10 s) and
 * then it retries WPP forever.
 */
class TcpPhoneServer(private val context: Context, private val port: Int) {

    companion object {
        const val TAG = "TcpPhone"
    }

    private val log = LogManager.getInstance(context)

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Fired on the accept thread with a live socket from the phone. */
    var onPhoneConnected: ((Socket) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun start(): Boolean {
        if (running) return true
        return try {
            serverSocket = ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"))
            running = true
            log.i(TAG, "TCP listening on 0.0.0.0:$port (plain AAP relay, no TLS)")
            acceptThread = Thread({ acceptLoop() }, "TcpPhoneAccept").also { it.start() }
            true
        } catch (e: Exception) {
            log.e(TAG, "Failed to bind TCP $port: ${e.javaClass.simpleName}: ${e.message}")
            onError?.invoke("TCP bind failed: ${e.message}")
            false
        }
    }

    private fun acceptLoop() {
        while (running) {
            val sock = try {
                serverSocket?.accept()
            } catch (e: IOException) {
                if (running) log.w(TAG, "accept failed: ${e.message}")
                null
            } ?: continue

            log.i(TAG, "Phone connected: ${sock.inetAddress?.hostAddress}:${sock.port}")
            try {
                onPhoneConnected?.invoke(sock)
            } catch (e: Exception) {
                log.e(TAG, "Connection handler threw: ${e.message}")
                try { sock.close() } catch (_: Exception) {}
            }
        }
        log.i(TAG, "TCP accept loop ended")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        log.i(TAG, "TCP server stopped")
    }
}
