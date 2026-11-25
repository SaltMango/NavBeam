package io.github.saltmango.navbeam.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.saltmango.navbeam.dash.DashKeepAliveService
import io.github.saltmango.navbeam.dash.DashSession
import io.github.saltmango.navbeam.dash.DashState
import io.github.saltmango.navbeam.dash.DashWifiManager
import io.github.saltmango.navbeam.dash.WifiConnStatus
import io.github.saltmango.navbeam.dash.map.LocationTracker
import io.github.saltmango.navbeam.dash.map.MapRenderer
import io.github.saltmango.navbeam.dash.map.PrefetchProgress
import kotlinx.coroutines.flow.StateFlow
import io.github.saltmango.navbeam.dash.map.Mercator
import io.github.saltmango.navbeam.dash.map.TileProvider
import io.github.saltmango.navbeam.dash.map.MotionProfile
import io.github.saltmango.navbeam.util.AppDispatchers
import io.github.saltmango.navbeam.dash.nav.GeoPoint
import io.github.saltmango.navbeam.dash.nav.Route
import io.github.saltmango.navbeam.dash.nav.Router
import io.github.saltmango.navbeam.dash.nav.NavigationEngine
import io.github.saltmango.navbeam.dash.nav.DefaultNavigationEngine
import io.github.saltmango.navbeam.dash.nav.TimeSource
import io.github.saltmango.navbeam.dash.nav.NavigationLocation
import io.github.saltmango.navbeam.dash.nav.Destination
import io.github.saltmango.navbeam.dash.nav.CompiledRoute
import io.github.saltmango.navbeam.dash.nav.NavigationState
import io.github.saltmango.navbeam.dash.nav.NavigationEvent
import io.github.saltmango.navbeam.dash.nav.NavigationResult
import io.github.saltmango.navbeam.dash.nav.NavigationGpsStatus
import io.github.saltmango.navbeam.dash.camera.CameraController
import io.github.saltmango.navbeam.dash.camera.DefaultCameraController
import io.github.saltmango.navbeam.dash.camera.CameraTarget
import io.github.saltmango.navbeam.dash.camera.CameraPosition
import io.github.saltmango.navbeam.dash.protocol.DashCommands
import io.github.saltmango.navbeam.dash.video.FrameStreamer
import io.github.saltmango.navbeam.dash.video.DefaultFrameStreamer
import io.github.saltmango.navbeam.dash.video.StreamerConfig
import io.github.saltmango.navbeam.dash.video.StreamFailureListener
import io.github.saltmango.navbeam.dash.mode.NavigationModeController
import io.github.saltmango.navbeam.dash.mode.NavigationModeContext
import io.github.saltmango.navbeam.dash.mode.RenderingContext
import io.github.saltmango.navbeam.dash.mode.MapModeController
import io.github.saltmango.navbeam.dash.mode.ArrowModeController
import io.github.saltmango.navbeam.data.SettingsPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import io.github.saltmango.navbeam.util.DeviceName
import android.location.Location

enum class ConnStage { OFFLINE, WIFI, AUTH, CONNECTED, STREAMING, ERROR }

enum class ApplicationState {
    DISCONNECTED,
    CONNECTED_FREE_ROAM,
    CONNECTED_NAVIGATION
}

sealed interface NavigationMode {
    data object MapMode : NavigationMode
    data object ArrowMode : NavigationMode

    companion object {
        fun fromString(mode: String): NavigationMode = when (mode) {
            "Arrow" -> ArrowMode
            else -> MapMode
        }
    }
}

data class RenderState(
    val centerLatBucket: Long,
    val centerLngBucket: Long,
    val zoom: Int,
    val bearingBucket: Int,
    val trafficEnabled: Boolean = false,
    val mapStyle: String = "",
    val routePointsSize: Int,
    val panX: Float,
    val panY: Float,
    val cameraAnimating: Boolean = false,
    val riderLat: Double? = null,
    val riderLng: Double? = null,
    val destLat: Double? = null,
    val destLng: Double? = null
)



/**
 * GPS health shown to the rider so a bad signal degrades *visibly* instead of the marker just
 * freezing. GOOD = fresh, accurate. WEAK = fresh but imprecise (or fixes coming slowly) → the
 * map eases gently toward truth, no jitter. LOST = no fresh fix for a few seconds → marker holds
 * its last known spot and the dash says so.
 */
enum class GpsStatus { GOOD, WEAK, LOST }

data class DashUiState(
    val stage: ConnStage = ConnStage.OFFLINE,
    val frameCount: Int = 0,
    val lastButton: String? = null,
    val ssid: String = "",            // empty until a dash is discovered/paired (see DashConfig)
    val wifiPassword: String = "",
    val destinationName: String? = null,
    val errorMessage: String? = null,
    val retryAttempt: Int = 0,        // >0 while auto-retrying the flaky auth handshake
    val mapZoom: Int = 16,
    val remainingKm: Double? = null,
    val etaMinutes: Int? = null,
    val etaArrival: String? = null,
    val maneuver: String? = null,
    val hasGps: Boolean = false,
    val gpsStatus: GpsStatus = GpsStatus.LOST,
    val hasRoute: Boolean = false,
    val offRoute: Boolean = false,
    val recalculating: Boolean = false, // off-route long enough / actively rerouting → ETA frozen, pill shows "Recalculating"
    val headingUp: Boolean = true,
    val followMode: Boolean = true,
    val adjustMode: Boolean = true,
    val thermal: String = "OK",
    val needsWifiOn: Boolean = false,   // Wi‑Fi radio is off → UI prompts to enable it
    // For the in-app Google Map view
    val riderLat: Double? = null,
    val riderLng: Double? = null,
    val riderBearing: Float = 0f,
    val destLatLng: Pair<Double, Double>? = null,
    val routePoints: List<GeoPoint> = emptyList(),
    val streamQuality: String = "Balanced",
    val navMode: String = "Map",
    val nextTurnDistanceM: Double? = null,
)

class DashViewModel(app: Application) : AndroidViewModel(app), NavigationModeContext, RenderingContext {
    private val _ui = MutableStateFlow(DashUiState())
    val ui = _ui.asStateFlow()

    // The EXACT frame being H.264-encoded to the dash, published for the in-app "Dash view"
    // so the phone preview is pixel-identical to the bike screen (same renderer, not a second
    // map engine). A fresh immutable snapshot per redraw — the encoder's own bitmap is mutated
    // on a background thread and recycled on teardown, so handing that out would tear/crash.
    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame = _previewFrame.asStateFlow()

    private val session     = DashSession(viewModelScope)
    private val wifiManager = DashWifiManager(app, viewModelScope)
    private val dashConfig  = io.github.saltmango.navbeam.dash.DashConfig.get(app)
    private val voice        = io.github.saltmango.navbeam.dash.nav.VoiceManager.get(app)
    private val repo         = io.github.saltmango.navbeam.data.SyncRepository.get(app)
    private val recorder     = io.github.saltmango.navbeam.data.RideRecorder()
    private var recordJob: Job? = null
    internal val tilesLazy  = lazy { TileProvider(app, viewModelScope) }
    private val tiles: TileProvider get() = tilesLazy.value
    val prefetchProgress: StateFlow<PrefetchProgress> get() = tiles.prefetchProgress
    // We will just expose tilesLazy as internal so tests can check it
    private val location    = LocationTracker(app, viewModelScope)

    internal val mapRendererLazy = lazy { MapRenderer(tiles) }
    private val mapRenderer: MapRenderer get() = mapRendererLazy.value

    private val mediaInfo   = io.github.saltmango.navbeam.media.MediaInfoProvider(app)
    private val callController = io.github.saltmango.navbeam.media.CallController(app)
    private var mediaObserveJob: Job? = null
    private val powerManager = app.getSystemService(Application.POWER_SERVICE) as PowerManager

    internal val frameStreamerLazy = lazy {
        DefaultFrameStreamer().apply {
            setFailureListener(object : StreamFailureListener {
                override fun onStreamingFailed(cause: Throwable) {
                    viewModelScope.launch(Dispatchers.Main) {
                        android.util.Log.e("DashViewModel", "Fatal streaming error reported by FrameStreamer", cause)
                        stopProjection()
                    }
                }
            })
        }
    }
    private val frameStreamer: FrameStreamer get() = frameStreamerLazy.value

    private val mapModeController = MapModeController(this, this)
    private val arrowModeController = ArrowModeController(this)
    private var activeModeController: NavigationModeController? = null
    private var useSoftwareEncoder = false
    // Backstop so a dropped/never-reached connection can't drain the battery forever: if we don't
    // reach STREAMING within this window, give up completely (stop the Wi‑Fi reconnect loop, the
    // foreground service, GPS and the encoder). Cancelled the moment streaming starts.
    private var giveupJob: Job? = null
    private var connLostAlertJob: Job? = null
    private var connLostAlerted = false

