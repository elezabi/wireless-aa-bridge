package com.ezabi.wirelessaabridge.relay

import android.content.Context
import com.ezabi.wirelessaabridge.logging.LogManager
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Transparent byte relay between the phone's TLS socket and the car's USB
 * accessory streams.
 *
 * No protocol awareness by design: the car is the head unit and the phone is the
 * phone, so their AAP conversation is forwarded untouched in both directions.
 * Anything that parses or rewrites frames here would be a bug, not a feature.
 */
class RelayEngine(private val context: Context) {

    companion object {
        const val TAG = "Relay"
        private const val LOG_EVERY_BYTES = 64 * 1024L
    }

    private val log = LogManager.getInstance(context)

    @Volatile private var running = false
    private var phoneToCar: Thread? = null
    private var carToPhone: Thread? = null

    private val toCarBytes = AtomicLong(0)
    private val toPhoneBytes = AtomicLong(0)
    private val loggedToCarFirst = AtomicBoolean(false)
    private val loggedToPhoneFirst = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private val framesPhoneToCar = AtomicLong(0)
    private val framesCarToPhone = AtomicLong(0)
    private val firstDropped = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastFrame: String = "no frames yet"

    fun isRunning(): Boolean = running

    /** Live counters for the status screen. */
    fun bytesToCar(): Long = toCarBytes.get()
    fun bytesToPhone(): Long = toPhoneBytes.get()

    fun framesToCar(): Long = framesPhoneToCar.get()
    fun framesFromCar(): Long = framesCarToPhone.get()

    /** Most recent frame seen in either direction, for the on-screen status. */
    fun lastFrameSummary(): String = lastFrame

    fun start(tls: Socket, carOut: OutputStream, carIn: InputStream? = null, dropFirstPhoneFrame: Boolean = false) {
        if (running) {
            log.w(TAG, "Relay already running; refusing second session")
            return
        }
        running = true
        toCarBytes.set(0)
        toPhoneBytes.set(0)
        loggedToCarFirst.set(false)
        loggedToPhoneFirst.set(false)
        log.i(TAG, "=== Relay started: phone <-> car ===")

        phoneToCar = Thread({
            pump(
                src = tls.getInputStream(), dst = carOut, label = "phone->car",
                counter = toCarBytes, firstLogged = loggedToCarFirst,
                dropFirst = dropFirstPhoneFrame
            )
        }, "RelayPhoneToCar").also { it.start() }

        if (carIn != null) {
            carToPhone = Thread({
                pump(
                    src = carIn, dst = tls.getOutputStream(), label = "car->phone",
                    counter = toPhoneBytes, firstLogged = loggedToPhoneFirst
                )
            }, "RelayCarToPhone").also { it.start() }
        }
    }

    private fun pump(
        src: InputStream,
        dst: OutputStream,
        label: String,
        counter: AtomicLong,
        firstLogged: AtomicBoolean,
        dropFirst: Boolean = false
    ) {
        val buf = ByteArray(16 * 1024)
        try {
            while (running) {
                val n = src.read(buf)
                if (n <= 0) {
                    log.i(TAG, "$label: peer closed (read=$n), stopping relay")
                    break
                }
                // The bridge may have already answered the car's version request with a
                // synthetic response; the phone's own (identical) VERSION_RESPONSE is then a
                // duplicate the head unit is no longer expecting. Drop exactly the first frame.
                var dropped = false
                if (dropFirst && !firstDropped.getAndSet(true)) {
                    if (n >= 6 && (buf[4].toInt() and 0xFF) == 0x00 && (buf[5].toInt() and 0xFF) == 0x02) {
                        log.i(TAG, "$label: dropped duplicate VERSION_RESPONSE ($n bytes)")
                        dropped = true
                    }
                }
                if (!dropped) {
                    dst.write(buf, 0, n)
                    dst.flush()
                }
                val total = counter.addAndGet(n.toLong())
                val frames = frameCount.addAndGet(1L)
                if (label == "phone->car") framesPhoneToCar.incrementAndGet() else framesCarToPhone.incrementAndGet()
                lastFrame = "$label ${describeFrame(buf, n)}"
                if (frames <= 10) {
                    // AAP header is 4 bytes (1 chan, 1 flags, 2 length) and grows to 8 when
                    // the FIRST flag is set; the message id leads the payload. Naming the
                    // frames is what makes one expensive car run readable.
                    log.i(TAG, "$label frame #$frames [$n bytes]: ${describeFrame(buf, n)}")
                    log.i(TAG, "$label frame #$frames hex: ${hex(buf, n, 48)}")
                } else if (firstLogged.compareAndSet(false, true)) {
                    log.i(TAG, "$label: first $n bytes: ${hex(buf, n, 32)}")
                } else if (total % LOG_EVERY_BYTES < n) {
                    log.d(TAG, "$label: $total bytes so far")
                }
            }
        } catch (e: Exception) {
            log.e(TAG, "$label pump failed: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            running = false
            log.i(TAG, "$label ended after $frameCount frames")
            log.i(TAG, "Relay stopped. phone->car=${toCarBytes.get()} bytes, car->phone=${toPhoneBytes.get()} bytes")
        }
    }

    private fun describeFrame(b: ByteArray, n: Int): String {
        if (n < 4) return "short frame (${hex(b, n, 16)})"
        // A TLS handshake record starts 16 03 0x. Seeing this means the AAP handshake got
        // past version exchange and the phone started the encrypted channel.
        if ((b[0].toInt() and 0xFF) == 0x16 && (b[1].toInt() and 0xFF) == 0x03) {
            val rec = when (b[2].toInt() and 0xFF) {
                0x01 -> "CLIENT_HELLO"
                0x02 -> "SERVER_HELLO"
                0x0b -> "CERTIFICATE"
                0x0e -> "SERVER_HELLO_DONE"
                0x10 -> "CLIENT_KEY_EXCHANGE"
                0x14 -> "FINISHED"
                else -> "handshake(type=${b[2].toInt() and 0xFF})"
            }
            return "TLS record: $rec ($n bytes)"
        }
        val chan = b[0].toInt() and 0xFF
        val flags = b[1].toInt() and 0xFF
        val len = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val first = (flags and 0x01) != 0
        val last = (flags and 0x02) != 0
        val enc = (flags and 0x08) != 0
        val headerLen = 4
        val msgId = if (n > 5) {
            ((b[headerLen].toInt() and 0xFF) shl 8) or (b[headerLen + 1].toInt() and 0xFF)
        } else -1
        val kind = StringBuilder()
        if (first) kind.append("first ")
        if (last) kind.append("last ")
        if (enc) kind.append("encrypted ")
        return "chan=$chan flags=$flags [${kind.toString().trim()}] declaredLen=$len msgId=$msgId actual=$n"
    }

    private fun hex(b: ByteArray, len: Int, max: Int): String {
        val n = minOf(len, max)
        val h = (0 until n).joinToString(" ") { "%02X".format(b[it]) }
        return if (len > max) "$h …" else h
    }

    fun stop() {
        running = false
        phoneToCar?.interrupt()
        carToPhone?.interrupt()
        phoneToCar = null
        carToPhone = null
    }
}
