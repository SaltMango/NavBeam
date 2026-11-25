# NavBeam 🧭⚡

**Universal low-power, screen-off navigation & telemetry streamer for vehicle digital dashes.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin%20%2F%20Compose-purple.svg)](https://kotlinlang.org)
[![Maps](https://img.shields.io/badge/Maps-OpenFreeMap%20%2B%20MapLibre-orange.svg)](https://openfreemap.org)

NavBeam is an open-source, vendor-neutral Android navigation engine designed to project turn-by-turn routes, vector maps, and media onto vehicle digital displays (such as round TFT clusters) — **while keeping your phone screen completely OFF in your pocket or tank bag.**

> ⚠️ **Independent, Community Project:** NavBeam is an independent open-source project and is not affiliated with, endorsed by, or sponsored by any vehicle or hardware manufacturer. All hardware links and protocol profiles are unofficial community implementations designed strictly for interoperability with equipment you already own. NavBeam only streams a display video feed over Wi-Fi and reads control inputs; it never touches the engine, ECU, brakes, or any vehicle safety system. Use at your own risk.
>
> ⚖️ **Interoperability & Profile Disclaimer:** This project is intended solely for interoperability, research, and compatibility purposes. NavBeam does not include, distribute, or require copyrighted software, firmware, cryptographic keys, or proprietary libraries. Users are solely responsible for ensuring that any hardware profiles, configuration files, or data imported into or used with this project are obtained and utilized in accordance with applicable laws and respective license terms. The authors and contributors assume no liability for user-provided configurations, third-party profiles, or hardware interactions.

---

## Why NavBeam?

Standard smartphone mirroring lights up the phone's OLED display continuously while transmitting what's on screen. On a long ride under direct sunlight, this rapidly overheats the phone, throttles GPS performance, and drains the battery.

NavBeam solves this at the architectural level:

> **It renders the vector map off-screen, hardware-encodes the canvas to an H.264 video stream, and transmits it directly over local Wi-Fi to the vehicle display — allowing your phone screen to remain 100% OFF for the entire journey.**

---

## Features

- **🧭 Full Turn-by-Turn Navigation** — Share any destination or pin directly from Google Maps into NavBeam. Previews the route, calculates live distance and ETA, and automatically calculates off-route reroutes on the fly.
- **🔋 Zero Screen Heat (Endurance-Built)** — Hardware H.264 encode via Android `MediaCodec` at low bitrate (~200 kbps), motion-adaptive frame pacing (4 FPS), wake locks, and thermal back-off so your phone stays cool.
- **🗺️ 100% Keyless Vector Maps** — Built on MapLibre and [OpenFreeMap](https://openfreemap.org). Runs fully local without requiring a Google Maps API key or paid subscription.
- **🔌 Pluggable Hardware Profiles** — Vendor-neutral profile architecture. Hardware parameters (Wi-Fi SSIDs, socket ports, display resolutions, packet framing, and maneuver glyph IDs) are decoupled into extensible, community-driven profiles.
- **🔊 On-Device Voice Guidance** — Off / Chime-before-turns / Spoken turn-by-turn audio cues powered by Android's native offline Text-to-Speech (TTS).
- **🏍️ Automatic Ride Tracking** — Automatically logs every connected session: distance, duration, average/maximum speed, battery/thermal stats, and GPS track geometry saved to on-device SQLite.
- **🩺 Local-First Diagnostics** — Captures JVM crashes, native/ANR process exits (`ApplicationExitInfo`), non-fatal errors, and ride session logs locally with one-tap `.zip` export for bug reports — zero servers, zero tracking, zero cost.
- **🕹️ Handlebar Controls** — Reads physical handlebar joystick/button events over the protocol control plane to pan, zoom, and navigate without taking your hands off the grips.

---

## How It Works

```
Google Maps Share ──▶ Route Engine (OSRM) ──▶ Off-Screen Map Renderer (Canvas)
                                                          │
                                               Hardware MediaCodec (H.264)
                                                          │
                                                RTP Packetizer over UDP
                                                          ▼
                                              Vehicle TFT Cluster Display
                  ┌───────────────────────────────────────┴───────────────────────────────────────┐
                  │                 Hardware Profile Control Plane (UDP Broadcast)                │
                  │   • Wi-Fi Discovery (SSID / WPA2)     • Rolling Sequence TLVs                 │
                  │   • Ephemeral RSA/AES Handshake       • Heartbeat & Watchdogs                 │
                  └───────────────────────────────────────────────────────────────────────────────┘
```

1. **Off-Screen Rendering:** The map and turn maneuvers are drawn to a virtual off-screen `Surface`.
2. **Hardware Encoding:** Android's hardware `MediaCodec` encodes the surface into an H.264 video stream with Baseline Profile constraints.
3. **Wireless Streaming:** Video slices are packaged into an RTP stream over the profile-configured UDP port.
4. **Control Plane:** The background control loop handles discovery, mutual authentication, periodic keep-alives (1 Hz route card / 4 Hz projection frame), and handlebar input handling.

---

## Tech Stack

- **Platform:** Native Android (Kotlin, Jetpack Compose, Coroutines/Flow)
- **Video Pipeline:** `MediaCodec` H.264 hardware encoder → Custom zero-allocation RTP packetizer → UDP DatagramSocket
- **Mapping & Routing:** MapLibre Native, OpenFreeMap vector tiles, OSRM road routing engine
- **Storage & Diagnostics:** Local SQLite database (100% on-device) + local crash/exit/ride `.zip` export via `FileProvider`
- **Audio & Positioning:** Native `TextToSpeech` engine and raw Android `LocationManager` GPS

---

## Download & Installation

**📦 Download the latest APK from [GitHub Releases](https://github.com/SaltMango/NavBeam/releases/latest)** and sideload it onto your phone (enable "Install unknown apps" when prompted). No account or setup is required.

All releases are signed with the stable project release key so updates install in place without losing your ride logs or settings.

### 🔄 Automatic Updates via Obtainium (Recommended)

To receive automatic release notifications and one-tap in-place updates:
1. Install **[Obtainium](https://github.com/ImranR98/Obtainium)** (free, open-source app manager).
2. Tap **Add App** and paste: `https://github.com/SaltMango/NavBeam`
3. Obtainium will automatically track every new release and notify you when an update is available.

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/SaltMango/NavBeam.git
cd NavBeam

# Build debug APK
./gradlew :app:assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`. 

*No proprietary API keys are required to build or run.* The vector basemap is keyless (OpenFreeMap) and runs completely local.

---

## Contributing & Hardware Profiles

Contributions, bug reports, and profile extensions are welcome!

- **Hardware Testing:** Report protocol behavior, connection results, and firmware compatibility for different vehicle models.
- **Hardware Profiles:** Help expand hardware profile definitions for new TFT dashes and DIY displays (ESP32 / Raspberry Pi).
- **Maneuver Mappings:** Help document and verify maneuver glyph hex codes.

---

## License & Legal Disclaimer

NavBeam is licensed under the **[GNU General Public License, Version 3 (GPLv3)](LICENSE)**. See [`NOTICE`](NOTICE) for additional legal details.

- **Interoperability & Clean-Room Design:** This project is intended strictly for interoperability, research, and compatibility purposes. It does not include, distribute, or require copyrighted software, firmware, cryptographic keys, or proprietary libraries.
- **User Responsibility for Profiles:** Users and contributors are solely responsible for ensuring that any hardware profiles, vendor configurations, or parameters imported into NavBeam comply with all applicable local laws, intellectual property rights, and third-party terms. The maintainers assume no responsibility or liability for user-imported profiles or third-party hardware interactions.
- **Trademarks:** All product names, logos, and brands are property of their respective owners. All company, product, and service names used in this project are for identification and interoperability purposes only. Use of these names, logos, and brands does not imply endorsement.
