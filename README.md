# Wireless AA Bridge

Turn a spare Android phone into a wireless Android Auto adapter for a wired-only head
unit — the same job as a commercial dongle, built from scratch with no root and no
custom firmware.

Tested on a Samsung Galaxy S9+ (Android 10) bridging a wired Toyota Touch 2 / Denso head
unit to a Xiaomi 13T Pro. Full Android Auto projection (video, audio, input) works over
the link.

## How it works

The bridge phone sits between the car and the client phone:

```
[car head unit] --USB / AOA--> [bridge phone] <--Wi-Fi TCP--- [client phone]
                                     ^
                        Bluetooth HFP + AA RFCOMM, then WPP
```

1. The bridge phone attaches to the car as a USB **Android Auto accessory** (AOA 2.0).
2. It advertises itself as a wireless Android Auto **head unit** over Bluetooth (the AA
   RFCOMM UUID) and answers the **Wi-Fi Projection Protocol** (WPP) with credentials for
   its own Wi-Fi Direct access point.
3. The client phone pairs, joins the AP, and opens a TCP connection to the bridge.
4. The bridge relays the raw AAP byte stream between the phone's TCP socket and the
   car's USB bulk endpoint.

The critical insight — and the thing most reimplementations get wrong — is that **the
client phone is the TLS server, not the client.** TLS runs *in band* inside AAP
`SSL_HANDSHAKE` frames, and the head unit is the TLS client. The bridge never decrypts
anything; it is a transparent byte pipe. That is why the bridge needs no certificate and
no key management.

## Status

Working end-to-end: version exchange, TLS handshake, authentication, service discovery,
and live projection through the relay. Verified on one car / one client pair.

The bridge is intentionally a *relay*, not a head-unit emulator. It does not implement
AAP services itself — the client phone talks to the real head unit through it.

## Requirements

- An Android phone that can act as a USB accessory (tested: Galaxy S9+ SM-G965F, Android
  10 / API 29). `minSdk 29`, `targetSdk 30`, `compileSdk 34`.
- The built-in Android Auto app (`com.google.android.projection.gearhead`) **disabled**
  on the bridge phone, or it will claim the USB accessory first.
- A wired-only Android Auto head unit.
- A client phone with Android Auto.

## Build

`./gradlew` is broken in this checkout — call the wrapper jar directly:

```bash
export ANDROID_HOME=/path/to/Android
java -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain assembleDebug
cd app/build/outputs/apk/debug
adb install -r app-debug.apk
```

## Setup

1. Disable built-in Android Auto on the bridge phone:

   ```bash
   adb shell pm disable-user --user 0 com.google.android.projection.gearhead
   ```

2. Install the app.
3. Pair the client phone with the bridge phone over Bluetooth (once).
4. Plug the bridge phone into the car's USB port.
5. Grant the USB accessory permission when prompted — tap **Always** (or "Use by default"),
   otherwise Android re-prompts on every attach.

The bridge then dials the client over HFP, serves the AP credentials over WPP, and
relays the session.

## Protocol notes

These are the facts that made it work, all reverse-engineered from open sources and
hardware captures.

- **AAP framing:** `[channel:1][flags:1][payloadLen:2 big-endian][payload]`. The payload
  starts with `[msgId:2 big-endian]`. The low bits of `flags` are the frame type
  (`0x03` = single control frame).
- **Control message IDs:** VERSION_REQUEST `0x0001`, VERSION_RESPONSE `0x0002`,
  SSL_HANDSHAKE `0x0003`, AUTH_COMPLETE `0x0004`, SERVICE_DISCOVERY_REQUEST `0x0005`,
  RESPONSE `0x0006`, CHANNEL_OPEN_REQUEST `0x0007`, RESPONSE `0x0008`,
  PING_REQUEST `0x000B`, RESPONSE `0x000C`.
- **TLS is in band.** The phone is the TLS *server*; the head unit is the TLS *client*.
  The handshake rides inside `SSL_HANDSHAKE` (`0x0003`) AAP frames on channel 0. Sending
  raw TLS bytes gets silence — the phone ignores them.
- **Protocol order:** head unit VERSION_REQUEST → phone VERSION_RESPONSE → TLS handshake →
  head unit AUTH_COMPLETE → phone SERVICE_DISCOVERY_REQUEST → head unit RESPONSE → channel
  opens → projection.
- **Toyota / Denso quirk:** the head unit requires the phone's Bluetooth handsfree to be
  connected to *it*, or it cuts USB power just after AA starts and shows "Android
  disconnected, unplug and replug". The bridge therefore forces the client→car HFP link
  and its own HFP link to the car (`BluetoothHeadset.connect()` is `@hide` — reached by
  reflection).
- **Version-response timing:** the head unit sends its ClientHello the instant it gets a
  version response, then waits roughly 10 seconds for the ServerHello. The client phone
  needs about 15 seconds to arrive (Bluetooth dial + WPP + Wi-Fi join). Answering the
  version request immediately therefore makes the head unit time out. The bridge holds
  the synthetic VERSION_RESPONSE until the phone is on TCP, so the ClientHello lands on
  an already-connected phone and the ServerHello comes back instantly.
- **One USB reader only:** `FileInputStream.read()` on the accessory does not unblock on
  interrupt, and a second reader on the same bulk endpoint returns `EIO` immediately.
  The read loop must be the sole reader, forwarding bytes to the phone socket when a
  phone is attached.

## Known limitations

- Verified on exactly one car / client / bridge combination.
- The Wi-Fi Direct AP passphrase is a compile-time default (`aabridge`) — change it
  before you rely on it.
- Samsung ignores `bluetooth_class_of_device`, so the bridge advertises as a phone rather
  than a car kit; some clients re-prompt for authorization.
- After a session ends, the client's Android Auto can take a while to re-trigger; a
  replug or a short wait may be needed.
- The bridge phone must stay powered. A USB port that cuts power with the ignition stops
  the bridge.

## Credits

Built by reverse-engineering the wireless Android Auto flow, standing on the work of:

- [WirelessAndroidAutoDongle](https://github.com/nisargjhaveri/WirelessAndroidAutoDongle)
- [aa-proxy-rs](https://github.com/aa-proxy/aa-proxy-rs)
- [aasdk](https://github.com/f1xpl/aasdk) / [openauto](https://github.com/f1xpl/openauto)
- [open-android-auto](https://github.com/mrmees/open-android-auto) (protocol docs)
- [mretallack/AndroidAuto](https://github.com/mretallack/AndroidAuto) (in-band TLS reference)

## License

MIT — see [LICENSE](LICENSE).
