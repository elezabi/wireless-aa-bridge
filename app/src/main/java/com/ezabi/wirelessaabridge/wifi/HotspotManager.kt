package com.ezabi.wirelessaabridge.wifi

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ezabi.wirelessaabridge.bluetooth.WppProtocol
import com.ezabi.wirelessaabridge.logging.LogManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class HotspotInfo(
    val ssid: String,
    val password: String,
    val bssid: String,
    val securityMode: Int,
    val apIp: String
)

/**
 * Brings up a local-only WiFi hotspot and reads back the credentials that must be
 * handed to the phone in WifiInfoResponse.
 *
 * Automatic mode only: WifiManager.startLocalOnlyHotspot() is the single API that
 * lets an app create an AP without user involvement. Everything about the result
 * (SSID, passphrase, BSSID, AP IP) is Android's choice, not ours, so all of it is
 * discovered at runtime and logged.
 *
 * UNVERIFIED on the target device: whether the S9+ exposes the passphrase via
 * getWifiConfiguration() on Android 10, and whether the phone will accept a
 * dynamically generated SSID/passphrase.
 */
class HotspotManager(private val context: Context) {

    companion object {
        const val TAG = "Hotspot"

        /**
         * Manual mode: the user turns on the phone's own Portable Hotspot with these
         * exact values, and the app simply reports them.
         *
         * Why not set them programmatically: startLocalOnlyHotspot() on Android 10
         * always generates a random 12-hex-character WPA2 key and offers no way to
         * override it (the SoftApConfiguration overload needs API 30).
         */
        const val MANUAL_SSID = "AA-Bridge"
        const val MANUAL_PASSWORD = "aa123456"

        /**
         * Stable AP identity for the Wi-Fi Direct group. Android rejects a group network
         * name that does not start with "DIRECT-xy"
         * (IllegalArgumentException: network name must starts with the prefix DIRECT-xy),
         * but the rest of the name and the passphrase are ours to choose — so clients keep
         * one saved network and a simple, unchanging key instead of a new random
         * AndroidShare_xxxx every session.
         */
        const val P2P_SSID = "DIRECT-AA-Bridge"
        const val P2P_PASS = "aabridge"

        /**
         * When true the app never creates its own access point; it waits for the user's
         * Portable Hotspot and reports MANUAL_SSID/MANUAL_PASSWORD.
         *
         * Must stay false on devices without a SIM: Samsung refuses to enable Portable
         * Hotspot without carrier service ("no SIM"), while startLocalOnlyHotspot() works
         * fine. The cost is that Android picks the passphrase, so the UI shows the
         * credentials as a scannable WiFi QR code instead of asking the user to type them.
         */
        const val MANUAL_ONLY = false

        /**
         * Try the fixed-name AP first: Wi-Fi Direct group owner with MANUAL_SSID /
         * MANUAL_PASSWORD. Stable across sessions, so clients keep one saved network and a
         * simple key instead of a new random AndroidShare_xxxx every run. Falls back to
         * startLocalOnlyHotspot() (random key) if the group cannot be created.
         */
        const val USE_P2P_FIXED = true

        private const val AP_WAIT_MS = 8000L
        private const val P2P_FALLBACK_IP = "192.168.49.1"
    }

    private val log = LogManager.getInstance(context)
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null

    @Volatile private var p2pGroupActive = false

    /**
     * Clients associated with our P2P group. Reading the ARP table is not an option:
     * Android 10 blocks proc-net access for apps (it works from adb shell, which is
     * exactly how that mistake survived testing), so the group's own client list is truth.
     */
    @Volatile private var p2pClientCount = 0

    fun clientCount(): Int = p2pClientCount