    private val _appState = MutableStateFlow(ApplicationState.DISCONNECTED)
    val appState = _appState.asStateFlow()

    private val _motionProfile = MutableStateFlow(MotionProfile.DISCONNECTED)
    val motionProfile = _motionProfile.asStateFlow()
    private var lastProfileTransitionTime = 0L

    private var previousRenderState: RenderState? = null
    private var previousRenderStateHash = 0

    // ── Diagnostic Metrics ───────────────────────────────────────────────────
    private var renderTimeSum = 0L
    private var renderTimeCount = 0
    private var gpsAccuracySum = 0.0
    private var gpsAccuracyCount = 0
    private var renderSkippedCount = 0
    private var reconnectCount = 0
    private var rerouteCount = 0
    private var lastGpsFixTimeMs = 0L
    private var gpsFixIntervalSumMs = 0L
    private var gpsFixIntervalCount = 0
    private var gpsDropsCount = 0

    // ── Debug Flags ──────────────────────────────────────────────────────────
    // Set to true to replace map rendering with a solid black frame.
    // NavInfo (TBT arrows, distances, ETA) continues to be sent normally.
    // Use this to confirm the dash TBT overlay works independently of map render.
    private val debugBlackFrameMode = DEBUG_BLACK_FRAME_MODE

    private var stoppedTimeMs = 0L
    private var thermalThrottled = false

    private var userWantsConnection = false
    private var userWantsProjection = false
    private var autoStartProjectionOnConnect = false
    private var authAttempts = 0       // bounded auto-retry of the (flaky) auth handshake

    // ── Decoupled Navigation/Camera Components ──
    private val timeSource = object : TimeSource {
        override fun wallTimeMs(): Long = System.currentTimeMillis()
        override fun monotonicNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
    }
    private val navEngine: NavigationEngine = DefaultNavigationEngine(timeSource)
    internal val cameraControllerLazy = lazy { DefaultCameraController(timeSource) }
    private val cameraController: CameraController get() = cameraControllerLazy.value

    // GPS health surfaced to the dash + phone
    @Volatile private var gpsStatus = GpsStatus.LOST

    // Smoothed rider position shown on the dash frame (locked to the camera centre so the
    // marker stays put and the map slides under it). null = no GPS.
    @Volatile private var frameRiderLat: Double? = null
    @Volatile private var frameRiderLng: Double? = null

    private var rerouting = false
    private var currentStops: List<io.github.saltmango.navbeam.data.SharedLocation> = emptyList()

    // Frame cache (avoid the expensive redraw when nothing changed)
    private var frameBitmap: Bitmap? = null
    private var lastSignature = ""
    private var lastRedrawAt = 0L

    // Ride diagnostics: one-shot "first frame" marker + a throttle for the periodic ride line.
    @Volatile private var loggedFirstFrame = false
    private var lastDiagMs = 0L

    companion object {
        private const val PROFILE_COOLDOWN_MS = 5_000L
        private const val MANUAL_IDLE_MS = 8_000L
        private val FORCE_REDRAW_MS = if (io.github.saltmango.navbeam.BuildConfig.DEBUG) 3_000L else 10_000L
        // How long to keep trying to (re)connect before giving up and freeing all background
        // resources. Covers the flaky initial handshake AND a mid-ride drop; if the dash is truly
        // gone (parked / powered off / out of range) we stop draining instead of retrying forever.
        // 2 min (was 45 s): if the dash AP hangs mid-ride it only comes back after a bike power-cycle,
        // and 45 s wasn't enough to safely pull over + restart before NavBeam gave up. With the
        // WiFi layer now retrying through an AP-missing window, this keeps trying long enough that a
        // power-cycle auto-rejoins with no manual step. Still bounded so a truly-gone dash frees power.
        private const val RECONNECT_GIVEUP_MS = 120_000L
        // Alert the rider after this long of sustained (non-transient) link loss — long enough to skip
        // a brief blip that auto-recovers, short enough that they're not riding blind for minutes.
        private const val CONN_LOST_ALERT_MS = 12_000L
        // Motion-adaptive frame cadence — the heart of the low-power stability model.
        // Active and idle frame durations & bitrates come from the active hardware profile JSON.
        // We gate on MOTION (smarter for our screen-off power goal): stream at the active profile
        // while the map is actually changing (driving / panning / zooming), drop to the idle profile
        // when it's static so the display decoder is never overrun.
        private const val MISSING_PROFILE_ERROR =
            "No hardware profile loaded. Upload a dash profile JSON in Settings first."

        // ── Debug / Test Flags ──────────────────────────────────────────────
        // BLACK FRAME: true → solid black video, NavInfo still flows. false → normal map render.
        const val DEBUG_BLACK_FRAME_MODE = false
        // SUPPRESS NAV INFO: true → map video streams, activeNavPacket NOT sent.
        // false → normal NavInfo sending (production default).
        const val DEBUG_SUPPRESS_NAV_INFO = false
        // FORCE MANEUVER: true → overrides navManeuver with the profile's UNKNOWN maneuver glyph in
        // outgoing packets for diagnostics. Set to false in production.
        const val DEBUG_FORCE_MANEUVER = false
    }

