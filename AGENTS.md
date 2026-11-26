# NavBeam

Personal Android companion app for motorcycle navigation,
designed to work with digital dash clusters. Single user, Android-only.

> Independent project, not affiliated with or endorsed by any vehicle manufacturer.
> The dash link is an unofficial interoperability feature for hardware the user already owns.

## Primary goal

Low-power **navigation shown on digital motorcycle displays** (round TFT screens)
without cooking the phone. NavBeam **renders the map off-screen and
hardware-encodes H.264**, so the phone screen can stay **OFF** during the ride.
That single architectural choice — keeping the screen off — is the whole point of
the project.

## Dash link

- Communicates with vehicle display over Wi-Fi UDP.
- After a handshake, the dash decodes an **H.264/RTP stream over the profile-configured UDP port**.
  It does not care what produces the video.
- Dash behaviour varies by hardware and firmware profile.
  The link layer is configured via pluggable hardware profiles.

## Core user flow

1. Share a destination from **Google Maps** into NavBeam.
2. NavBeam previews the route, tap **Send to Dash**.
3. While riding, the dash shows the map; the bike's **physical joystick**
   pans/zooms. The phone screen stays off.

## Tech stack

- **Language:** Kotlin (Android, native).
- **Map rendering:** off-screen render → `MediaCodec` hardware H.264 encode →
  `MediaCodec`/RTP to the dash over the profile-configured UDP port.
- **Maps/offline:** offline maps preferred for riding; an OSM stack (MapLibre).
  Decide during build — don't over-engineer the map layer up front.
- **Backend:** none — 100% local on-device operation, zero server/cloud dependency.
- **Local persistence:** on-device **SQLite** as the source of truth, plus local diagnostic & crash log export (`.zip` share from Menu).

## Features

1. **Navigation** (primary) — receive shared location, route preview, send to dash,
   joystick pan/zoom, turn-by-turn.
2. **TTS / voice overlay** — toggle per trip: off / chime-only / full. We own the
   TTS layer, so this is just a setting.
3. **Telemetry / ride history** — distance, duration, map snapshot per ride.
4. **Media controls** — now-playing overlaid onto our own video frame. Note:
   Android restricts answering/ending calls programmatically — calls are
   realistically display + reject + alert only.

## Build phasing

Sequenced so standalone, useful parts land first and the hardware-dependent dash
link is isolated:

1. **Phase 1:** Kotlin link layer validated against hardware profile (stream a static
   test video to the dash). In parallel, the standalone features (telemetry) — no dash dependency, usable day one.
2. **Phase 2:** off-screen MapLibre/map → MediaCodec → dash with screen OFF. Proves
   the power fix.
3. **Phase 3:** GPS + offline routing + turn-by-turn rendering.
4. **Phase 4:** polish — TTS, day/night, reconnect handling, settings, media.

## Hard constraints / non-goals

- **Android only.** No iOS.
- No personas, no branding-as-product, no team/lab infrastructure. Keep it lean.
- The dash-streaming core requires **hardware-in-the-loop validation on the bike** —
  that part can't be verified from code alone.

## Reference docs in this repo

- `@docs/HLD-LLD.md` — full architecture (high- and low-level design).
- `@docs/design/` — UI prototype and screen specs (from Claude Design).
- `@docs/TESTING.md` — test pyramid, coverage tooling, and priority roadmap.

## Production Engineering Rules (for AI Agents)

### 1. Architecture & Concurrency
- **Unidirectional Data Flow:** Keep Composables stateless/UI-only. Business logic, state management, and I/O belong in `viewmodel/`, `data/`, or `dash/`. Expose immutable `StateFlow` state from ViewModels.
- **Structured Concurrency:** Never use `GlobalScope`. Always inject dispatchers (via `AppDispatchers`) instead of hardcoding `Dispatchers.IO`/`Default` in testable classes, and never block the Main thread with SQLite, file I/O, or network/socket calls.
- **Resource & Lifecycle Safety:** Always release `MediaCodec`, `DatagramSocket`, `Cursor`, `WakeLock`, and `WifiNetworkSpecifier` callbacks deterministically (`use { }`, `try/finally`, or `onCleared()`/`onDestroy()`).
- **API Level Floor (`minSdk = 29`):** Never introduce unguarded calls requiring API > 29; gate newer Android APIs with `Build.VERSION.SDK_INT` checks.

