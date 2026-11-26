# NavBeam — Roadmap & Backlog

---

## 🟢 P1 — Map & Routing Independence

- [ ] **Keyless Open Vector Basemap (In-App & Streamed Dash):**
  - Currently, `NavBeamMap` uses the Google Maps Compose SDK (requires `MAPS_API_KEY`) and the streamed dash (`TileProvider` + `MapRenderer`) fetches `mt1.google.com` 256 px raster tiles.
  - Migrate both in-app display and off-screen dash rendering to a keyless open vector basemap (e.g., MapLibre Native + OpenFreeMap / PMTiles) with change-detection caching to maintain low CPU/thermal overhead.
- [ ] **Independent Routing & Search Backend:**
  - Currently, `Router` depends on Google Directions API (debug) and Google Routes API (release) with `MAPS_API_KEY`.
  - Support an open, keyless/self-hostable routing & geocoding provider (e.g., Valhalla, GraphHopper, or self-hosted OSRM + Photon/Nominatim).
- [ ] **Persistent Offline Regions & Long-Corridor Packs:**
  - Basic route corridor prefetching (`TileProvider.prefetchRoute` + `RouteViewModel.saveActiveRouteOffline`) is already implemented, but tiles are stored in `cacheDir`, capped at `600` tiles, and marked stale after 5 minutes.
  - Move saved offline route/region packs to persistent app storage without TTL eviction, support long-distance corridors (>600 tiles), and/or package offline `.pmtiles`/`.mbtiles` regions.
- [ ] **Dash & Map Night / High-Contrast Style:**
  - App UI now supports Light/Dark/System themes, but `MapRenderer` and `NavBeamMap` still render a fixed daytime map style.
  - Add a toggle between high-contrast daylight and low-glare dark/OLED styling for both the streamed dash map and in-app map.

---

## 🧪 P2 — Test Setup & Coverage

- [ ] **Add Code Coverage (`kotlinx-kover`):**
  - Add the Kover Gradle plugin to `gradle/libs.versions.toml` and `app/build.gradle.kts`.
  - Generate Kotlin-accurate line/branch reports (`./gradlew koverHtmlReport`) and optional minimum thresholds (`koverVerify`).
- [ ] **Add Essential Test Dependencies:**
  - `kotlinx-coroutines-test`: test `ViewModel`s, `DashSession`, and `StateFlow`s deterministically with `StandardTestDispatcher`.
  - `turbine`: clean assertions on `Flow`/`StateFlow` emissions.
  - `robolectric`: run `NavBeamDb` (SQLite), `SharedPreferences`, `LocationParser`, and Compose UI tests on the JVM in seconds without an emulator.
- [ ] **Run Tests & Lint on Every PR in GitHub Actions:**
  - Add `.github/workflows/ci.yml` triggered on `pull_request` and `push` to `main` running `./gradlew testDebugUnitTest lintDebug`.
  - Pairs with `CONTRIBUTING.md` PR policy and enables branch protection on `main`.
- [ ] **Prioritize High-ROI Tests First:**
  - **Tier 1 (Pure Kotlin, fastest):** `Mercator`, `PolylineCodec`, `RouteJsonCodec`, `MotionProfile`, `DashAuth`, `LocationParser`.
  - **Tier 2 (Robolectric + Coroutines):** `NavBeamDb`, `RideRecorder`, `SettingsPrefs`, `RouteViewModel`, `RidesViewModel`, `DashViewModel`.
  - **Tier 3 (Compose UI):** Smoke/interaction tests for `HomeScreen`, `RouteScreen`, and `ProfileSetupScreen` using `compose-ui-test-junit4`.

---

## 🔌 P2 — Profile Sharing & URL Import

- [ ] **Import Hardware Profile from URL / GitHub Link:**
  - Add an **Import from URL** flow in `ProfileSetupScreen` (and optional `navbeam://import?url=...` deep link) accepting direct `.json`/`.navbeam` HTTPS URLs and GitHub `blob` URLs (auto-converting `github.com/.../blob/...` → `raw.githubusercontent.com/...`).
  - Enforce security guardrails: HTTPS-only (reject `http://`, `file://`, `localhost`, and private/LAN IPs), 64 KB streaming size cap (`DashProfileJsonCodec.MAX_PROFILE_BYTES`), short timeout on `Dispatchers.IO`, and mandatory **Verify Profile** confirmation dialog before saving/activating.
- [ ] **One-Tap `.navbeam` Sharing & Direct-Open Import:**
  - Export/share active profiles as `<profile.id>.navbeam` (plain UTF-8 JSON inside) via Android Sharesheet (`FileProvider` + `Intent.ACTION_SEND`) for WhatsApp, Telegram, and Email.
  - Register `<intent-filter>` on `MainActivity` for `.navbeam` (`ACTION_VIEW` / `ACTION_SEND`) to route directly to the **Verify Profile** dialog while keeping `.json` and **Paste JSON** supported.

---

## 🚀 P3 — Store Publishing Checklists

### Google Play Store (`play` flavor)
- [ ] **Remove Self-Updater (`REQUEST_INSTALL_PACKAGES`):** Omit `REQUEST_INSTALL_PACKAGES` and disable `UpdateChecker` in the `play` build flavor (Google Play handles app updates).
- [ ] **Bundle Release (`.aab`):** Configure Play App Signing and build with `./gradlew bundleRelease`.
- [ ] **Privacy Policy & Data Safety:** Host a public Privacy Policy (e.g., `PRIVACY.md` / GitHub Pages) stating 100% local on-device operation with zero data collection, and complete the Play Console Data Safety form.
- [ ] **Restricted Permissions Declarations & Demo Videos:**
  - Add prominent in-app disclosures before requesting background/FGS location (`FOREGROUND_SERVICE_LOCATION`), `NavBeamNotificationListener`, and `ANSWER_PHONE_CALLS`.
  - Record short screen-capture demo videos for Play Console review (and justify or make optional `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- [ ] **Clean-Room Store Listing:** Ensure app title, screenshots, and description avoid manufacturer trademarks or implied endorsement.
- [ ] **Closed Testing Gate:** Complete the 12-tester / 14-day closed test requirement (if using a new personal Play Console account) before Production rollout.

### F-Droid Official Repository (`fdroid` flavor)
- [ ] **100% FOSS Dependencies:** Move `play-services-maps`, `maps-compose`, `places`, `play-services-location`, and `kotlinx-coroutines-play-services` to `standardImplementation(...)` only; use MapLibre + OpenFreeMap + open routing + raw `LocationManager` in `fdroid` (or complete P1 across all builds).
- [ ] **Remove Self-Updater:** Omit `REQUEST_INSTALL_PACKAGES` and `UpdateChecker` in the `fdroid` flavor (F-Droid signs and distributes updates).
- [ ] **Bundle Offline Fonts:** Replace `com.google.android.gms.fonts` in `Type.kt` with bundled `Geist` & `Geist Mono` `.ttf` files in `app/src/main/res/font/`.
- [ ] **Reproducible Keyless Build:** Verify `./gradlew assembleFdroidRelease` builds from a clean checkout without `local.properties` or API keys.
- [ ] **Fastlane Metadata & Submission:** Add `fastlane/metadata/android/en-US/` (descriptions, icon, screenshots, changelogs) and submit `metadata/io.github.saltmango.navbeam.yml` to `fdroid/fdroiddata`.