    /** Snapshot of the currently live P2P group, if it matches our SSID. */
    private fun existingGroupInfo(): HotspotInfo? {
        val pm = p2pManager ?: return null
        val ch = p2pChannel ?: return null
        var found: WifiP2pGroup? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            pm.requestGroupInfo(ch) { g ->
                found = g
                latch.countDown()
            }
        } catch (_: Exception) {
            return null
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        val group = found ?: return null
        if (group.networkName != P2P_SSID) return null
        val iface = group.`interface`?.takeIf { it.isNotBlank() } ?: return null
        val ip = interfaceIp(iface) ?: return null
        val mac = readInterfaceMac(iface) ?: return null
        return HotspotInfo(
            ssid = P2P_SSID,
            password = P2P_PASS,
            bssid = mac,
            securityMode = WppProtocol.SECURITY_WPA2_PERSONAL,
            apIp = ip
        )
    }

    fun refreshClientCount() {
        val pm = p2pManager ?: return
        val ch = p2pChannel ?: return
        try {
            pm.requestGroupInfo(ch) { g ->
                p2pClientCount = g?.clientList?.size ?: 0
            }
        } catch (_: Exception) {
        }
    }

    fun isActive(): Boolean = reservation != null || p2pGroupActive

    /**
     * Create a Wi-Fi Direct group with a fixed network name and passphrase. The group
     * owner acts as an access point on 192.168.49.1 and accepts ordinary station joins,
     * so the phone's AA network request can connect to it exactly like a normal AP.
     */
    private fun tryStartP2p(): HotspotInfo? {
        return try {
            val pm = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return null
            val ch = pm.initialize(context.applicationContext, Looper.getMainLooper(), null)
                ?: run {
                    log.w(TAG, "Wi-Fi Direct channel unavailable")
                    return null
                }
            p2pManager = pm
            p2pChannel = ch

            // A group from a previous run can still be alive: removeGroup returns BUSY
            // (reason=2) almost every time, so blindly creating another one produced two
            // APs advertising the same SSID. Reuse the live group when it is ours.
            existingGroupInfo()?.let { group ->
                p2pGroupActive = true
                log.i(TAG, "Reusing existing P2P group (ssid=${group.ssid}, ip=${group.apIp})")
                return group
            }

            // A stale group or an in-flight P2P operation makes createGroup fail with
            // BUSY (reason 2). Clear any existing group first and give the stack a moment.
            val clearLatch = CountDownLatch(1)
            pm.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log.i(TAG, "Cleared existing P2P group")
                    clearLatch.countDown()
                }

                override fun onFailure(reason: Int) {
                    log.i(TAG, "No P2P group to clear (reason=$reason)")
                    clearLatch.countDown()
                }
            })
            clearLatch.await(5, TimeUnit.SECONDS)
            Thread.sleep(800)

            val cfg = WifiP2pConfig.Builder()
                .setNetworkName(P2P_SSID)
                .setPassphrase(P2P_PASS)
                .build()

            val latch = CountDownLatch(1)
            var failure = -1
            pm.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    latch.countDown()
                }

                override fun onFailure(reason: Int) {
                    failure = reason
                    latch.countDown()
                }
            })
            if (!latch.await(15, TimeUnit.SECONDS)) {
                log.w(TAG, "createGroup timed out")
                return null
            }
            if (failure != -1) {
                log.w(TAG, "createGroup failed, reason=$failure")
                return null
            }
            p2pGroupActive = true

            // The framework answers asynchronously; give the interface a moment to come up.
            Thread.sleep(1500)

            // Read back what was actually created — some builds override the requested name.
            val groupLatch = CountDownLatch(1)
            var group: WifiP2pGroup? = null
            try {
                pm.requestGroupInfo(ch) { g ->
                    group = g
                    groupLatch.countDown()
                }
                groupLatch.await(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                log.w(TAG, "requestGroupInfo failed: ${e.message}")
            }

            val ssid = group?.networkName?.takeIf { it.isNotBlank() } ?: MANUAL_SSID
            val pass = group?.passphrase?.takeIf { it.isNotBlank() } ?: MANUAL_PASSWORD
            // The BSSID AA validates is the *group interface* MAC (p2p-wlan0-0), which
            // differs from wlan0/p2p0 (e.g. 8e:45:00:b2:6d:e6 vs 8e:45:00:b2:ed:e6).
            // Sending the wrong one makes AA fail with WRONG_BSSID before it ever opens TCP.
            val groupIface = group?.`interface`?.takeIf { it.isNotBlank() } ?: "p2p0"
            val ifaceMac = readInterfaceMac(groupIface)
                ?: readInterfaceMac("p2p0")
                ?: readInterfaceMac("wlan0")
            val ip = interfaceIp(groupIface) ?: interfaceIp("p2p0") ?: P2P_FALLBACK_IP

            log.i(
                TAG,
                "P2P group: ssid=\"$ssid\" pass=\"$pass\" iface=$groupIface ip=$ip mac=$ifaceMac"
            )
            HotspotInfo(
                ssid = ssid,
                password = pass,
                bssid = (ifaceMac ?: "02:00:00:00:00:00").uppercase(),
                securityMode = WppProtocol.SECURITY_WPA2_PERSONAL,
                apIp = ip
            )
        } catch (e: Exception) {
            log.w(TAG, "Wi-Fi Direct group attempt failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun interfaceIp(name: String): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { it.name == name }
            ?.inetAddresses?.toList()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()
            ?.hostAddress
    } catch (e: Exception) {
        null
    }


    fun start(onReady: (HotspotInfo) -> Unit, onError: (String) -> Unit) {
        if (reservation != null) {
            log.w(TAG, "Hotspot already active, ignoring start")
            return
        }
        Thread({
            // The AP interface can take a moment to appear after the user flips the
            // Portable Hotspot toggle, so poll rather than sampling once.
            val manualAp = waitForApInterface()
            if (manualAp != null) {
                val (name, ip, mac) = manualAp
                val info = HotspotInfo(
                    ssid = MANUAL_SSID,
                    password = MANUAL_PASSWORD,
                    bssid = mac ?: "02:00:00:00:00:00",
                    securityMode = WppProtocol.SECURITY_WPA2_PERSONAL,
                    apIp = ip
                )
                log.i(
                    TAG,
                    "Portable Hotspot detected on $name ($ip, bssid=${info.bssid}). " +
                        "Advertising SSID=\"$MANUAL_SSID\" password=\"$MANUAL_PASSWORD\" to the phone — " +
                        "these MUST match the hotspot's own settings."
                )
                onReady(info)
                return@Thread
            }

            // Fixed SSID + passphrase via a Wi-Fi Direct group. Android 10's
            // startLocalOnlyHotspot() always invents a random 12-hex key, but a P2P group
            // owner can be created with a network name and passphrase we choose, and the
            // resulting AP takes normal station joins.
            if (USE_P2P_FIXED) {
                // After a reboot the service starts within seconds, but Wi-Fi takes longer
                // to come up; P2P refuses while the radio is off (createGroup reason=0) and
                // we would silently fall back to the random-key hotspot. Wait for the radio,
                // then retry a few times before giving up.
                var waited = 0
                while (!wifiManager.isWifiEnabled && waited < 60_000) {
                    Thread.sleep(2_000)
                    waited += 2_000
                }
                if (waited >= 60_000) log.w(TAG, "Wi-Fi still off after 60s — P2P will fail")

                var p2p: HotspotInfo? = null
                for (attempt in 1..3) {
                    p2p = tryStartP2p()
                    if (p2p != null) break
                    if (attempt < 3) {
                        log.w(TAG, "P2P attempt $attempt/3 failed — retrying in 6s")
                        Thread.sleep(6_000)
                    }
                }
                if (p2p != null) {
                    log.i(
                        TAG,
                        "Fixed AP up via Wi-Fi Direct: ssid=\"${p2p.ssid}\" key=\"${p2p.password}\" " +
                            "ip=${p2p.apIp} bssid=${p2p.bssid}"
                    )
                    onReady(p2p)
                    return@Thread
                }
                log.w(TAG, "Wi-Fi Direct group failed; falling back to local-only hotspot")
            }

            if (MANUAL_ONLY) {
                log.e(
                    TAG,
                    "No hotspot interface after ${AP_WAIT_MS}ms. The app will NOT create its own AP " +
                        "(that would silently replace yours and reintroduce a random password)."
                )
                onError("Turn on Portable Hotspot first (SSID $MANUAL_SSID, password $MANUAL_PASSWORD)")
                return@Thread
            }

            log.i(TAG, "No Portable Hotspot detected; requesting local-only hotspot (automatic mode)")
            startLocalOnly(onReady, onError)
        }, "HotspotStart").start()
    }

    private fun waitForApInterface(): Triple<String, String, String?>? {
        val deadline = System.currentTimeMillis() + AP_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val ap = findApInterface()
            if (ap != null) return ap
            try {
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return null
    }

    private fun startLocalOnly(onReady: (HotspotInfo) -> Unit, onError: (String) -> Unit) {
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    log.i(TAG, "Local-only hotspot started")
                    try {
                        val info = describe(res)
                        log.i(
                            TAG,
                            "Hotspot credentials: ssid=\"${info.ssid}\", key=\"${info.password}\", " +
                                "bssid=${info.bssid}, securityMode=${info.securityMode}, apIp=${info.apIp}"
                        )
                        onReady(info)
                    } catch (e: Exception) {
                        log.e(TAG, "Hotspot started but credentials could not be read", e)
                        onError("Hotspot started but credentials unreadable: ${e.message}")
                    }
                }

                override fun onStopped() {
                    log.i(TAG, "Local-only hotspot stopped by system")
                    reservation = null
                }

                override fun onFailed(reason: Int) {
                    val why = when (reason) {
                        ERROR_NO_CHANNEL -> "ERROR_NO_CHANNEL"
                        ERROR_INCOMPATIBLE_MODE -> "ERROR_INCOMPATIBLE_MODE"
                        ERROR_TETHERING_DISALLOWED -> "ERROR_TETHERING_DISALLOWED"
                        else -> "reason=$reason"
                    }
                    log.e(TAG, "Local-only hotspot failed: $why")
                    onError("Hotspot failed: $why")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            log.e(TAG, "Hotspot request denied", e)
            onError("Hotspot permission denied: ${e.message}")
        } catch (e: Exception) {
            log.e(TAG, "Hotspot request threw", e)
            onError("Hotspot request failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun describe(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo {
        var ssid = ""
        var psk = ""
        var bssid = ""
        var security = WppProtocol.SECURITY_WPA2_PERSONAL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = res.softApConfiguration
            ssid = cfg.ssid ?: ""
            psk = cfg.passphrase ?: ""
            bssid = cfg.bssid?.toString() ?: ""
            val isOpen = cfg.securityType == android.net.wifi.SoftApConfiguration.SECURITY_TYPE_OPEN
            security = if (isOpen) WppProtocol.SECURITY_OPEN else WppProtocol.SECURITY_WPA2_PERSONAL
        }
        if (ssid.isEmpty()) {
            // Android 10 path (and fallback if the newer API returned blanks)
            val legacy: WifiConfiguration? = try {
                res.wifiConfiguration
            } catch (e: Throwable) {
                null
            }
            if (legacy != null) {
                ssid = legacy.SSID?.trim('"') ?: ""
                psk = legacy.preSharedKey?.trim('"') ?: ""
                bssid = legacy.BSSID?.trim('"') ?: ""
                val km = legacy.allowedKeyManagement
                security = when {
                    km.get(WifiConfiguration.KeyMgmt.WPA2_PSK) -> WppProtocol.SECURITY_WPA2_PERSONAL
                    km.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> WppProtocol.SECURITY_WPA2_PERSONAL
                    km.get(WifiConfiguration.KeyMgmt.NONE) -> WppProtocol.SECURITY_OPEN
                    else -> WppProtocol.SECURITY_WPA2_PERSONAL
                }
            }
        }
        // AA compares the BSSID we send against the BSSID of the AP it actually joined.
        // WifiConfiguration/SoftApConfiguration report a phantom value (06:93:0D:...),
        // which makes the phone log WIRELESS_WIFI_CONNECTED_TO_WRONG_BSSID and abort the
        // session before it ever opens TCP. The real BSSID is the AP interface's own MAC.
        val apIface = findApInterface()
        val ifaceMac = apIface?.third
        if (!ifaceMac.isNullOrEmpty()) bssid = ifaceMac.uppercase()
        if (bssid.isEmpty()) bssid = readInterfaceMac("wlan0") ?: "02:00:00:00:00:00"
        val apIp = apIface?.second ?: detectApIp()
        return HotspotInfo(ssid, psk, bssid, security, apIp)
    }

    /**
     * Look for an already-running access point interface. Returns (name, ipv4, mac) or
     * null when the phone is not acting as an AP.
     */
    private fun findApInterface(): Triple<String, String, String?>? {
        val apNames = listOf("swlan", "softap", "ap0", "ap1", "wlan1", "rndis")
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                if (apNames.none { nif.name.contains(it, ignoreCase = true) }) continue
                val ip = Collections.list(nif.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress ?: continue
                val mac = try {
                    nif.hardwareAddress?.joinToString(":") { "%02X".format(it) }
                } catch (e: Exception) {
                    null
                }
                return Triple(nif.name, ip, mac)
            }
        } catch (e: Exception) {
            log.w(TAG, "AP interface enumeration failed: ${e.message}")
        }
        return null
    }

    /**
     * Find the IPv4 address the phone should connect back to: the address on the
     * hotspot interface, i.e. any site-local IPv4 that is not the WiFi client address.
     */
    private fun detectApIp(): String {
        val clientIp = currentWifiClientIp()
        val candidates = ArrayList<Pair<String, String>>()
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress && !addr.isLoopbackAddress) {
                        candidates.add(nif.name to addr.hostAddress.orEmpty())
                    }
                }
            }
        } catch (e: Exception) {
            log.w(TAG, "Interface enumeration failed: ${e.message}")
        }
        candidates.forEach { log.i(TAG, "Hotspot IP candidate: iface=${it.first} ip=${it.second}") }

        if (candidates.isEmpty()) {
            log.w(TAG, "No site-local IPv4 found, falling back to 192.168.43.1")
            return "192.168.43.1"
        }
        // Prefer interfaces that look like an AP interface.
        val apNames = listOf("ap", "swlan", "softap", "wlan1", "rndis")
        for (name in apNames) {
            candidates.firstOrNull { it.first.contains(name, ignoreCase = true) }?.let {
                log.i(TAG, "Chose AP interface by name: ${it.first} -> ${it.second}")
                return it.second
            }
        }
        // Otherwise any address that is not the current client address.
        candidates.firstOrNull { it.second != clientIp }?.let {
            log.i(TAG, "Chose first non-client interface: ${it.first} -> ${it.second}")
            return it.second
        }
        log.w(TAG, "All candidates equal client IP $clientIp, using first candidate")
        return candidates.first().second
    }

    private fun currentWifiClientIp(): String? = try {
        val ipInt = wifiManager.connectionInfo?.ipAddress ?: 0
        if (ipInt == 0) null
        else "${ipInt and 0xFF}.${(ipInt shr 8) and 0xFF}.${(ipInt shr 16) and 0xFF}.${(ipInt shr 24) and 0xFF}"
    } catch (e: Exception) {
        null
    }

    private fun readInterfaceMac(iface: String): String? = try {
        NetworkInterface.getByName(iface)?.hardwareAddress?.joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        null
    }

    fun stop() {
        try {
            reservation?.close()
            log.i(TAG, "Hotspot reservation closed")
        } catch (e: Exception) {
            log.w(TAG, "Closing hotspot reservation failed: ${e.message}")
        }
        reservation = null
        if (p2pGroupActive) {
            try {
                p2pManager?.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = log.i(TAG, "Wi-Fi Direct group removed")
                    override fun onFailure(reason: Int) = log.w(TAG, "removeGroup failed: $reason")
                })
            } catch (e: Exception) {
                log.w(TAG, "removeGroup threw: ${e.message}")
            }
            p2pGroupActive = false
        }
    }
}