    init {
        // Reflect the rider's stored dash WiFi config (SSID may be blank until discovered).
        _ui.update { it.copy(
            ssid = dashConfig.ssid,
            wifiPassword = dashConfig.password,
            streamQuality = SettingsPrefs.getStreamQuality(app),
            navMode = SettingsPrefs.getNavMode(app)
        ) }
        activeModeController = when (NavigationMode.fromString(_ui.value.navMode)) {
            is NavigationMode.MapMode -> mapModeController
            is NavigationMode.ArrowMode -> arrowModeController
        }

        // Keep WiFi defaults & missing-profile state synced with the active hardware profile.
        viewModelScope.launch {
            var prevId = io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value.id
            io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.collect { profile ->
                if (prevId.isNotBlank() && profile.isConfigured && prevId != profile.id) {
                    dashConfig.forgetDash()
                }
                prevId = profile.id
                _ui.update { uiState ->
                    val clearMissingProfileErr = profile.isConfigured &&
                        uiState.stage == ConnStage.ERROR &&
                        uiState.errorMessage == MISSING_PROFILE_ERROR
                    uiState.copy(
                        ssid = dashConfig.ssid,
                        wifiPassword = dashConfig.password,
                        errorMessage = if (clearMissingProfileErr) null else uiState.errorMessage,
                    )
                }
                if (profile.isConfigured && _ui.value.stage == ConnStage.ERROR && _ui.value.errorMessage == null) {
                    refreshStage()
                }
            }
        }

        // Auto-clear "Wi-Fi Required" if the user turns it on (e.g. via notification shade)
        viewModelScope.launch {
            wifiManager.isWifiEnabledFlow.collect { enabled ->
                if (enabled && _ui.value.needsWifiOn) {
                    _ui.update { it.copy(needsWifiOn = false) }
                    // Optionally auto-connect if they were in the middle of a connection attempt
                    if (userWantsConnection) connect()
                }
            }
        }

        // When we connect to a previously-unknown dash by prefix, learn + persist its exact
        // SSID so subsequent connects target it directly (no system picker again).
        wifiManager.onSsidResolved = { learned ->
            dashConfig.ssid = learned
            _ui.update { it.copy(ssid = learned) }
        }

        viewModelScope.launch {
            wifiManager.state.collect { ws ->
                io.github.saltmango.navbeam.data.RideDiagnostics.log(
                    "wifi",
                    ws.status.toString() +
                        (if (ws.ssid.isNotBlank()) " hasSsid=true" else "") +
                        (ws.error?.let { " err=$it" } ?: ""),
                )
                when (ws.status) {
                    WifiConnStatus.CONNECTED -> {
                        refreshStage()
                        if (userWantsConnection &&
                            session.state.value in listOf(DashState.IDLE, DashState.ERROR)
                        ) {
                            delay(1_200)
                            // Re-check: the user may have hit Disconnect during the delay.
                            if (userWantsConnection &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED
                            ) session.connect(_ui.value.ssid, wifiManager.network, DeviceName.get(getApplication()))
                        }
                    }
                    WifiConnStatus.ERROR -> {
                        // Dash is gone for good (bike powered off / out of range) → the ride is
                        // over. Save it even though the user never tapped Disconnect.
                        stopRecording()
                        // …and release everything connect() acquired (wake/wifi locks, GPS,
                        // encoder loop). These used to leak when a connection ENDED on failure
                        // rather than via the Disconnect button, draining the battery in the
                        // background until the app was force-closed.
                        userWantsConnection = false
                        releaseBackgroundResources("wifi error: ${ws.error}")
                        session.disconnect()   // wifi status stays ERROR → stage remains ERROR
                        _ui.update { it.copy(errorMessage = ws.error) }; refreshStage()
                        io.github.saltmango.navbeam.data.RideDiagnostics.stop("wifi error / dash gone")
                    }
                    else -> refreshStage()
                }
            }
        }

        viewModelScope.launch {
            session.state.collect { state ->
                io.github.saltmango.navbeam.data.RideDiagnostics.log("session", "→ $state")
                refreshStage()
                // Battery backstop: count down to a full give-up unless/until we're actually
                // streaming. Reaching STREAMING cancels it; any non-streaming state (re)arms it.
                if (state == DashState.STREAMING || state == DashState.CONNECTED) { cancelReconnectGiveup(); cancelConnLostAlert() }
                else if (userWantsConnection) { armReconnectGiveup(); armConnLostAlert() }
                when (state) {
                    DashState.CONNECTED -> {
                        authAttempts = 0
                        _ui.update { it.copy(retryAttempt = 0, errorMessage = null) }
                        if (userWantsProjection || autoStartProjectionOnConnect) {
                            autoStartProjectionOnConnect = false
                            startProjection()
                        } else {
                            teardown()
                        }
                    }
                    DashState.PROJECTING -> { authAttempts = 0; _ui.update { it.copy(retryAttempt = 0) }; activeModeController?.start(viewModelScope) }
                    DashState.STREAMING -> {
                        authAttempts = 0; _ui.update { it.copy(retryAttempt = 0, errorMessage = null) }
                        // Usage: the dash actually started showing the map — the core feature working.
                        io.github.saltmango.navbeam.util.Telemetry.logEvent("dash_streaming")
                    }
                    // Dash handshake auto-retry on transient auth failure.
                    // Auto-retry (WiFi is already up) instead of making the rider re-tap Connect.
                    DashState.ERROR -> {
                        val maxAuthAttempts = io.github.saltmango.navbeam.dash.profile.DashProfileManager
                            .activeProfile.value.protocol.maxAuthAttempts.coerceAtLeast(1)
                        if (userWantsConnection &&
                            wifiManager.state.value.status == WifiConnStatus.CONNECTED &&
                            authAttempts < maxAuthAttempts
                        ) {
                            authAttempts++
                            _ui.update { it.copy(retryAttempt = authAttempts, errorMessage = null) }
                            delay(1_500)
                            if (userWantsConnection && wifiManager.state.value.status == WifiConnStatus.CONNECTED)
                                session.connect(_ui.value.ssid, wifiManager.network, DeviceName.get(getApplication()))
                        } else {
                            _ui.update { it.copy(retryAttempt = 0) }
                            // Retries exhausted while the WiFi link is still up → give up fully now
                            // (don't wait out the timer). giveUp() also stops the Wi‑Fi reconnect loop.
                            if (userWantsConnection && authAttempts >= maxAuthAttempts &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED
                            ) {
                                android.util.Log.e("DashViewModel", "Auth retries exhausted ($authAttempts attempts)")
                                giveUp("auth exhausted after $authAttempts attempts")
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        session.onError = { msg ->
            io.github.saltmango.navbeam.data.RideDiagnostics.log("error", msg)
            _ui.update { it.copy(errorMessage = msg) }; refreshStage()
        }

        // Joystick handling. While a call card is on the dash, the joystick answers/rejects the call
        // instead of zooming; otherwise zoom/media controls run. The separate HOME button is
        // intentionally untouched — it does the dash's native exit; its code falls through to the
        // logged-else branch. Button codes are mapped dynamically from the active DashProfile inputs.
        session.onButton = { btn ->
            val code = btn.toInt() and 0xFF
            val call = io.github.saltmango.navbeam.media.CallInfoProvider.incomingCall.value
            val ringing = call?.incoming == true
            val active  = call != null && !call.incoming
            val inputs = io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value.inputs

            val btnAnswer = inputs["callAnswer"] ?: -1
            val btnReject = inputs["callReject"] ?: -1
            val btnZoomIn = inputs["mapZoomIn"] ?: -1
            val btnZoomOut = inputs["mapZoomOut"] ?: -1
            val btnNext = inputs["mediaNext"] ?: -1
            val btnPrev = inputs["mediaPrev"] ?: -1

            val label = when {
                // Ringing: IN answers, DOWN rejects. Active call: DOWN hangs up (fixes "can't end").
                ringing && code == btnAnswer -> { answerCall(call!!); "Call answered" }
                ringing && code == btnReject -> { endCall(call!!);    "Call rejected" }
                active  && code == btnReject -> { endCall(call!!);    "Call ended" }
                // Map/media controls run whenever a call ISN'T ringing (still usable during an active
                // call — only DOWN is reserved for hang-up, and that's not a map/media code anyway).
                !ringing && code == btnZoomIn  -> { zoomIn();  "Zoom in (map)" }
                !ringing && code == btnZoomOut -> { zoomOut(); "Zoom out (map)" }
                !ringing && code == btnNext -> { mediaInfo.skipNext(); "Next track" }
                !ringing && code == btnPrev -> { mediaInfo.skipPrevious(); "Previous track" }
                else -> "code 0x${code.toString(16).uppercase()}"
            }
            // Persist every button code so untethered tests are diagnosable from the remote pull
            // (the on-screen readout is invisible with the phone screen off / projecting).
            io.github.saltmango.navbeam.data.RideDiagnostics.log("button",
                "0x%02X (ringing=%b active=%b) → %s".format(code, ringing, active, label))
            _ui.update { it.copy(lastButton = label) }
        }

        viewModelScope.launch(AppDispatchers.location) {
            location.location.collect { loc ->
                evaluateMotionProfile(loc)
                if (loc != null) {
                    gpsAccuracySum += loc.accuracy
                    gpsAccuracyCount++

                    // Track GPS update intervals
                    val lastFix = lastGpsFixTimeMs
                    val fixTime = loc.time
                    if (lastFix > 0L) {
                        val interval = fixTime - lastFix
                        if (interval > 0L) {
                            gpsFixIntervalSumMs += interval
                            gpsFixIntervalCount++
                        }
                    }
                    lastGpsFixTimeMs = fixTime
                }
                val navLoc = loc?.let {
                    NavigationLocation(
                        latitude = it.latitude,
                        longitude = it.longitude,
                        speedMps = it.speed,
                        bearing = it.bearing,
                        accuracy = it.accuracy,
                        timeMs = it.time,
                        elapsedRealtimeNanos = it.elapsedRealtimeNanos
                    )
                }
                val result = navEngine.onLocation(navLoc)
                processNavigationResult(result)
            }
        }

        // 1 Hz timer to detect GPS loss if fixes stop arriving
        viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val result = navEngine.onLocation(null)
                processNavigationResult(result)
            }
        }
    }

    private fun evaluateMotionProfile(loc: Location?) {
        val current = _motionProfile.value
        if (loc == null) {
            updateMotionProfile(MotionProfile.STOPPED)
            return
        }
        val speedKmh = loc.speed * 3.6
        val now = System.currentTimeMillis()

        val nextProfile = when (current) {
            MotionProfile.HIGHWAY -> {
                if (speedKmh < 35.0) MotionProfile.CITY else MotionProfile.HIGHWAY
            }
            MotionProfile.CITY -> {
                when {
                    speedKmh >= 40.0 -> MotionProfile.HIGHWAY
                    speedKmh < 8.0 -> MotionProfile.SLOW
                    else -> MotionProfile.CITY
                }
            }
            MotionProfile.SLOW -> {
                when {
                    speedKmh >= 10.0 -> MotionProfile.CITY
                    speedKmh < 2.0 -> {
                        if (stoppedTimeMs == 0L) stoppedTimeMs = now
                        if (now - stoppedTimeMs >= 10_000L) MotionProfile.STOPPED else MotionProfile.SLOW
                    }
                    else -> {
                        stoppedTimeMs = 0L
                        MotionProfile.SLOW
                    }
                }
            }
            MotionProfile.STOPPED, MotionProfile.DISCONNECTED -> {
                if (speedKmh >= 2.0) {
                    stoppedTimeMs = 0L
                    MotionProfile.SLOW
                } else {
                    MotionProfile.STOPPED
                }
            }
        }
        updateMotionProfile(nextProfile)
    }

    override fun isTurnNear(): Boolean {
        val nextTurnM = _ui.value.nextTurnDistanceM
        return nextTurnM != null && nextTurnM <= 300.0
    }

    private fun updateMotionProfile(newProfile: MotionProfile) {
        val effectiveProfile = if (isTurnNear() && newProfile != MotionProfile.DISCONNECTED) {
            MotionProfile.HIGHWAY
        } else {
            newProfile
        }

        if (_motionProfile.value == effectiveProfile && !thermalThrottled) return
        val current = _motionProfile.value
        val now = System.currentTimeMillis()

        // Cooldown: limit transitions between active moving profiles (HIGHWAY, CITY, SLOW)
        // to at most once per 5 seconds. Exempt transitions to/from DISCONNECTED or under thermal throttling,
        // as well as transitioning out of STOPPED (since accelerating from standstill should be immediate).
        val isMovingTransition = current in listOf(MotionProfile.HIGHWAY, MotionProfile.CITY, MotionProfile.SLOW)
        if (isMovingTransition &&
            effectiveProfile != MotionProfile.DISCONNECTED &&
            !thermalThrottled &&
            now - lastProfileTransitionTime < PROFILE_COOLDOWN_MS
        ) {
            return
        }

        val oldProfile = _motionProfile.value
        _motionProfile.value = effectiveProfile
        lastProfileTransitionTime = now
        android.util.Log.i("DashViewModel", "MotionProfile: $oldProfile -> $effectiveProfile (thermalThrottled=$thermalThrottled, turnNear=${isTurnNear()})")

        // 1. Forward the profile to the location tracker
        if (thermalThrottled && effectiveProfile != MotionProfile.DISCONNECTED) {
            location.setProfile(MotionProfile.STOPPED)
        } else {
            location.setProfile(effectiveProfile)
        }

        // 2. Adjust control socket timeout
        val socketTimeout = when (effectiveProfile) {
            MotionProfile.HIGHWAY, MotionProfile.CITY -> if (thermalThrottled) 1000 else 500
            MotionProfile.SLOW, MotionProfile.STOPPED -> 1000
            MotionProfile.DISCONNECTED -> 1000
        }
        session.setReceiveTimeout(socketTimeout)

        // 3. Update the encoder bitrate (if encoder is alive) derived from the active hardware profile
        val disp = io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value.display
        val activeBps = disp.activeBitrateBps.coerceIn(50_000, 3_000_000)
        val idleBps = disp.idleBitrateBps.coerceIn(50_000, activeBps)
        val cityBps = ((activeBps * 3) / 4).coerceIn(idleBps, activeBps)
        val maxBps = (activeBps * 2).coerceAtMost(3_000_000)
        val maxCityBps = ((activeBps * 3) / 2).coerceAtMost(maxBps)

        val quality = _ui.value.streamQuality
        val targetBitrate = if (thermalThrottled && effectiveProfile != MotionProfile.DISCONNECTED) {
            idleBps
        } else {
            if (isTurnNear()) {
                when (quality) {
                    "Max" -> maxBps
                    "Custom" -> SettingsPrefs.getCustomBitrate(getApplication(), "CORNERING") * 1000
                    else -> activeBps
                }
            } else {
                when (effectiveProfile) {
                    MotionProfile.HIGHWAY -> when (quality) {
                        "Max" -> maxBps
                        "Battery Saver" -> idleBps
                        "Custom" -> SettingsPrefs.getCustomBitrate(getApplication(), "HIGHWAY") * 1000
                        else -> activeBps
                    }
                    MotionProfile.CITY -> when (quality) {
                        "Max" -> maxCityBps
                        "Battery Saver" -> idleBps
                        "Custom" -> SettingsPrefs.getCustomBitrate(getApplication(), "CITY") * 1000
                        else -> cityBps
                    }
                    MotionProfile.SLOW -> when (quality) {
                        "Max" -> activeBps
                        "Battery Saver" -> idleBps
                        "Custom" -> SettingsPrefs.getCustomBitrate(getApplication(), "SLOW") * 1000
                        else -> idleBps
                    }
                    MotionProfile.STOPPED -> when (quality) {
                        "Max" -> cityBps
                        "Battery Saver" -> idleBps
                        "Custom" -> SettingsPrefs.getCustomBitrate(getApplication(), "STOPPED") * 1000
                        else -> idleBps
                    }
                    MotionProfile.DISCONNECTED -> 0
                }
            }
        }
        if (targetBitrate > 0) {
            frameStreamer.updateBitrate(targetBitrate)
        }
        io.github.saltmango.navbeam.data.RideDiagnostics.log("profile", "$oldProfile -> $effectiveProfile (quality=$quality, targetBitrate=${targetBitrate/1000}Kbps, turnNear=${isTurnNear()})")
    }

    private fun updateApplicationState(newState: ApplicationState) {
        if (_appState.value == newState) return
        _appState.value = newState
        android.util.Log.i("DashViewModel", "ApplicationState: $newState")

        if (newState == ApplicationState.DISCONNECTED) {
            updateMotionProfile(MotionProfile.DISCONNECTED)
        } else {
            evaluateMotionProfile(location.location.value)
        }
    }

    private fun refreshStage() {
        val wifi = wifiManager.state.value.status
        val dash = session.state.value
        val stage = when {
            dash == DashState.STREAMING -> ConnStage.STREAMING
            dash == DashState.CONNECTED -> ConnStage.CONNECTED
            dash == DashState.ERROR || wifi == WifiConnStatus.ERROR -> ConnStage.ERROR
            dash == DashState.AUTHENTICATING || dash == DashState.CONNECTING || dash == DashState.PROJECTING -> ConnStage.AUTH
            wifi == WifiConnStatus.REQUESTING || wifi == WifiConnStatus.CONNECTED -> ConnStage.WIFI
            _ui.value.stage == ConnStage.ERROR && _ui.value.errorMessage == MISSING_PROFILE_ERROR -> ConnStage.ERROR
            else -> ConnStage.OFFLINE
        }
        _ui.update { it.copy(stage = stage) }

        val newAppState = when {
            stage == ConnStage.STREAMING -> {
                if (navEngine.currentState().destination.destination != null) {
                    ApplicationState.CONNECTED_NAVIGATION
                } else {
                    ApplicationState.CONNECTED_FREE_ROAM
                }
            }
            else -> ApplicationState.DISCONNECTED
        }
        updateApplicationState(newAppState)
    }

    // ── Connection ─────────────────────────────────────────────────────────

    fun connect(autoStartProj: Boolean = false) {
        if (!io.github.saltmango.navbeam.dash.profile.DashProfileManager.hasProfile()) {
            userWantsConnection = false
            autoStartProjectionOnConnect = false
            authAttempts = 0
            _ui.update { it.copy(
                stage = ConnStage.ERROR,
                errorMessage = MISSING_PROFILE_ERROR,
                retryAttempt = 0,
                needsWifiOn = false,
            ) }
            return
        }
        userWantsConnection = true
        autoStartProjectionOnConnect = autoStartProj
        authAttempts = 0
        _ui.update { it.copy(errorMessage = null, retryAttempt = 0, needsWifiOn = false) }

        // The dash link needs the Wi‑Fi radio on (WifiNetworkSpecifier joins it as a separate
        // per-app network — the phone's current Wi‑Fi/cellular is untouched). Android 10+ won't
        // let us enable Wi‑Fi for the user, so if it's off, surface it immediately and let the UI
        // open the Wi‑Fi panel — far better than a silent 30s timeout.
        if (!wifiManager.isWifiEnabled()) {
            _ui.update { it.copy(needsWifiOn = true) }
            return
        }
        io.github.saltmango.navbeam.data.RideDiagnostics.init(getApplication())
        io.github.saltmango.navbeam.data.RideDiagnostics.start("connect")
        // Stamp the running build's identity into the log so a pulled ride is matched 1:1 to the
        // pushed APK — never again analyse a ride and not know which build produced it.
        io.github.saltmango.navbeam.data.RideDiagnostics.log(
            "build", "apk=${io.github.saltmango.navbeam.util.BuildId.sha12(getApplication())}" +
                " vc=${io.github.saltmango.navbeam.BuildConfig.VERSION_CODE} name=${io.github.saltmango.navbeam.BuildConfig.VERSION_NAME}",
        )
        io.github.saltmango.navbeam.data.RideDiagnostics.log(
            "connect", "hasSsid=${dashConfig.ssid.isNotBlank()} needsDiscovery=${dashConfig.needsDiscovery} hasDest=${!_ui.value.destinationName.isNullOrBlank()}",
        )
        loggedFirstFrame = false
        DashKeepAliveService.start(getApplication())

        // We must know the EXACT dash SSID before connecting — the dash validates it inside
        // the encrypted auth handshake (DashAuth), and Android redacts the SSID of a network
        // we've already joined. So if we don't have it stored, find it from a WiFi scan.
        if (dashConfig.needsDiscovery) {
            wifiManager.findDashSsid(dashConfig.ssidPrefix)?.let { found ->
                dashConfig.ssid = found
                _ui.update { it.copy(ssid = found) }
            }
        }

        when {
            wifiManager.state.value.status == WifiConnStatus.CONNECTED ->
                session.connect(_ui.value.ssid, wifiManager.network, DeviceName.get(getApplication()))
            // Known SSID (stored or just found by scan) → exact connect + correct auth.
            dashConfig.ssid.isNotBlank() ->
                wifiManager.connect(dashConfig.ssid, dashConfig.password)
            // Couldn't find it in scan results — fall back to prefix discovery so we at
            // least associate (auth may still need the SSID; a rescan usually fixes it).
            else ->
                wifiManager.connect(dashConfig.ssidPrefix, dashConfig.password, prefixMatch = true)
        }
    }

    fun startProjection() {
        val state = session.state.value
        if (state == io.github.saltmango.navbeam.dash.DashState.PROJECTING ||
            state == io.github.saltmango.navbeam.dash.DashState.STREAMING
        ) {
            return
        }
        if (state != io.github.saltmango.navbeam.dash.DashState.CONNECTED) {
            android.util.Log.w("DashViewModel", "Cannot start projection: state is $state")
            return
        }
        userWantsProjection = true
        location.start()
        startRecording()
        activeModeController?.start(viewModelScope)
    }

    fun stopProjection() {
        userWantsProjection = false
        location.stop()
        stopRecording()
        teardown()
        refreshStage()
    }

    fun disconnect() {
        userWantsConnection = false
        cancelReconnectGiveup()
        io.github.saltmango.navbeam.data.RideDiagnostics.log("connect", "user disconnect")
        stopProjection()
        session.disconnect()
        wifiManager.disconnect()
        releaseBackgroundResources("user disconnect")
        refreshStage()
        io.github.saltmango.navbeam.data.RideDiagnostics.stop("disconnect")
        io.github.saltmango.navbeam.util.Telemetry.logEvent("dash_disconnected")
    }

    /**
     * Release everything that keeps the CPU/GPS/WiFi awake: the foreground service
     * (PARTIAL_WAKE_LOCK + WifiLock), GPS updates, and the encoder/stream loop. Called
     * whenever the connection has ended so the app stops draining the battery in the
     * background — on an explicit disconnect, on a terminal connection failure (dash
     * unreachable / handshake exhausted), and when the ViewModel is cleared.
     */
    private fun releaseBackgroundResources(reason: String) {
        android.util.Log.i("DashViewModel", "releaseBackgroundResources: $reason")
        userWantsProjection = false
        autoStartProjectionOnConnect = false
        teardown()                          // stream loop + encoder + frame bitmap
        location.stop()                              // stop GPS updates
        DashKeepAliveService.stop(getApplication())  // release wake + wifi locks
    }

    /**
     * Complete stop of all background work — including the Wi‑Fi manager's reconnect loop, which
     * [releaseBackgroundResources] alone does NOT stop (that loop re-joins the dash AP every few
     * seconds and was the main source of drain after the dash went away). The session stays in
     * ERROR so the Dash screen still shows "Couldn't connect" + Try again.
     */
    private fun giveUp(reason: String) {
        cancelReconnectGiveup()
        userWantsConnection = false
        session.disconnect()
        wifiManager.disconnect()       // stops the indefinite AP reconnect loop
        releaseBackgroundResources("gave up: $reason")
        refreshStage()
        io.github.saltmango.navbeam.data.RideDiagnostics.log("error", "gave up: $reason — background released")
        io.github.saltmango.navbeam.data.RideDiagnostics.stop(reason)
    }

    /** Start the give-up countdown if it isn't already running (idempotent). */
    private fun armReconnectGiveup() {
        if (giveupJob?.isActive == true) return
        giveupJob = viewModelScope.launch {
            delay(RECONNECT_GIVEUP_MS)
            if (session.state.value != DashState.STREAMING) giveUp("reconnect window elapsed")
        }
    }

    private fun cancelReconnectGiveup() { giveupJob?.cancel(); giveupJob = null }

    // Non-silent disconnect alert: this dash-AP-hang failure only recovers after the rider
    // power-cycles the bike, so they must KNOW the link dropped. Fire once after a sustained loss
    // (not a transient blip) via vibration + a BT-routed voice cue, so it carries through the helmet
    // with the phone screen off. Re-armed each non-streaming state; cancelled (and reset) on recovery.
    private fun armConnLostAlert() {
        if (connLostAlertJob?.isActive == true || connLostAlerted) return
        connLostAlertJob = viewModelScope.launch {
            delay(CONN_LOST_ALERT_MS)
            if (session.state.value != DashState.STREAMING && userWantsConnection) {
                connLostAlerted = true
                io.github.saltmango.navbeam.data.RideDiagnostics.log("connect", "sustained link loss → rider alert (vibrate + voice)")
                runCatching {
                    val v = getApplication<Application>().getSystemService(android.os.Vibrator::class.java)
                    v?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 300), -1))
                }
                voice.alert("Dash disconnected. Restart the dash to reconnect.")
            }
        }
    }

    private fun cancelConnLostAlert() {
        connLostAlertJob?.cancel(); connLostAlertJob = null
        if (connLostAlerted) {
            connLostAlerted = false
            voice.alert("Dash reconnected")
        }
    }

    // ── Ride recording (the connected session) ───────────────────────────────
    private fun startRecording() {
        if (recorder.isRecording) return
        gpsAccuracySum = 0.0
        gpsAccuracyCount = 0
        reconnectCount = 0
        rerouteCount = 0
        lastGpsFixTimeMs = 0L
        gpsFixIntervalSumMs = 0L
        gpsFixIntervalCount = 0
        gpsDropsCount = 0

        val bState = getBatteryState()
        recorder.start(
            startBattery = bState.level,
            startBatteryTemp = bState.temp,
            destinationName = _ui.value.destinationName,
            stopsList = currentStops
        )
        recordJob = viewModelScope.launch {
            location.location.collect { loc ->
                if (loc != null) recorder.add(loc.latitude, loc.longitude, loc.speed, loc.accuracy, loc.time)
            }
        }
    }

    private fun stopRecording() {
        recordJob?.cancel(); recordJob = null
        if (!recorder.isRecording) return

        val bState = getBatteryState()
        val metrics = runCatching { frameStreamer.getMetrics() }.getOrNull()

        val avgFps = if (metrics != null && recorder.isRecording) {
            val durationS = ((System.currentTimeMillis() - recorder.startPointTimeMs) / 1000.0).coerceAtLeast(1.0)
            metrics.encoderTimeCount.toDouble() / durationS
        } else 0.0

        val avgEncodeTimeMs = if (metrics != null && metrics.encoderTimeCount > 0) {
            metrics.encoderTimeSum.toDouble() / metrics.encoderTimeCount
        } else 0.0

        val avgNetworkLatencyMs = if (metrics != null && metrics.rtpLatencyCount > 0) {
            metrics.rtpLatencySum.toDouble() / metrics.rtpLatencyCount
        } else 0.0

        val ride = recorder.stop(
            endBattery = bState.level,
            endBatteryTemp = bState.temp,
            thermalThrottled = thermalThrottled,
            reconnectCount = reconnectCount,
            gpsAccuracyAvg = if (gpsAccuracyCount > 0) gpsAccuracySum / gpsAccuracyCount else 0.0,
            gpsFixCount = gpsAccuracyCount,
            gpsIntervalAvgMs = if (gpsFixIntervalCount > 0) gpsFixIntervalSumMs / gpsFixIntervalCount else 0L,
            gpsDropsCount = gpsDropsCount,
            recalcCount = rerouteCount,
            avgFps = avgFps,
            skippedFrames = metrics?.encoderSkippedCount ?: 0,
            totalBytesSent = metrics?.totalBytesSent ?: 0L,
            avgEncodeTimeMs = avgEncodeTimeMs,
            avgNetworkLatencyMs = avgNetworkLatencyMs,
            encoderMode = metrics?.encoderMode ?: "",
            navMode = _ui.value.navMode,
            streamQuality = _ui.value.streamQuality
        ) ?: return

        repo.addRide(ride)   // self-scoped on the repo (survives ViewModel teardown)
        android.util.Log.i("DashViewModel", "Ride saved: ${"%.1f".format(ride.distanceKm)} km, ${ride.durationSec}s")
    }

    // ── Dash WiFi config (Settings) ──────────────────────────────────────────
    fun setSsid(s: String) { dashConfig.ssid = s.trim(); _ui.update { it.copy(ssid = s.trim()) } }
    fun setWifiPassword(p: String) { dashConfig.password = p; _ui.update { it.copy(wifiPassword = p) } }
    fun setStreamQuality(q: String) {
        SettingsPrefs.setStreamQuality(getApplication(), q)
        _ui.update { it.copy(streamQuality = q) }
        if (_ui.value.stage == ConnStage.STREAMING) {
            evaluateMotionProfile(location.location.value)
        }
    }
    fun setNavMode(mode: String) {
        SettingsPrefs.setNavMode(getApplication(), mode)
        _ui.update { it.copy(navMode = mode) }
        val newController = when (NavigationMode.fromString(mode)) {
            is NavigationMode.MapMode -> mapModeController
            is NavigationMode.ArrowMode -> arrowModeController
        }
        val oldController = activeModeController
        if (oldController != newController) {
            if (_ui.value.stage == ConnStage.STREAMING) {
                viewModelScope.launch(Dispatchers.Default) {
                    activeModeController = newController
                    oldController?.stop()
                    newController?.start(viewModelScope)
                }
            } else {
                activeModeController = newController
            }
        }
    }
    /** Forget the paired dash so the next connect rediscovers the dash by prefix. */
    fun forgetDash() { dashConfig.forgetDash(); _ui.update { it.copy(ssid = "") } }

    // ── Destination + routing ───────────────────────────────────────────────

    fun prefetchTiles(lat: Double, lng: Double) {
        activeModeController?.onLocationUpdated(lat, lng)
    }

    fun setDestination(name: String, lat: Double?, lng: Double?, stops: List<io.github.saltmango.navbeam.data.SharedLocation> = emptyList()) {
        currentStops = stops
        _ui.update { it.copy(
            destinationName = name, hasRoute = false,
            destLatLng = if (lat != null && lng != null) lat to lng else null,
            routePoints = emptyList(),
        ) }
        if (lat != null && lng != null) {
            session.updateRouteCard(name)
            val dest = Destination(name, lat, lng)
            navEngine.setDestination(dest)
            activeModeController?.onDestinationUpdated(lat, lng)
            fetchRoute(lat, lng, stops)
        } else {
            navEngine.clearDestination()
            session.updateRouteCard("NavBeam")
        }
    }

    /** Drop the destination/route → free roam. The map keeps streaming and follows the rider. */
    fun exitNavigation() {
        currentStops = emptyList()
        navEngine.clearDestination()
        navEngine.clearRoute()
        cameraController.recenter()
        lastSignature = ""   // force a redraw with no route line
        _ui.update { it.copy(
            destinationName = null,
            hasRoute = false,
            destLatLng = null,
            routePoints = emptyList(),
            remainingKm = null,
            etaMinutes = null,
            maneuver = null,
            offRoute = false,
            followMode = true,
        ) }
        session.updateRouteCard("NavBeam")   // dash card → name + 0.0 km, nav off
        // Stop the stream automatically when navigation ends — the rider is done projecting
        stopProjection()
    }

    /** Compute the road route now (while internet is reachable) and cache it. */
    private fun fetchRoute(destLatV: Double, destLngV: Double, stops: List<io.github.saltmango.navbeam.data.SharedLocation> = emptyList()) {
        val loc = location.lastKnown()
        if (loc == null) {
            android.util.Log.w("DashViewModel", "fetchRoute: no origin location yet")
            return
        }
        viewModelScope.launch {
            val stopPoints = if (stops.isNotEmpty()) {
                stops.map { GeoPoint(it.lat, it.lng) }
            } else {
                listOf(GeoPoint(destLatV, destLngV))
            }
            val r = Router.route(getApplication(), GeoPoint(loc.latitude, loc.longitude), stopPoints)
            if (r != null) {
                val compRoute = CompiledRoute(
                    geometry = r.geometry,
                    maneuvers = r.maneuvers,
                    totalMeters = r.totalMeters,
                    totalSeconds = r.totalSeconds,
                    cumulative = r.cumulative
                )
                session.resetNavForNewRoute()
                navEngine.setRoute(compRoute)
                activeModeController?.onRouteUpdated(r.geometry)
                _ui.update { it.copy(hasRoute = true, routePoints = r.geometry) }
                android.util.Log.i("DashViewModel", "Route ready: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
            } else {
                android.util.Log.w("DashViewModel", "Router returned null")
            }
        }
    }

    // ── Map controls ────────────────────────────────────────────────────────

    fun zoomIn() {
        cameraController.zoomIn()
        val pos = cameraController.getPosition()
        _ui.update { it.copy(mapZoom = pos.zoom) }
        lastSignature = ""
        if (pos.center.lat != 0.0) tiles.prefetchZoom(pos.center.lat, pos.center.lng, pos.zoom)
    }

    fun zoomOut() {
        cameraController.zoomOut()
        val pos = cameraController.getPosition()
        _ui.update { it.copy(mapZoom = pos.zoom) }
        lastSignature = ""
        if (pos.center.lat != 0.0) tiles.prefetchZoom(pos.center.lat, pos.center.lng, pos.zoom)
    }

    fun panBy(dx: Float, dy: Float) {
        cameraController.panBy(dx, dy)
        _ui.update { it.copy(followMode = false) }
    }

    fun recenter() {
        cameraController.recenter()
        _ui.update { it.copy(followMode = true) }
    }

    fun toggleHeadingUp() {
        cameraController.setHeadingUp(!cameraController.isHeadingUp())
        _ui.update { it.copy(headingUp = cameraController.isHeadingUp()) }
    }

    fun setAdjustMode(enabled: Boolean) {
        _ui.update { it.copy(adjustMode = enabled) }
    }



    // ── Video + nav loop ────────────────────────────────────────────────────

    // ── MapModeContext implementation ────────────────────────────────────────

    override fun enterNavigation(projection: Boolean) {
        session.enterNavigation(projection)
    }

    override fun stopNavigation() {
        session.stopNavigation()
    }

    override fun isNavigationRunning(): Boolean {
        return session.isNavigationRunning()
    }

    override suspend fun startStreamPipeline(initialBitrate: Int, forceSoftware: Boolean) {
        android.util.Log.i("DashViewModel", "startStreamPipeline — frameStreamer starting")
        val disp = io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value.display
        if (disp.width <= 0 || disp.height <= 0) {
            throw IllegalStateException("Cannot start stream: invalid display dimensions (${disp.width}x${disp.height})")
        }
        reconnectCount++
        frameBitmap = Bitmap.createBitmap(disp.width, disp.height, Bitmap.Config.ARGB_8888)
        lastSignature = ""
        cameraController.reset()

        val config = StreamerConfig(
            session = session,
            initialBitrate = initialBitrate,
            initialFrameDurationMs = disp.frameDurationActiveMs,
            width = disp.width,
            height = disp.height,
            h264Level = disp.h264Level,
            prependSpsPpsToIdr = disp.prependSpsPpsToIdr,
            forceSpsBaseline = disp.forceSpsBaseline,
            dropSeiAndAud = disp.dropSeiAndAud,
            rtpMaxPayloadBytes = disp.rtpMaxPayloadBytes,
            rtpPayloadType = disp.rtpPayloadType,
            forceSoftware = forceSoftware
        )
        frameStreamer.start(config)
    }

    override fun stopStreamPipeline() {
        frameStreamer.stop()
        frameBitmap?.recycle()
        frameBitmap = null
        _previewFrame.value = null
    }

    override fun updateBitrate(bitrate: Int) {
        frameStreamer.updateBitrate(bitrate)
    }

    override fun setFrameDuration(durationMs: Long) {
        frameStreamer.setFrameDuration(durationMs)
    }

    override suspend fun renderAndSubmitFrame() {
        if (!userWantsProjection) return

        if (frameBitmap == null) {
            val disp = io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value.display
            if (disp.width <= 0 || disp.height <= 0) return
            frameBitmap = Bitmap.createBitmap(disp.width, disp.height, Bitmap.Config.ARGB_8888)
            lastSignature = ""
        }

        if (debugBlackFrameMode || _ui.value.navMode == "Arrow") {
            // Arrow Mode or DEBUG: skip map rendering — send solid black frame only.
            // NavInfo (TBT maneuver, distance, ETA) is still sent by DashSession keep-alives.
            frameBitmap?.eraseColor(android.graphics.Color.BLACK)
        } else {
            tick()
        }

        val bmp = frameBitmap
        if (bmp != null) {
            frameStreamer.submitFrame(bmp)
        }
        // If we just started streaming, notify the session to start the 1Hz nav/media keep-alives
        if (session.state.value == DashState.PROJECTING) {
            session.startStreaming()
        }
    }

    override fun prefetch(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) {
            location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }
        } else {
            val loc = location.location.value
            tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
        }
    }

