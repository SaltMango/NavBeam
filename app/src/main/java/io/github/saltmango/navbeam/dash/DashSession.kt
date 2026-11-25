package io.github.saltmango.navbeam.dash

import android.util.Log
import io.github.saltmango.navbeam.dash.protocol.DashCommands
import io.github.saltmango.navbeam.dash.protocol.DashPacket
import io.github.saltmango.navbeam.dash.nav.NavigationState
import io.github.saltmango.navbeam.util.Dbg
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashState { IDLE, CONNECTING, AUTHENTICATING, CONNECTED, PROJECTING, STREAMING, ERROR }

/**
 * Hardware dash session control flow driven by the active [io.github.saltmango.navbeam.dash.profile.DashProfile]:
 *   1. Open sockets (RX bound first).
 *   2. Send initial burst on control TX port.
 *   3. If auth is enabled, RX loop ingests pubkey TLVs → sends encrypted session key → waits for confirm.
 *   4. Nav entry: route-card ×4 → projectionFrame → navPlaceholder → navStart → route-card.
 *   5. Start RTP + projection keep-alive + route-card keep-alive.
 * The RX loop runs the WHOLE time, answering auth, frame-decoded acks, and button events.
 */
class DashSession(private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "DashSession"
    }

    private val activeProfile get() = io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value
    private val activeProtocol get() = activeProfile.protocol
    private val activeCommands get() = activeProfile.commands
    private val authTimeoutMs get() = activeProtocol.authTimeoutMs
    private val projHbMs get() = activeProtocol.projKeepAliveMs
    private val routeCardMs get() = activeProtocol.routeCardKeepAliveMs
    private val burstPauseMs get() = activeProtocol.burstPauseMs
    private val rxWatchdogMs get() = activeProtocol.rxWatchdogMs

    private val _state = MutableStateFlow(DashState.IDLE)
    val state = _state.asStateFlow()

    @Volatile private var videoProjectionEnabled = true

    fun isNavigationRunning(): Boolean {
        val s = _state.value
        return s == DashState.PROJECTING || s == DashState.STREAMING
    }

    private var socket: DashSocket? = null
    private var auth: DashAuth? = null
    @Volatile private var authConfirmed = false
    @Volatile private var authRetries = 0
    // Wall-clock of the last packet received from the dash — fed to the RX watchdog.
    @Volatile private var lastRxMs = 0L
    // One-shot: the first time the dash reports it DECODED our video (`frameDecodedIdrNotify`). If
    // this never appears in the ride log, the dash never accepted the stream.
    @Volatile private var loggedFirstAck = false

    var onButton: ((Byte) -> Unit)? = null
    var onError:  ((String) -> Unit)? = null

    @Volatile var destinationName: String = "NavBeam"

    private var sessionJob: Job? = null
    private var rxJob: Job? = null
    private var projHbJob: Job? = null
    private var routeCardJob: Job? = null
    private var heartbeatJob: Job? = null
    private var navInfoJob: Job? = null
    private var mediaInfoJob: Job? = null

    // Now-playing + incoming-call pushed to the dash at the profile's route-card cadence.
    // Strings, not the media model, so the protocol layer stays decoupled from the media provider.
    @Volatile private var npTitle: String? = null
    @Volatile private var npAlbum = ""
    @Volatile private var npArtist = ""
    @Volatile private var caller: String? = null

    /** Push the latest now-playing metadata; sent to the dash while streaming (null title = hide). */
    fun updateNowPlaying(title: String?, album: String, artist: String) {
        npTitle = title?.takeIf { it.isNotBlank() }
        npAlbum = album
        npArtist = artist
    }

    /** Push the current incoming-call caller name (null clears it). */
    fun updateCall(callerName: String?) { caller = callerName?.takeIf { it.isNotBlank() } }

    // Live nav-info pushed to the dash bubble (set by NavEngine output).
    @Volatile private var navManeuver: Int = 0
    @Volatile private var navSecondaryManeuver: Int = 0
    @Volatile private var navSecondaryDist: Int = 0
    @Volatile private var navSecondaryUnit: Int = 0
    @Volatile private var navPrimaryDist: Int = 0
    @Volatile private var navPrimaryUnit: Int = 0
    @Volatile private var navTotalDist: Int = 0
    @Volatile private var navTotalUnit: Int = 0
    @Volatile private var navEta: String? = null
    @Volatile private var navInstruction: String? = null
    @Volatile private var navActive: Boolean = false

    /**
     * Called when a brand-new route is loaded — resets the nav glyph back to DEPART so
     * the dash shows the departure icon in the gap before the first GPS fix arrives.
     */
    fun resetNavForNewRoute() {
        val metersUnit = DashCommands.unitMeters()
        navManeuver          = DashCommands.maneuverCode("DEPART")
        navSecondaryManeuver = DashCommands.maneuverCode("BLANK")
        navSecondaryDist     = 0
        navSecondaryUnit     = metersUnit
        navPrimaryDist       = 0
        navActive            = false
        Log.d(TAG, "resetNavForNewRoute: primary=0x%02X, secondary=0x%02X".format(navManeuver, navSecondaryManeuver))
    }

    /** Push the latest turn-by-turn figures; sent to the dash at 1 Hz. */
    fun updateNavInfo(
        maneuver: Int, primaryDist: Int, primaryUnit: Int,
        totalDist: Int, totalUnit: Int, etaHHMM: String? = null,
    ) {
        navManeuver = maneuver
        navPrimaryDist = primaryDist
        navPrimaryUnit = primaryUnit
        navTotalDist = totalDist
        navTotalUnit = totalUnit
        navEta = etaHHMM
        navActive = true
    }

    /**
     * Route card with the LIVE nav figures patched in.
     */
    private fun liveRouteCard(projectionOn: Boolean): ByteArray {
        val maneuver = if (io.github.saltmango.navbeam.viewmodel.DashViewModel.DEBUG_FORCE_MANEUVER)
            DashCommands.maneuverCode("UNKNOWN", navManeuver) else navManeuver
        val titleText = navInstruction ?: destinationName
        return if (navActive) DashCommands.routeCard(
            titleText, projectionOn,
            maneuver = maneuver,
            secondaryManeuver = navSecondaryManeuver,
            secondaryDist = navSecondaryDist,
            secondaryUnit = navSecondaryUnit,
            primaryDist = navPrimaryDist,
            primaryUnit = navPrimaryUnit,
            totalDist = navTotalDist,
            totalUnit = navTotalUnit,
            etaHHMM = navEta,
        )
        else DashCommands.routeCard(destinationName, projectionOn)
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun connect(ssid: String, network: android.net.Network? = null, hostname: String = "NavBeam") {
        if (_state.value != DashState.IDLE && _state.value != DashState.ERROR) return
        Log.i(TAG, "connect() — network=$network")
        io.github.saltmango.navbeam.data.RideDiagnostics.log("session", "connect(hostnameLen=${hostname.length})")
        sessionJob = scope.launch(Dispatchers.IO) { runSession(ssid, network, hostname) }
    }

    fun enableVideoProjection() {
        if (videoProjectionEnabled) return
        videoProjectionEnabled = true
        Log.i(TAG, "Video projection enabled")
        if (isNavigationRunning()) {
            launchProjectionHeartbeat()
            scope.launch(Dispatchers.IO) {
                socket?.let { sock ->
                    sock.send(liveRouteCard(projectionOn = true))
                    if (navActive) {
                        sock.send(
                            DashCommands.activeNavPacket(
                                maneuver = navManeuver,
                                primaryDist = navPrimaryDist,
                                primaryUnit = navPrimaryUnit,
                                totalDist = navTotalDist,
                                totalUnit = navTotalUnit,
                                secondaryManeuver = navSecondaryManeuver,
                                secondaryDist = navSecondaryDist,
                                secondaryUnit = navSecondaryUnit,
                                projectionOn = true,
                                roadName = navInstruction
                            )
                        )
                    }
                }
            }
        }
    }

    fun disableVideoProjection() {
        if (!videoProjectionEnabled) return
        videoProjectionEnabled = false
        Log.i(TAG, "Video projection disabled")
        projHbJob?.cancel()
        projHbJob = null
        
        scope.launch(Dispatchers.IO) {
            socket?.let { sock ->
                runCatching { sock.send(DashCommands.projectionStop()) }
                runCatching { sock.send(DashCommands.projectionOff()) }
                if (isNavigationRunning()) {
                    sock.send(liveRouteCard(projectionOn = false))
                    if (navActive) {
                        sock.send(
                            DashCommands.activeNavPacket(
                                maneuver = navManeuver,
                                primaryDist = navPrimaryDist,
                                primaryUnit = navPrimaryUnit,
                                totalDist = navTotalDist,
                                totalUnit = navTotalUnit,
                                secondaryManeuver = navSecondaryManeuver,
                                secondaryDist = navSecondaryDist,
                                secondaryUnit = navSecondaryUnit,
                                projectionOn = false,
                                roadName = navInstruction
                            )
                        )
                    }
                }
            }
        }
    }

    fun enterNavigation(projection: Boolean) {
        val sock = socket ?: return
        if (_state.value == DashState.IDLE || _state.value == DashState.ERROR) return

        if (isNavigationRunning()) {
            if (projection) {
                enableVideoProjection()
            } else {
                disableVideoProjection()
            }
            return
        }

        if (_state.value != DashState.CONNECTED) return

        if (projection) {
            enableVideoProjection()
            startVideoProjection()
        } else {
            disableVideoProjection()
            startNavigationOnly()
        }
    }

    private fun startVideoProjection() {
        val sock = socket ?: return
        navActive = true
        sessionJob?.cancel()
        sessionJob = scope.launch(Dispatchers.IO) {
            try {
                enterNavMode(sock)
                _state.value = DashState.PROJECTING
                Log.i(TAG, "PROJECTING ✓")
                io.github.saltmango.navbeam.data.RideDiagnostics.log("session", "nav-mode kick sent — PROJECTING (waiting for video pipeline)")
            } catch (e: Exception) {
                Log.e(TAG, "Projection start failed", e)
                fail("Failed to start projection: ${e.message}")
            }
        }
    }

    private fun startNavigationOnly() {
        val sock = socket ?: return
        navActive = true
        sessionJob?.cancel()
        sessionJob = scope.launch(Dispatchers.IO) {
            try {
                enterNavMode(sock)
                _state.value = DashState.STREAMING
                Log.i(TAG, "STREAMING ✓ (Navigation only)")
                io.github.saltmango.navbeam.data.RideDiagnostics.log("session", "nav-mode kick sent — STREAMING (Navigation only)")
                withContext(Dispatchers.Main) {
                    launchRouteCardKeepAlive()
                    launchNavInfo()
                    launchMediaInfo()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Navigation start failed", e)
                fail("Failed to start navigation: ${e.message}")
            }
        }
    }

    fun stopNavigation() {
        if (_state.value != DashState.PROJECTING && _state.value != DashState.STREAMING) return
        
        _state.value = DashState.CONNECTED
        
        projHbJob?.cancel(); projHbJob = null
        routeCardJob?.cancel(); routeCardJob = null
        navInfoJob?.cancel(); navInfoJob = null
        mediaInfoJob?.cancel(); mediaInfoJob = null
        navActive = false
        
        scope.launch(Dispatchers.IO) {
            socket?.let {
                runCatching { it.send(DashCommands.projectionStop()) }
                runCatching { it.send(DashCommands.projectionOff()) }
            }
        }
    }

    fun startStreaming() {
        if (_state.value != DashState.PROJECTING) return
        _state.value = DashState.STREAMING
        // Projection-on route card + faster keep-alive begin now
        launchProjectionHeartbeat()
        launchRouteCardKeepAlive()
        launchNavInfo()
        launchMediaInfo()
    }

    fun sendRtp(data: ByteArray, length: Int) { socket?.sendRtp(data, length) }

    fun setReceiveTimeout(ms: Int) {
        socket?.setReceiveTimeout(ms)
    }

    fun getSocketMetrics(): Triple<Int, Int, Int>? = socket?.getAndResetMetrics()

    fun updateRouteCard(name: String) {
        destinationName = name.ifBlank { "NavBeam" }
        navActive = false   // new destination — old figures are stale until the next updateNavInfo
        if (isNavigationRunning()) {
            scope.launch(Dispatchers.IO) {
                val sock = socket ?: return@launch
                sock.send(liveRouteCard(projectionOn = videoProjectionEnabled))
                // If transitioning to a real destination, re-send the 'start nav' trigger (z2)
                // so the dash opens the instruction bubble.
                if (destinationName != "NavBeam") {
                    delay(100)
                    sock.send(DashCommands.navStart())
                    delay(100)
                    sock.send(liveRouteCard(projectionOn = videoProjectionEnabled))
                }
            }
        }
    }

    fun disconnect() {
        // Cancel the session coroutine FIRST so it can't race past auth and flip state to
        // READY after we tear down (which would re-trigger streaming on a dead socket).
        sessionJob?.cancel(); sessionJob = null
        rxJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel(); heartbeatJob?.cancel(); navInfoJob?.cancel(); mediaInfoJob?.cancel()
        navActive = false
        socket?.let {
            runCatching { it.send(DashCommands.projectionStop()) }
            runCatching { it.send(DashCommands.projectionOff()) }
            it.close()
        }
        socket = null
        _state.value = DashState.IDLE
        Log.i(TAG, "Disconnected")
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private suspend fun runSession(ssid: String, network: android.net.Network? = null, hostname: String = "NavBeam") {
        try {
            _state.value = DashState.CONNECTING
            val sock = try {
                DashSocket(network).also { socket = it }
            } catch (e: java.net.BindException) {
                fail("Port in use (${e.message})")
                return
            }

            auth = DashAuth(ssid)
            authConfirmed = false
            authRetries = 0
            loggedFirstAck = false
            lastRxMs = 0L

            // 1. RX loop MUST be running before the burst (early pubkey + no ICMP).
            launchReceiveLoop(sock)

            _state.value = DashState.AUTHENTICATING
            Log.i(TAG, "Starting session handshake…")

            // 2. Send initial burst from active profile.
            val burst = DashCommands.initialBurst(hostname)
            for ((idx, pkt) in burst.withIndex()) {
                sock.send(pkt)
                if (idx < burst.lastIndex && burstPauseMs > 0) delay(burstPauseMs)
            }

            val requiresAuth = activeProtocol.authType != "NONE"
            if (requiresAuth) {
                io.github.saltmango.navbeam.data.RideDiagnostics.log("auth", "burst sent — waiting up to ${authTimeoutMs}ms for confirmation")
                Log.i(TAG, "Waiting up to ${authTimeoutMs}ms for auth confirmation…")
                val deadline = System.currentTimeMillis() + authTimeoutMs
                while (!authConfirmed && System.currentTimeMillis() < deadline) delay(100)

                if (!authConfirmed) {
                    fail("Auth timed out — no confirmation from display. Check SSID matches '$ssid'.")
                    return
                }
                Log.i(TAG, "Authenticated ✓")
                io.github.saltmango.navbeam.data.RideDiagnostics.log("auth", "authenticated — entering nav mode")

                // Re-send hostname announce once authenticated.
                sock.send(DashCommands.hostnameAnnounce(hostname))
                if (burstPauseMs > 0) delay(burstPauseMs)
            } else {
                authConfirmed = true
                io.github.saltmango.navbeam.data.RideDiagnostics.log("auth", "auth bypassed (authType=NONE)")
            }

            // 4. Once authenticated, start the status heartbeat.
            launchStatusHeartbeat(sock)
            _state.value = DashState.CONNECTED
            Log.i(TAG, "CONNECTED ✓")
            io.github.saltmango.navbeam.data.RideDiagnostics.log("session", "CONNECTED (heartbeat only)")

        } catch (e: Exception) {
            Log.e(TAG, "Session error", e)
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Nav entry in profile-defined sequence:
     *   navContext → emptyLists → route-card ×4 → projectionFrame → navPlaceholder → navStart → route-card.
     */
    private suspend fun enterNavMode(sock: DashSocket) {
        if (activeCommands.navContext.isNotEmpty()) {
            sock.send(DashCommands.navContext()); delay(40)
        }
        if (activeCommands.emptyLists.isNotEmpty()) {
            sock.send(DashCommands.emptyLists()); delay(40)
        }

        repeat(4) {
            sock.send(DashCommands.routeCard(destinationName, projectionOn = false))
            delay(if (it < 1) 100 else 500)
        }
        if (videoProjectionEnabled) {
            sock.send(DashCommands.projectionFrame()); delay(60)
        }
        if (activeCommands.navPlaceholder.isNotEmpty()) {
            sock.send(DashCommands.navPlaceholder()); delay(10)
        }
        sock.send(DashCommands.navStart()); delay(40)
        sock.send(DashCommands.routeCard(destinationName, projectionOn = videoProjectionEnabled))
        Log.i(TAG, "Nav mode kick sent")
    }

    private fun launchReceiveLoop(sock: DashSocket) {
        rxJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pkt = try {
                    sock.receive()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Link dropped (EBADF/ENETUNREACH) — end the loop cleanly instead of
                    // crashing the app; DashWifiManager handles reconnect.
                    Log.w(TAG, "RX loop stopped — socket error: ${e.message}")
                    io.github.saltmango.navbeam.data.RideDiagnostics.log("error", "RX loop stopped — socket error: ${e.message}")
                    fail("Lost connection to dash")
                    break
                } ?: continue
                lastRxMs = System.currentTimeMillis()
                dispatchIncoming(pkt, sock)
            }
        }
    }

    private fun dispatchIncoming(pkt: ByteArray, sock: DashSocket) {
        val cmd = activeCommands
        val tlvs = DashPacket.parseIncoming(pkt)
        val idrNotify = cmd.frameDecodedIdrNotify
        val pNotify = cmd.frameDecodedPNotify

        fun isTlvMatch(tlv: io.github.saltmango.navbeam.dash.protocol.Tlv, spec: io.github.saltmango.navbeam.dash.profile.TlvSpec): Boolean =
            tlv.type == spec.type && tlv.sub == spec.sub &&
                (spec.value.isEmpty() || tlv.value.startsWithBytes(spec.value))

        val onlyAcks = tlvs.isNotEmpty() && tlvs.all { isTlvMatch(it, idrNotify) || isTlvMatch(it, pNotify) }
        if (!onlyAcks) Dbg.i(TAG) { "RX RAW (${pkt.size}B): ${pkt.toHexFull()}" }

        val maxAuthRetries = activeProtocol.maxAuthAttempts.coerceAtLeast(1)
        val isAuthPacketType = { t: io.github.saltmango.navbeam.dash.protocol.Tlv ->
            (t.type == cmd.authModulusTag.type && t.sub == cmd.authModulusTag.sub) ||
                (t.type == cmd.authExponentTag.type && t.sub == cmd.authExponentTag.sub) ||
                (t.type == cmd.authStatusTag.type && t.sub == cmd.authStatusTag.sub)
        }

        for (tlv in tlvs) {
            // ── Auth ──
            if (isAuthPacketType(tlv)) {
                when (val ev = auth?.ingest(tlv)) {
                    is AuthEvent.SendKey -> {
                        Log.i(TAG, "Got RSA pubkey — sending session key")
                        io.github.saltmango.navbeam.data.RideDiagnostics.log("auth", "RSA pubkey received → sending session key")
                        sock.send(ev.packet)
                    }
                    AuthEvent.Confirmed -> { authConfirmed = true }
                    AuthEvent.Rejected -> {
                        authRetries++
                        Log.w(TAG, "Auth rejected — retry #$authRetries")
                        io.github.saltmango.navbeam.data.RideDiagnostics.log("auth", "REJECTED — retry #$authRetries")
                        auth?.reset()
                        if (authRetries <= maxAuthRetries) sock.send(DashCommands.authRequest())
                    }
                    else -> {}
                }
                continue
            }
            // ── Per-IDR frame-decoded notify → mandatory IDR frame ACK ──
            if (isTlvMatch(tlv, idrNotify)) {
                if (!loggedFirstAck) {
                    loggedFirstAck = true
                    io.github.saltmango.navbeam.data.RideDiagnostics.log("dash", "display DECODED first IDR — video accepted ✓")
                }
                sock.send(DashCommands.frameDecodedIdr())
                continue
            }
            // ── P-frame decoded → P-frame ACK ──
            if (isTlvMatch(tlv, pNotify)) {
                sock.send(DashCommands.frameDecodedP())
                continue
            }
            // ── Button / joystick event → echo ack + notify UI ──
            if (tlv.type == cmd.buttonEventTag.type && tlv.sub == cmd.buttonEventTag.sub && tlv.value.isNotEmpty()) {
                val btn = tlv.value.last()
                Dbg.i(TAG) { "INPUT EVENT → code 0x${(btn.toInt() and 0xFF).toString(16).uppercase()}  full=${tlv.value.toHexFull()}" }
                sock.send(DashCommands.buttonAck(btn))
                scope.launch(Dispatchers.Main) { onButton?.invoke(btn) }
                continue
            }
            // ── Vehicle-secure telemetry (AES-256-CBC under the session key) ──
            if (tlv.type == cmd.telemetryEncryptedType) {
                val key = auth?.sessionKey
                val plain = key?.let { aesDecryptCbc(tlv.value, it) }
                Dbg.i(TAG) {
                    "DASH TELEMETRY (enc) sub=0x%02X enc(%dB)=%s  dec=%s".format(
                        tlv.sub, tlv.value.size, tlv.value.toHexFull(),
                        plain?.toHexFull() ?: "<key=${key != null}; decrypt failed>"
                    )
                }
                continue
            }
            // ── Plain telemetry ──
            if (tlv.type == cmd.telemetryPlainType) {
                Dbg.i(TAG) {
                    "DASH TELEMETRY sub=0x%02X (%dB) val=%s"
                        .format(tlv.sub, tlv.value.size, tlv.value.toHexFull())
                }
                continue
            }
            Dbg.i(TAG) {
                "DASH EVENT type=0x%02X sub=0x%02X (%dB) val=%s"
                    .format(tlv.type, tlv.sub, tlv.value.size, tlv.value.toHexFull())
            }
        }
    }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun launchStatusHeartbeat(sock: DashSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                runCatching { sock.send(DashCommands.heartbeat()) }
                // Keep the display clock correct.
                if (n++ % 30 == 0) runCatching { sock.send(DashCommands.timeSync()) }
                // RX watchdog: detect silent UDP link drops.
                if ((_state.value == DashState.STREAMING || _state.value == DashState.CONNECTED) &&
                    lastRxMs > 0L && System.currentTimeMillis() - lastRxMs > rxWatchdogMs
                ) {
                    val silent = (System.currentTimeMillis() - lastRxMs) / 1000
                    Log.w(TAG, "RX watchdog: no display packets for ${silent}s — link lost")
                    io.github.saltmango.navbeam.data.RideDiagnostics.log("error", "RX watchdog: display silent ${silent}s → link lost")
                    fail("Display stopped responding — connection lost")
                    break
                }
                delay(1_000)
            }
        }
    }

    private fun toDashDistance(meters: Double): Pair<Int, Int> =
        if (meters < 1000.0) meters.toInt().coerceIn(0, 0xFFFF) to DashCommands.unitMeters()
        else (meters / 100.0).toInt().coerceIn(0, 0xFFFF) to DashCommands.unitKmTenths()

    fun updateNavigation(state: NavigationState) {
        val s = _state.value
        if (s != DashState.STREAMING) return
        
        val progress = state.progress
        val remainingDistance = progress.remainingDistance ?: return
        
        val nextManeuver = state.maneuver.nextManeuver
        val secondaryManeuver = state.maneuver.secondaryManeuver
        val nextTurnM = if (nextManeuver != null) {
            (nextManeuver.cumulativeMeters - progress.progress).coerceAtLeast(0.0)
        } else {
            remainingDistance
        }
        val secondaryTurnM = if (secondaryManeuver != null) {
            (secondaryManeuver.cumulativeMeters - progress.progress).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val (pv, pu) = toDashDistance(nextTurnM)
        val (tv, tu) = toDashDistance(remainingDistance)
        val etaHHMM = progress.etaSeconds?.let {
            val arrival = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, it) }
            "%02d%02d".format(arrival.get(java.util.Calendar.HOUR_OF_DAY), arrival.get(java.util.Calendar.MINUTE))
        }

        val (sv, su) = toDashDistance(secondaryTurnM)
        
        navManeuver = nextManeuver?.dashCode ?: DashCommands.maneuverCode("STRAIGHT")
        
        if (secondaryManeuver != null) {
            navSecondaryManeuver = secondaryManeuver.dashCode
            var finalSv = sv
            if (su == pu && finalSv <= pv) {
                finalSv = pv + 1
            }
            navSecondaryDist = finalSv
            navSecondaryUnit = su
        } else {
            navSecondaryManeuver = DashCommands.maneuverCode("BLANK")
            navSecondaryDist = 0
            navSecondaryUnit = DashCommands.unitMeters()
        }
        
        navPrimaryDist = pv
        navPrimaryUnit = pu
        navTotalDist = tv
        navTotalUnit = tu
        navEta = etaHHMM
        navInstruction = nextManeuver?.instruction?.let { instr ->
            val ontoIndex = instr.indexOf("onto ", ignoreCase = true)
            var road = if (ontoIndex != -1) instr.substring(ontoIndex + 5).trim() else instr
            val parenIndex = road.indexOf("(")
            if (parenIndex != -1) {
                road = road.substring(0, parenIndex).trim()
            }
            val slashIndex = road.indexOf("/")
            if (slashIndex != -1) {
                road = road.substring(0, slashIndex).trim()
            }
            if (road.length > 25) {
                road.take(22).trim() + "..."
            } else {
                road
            }
        }
        val secM = state.maneuver.secondaryManeuver
        Log.d(TAG, "Navigation Update: " +
            "Primary: {type=${nextManeuver?.type}, raw=${nextManeuver?.rawGoogleManeuver}, instr=\"${nextManeuver?.instruction}\"} -> dashCode=0x%02X (dist=$pv). ".format(navManeuver) +
            "Secondary: {type=${secM?.type}, raw=${secM?.rawGoogleManeuver}, instr=\"${secM?.instruction}\"} -> dashCode=0x%02X (dist=$navSecondaryDist, unit=$navSecondaryUnit)".format(navSecondaryManeuver)
        )
        navActive = true
    }

    private fun launchProjectionHeartbeat() {
        projHbJob?.cancel()
        if (!videoProjectionEnabled) return
        projHbJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING && videoProjectionEnabled) {
                socket?.send(DashCommands.projectionFrame())
                delay(projHbMs)
            }
        }
    }

    private fun launchRouteCardKeepAlive() {
        routeCardJob?.cancel()
        routeCardJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                socket?.send(liveRouteCard(projectionOn = videoProjectionEnabled))
                delay(routeCardMs)
            }
        }
    }

    private fun launchNavInfo() {
        navInfoJob?.cancel()
        navInfoJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                if (!io.github.saltmango.navbeam.viewmodel.DashViewModel.DEBUG_SUPPRESS_NAV_INFO && navActive) {
                    val maneuver = if (io.github.saltmango.navbeam.viewmodel.DashViewModel.DEBUG_FORCE_MANEUVER)
                        DashCommands.maneuverCode("UNKNOWN", navManeuver) else navManeuver
                    socket?.send(
                        DashCommands.activeNavPacket(
                            maneuver = maneuver,
                            primaryDist = navPrimaryDist,
                            primaryUnit = navPrimaryUnit,
                            totalDist = navTotalDist,
                            totalUnit = navTotalUnit,
                            secondaryManeuver = navSecondaryManeuver,
                            secondaryDist = navSecondaryDist,
                            secondaryUnit = navSecondaryUnit,
                            projectionOn = videoProjectionEnabled,
                            roadName = navInstruction
                        )
                    )
                }
                delay(routeCardMs)
            }
        }
    }

    /**
     * Now-playing + incoming-call keep-alive at the profile's route-card cadence.
     */
    private fun launchMediaInfo() {
        mediaInfoJob?.cancel()
        mediaInfoJob = scope.launch(Dispatchers.IO) {
            var prevCaller: String? = null
            var loggedNp = false
            var loggedCall = false
            while (isActive && _state.value == DashState.STREAMING) {
                val c = caller
                when {
                    c != null -> {
                        runCatching { socket?.send(DashCommands.callNotify(c)) }
                        if (!loggedCall) { loggedCall = true
                            io.github.saltmango.navbeam.data.RideDiagnostics.log("media", "TX call card (active)") }
                    }
                    prevCaller != null -> { runCatching { socket?.send(DashCommands.callClear()) }; loggedCall = false }
                }
                prevCaller = c
                npTitle?.let {
                    runCatching { socket?.send(DashCommands.nowPlaying(it, npAlbum, npArtist)) }
                    if (!loggedNp) { loggedNp = true
                        io.github.saltmango.navbeam.data.RideDiagnostics.log("media", "TX now-playing (active)") }
                }
                delay(routeCardMs)
            }
        }
    }

    private fun fail(msg: String) {
        Log.e(TAG, "ERROR — $msg")
        io.github.saltmango.navbeam.data.RideDiagnostics.log("error", "session fail: $msg")
        rxJob?.cancel(); heartbeatJob?.cancel()
        socket?.close(); socket = null
        _state.value = DashState.ERROR
        onError?.invoke(msg)
    }

    /** Full hex dump (no truncation) — used for protocol-capture logging. */
    private fun ByteArray.toHexFull(): String =
        joinToString(" ") { "%02X".format(it) }

    /** AES-256-CBC/PKCS5 decrypt of an [iv(16) ‖ ciphertext] blob under the session key. */
    private fun aesDecryptCbc(ivAndCt: ByteArray, key: ByteArray): ByteArray? = runCatching {
        if (ivAndCt.size <= 16) return null
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(ivAndCt.copyOfRange(0, 16)),
        )
        cipher.doFinal(ivAndCt.copyOfRange(16, ivAndCt.size))
    }.getOrNull()
}
