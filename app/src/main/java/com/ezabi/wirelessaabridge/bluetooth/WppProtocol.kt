package com.ezabi.wirelessaabridge.bluetooth

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * WPP (WiFi Projection Protocol) wire format, as implemented by the reference
 * dongle (nisargjhaveri/WirelessAndroidAutoDongle, bluetoothProfiles.cpp):
 *
 *   [2B big-endian payload length][2B big-endian message id][protobuf payload]
 *
 * Message ids are fixed and small (see MSG_* constants). The protobuf bodies are
 * proto2 lite; field numbers below were taken verbatim from the reference .proto
 * files, not guessed.
 */
object WppProtocol {

    const val MSG_WIFI_START_REQUEST = 1
    const val MSG_WIFI_INFO_REQUEST = 2
    const val MSG_WIFI_INFO_RESPONSE = 3
    const val MSG_WIFI_VERSION_REQUEST = 4
    const val MSG_WIFI_VERSION_RESPONSE = 5
    const val MSG_WIFI_CONNECT_STATUS = 6
    const val MSG_WIFI_START_RESPONSE = 7

    // SecurityMode enum (WifiInfoResponse.proto)
    const val SECURITY_OPEN = 1
    const val SECURITY_WPA2_PERSONAL = 8
    const val SECURITY_WPA_WPA2_PERSONAL = 12

    // AccessPointType enum
    const val AP_TYPE_STATIC = 0
    const val AP_TYPE_DYNAMIC = 1

    const val MAX_PAYLOAD = 64 * 1024

    data class WppMessage(val id: Int, val payload: ByteArray) {
        fun idName(): String = name(id)
    }

    fun name(id: Int): String = when (id) {
        MSG_WIFI_START_REQUEST -> "WifiStartRequest"
        MSG_WIFI_INFO_REQUEST -> "WifiInfoRequest"
        MSG_WIFI_INFO_RESPONSE -> "WifiInfoResponse"
        MSG_WIFI_VERSION_REQUEST -> "WifiVersionRequest"
        MSG_WIFI_VERSION_RESPONSE -> "WifiVersionResponse"
        MSG_WIFI_CONNECT_STATUS -> "WifiConnectStatus"
        MSG_WIFI_START_RESPONSE -> "WifiStartResponse"
        else -> "unknown($id)"
    }

    fun encode(id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = ((payload.size ushr 8) and 0xFF).toByte()
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((id ushr 8) and 0xFF).toByte()
        out[3] = (id and 0xFF).toByte()
        payload.copyInto(out, 4)
        return out
    }

    fun write(out: OutputStream, id: Int, payload: ByteArray = ByteArray(0)) {
        out.write(encode(id, payload))
        out.flush()
    }

    /** Blocking read of one whole WPP message. Throws EOFException on clean EOF. */
    fun read(input: InputStream): WppMessage {
        val hdr = readExact(input, 4) ?: throw EOFException("EOF while reading WPP header")
        val len = ((hdr[0].toInt() and 0xFF) shl 8) or (hdr[1].toInt() and 0xFF)
        val id = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
        if (len > MAX_PAYLOAD) throw IOException("WPP length out of range: $len")
        val payload = if (len == 0) ByteArray(0)
        else (readExact(input, len) ?: throw EOFException("EOF while reading WPP payload ($len bytes)"))
        return WppMessage(id, payload)
    }

    fun readExact(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r <= 0) return null
            off += r
        }
        return buf
    }

    // ---- minimal proto2 writer (wire types 0 and 2 only, all these messages need) ----

    private fun varint(value: Int): ByteArray {
        var v = value
        val out = ArrayList<Byte>(8)
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.add(v.toByte())
                break
            }
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        return out.toByteArray()
    }

    private fun key(field: Int, wire: Int) = varint((field shl 3) or wire)

    fun pbString(field: Int, s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return key(field, 2) + varint(b.size) + b
    }

    fun pbInt(field: Int, v: Int): ByteArray = key(field, 0) + varint(v)

    /** WifiStartRequest { required string ip_address = 1; required int32 port = 2; } */
    fun wifiStartRequest(ip: String, port: Int): ByteArray =
        pbString(1, ip) + pbInt(2, port)

    /**
     * WifiInfoResponse {
     *   required string ssid = 1; required string key = 2; required string bssid = 3;
     *   required SecurityMode security_mode = 4; required AccessPointType access_point_type = 5;
     * }
     */
    fun wifiInfoResponse(
        ssid: String,
        key: String,
        bssid: String,
        securityMode: Int,
        accessPointType: Int
    ): ByteArray =
        pbString(1, ssid) + pbString(2, key) + pbString(3, bssid) +
            pbInt(4, securityMode) + pbInt(5, accessPointType)

    /** Best-effort decode of a WifiInfoResponse-shaped payload, for logging only. */
    fun describeWifiInfoResponse(payload: ByteArray): String {
        var i = 0
        val fields = LinkedHashMap<Int, String>()
        while (i < payload.size) {
            // read varint key
            var shift = 0
            var k = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF
                i++
                k = k or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
                if (shift > 28) return "unparseable (bad key varint)"
            }
            val field = k ushr 3
            val wire = k and 0x7
            when (wire) {
                0 -> { // varint
                    var v = 0
                    shift = 0
                    while (i < payload.size) {
                        val b = payload[i].toInt() and 0xFF
                        i++
                        v = v or ((b and 0x7F) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    fields[field] = v.toString()
                }
                2 -> { // length-delimited
                    var len = 0
                    shift = 0
                    while (i < payload.size) {
                        val b = payload[i].toInt() and 0xFF
                        i++
                        len = len or ((b and 0x7F) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    if (i + len > payload.size) return "unparseable (truncated field $field)"
                    val bytes = payload.copyOfRange(i, i + len)
                    i += len
                    fields[field] = "\"${String(bytes, Charsets.UTF_8)}\""
                }
                5 -> i += 4
                1 -> i += 8
                else -> return "unparseable (wire type $wire at field $field)"
            }
        }
        return fields.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }
}
