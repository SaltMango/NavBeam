# NavBeam — Roadmap & Backlog

**Current Status:** Core off-screen map rendering, hardware H.264 MediaCodec streaming, screen-off keep-alive service, and keyless vector maps (MapLibre + OpenFreeMap) are fully functional.

---

## 🟢 P0 — Vendor-Neutral Hardware Profiles (Architecture)

Decouple all hardcoded protocol constants, Wi-Fi parameters, and screen resolutions into pluggable JSON configuration files.

- [x] **Define `DashProfile.json` Schema:**
  - `network`: default SSID prefix, default WPA2 passphrase, gateway IP, control TX/RX ports, video RTP port.
  - `display`: resolution width/height, shape mask (circle/rect), target FPS, CBR bitrate, H.264 baseline level, RTP payload parameters, and SPS/NAL quirks.
  - `protocol`: packet magic bytes, header constants, auth mechanism (RSA/AES vs none), offsets, limits, and keep-alive intervals.
  - `commands`: protocol TLV tags, initial burst, heartbeat, projection controls, frame-decoded acks, button events, media/call tags, and navigation field tags.
  - `maneuvers`: map navigation actions (turn left, roundabout exits, destination arrived) to device-specific glyph hex codes.
  - `inputs`: map hardware button/joystick codes to app camera/media/call actions (zoom in/out, media skip, call answer/reject).
- [x] **Profile Loader & Storage:**
  - Create Kotlin data models, JSON codec, and security validator for `DashProfile`.
  - Store active profile in app data storage (`dash_profiles/`).
  - Pure vendor-neutral app behavior: user uploads or pastes their hardware profile JSON.
- [x] **Settings Profile UI:**
  - Add "Hardware Profile" setup and Settings management UI.
  - Support "Import Profile (.json)" via Android system file picker and JSON paste.
  - Support exporting the current profile for community sharing.

---

## 🟢 P1 — Map & Routing Independence

- [ ] **Streamed Dash Vector Basemap:**
  - Currently, the in-app map uses vector OpenFreeMap, but the streamed dash canvas still relies on raster map tiles (`TileProvider`).
  - Render vector maps directly onto the off-screen surface (via MapLibre snapshotting or off-screen GL) with change-detection caching to maintain low CPU/thermal overhead.
- [ ] **Independent Routing Backend:**
  - Replace dependency on the public demo `router.project-osrm.org` (rate-limited, no SLA) with a reliable open routing provider (e.g., self-hosted OSRM, Valhalla, or GraphHopper).
- [ ] **Offline Region / Corridor Caching:**
  - Downloadable offline tile packets along a planned route for no-signal mountain or cross-country riding.
- [ ] **Optional Night / High-Contrast Style:**
  - Toggle between high-contrast day theme and low-glare dark/OLED theme for the streamed dash map.

---

## 🟢 P2 — UI Polish & Hardware Controls

- [ ] **Wi-Fi Settings Management Card:**
  - Add fields in Settings to view and edit Wi-Fi prefix, exact paired SSID, and passphrase.
  - Add a one-tap "Forget Dash" button to trigger fresh discovery (backing ViewModel methods already exist).
- [ ] **Maneuver Glyph Verification:**
  - Continuously verify turn maneuver hex codes across user-contributed hardware profile JSONs and road layouts (forks, ramps, roundabouts).
- [ ] **Joystick Forwarding in Navigation View:**
  - Investigate protocol flags that enable hardware joystick event forwarding while navigation projection is active.

---

## 🟢 P3 — Thermal & Battery Benchmarking

- [ ] **Real-Ride Power Measurements:**
  - Benchmark continuous 2–3 hour rides with phone screen off in direct sunlight.
  - Log battery drain (% per hour) via `dumpsys batterystats`.
  - Tune dynamic frame rate back-off thresholds (e.g. drop from 4 FPS to 2 FPS when stationary or when device thermal status rises).

---

## 🟢 P4 — Local Backup & Distribution

- [ ] **Local Backup Export / Import:**
  - Export/import ride history and saved routes as a local JSON/ZIP backup without any cloud server.
- [ ] **Automated GitHub Releases (CI):**
  - GitHub Actions workflow to build release APKs and automatically draft GitHub Releases on tag push.
- [ ] **F-Droid Inclusion:**
  - Prepare metadata and build configuration for inclusion in F-Droid / IzzyOnDroid open-source repositories.

---

## 🏁 Completed Core Milestones

- [x] **Screen-Off Streaming:** Hardware H.264 MediaCodec encoding streaming over UDP/RTP with foreground service and wake/Wi-Fi locks (`DashKeepAliveService`).
- [x] **Keyless Open Maps:** Migrated in-app map to MapLibre Native + OpenFreeMap vector tiles (zero proprietary API keys required).
- [x] **Turn-by-Turn Engine:** Real road-following routing via OSRM, map-matching (snapping within 25m), dynamic rerouting, distance-to-turn, and ETA calculation.
- [x] **Dynamic Wi-Fi Discovery:** Network specifier prefix discovery (`DashWifiManager`) with automatic pairing and reconnect logic.
- [x] **On-Device Voice Guidance:** Offline Text-to-Speech (TTS) turn prompts with chime alerts.
- [x] **Ride Tracking & Local Persistence:** SQLite database storing rides, statistics, and GPS tracks without requiring accounts or cloud infrastructure.
- [x] **OTA In-App Updates:** Integrated GitHub Releases update checker with one-tap in-place APK updates and Obtainium support.
- [x] **GPLv3 Relicensing & Rebrand:** Fully transitioned to `NavBeam` under the GNU General Public License v3 with complete clean-break refactoring.