### 2. Security, Privacy & Clean-Room Compliance
- **No Secrets or Proprietary Assets:** Never commit API keys, `local.properties`, keystores (`*.jks`), `signing.properties`, manufacturer firmware, proprietary keys, or third-party binaries.
- **Validate Untrusted Inputs:** Strictly bounds-check and sanitize all external inputs—imported `DashProfile` JSONs (`DashProfileValidator`), shared Google Maps URLs/Intents (`LocationParser`), and incoming UDP/RTP packets.
- **Local-First Privacy:** NavBeam has no backend. Never add analytics, tracking SDKs, or unrequested outbound network calls. Never log tokens, credentials, or sensitive user data in `Dbg` or `DiagReporter`.

### 3. Reliability & Performance
- **No Silent Failures:** Never swallow exceptions (`catch (_: Exception) {}`) without a documented reason; propagate typed errors, surface user-actionable states, or record non-fatal diagnostics cleanly.
- **Database Integrity:** Wrap multi-step SQLite writes in transactions and handle schema upgrades explicitly in `NavBeamDb` without data loss.
- **Hot-Path & Thermal Discipline:** Avoid per-frame allocations, tight polling loops, or unnecessary wakeups in the rendering, H.264 encoding, and RTP packetization pipeline (`RtpPacketizer`, `NalProcessor`, `FrameStreamer`).

### 4. Change Hygiene
- **Minimal, Focused Diffs:** Keep changes scoped to the task. Avoid unrelated refactors, dead code, unused imports, or speculative abstractions.
- **Clean-Break Maintenance:** Remove obsolete code paths completely rather than leaving `@Deprecated` shims or duplicate logic.
- **Never Bypass Safeguards:** Never use `--no-verify` on git commits, never disable lint/compiler checks, and never weaken tests to make a build pass.

### 5. Comments & Documentation Discipline
- **Explain the "Why", Not the "What":** Add concise KDoc on public APIs/classes and inline comments only for non-obvious invariants, OEM/Android quirks, protocol constraints, or concurrency rationale—never narrate obvious code.
- **No In-Code Changelogs:** Never write changelogs, version numbers, dates, author tags, or *"changed/removed X"* edit history inside source comments or KDoc. Git history is the changelog.
- **Keep Comments Accurate:** Preserve unrelated existing comments, and update or delete stale comments that contradict your changes.

## Testing & Verification Rules (for AI Agents)

1. **Run verification after every code change:** Execute `./gradlew :app:assembleDebug testDebugUnitTest` after editing Kotlin code (and `lintDebug` when modifying `AndroidManifest.xml`, permissions, or resources) and verify all checks pass before finishing.
2. **Write tests alongside changes:**
   - Any new or modified logic in `dash/`, `data/`, `util/`, or `viewmodel/` must include corresponding unit tests under `app/src/test/`.
   - Prefer testing against interfaces/fakes (`TimeSource`, `LocationProvider`, `FrameStreamer`, etc.) rather than concrete Android hardware/network classes.
3. **Deterministic & offline only:** Unit tests must never make real network calls, bind real Wi-Fi/hardware devices, or use `Thread.sleep` for timing. Use `kotlinx-coroutines-test` (`runTest`) for async/Flow tests.
4. **Never weaken or skip tests:** Do not delete, `@Ignore`, or relax assertions to make a failing test pass—fix the underlying bug or update expectations only when the intended behavior explicitly changed.
5. **Hardware-in-the-loop honesty:** Changes to `DashWifiManager`, `DashEncoder` (`MediaCodec`), or live dash UDP/RTP behavior cannot be fully verified by JVM unit tests alone; explicitly state what was unit-tested vs. what still requires physical bike/dash testing.
