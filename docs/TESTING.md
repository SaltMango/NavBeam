# NavBeam Testing Strategy & Setup

This document defines the testing architecture, tools, and conventions for **NavBeam**.

---

## 1. Test Pyramid & Tiers

| Tier | Scope | Tools | Speed | Runs in CI? |
| --- | --- | --- | --- | --- |
| **Tier 1: Pure JVM Unit Tests** | Protocol packets, codecs (`PolylineCodec`, `RouteJsonCodec`, `DashProfileJsonCodec`), math (`Mercator`, `MotionProfile`), `NavigationEngine`, `DashAuth`, `LocationParser`, `DashProfileValidator` | `JUnit 4`, `org.json` | Milliseconds | Yes |
| **Tier 2: Coroutines & State Tests** | `ViewModel`s (`AppViewModel`, `DashViewModel`, `RidesViewModel`, `RouteViewModel`), `DashSession`, `FrameStreamer`, `LocationTracker` | `kotlinx-coroutines-test`, `turbine`, fake/in-memory interfaces | Fast (JVM) | Yes |
| **Tier 3: Android Framework JVM Tests** | `NavBeamDb` (SQLite), `RideRecorder`, `SettingsPrefs`, `AuthPrefs`, `DiagReporter`, `SharedLocation` (`Uri`/Intent parsing), Compose UI smoke tests | `Robolectric`, `androidx.compose.ui:ui-test-junit4` | Seconds (JVM) | Yes |
| **Tier 4: Hardware-in-the-Loop (Manual)** | Real `WifiNetworkSpecifier` binding, hardware `MediaCodec` H.264 encode, live UDP/RTP streaming to physical TFT dash | Physical Android 10+ device + target dash | Manual | No |

---

## 2. Recommended Test Tooling Stack

To achieve full, fast, and reliable test coverage without requiring a physical device for every change:

1. **Code Coverage — `kotlinx-kover`**
   - Tracks Kotlin line and branch coverage accurately (including inline functions and coroutines).
   - Commands:
     ```bash
     ./gradlew koverHtmlReport   # Generate HTML report in app/build/reports/kover/html/
     ./gradlew koverVerify       # Enforce minimum coverage thresholds in CI
     ```

2. **Coroutines & Flow Testing — `kotlinx-coroutines-test` + `turbine`**
   - Use `StandardTestDispatcher` / `runTest` for deterministic virtual time (no `Thread.sleep` or flaky timing).
   - Use `turbine` (`flow.test { ... }`) to assert `StateFlow` and `SharedFlow` emissions cleanly.

3. **Android Framework & UI on JVM — `robolectric`**
   - Enables testing `SQLiteOpenHelper` (`NavBeamDb`), `SharedPreferences` (`SettingsPrefs`, `AuthPrefs`), `Intent`/`Uri` parsing, and Compose screens (`compose-ui-test-junit4`) directly on the JVM.

4. **CI Enforcement — GitHub Actions**
   - Every `pull_request` and `push` to `main` should run:
     ```bash
     ./gradlew testDebugUnitTest lintDebug
     ```

---

## 3. Priority Coverage Roadmap

1. **Pure Logic & Codecs (Highest ROI):**
   - `dash/map/Mercator.kt`, `MotionProfile.kt`
   - `dash/nav/PolylineCodec.kt`, `RouteJsonCodec.kt`
   - `dash/DashAuth.kt`, `util/LocationParser.kt`
2. **Persistence & Data Layer:**
   - `data/NavBeamDb.kt`, `data/RideRecorder.kt`, `data/SettingsPrefs.kt`, `data/SharedLocation.kt`
3. **ViewModels & Session State:**
   - `viewmodel/RouteViewModel.kt`, `RidesViewModel.kt`, `DashViewModel.kt`, `AppViewModel.kt`
   - `dash/DashSession.kt` (using fake socket/auth/encoder interfaces)
4. **Compose UI Smoke Tests:**
   - `HomeScreen`, `RouteScreen`, `ProfileSetupScreen`, `RidesScreen`

---

## 4. Test Conventions

- **No network or hardware dependencies in unit tests:** Inject `TimeSource`, `Router`, `LocationProvider`, `FrameStreamer`, or socket abstractions so tests run offline and deterministically.
- **No flaky sleeps:** Never use `Thread.sleep(...)` in tests; use `runTest` virtual time or explicit synchronization.
- **Descriptive test names:** Use backticked behavior names, e.g. `` `parse rejects profile with non-loopback host`() ``.
- **Keep tests self-contained:** Each test sets up and cleans up its own state (e.g., temporary files or in-memory DBs).
