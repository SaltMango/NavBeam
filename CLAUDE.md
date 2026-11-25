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