    override fun prefetchRoute(geometry: List<GeoPoint>) {
        tiles.prefetchRoute(geometry)
    }

    override fun getStreamQuality(): String {
        return SettingsPrefs.getStreamQuality(getApplication())
    }

    override fun useSoftwareEncoder(): Boolean {
        return useSoftwareEncoder
    }

    override fun isStreamingActive(): Boolean {
        return frameStreamer.isRunning()
    }

    override fun isThermalThrottled(): Boolean {
        return thermalThrottled
    }

    override fun currentMotionProfile(): MotionProfile {
        return _motionProfile.value
    }

    override fun getCustomFps(profile: String): Int {
        return SettingsPrefs.getCustomFps(getApplication(), profile)
    }

    override fun getCustomBitrate(profile: String): Int {
        return SettingsPrefs.getCustomBitrate(getApplication(), profile)
    }

    fun setCustomFps(profile: String, value: Int) {
        SettingsPrefs.setCustomFps(getApplication(), profile, value)
        if (_ui.value.streamQuality == "Custom") {
            evaluateMotionProfile(location.location.value)
        }
    }

    fun setCustomBitrate(profile: String, value: Int) {
        SettingsPrefs.setCustomBitrate(getApplication(), profile, value)
        if (_ui.value.streamQuality == "Custom") {
            evaluateMotionProfile(location.location.value)
        }
    }

