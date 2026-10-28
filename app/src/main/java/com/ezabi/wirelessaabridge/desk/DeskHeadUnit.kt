package com.ezabi.wirelessaabridge.desk

import android.content.Context
import com.ezabi.wirelessaabridge.logging.LogManager
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.net.ServerSocket
import java.net.Socket

/**
 * Stand-in for the head unit, so the whole chain can be exercised at a desk.
 *
 * Car tests are expensive; this makes the phone-side chain testable in seconds. The bridge
 * keeps its real Bluetooth, WPP and Wi-Fi Direct path, and the real client phone still
 * completes HFP -> WPP -> AP join -> TCP. Only the USB side is replaced: instead of the
 * car's accessory streams, the relay talks to this loopback socket, which speaks first
 * exactly like a head unit (version request), then reports whatever the phone answers.
 *
 * What it can prove at a desk:
 *   - the phone accepts our WPP credentials and joins the AP
 *   - the phone opens TCP and answers the HU version request
 *   - the phone starts TLS after that (ClientHello reaching "the car")
 * What it cannot prove: that a specific car accepts our presence. Only the car can.
 */
class DeskHeadUnit(private val context: Context) {

    companion object {
        const val TAG = "DeskHU"
        private const val PORT = 5289
        // Exactly the frame a real HU sends the instant the accessory is opened:
        // chan=0, flags=3, declaredLen=6, msgId=1 (version request), payload 1 byte = 5
        val VERSION_REQUEST = byteArrayOf(
            0x00, 0x03, 0x00, 0x06, 0x00, 0x01, 0x00, 0x01, 0x00, 0x05
        )
    }