    override fun startMediaForwarding() {
        val granted = io.github.saltmango.navbeam.media.MediaInfoProvider.isAccessGranted(getApplication())
        io.github.saltmango.navbeam.data.RideDiagnostics.log("media", "forwarding start — notification access=$granted")
        mediaInfo.start()
        mediaObserveJob?.cancel()
        mediaObserveJob = viewModelScope.launch {
            launch {
                mediaInfo.nowPlaying.collect { np ->
                    io.github.saltmango.navbeam.data.RideDiagnostics.log("media", "now-playing observed: active=${np != null}")
                    session.updateNowPlaying(np?.title, np?.album.orEmpty(), np?.artist.orEmpty())
                }
            }
            launch {
                io.github.saltmango.navbeam.media.CallInfoProvider.incomingCall.collect { call ->
                    io.github.saltmango.navbeam.data.RideDiagnostics.log("media", "call observed: active=${call != null}")
                    session.updateCall(call?.caller)
                }
            }
        }
    }

    /**
     * Compute nav state, push nav-info to the dash, and redraw the frame only if it changed.
     * @return true if the visible map content changed this tick (drives the motion-adaptive cadence).
     */
    /**
     * Compute camera position, prediction, and redraw the frame only if it changed.
     * @return true if the visible map content changed this tick (drives the motion-adaptive cadence).
     */
    private fun tick(): Boolean {
        val loc = location.location.value
        val navState = navEngine.currentState()
        val r = navEngine.currentRoute()
        val dState = navState.destination
        val dLat = dState.destination?.latitude
        val dLng = dState.destination?.longitude

        val bestLoc = loc ?: location.lastKnown()
        val rawTarget = when {
            bestLoc != null -> NavigationLocation(
                latitude = bestLoc.latitude,
                longitude = bestLoc.longitude,
                speedMps = bestLoc.speed,
                bearing = bestLoc.bearing,
                accuracy = bestLoc.accuracy,
                timeMs = bestLoc.time,
                elapsedRealtimeNanos = bestLoc.elapsedRealtimeNanos
            )
            dLat != null && dLng != null -> NavigationLocation(
                latitude = dLat,
                longitude = dLng,
                speedMps = 0f,
                bearing = 0f,
                accuracy = 10f,
                timeMs = System.currentTimeMillis(),
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            )
            else -> null
        }

        val target = CameraTarget(
            raw = rawTarget,
            matchedLat = navState.gps.matchedLat,
            matchedLng = navState.gps.matchedLng,
            nextTurnDistanceM = _ui.value.nextTurnDistanceM
        )

        val camPos = cameraController.update(target)

        if (_ui.value.followMode != camPos.followMode) {
            _ui.update { it.copy(followMode = camPos.followMode) }
        }

        frameRiderLat = if (loc != null) camPos.center.lat else null
        frameRiderLng = if (loc != null) camPos.center.lng else null

        val newState = RenderState(
            centerLatBucket = (Mercator.latToTileY(camPos.center.lat, 19) * 25.6).toLong(),
            centerLngBucket = (Mercator.lngToTileX(camPos.center.lng, 19) * 25.6).toLong(),
            zoom = camPos.zoom,
            bearingBucket = Math.round(camPos.heading * 2f),
            routePointsSize = r?.geometry?.size ?: 0,
            panX = camPos.panX,
            panY = camPos.panY,
            riderLat = if (loc != null) camPos.center.lat else null,
            riderLng = if (loc != null) camPos.center.lng else null,
            destLat = dLat,
            destLng = dLng
        )
        val newHash = newState.hashCode()
        val contentChanged = (newHash != previousRenderStateHash || newState != previousRenderState)

        val now = System.currentTimeMillis()
        if (contentChanged || now - lastRedrawAt > FORCE_REDRAW_MS) {
            previousRenderStateHash = newHash
            previousRenderState = newState
            lastRedrawAt = now
            redrawFrame(
                centerLat = camPos.center.lat,
                centerLng = camPos.center.lng,
                heading = camPos.heading,
                remainingM = navState.progress.remainingDistance,
                camPos = camPos
            )
        } else {
            renderSkippedCount++
        }
        return contentChanged
    }

    /**
     * Reroute when off the line for >5 s (12 s cooldown between attempts). Routes from
     * the live GPS position to the saved destination and swaps the polyline in. Needs
     * internet — available now because only the dash sockets are bound to the dash WiFi.
     */
    private fun processNavigationResult(result: NavigationResult) {
        val state = result.state

        val gpsStatusMapped = when (state.gps.gpsStatus) {
            NavigationGpsStatus.GOOD -> GpsStatus.GOOD
            NavigationGpsStatus.WEAK -> GpsStatus.WEAK
            NavigationGpsStatus.LOST -> GpsStatus.LOST
        }

        if ((gpsStatusMapped == GpsStatus.WEAK || gpsStatusMapped == GpsStatus.LOST) && gpsStatus == GpsStatus.GOOD) {
            gpsDropsCount++
        }
        gpsStatus = gpsStatusMapped

        _ui.update { it.copy(
            hasGps = location.location.value != null,
            gpsStatus = gpsStatusMapped,
            riderLat = state.gps.matchedLat,
            riderLng = state.gps.matchedLng,
            riderBearing = state.gps.riderBearing,
            remainingKm = state.progress.remainingDistance?.let { d -> d / 1000.0 },
            etaMinutes = state.progress.etaSeconds?.let { s -> Math.round(s / 60.0).toInt() },
            etaArrival = if (state.progress.etaArrivalMs > 0) {
                java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(state.progress.etaArrivalMs))
            } else null,
            maneuver = state.maneuver.nextManeuver?.instruction,
            offRoute = state.progress.offRoute,
            recalculating = state.progress.recalculating
        ) }