    private val log = LogManager.getInstance(context)

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                val ss = ServerSocket(PORT, 1, java.net.InetAddress.getByName("127.0.0.1"))
                server = ss
                log.i(TAG, "Fake head unit listening on 127.0.0.1:$PORT (desk mode)")
                val socket = ss.accept()
                log.i(TAG, "Bridge connected to the fake head unit — speaking first like a real HU")
                runScript(socket)
            } catch (e: Exception) {
                if (running) log.e(TAG, "Fake HU failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "DeskHU").apply { isDaemon = true; start() }
    }

    private fun runScript(socket: Socket) {
        try {
            val out: OutputStream = socket.getOutputStream()
            val input: InputStream = socket.getInputStream()
            out.write(VERSION_REQUEST)
            out.flush()
            log.i(TAG, "Sent version request: ${hex(VERSION_REQUEST)}")
            val buf = ByteArray(16 * 1024)
            var frames = 0
            while (running) {
                val n = input.read(buf)
                if (n <= 0) {
                    log.i(TAG, "Phone closed the session (read=$n)")
                    break
                }
                frames++
                val data = buf.copyOf(n)
                log.i(TAG, "Frame #$frames from phone [$n bytes]: ${describe(data)}")
                log.i(TAG, "Frame #$frames hex: ${hex(data)}")
                if (frames == 1 && isVersionResponse(data)) {
                    log.i(TAG, "VERDICT: phone answered the version request — the relay carries AAP correctly")
                }
                if ((data[0].toInt() and 0xFF) == 0x16) {
                    log.i(TAG, "VERDICT: TLS started — phone accepted the version exchange and began the encrypted channel")
                }
                if (frames == 1 && isVersionResponse(data)) {
                    // The phone is the TLS SERVER and TLS rides INSIDE SSL_HANDSHAKE (0x0003)
                    // frames on channel 0 — not as raw TLS records. The earlier raw probe got
                    // silence for exactly that reason. Act as a real HU: TLS client, in-band.
                    runInBandTlsClient(socket)
                }
            }
        } catch (e: Exception) {
            log.w(TAG, "Fake HU session ended: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Wrap a TLS record in an SSL_HANDSHAKE AAP frame and send it to the phone. */
    private fun sendSslHandshake(out: OutputStream, tlsRecord: ByteArray) {
        val payload = ByteArray(2 + tlsRecord.size)
        payload[0] = 0x00
        payload[1] = 0x03 // SSL_HANDSHAKE
        System.arraycopy(tlsRecord, 0, payload, 2, tlsRecord.size)
        val frame = ByteArray(4 + payload.size)
        frame[0] = 0x00          // channel 0
        frame[1] = 0x03          // SINGLE frame
        frame[2] = ((payload.size shr 8) and 0xFF).toByte()
        frame[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 4, payload.size)
        out.write(frame)
        out.flush()
        log.i(TAG, "Sent SSL_HANDSHAKE frame (${tlsRecord.size} TLS bytes, ${payload.size} payload)")
    }

    /** The head unit is the TLS client, in-band. Drive a full handshake and report the cert. */
    private fun runInBandTlsClient(socket: Socket) {
        Thread({
            try {
                val tmf = object : javax.net.ssl.TrustManager {}
                val ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
                ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(tmf), null)
                val engine = ctx.createSSLEngine("android-auto", socket.port)
                engine.useClientMode = true
                val p = engine.sslParameters
                p.applicationProtocols = arrayOf("com.google.android.automotive.gal")
                engine.sslParameters = p
                engine.beginHandshake()

                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val appBuf = ByteBuffer.allocate(64 * 1024)
                val netBuf = ByteBuffer.allocate(64 * 1024)

                var guard = 0
                while (guard++ < 40) {
                    when (engine.handshakeStatus) {
                        javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                            netBuf.clear()
                            engine.wrap(ByteBuffer.allocate(0), netBuf)
                            netBuf.flip()
                            val rec = ByteArray(netBuf.remaining())
                            netBuf.get(rec)
                            sendSslHandshake(out, rec)
                        }
                        javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                            // read a frame, expect SSL_HANDSHAKE, feed TLS bytes to engine
                            val hdr = ByteArray(4)
                            if (!readFully(input, hdr)) break
                            val len = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                            val body = ByteArray(len)
                            if (!readFully(input, body)) break
                            val msgId = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                            if (msgId != 0x0003) {
                                log.i(TAG, "TLS: expected SSL_HANDSHAKE, got msgId=$msgId (len=$len)")
                                continue
                            }
                            netBuf.clear()
                            netBuf.put(body, 2, len - 2)
                            netBuf.flip()
                            appBuf.clear()
                            engine.unwrap(netBuf, appBuf)
                        }
                        javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                            var t = engine.delegatedTask
                            while (t != null) { t.run(); t = engine.delegatedTask }
                        }
                        javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED,
                        javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> break
                        else -> break
                    }
                }
                if (engine.session != null && engine.session.isValid) {
                    val peer = engine.session.peerCertificates?.firstOrNull() as? java.security.cert.X509Certificate
                    log.i(TAG, "VERDICT: TLS handshake COMPLETED with the phone — phone is the server")
                    log.i(TAG, "Phone certificate subject: ${peer?.subjectDN}")
                } else {
                    log.i(TAG, "TLS: handshake did not finish (status ${engine.handshakeStatus})")
                }
            } catch (e: Exception) {
                log.w(TAG, "In-band TLS client failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "DeskInBandTls").apply { isDaemon = true; start() }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun isVersionResponse(d: ByteArray): Boolean =
        d.size >= 6 && d[0].toInt() == 0x00 && d[4].toInt() == 0x00 && d[5].toInt() == 0x02

    private fun describe(b: ByteArray): String {
        if (b.size < 4) return hex(b)
        val chan = b[0].toInt() and 0xFF
        val flags = b[1].toInt() and 0xFF
        val len = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val msgId = if (b.size > 5) ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF) else -1
        if ((b[0].toInt() and 0xFF) == 0x16) return "TLS record (${b.size} bytes)"
        return "chan=$chan flags=$flags declaredLen=$len msgId=$msgId actual=${b.size}"
    }

    private fun hex(b: ByteArray): String =
        b.take(48).joinToString(" ") { "%02X".format(it) } + if (b.size > 48) " …" else ""

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        thread?.interrupt()
        thread = null
    }
}