        session.updateNavigation(state)

        val nextTurnM = state.maneuver.nextManeuver?.let {
            (it.cumulativeMeters - state.progress.progress).coerceAtLeast(0.0)
        } ?: state.progress.remainingDistance
        _ui.update { it.copy(nextTurnDistanceM = nextTurnM) }

        // Trigger profile update if turn proximity status changes to boost GPS/bitrate
        evaluateMotionProfile(location.location.value)

        result.event?.let { event ->
            when (event) {
                is NavigationEvent.ManeuverAnnounce -> {
                    voice.maybeAnnounce(event.maneuver, event.distanceM, event.remainingM)
                }
                is NavigationEvent.Arrived -> {
                    voice.maybeAnnounce(null, 0.0, event.remainingM)
                }
                is NavigationEvent.RequestReroute -> {
                    triggerReroute(event.location, event.destination)
                }
            }
        }
    }

    private fun triggerReroute(loc: NavigationLocation, dest: Destination) {
        if (rerouting) return
        rerouteCount++
        rerouting = true
        android.util.Log.i("DashViewModel", "Off-route → rerouting")
        io.github.saltmango.navbeam.data.RideDiagnostics.log("reroute", "requesting new route")
        val startMs = System.currentTimeMillis()
        viewModelScope.launch {
            val stopPoints = if (currentStops.isNotEmpty()) {
                currentStops.map { GeoPoint(it.lat, it.lng) }
            } else {
                listOf(GeoPoint(dest.latitude, dest.longitude))
            }
            val r = Router.route(getApplication(), GeoPoint(loc.latitude, loc.longitude), stopPoints)
            val took = System.currentTimeMillis() - startMs
            if (r != null) {
                val compRoute = CompiledRoute(
                    geometry = r.geometry,
                    maneuvers = r.maneuvers,
                    totalMeters = r.totalMeters,
                    totalSeconds = r.totalSeconds,
                    cumulative = r.cumulative
                )
                session.resetNavForNewRoute()
                navEngine.setRoute(compRoute)
                activeModeController?.onRouteUpdated(r.geometry)
                _ui.update { it.copy(hasRoute = true, routePoints = r.geometry) }
                android.util.Log.i("DashViewModel", "Reroute ok: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
                io.github.saltmango.navbeam.data.RideDiagnostics.log("reroute", "new route in ${took}ms (${r.geometry.size} pts, ${r.totalMeters.toInt()} m)")
            } else {
                android.util.Log.w("DashViewModel", "Reroute failed (no internet?)")
                io.github.saltmango.navbeam.data.RideDiagnostics.log("reroute", "FAILED after ${took}ms (no internet?)")
                // Reset rerouting on failure
                rerouting = false
            }
            rerouting = false
        }
    }

    private fun getRouteColorInt(): Int {
        val ctx = getApplication<Application>()
        val colorName = SettingsPrefs.getRouteColor(ctx)
        val hex = when (colorName) {
            "Laser Purple" -> "#BF00FF"
            "Acid Pink" -> "#FF007F"
            "Fuchsia" -> "#FF00FF"
            "Black" -> "#000000"
            "Default Blue" -> "#4285F4"
            else -> SettingsPrefs.getCustomRouteColorHex(ctx)
        }
        return try {
            val cleanHex = hex.trim().removePrefix("#")
            val longVal = cleanHex.toLong(16)
            if (cleanHex.length <= 6) (0xFF000000 or longVal).toInt() else longVal.toInt()
        } catch (e: Exception) {
            android.graphics.Color.rgb(160, 64, 255)
        }
    }

    private fun redrawFrame(
        centerLat: Double, centerLng: Double, heading: Float, remainingM: Double?, camPos: CameraPosition
    ) {
        val startRenderTime = System.currentTimeMillis()
        val bmp = frameBitmap ?: return
        val loc = location.location.value
        val mins = _ui.value.etaMinutes
        val arriving = mins != null && mins <= 0
        val recalculating = _ui.value.recalculating
        val navState = navEngine.currentState()
        val etaPrimary = when {
            recalculating && navEngine.currentRoute() != null -> "Recalculating"
            mins != null && navState.progress.etaArrivalMs > 0L -> when {
                arriving   -> "Arriving"
                mins >= 60 -> "${mins / 60}h ${mins % 60}m"
                else       -> "$mins min"
            }
            else -> null
        }
        val etaSecondary = if (etaPrimary != null && !arriving && !recalculating)
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(navState.progress.etaArrivalMs))
        else null
        val frame = MapRenderer.Frame(
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = camPos.zoom,
            panX = camPos.panX,
            panY = camPos.panY,
            headingUp = cameraController.isHeadingUp() && (loc != null),
            heading = heading,
            riderLat = frameRiderLat,
            riderLng = frameRiderLng,
            destLat = navState.destination.destination?.latitude,
            destLng = navState.destination.destination?.longitude,
            destName = _ui.value.destinationName,
            route = navEngine.currentRoute()?.geometry ?: emptyList(),
            maneuverText = null,
            remainingText = remainingM?.let { fmtDist(it) },
            tilt3d = false,
            etaPrimary = etaPrimary,
            etaSecondary = etaSecondary,
            gpsWeak = navState.gps.gpsStatus == NavigationGpsStatus.WEAK,
            gpsLost = navState.gps.gpsStatus == NavigationGpsStatus.LOST,
            routeColor = getRouteColorInt(),
            routeWidth = SettingsPrefs.getRouteLineWidth(getApplication()),
        )
        val canvas = Canvas(bmp)
        mapRenderer.draw(canvas, frame)
        drawDashOverlays(canvas, bmp.width, bmp.height)
        if (_previewFrame.subscriptionCount.value > 0) {
            _previewFrame.value = bmp.copy(Bitmap.Config.ARGB_8888, false)
        }
        val duration = System.currentTimeMillis() - startRenderTime
        renderTimeSum += duration
        renderTimeCount++
    }

    private fun updateThermal() {
        val status = runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val label = when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "OK"
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> "Hot"
            else -> "Throttling"
        }
        if (label != _ui.value.thermal) _ui.update { it.copy(thermal = label) }

        val throttled = when (status) {
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> true
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> false
            else -> false
        }
        if (throttled != thermalThrottled) {
            thermalThrottled = throttled
            android.util.Log.i("DashViewModel", "Thermal throttling state changed: $throttled (status=$status)")
            if (_ui.value.stage == ConnStage.STREAMING) {
                evaluateMotionProfile(location.location.value)
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** metres → (value, dash unit). <1 km → metres (0x30); else km×10 (0x10). */
    // toDashDistance removed (now owned by DashSession)

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)
    private val ovBg by lazy { android.graphics.Paint().apply { color = 0xCC0B0D0E.toInt(); isAntiAlias = true } }
    private val ovTitle by lazy { android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE; isAntiAlias = true; textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER } }
    private val ovSub by lazy { android.graphics.Paint().apply {
        color = 0xFFCFCFCF.toInt(); isAntiAlias = true; textSize = 10f; textAlign = android.graphics.Paint.Align.CENTER } }
    private val ovAccent by lazy { android.graphics.Paint().apply {
        color = 0xFFF2A93B.toInt(); isAntiAlias = true; textSize = 10f; textAlign = android.graphics.Paint.Align.CENTER } }

    private fun drawDashOverlays(canvas: Canvas, w: Int, h: Int) {
        val call = io.github.saltmango.navbeam.media.CallInfoProvider.incomingCall.value
        if (call == null || !call.incoming) return
        val cx = w / 2f; val cy = h / 2f
        val half = h * 0.28f
        canvas.drawRoundRect(cx - half, cy - 26f, cx + half, cy + 26f, 10f, 10f, ovBg)
        canvas.drawText("Incoming call", cx, cy - 11f, ovSub)
        canvas.drawText(ellipsizeOverlay(call.caller, 16), cx, cy + 5f, ovTitle)
        canvas.drawText("UP answer · DOWN reject", cx, cy + 19f, ovAccent)
    }

    private fun ellipsizeOverlay(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s

    private fun answerCall(c: io.github.saltmango.navbeam.media.IncomingCall) {
        val sent = c.answerIntent?.let { runCatching { it.send() }.isSuccess } ?: false
        if (!sent) callController.answer()
        io.github.saltmango.navbeam.data.RideDiagnostics.log("media", "answer (notifAction=$sent)")
    }

    private fun endCall(c: io.github.saltmango.navbeam.media.IncomingCall) {
        val sent = c.declineIntent?.let { runCatching { it.send() }.isSuccess } ?: false
        if (!sent) callController.hangup()
        io.github.saltmango.navbeam.data.RideDiagnostics.log("media", "end/reject (notifAction=$sent)")
    }

    private fun teardown() {
        activeModeController?.stop()
        stopMediaForwarding()
        useSoftwareEncoder = false
    }

    /**
     * Bridge the phone's now-playing session + incoming-call notification onto the dash using the
     * profile's configured media/call TLV tags. Needs notification access; without it
     * MediaInfoProvider stays silent and the dash simply shows no media card. Album art framing is
     * exposed via DashCommands.albumArtFrames.
     */

    private fun stopMediaForwarding() {
        mediaObserveJob?.cancel(); mediaObserveJob = null
        mediaInfo.stop()
        session.updateNowPlaying(null, "", "")
        session.updateCall(null)
    }

    override fun onCleared() {
        super.onCleared()
        stopRecording()        // save the in-progress ride if the app is closed mid-session
        session.disconnect()
        wifiManager.disconnect()
        // Streaming can't continue once the ViewModel is gone, so make sure the foreground
        // service + wake/wifi locks + GPS don't outlive it (otherwise they'd hold the CPU/WiFi
        // awake with nothing producing frames).
        releaseBackgroundResources("viewModel cleared")
        voice.shutdown()
        io.github.saltmango.navbeam.data.RideDiagnostics.stop("app closed")
    }

    private data class BatteryState(val level: Int, val temp: Double)

    private fun getBatteryState(): BatteryState {
        val intent = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val tempC = tempTenths / 10.0
            return BatteryState(pct, tempC)
        }
        return BatteryState(-1, 0.0)
    }
}
