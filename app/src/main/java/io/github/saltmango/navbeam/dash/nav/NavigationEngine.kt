package io.github.saltmango.navbeam.dash.nav

private const val BEARING_MIN_SPEED = 2.0f

interface NavigationEngine {
    fun currentState(): NavigationState
    fun currentRoute(): CompiledRoute?
    fun onLocation(location: NavigationLocation?): NavigationResult
    fun setDestination(destination: Destination)
    fun clearDestination()
    fun setRoute(route: CompiledRoute?)
    fun clearRoute()
    fun reset()
}

class DefaultNavigationEngine(
    private val timeSource: TimeSource
) : NavigationEngine {
    @Volatile private var state = NavigationState()
    @Volatile private var route: CompiledRoute? = null

    // Mutable internal progress/smooth variables
    private var progressM = 0.0
    private var smoothSpeedMps = 0.0
    private var smoothEtaSec = 0.0
    private var etaArrivalMs = 0L
    private var lastArrivalCalcMs = 0L
    private var displayedEtaMin: Int? = null

    // Rerouting tracking
    private var offRouteSince = 0L
    private var lastRerouteAt = 0L
    private var rerouting = false
    private var offRouteStreakStart = 0L
    private var recalcHoldUntil = 0L
    private var lastSnapDist = -1.0

    // Voice announcements tracking
    private var lastManeuverKey = -1.0
    private var farDone = false
    private var nearDone = false
    private var arrivedAnnounced = false

    // GPS status
    private var lastHeading = 0f
    private var lastFixTimeMs = 0L
    private var lastLocationReceivedTimeMs = 0L

    override fun currentState(): NavigationState = state

    override fun currentRoute(): CompiledRoute? = route

    override fun onLocation(location: NavigationLocation?): NavigationResult {
        val nowMs = timeSource.wallTimeMs()
        var event: NavigationEvent? = null

        val r = route
        val dState = state.destination
        val dLat = dState.destination?.latitude
        val dLng = dState.destination?.longitude

        var currentGpsStatus = state.gps.gpsStatus
        var currentMatchedLat = state.gps.matchedLat
        var currentMatchedLng = state.gps.matchedLng
        var currentRiderBearing = state.gps.riderBearing

        var currentProgress = state.progress
        var currentManeuver = state.maneuver
        var currentDestination = state.destination

        if (location != null) {
            lastFixTimeMs = location.timeMs
            lastLocationReceivedTimeMs = nowMs

            currentMatchedLat = location.latitude
            currentMatchedLng = location.longitude

            // Heading calculation
            val heading = if (location.speedMps >= BEARING_MIN_SPEED) {
                location.bearing
            } else {
                currentRiderBearing
            }
            if (location.speedMps >= BEARING_MIN_SPEED) {
                lastHeading = location.bearing
            }
            currentRiderBearing = heading

            // GPS status
            currentGpsStatus = if (location.accuracy > 80f) {
                NavigationGpsStatus.WEAK
            } else {
                NavigationGpsStatus.GOOD
            }

            var remainingMeters: Double? = null
            var etaSec: Double? = null

            if (r != null) {
                val ns = trackProgress(r, GeoPoint(location.latitude, location.longitude))
                remainingMeters = ns.remainingM
                lastSnapDist = ns.snapDist

                val headingKnown = location.speedMps >= BEARING_MIN_SPEED
                val headingOff = headingKnown && angleDelta(location.bearing, ns.heading) > 50f
                val isOffRoute = when {
                    ns.snapDist > 150.0 -> true
                    ns.snapDist > 70.0  -> headingOff
                    else -> false
                }

                // Recalculating state
                if (isOffRoute) {
                    if (offRouteStreakStart == 0L) offRouteStreakStart = nowMs
                } else {
                    offRouteStreakStart = 0L
                }
                val rawRecalc = rerouting || (offRouteStreakStart != 0L && nowMs - offRouteStreakStart > 800)
                if (rawRecalc) recalcHoldUntil = nowMs + 1500
                val recalculating = rawRecalc || nowMs < recalcHoldUntil

                if (!recalculating) {
                    if (location.speedMps > 0.5f) {
                        smoothSpeedMps = if (smoothSpeedMps <= 0.0) location.speedMps.toDouble()
                                         else smoothSpeedMps + (location.speedMps - smoothSpeedMps) * 0.05
                    }
                    val speed = smoothSpeedMps.coerceAtLeast(3.0)
                    val rawEta = ns.remainingM / speed
                    smoothEtaSec = if (smoothEtaSec <= 0.0) rawEta else smoothEtaSec + (rawEta - smoothEtaSec) * 0.08
                    if (etaArrivalMs == 0L || nowMs - lastArrivalCalcMs > 5000) {
                        etaArrivalMs = nowMs + (smoothEtaSec * 1000).toLong()
                        displayedEtaMin = Math.round(smoothEtaSec / 60.0).toInt()
                        lastArrivalCalcMs = nowMs
                    }
                }
                etaSec = if (smoothEtaSec > 0.0) smoothEtaSec else null

                currentProgress = RouteProgress(
                    progress = progressM,
                    remainingDistance = remainingMeters,
                    etaSeconds = etaSec?.toInt(),
                    etaArrivalMs = etaArrivalMs,
                    offRoute = isOffRoute,
                    recalculating = recalculating
                )

                currentManeuver = ManeuverState(
                    nextManeuver = ns.nextManeuver,
                    secondaryManeuver = ns.secondaryManeuver
                )

                // Check voice / maneuver events
                if (remainingMeters in 0.0..30.0 && !arrivedAnnounced) {
                    arrivedAnnounced = true
                    event = NavigationEvent.Arrived(remainingMeters)
                }
                if (remainingMeters > 30.0) {
                    arrivedAnnounced = false
                }

                val nextMan = ns.nextManeuver
                if (nextMan != null) {
                    if (nextMan.cumulativeMeters != lastManeuverKey) {
                        lastManeuverKey = nextMan.cumulativeMeters
                        farDone = false
                        nearDone = false
                    }
                    when {
                        ns.nextTurnM < 60.0 && !nearDone -> {
                            nearDone = true
                            farDone = true
                            event = NavigationEvent.ManeuverAnnounce(nextMan, ns.nextTurnM, remainingMeters)
                        }
                        ns.nextTurnM in 60.0..450.0 && !farDone -> {
                            farDone = true
                            event = NavigationEvent.ManeuverAnnounce(nextMan, ns.nextTurnM, remainingMeters)
                        }
                    }
                }

                // Check for reroute event
                if (isOffRoute) {
                    if (offRouteSince == 0L) {
                        offRouteSince = nowMs
                    }
                    if (nowMs - offRouteSince >= 2000 && nowMs - lastRerouteAt >= 6000 && !rerouting) {
                        lastRerouteAt = nowMs
                        rerouting = true
                        event = NavigationEvent.RequestReroute(location, dState.destination!!)
                    }
                } else {
                    offRouteSince = 0L
                }

            } else if (dLat != null && dLng != null) {
                remainingMeters = GeoPoint.distMeters(
                    GeoPoint(location.latitude, location.longitude), GeoPoint(dLat, dLng)
                )
                currentProgress = RouteProgress(
                    progress = 0.0,
                    remainingDistance = remainingMeters,
                    etaSeconds = null,
                    etaArrivalMs = 0L,
                    offRoute = false,
                    recalculating = false
                )
            }
        } else {
            // Null location checks for timeout
            if (lastLocationReceivedTimeMs > 0L && nowMs - lastLocationReceivedTimeMs > 6000L) {
                currentGpsStatus = NavigationGpsStatus.LOST
            }
        }

        val currentArrived = r?.let {
            currentProgress.remainingDistance != null && currentProgress.remainingDistance <= 30.0
        } ?: false

        currentDestination = DestinationState(
            destination = dState.destination,
            arrived = currentArrived
        )

        state = NavigationState(
            progress = currentProgress,
            maneuver = currentManeuver,
            gps = GpsState(
                matchedLat = currentMatchedLat,
                matchedLng = currentMatchedLng,
                riderBearing = currentRiderBearing,
                gpsStatus = currentGpsStatus
            ),
            destination = currentDestination
        )

        return NavigationResult(state, event)
    }

    override fun setDestination(destination: Destination) {
        state = state.copy(
            destination = DestinationState(destination = destination, arrived = false),
            progress = RouteProgress()
        )
        route = null
        progressM = 0.0
        smoothEtaSec = 0.0
        etaArrivalMs = 0L
        displayedEtaMin = null
        rerouting = false
    }

    override fun clearDestination() {
        state = NavigationState()
        route = null
        progressM = 0.0
        smoothEtaSec = 0.0
        etaArrivalMs = 0L
        displayedEtaMin = null
        rerouting = false
    }

    override fun setRoute(route: CompiledRoute?) {
        this.route = route
        progressM = 0.0
        offRouteSince = 0L
        smoothEtaSec = route?.totalSeconds ?: 0.0
        etaArrivalMs = if (route != null) timeSource.wallTimeMs() + (route.totalSeconds * 1000).toLong() else 0L
        displayedEtaMin = route?.totalSeconds?.let { Math.round(it / 60.0).toInt() }
        rerouting = false

        val upcoming = route?.maneuvers?.filter {
            it.cumulativeMeters > progressM + 1.0 && it.type != ManeuverType.DEPART
        }
        val nextMan = upcoming?.firstOrNull()
        val secondaryMan = upcoming?.drop(1)?.firstOrNull()

        io.github.saltmango.navbeam.util.Dbg.d("DashSession") {
            "Engine setRoute: nextMan=${nextMan?.instruction} (0x%02X), secondaryMan=${secondaryMan?.instruction} (0x%02X)"
                .format(nextMan?.dashCode ?: 0x09, secondaryMan?.dashCode ?: 0x40)
        }

        state = state.copy(
            progress = RouteProgress(
                progress = 0.0,
                remainingDistance = route?.totalMeters,
                etaSeconds = displayedEtaMin?.let { it * 60 },
                etaArrivalMs = etaArrivalMs,
                offRoute = false,
                recalculating = false
            ),
            maneuver = ManeuverState(
                nextManeuver = nextMan,
                secondaryManeuver = secondaryMan
            )
        )
    }

    override fun clearRoute() {
        route = null
        progressM = 0.0
        smoothEtaSec = 0.0
        etaArrivalMs = 0L
        displayedEtaMin = null
        rerouting = false
        state = state.copy(
            progress = RouteProgress(),
            maneuver = ManeuverState()
        )
    }

    override fun reset() {
        state = NavigationState()
        route = null
        progressM = 0.0
        smoothSpeedMps = 0.0
        smoothEtaSec = 0.0
        etaArrivalMs = 0L
        lastArrivalCalcMs = 0L
        displayedEtaMin = null
        offRouteSince = 0L
        lastRerouteAt = 0L
        rerouting = false
        offRouteStreakStart = 0L
        recalcHoldUntil = 0L
        lastSnapDist = -1.0
        lastManeuverKey = -1.0
        farDone = false
        nearDone = false
        arrivedAnnounced = false
        lastHeading = 0f
        lastFixTimeMs = 0L
        lastLocationReceivedTimeMs = 0L
    }

    private data class EngineNavState(
        val remainingM: Double, val nextTurnM: Double, val heading: Float, val offRoute: Boolean,
        val snapped: GeoPoint, val snapDist: Double,
        val nextManeuver: Maneuver?,
        val secondaryManeuver: Maneuver?,
    )

    private data class Match(val cum: Double, val dist: Double, val bearing: Float, val proj: GeoPoint)

    private fun trackProgress(r: CompiledRoute, pos: GeoPoint): EngineNavState {
        val geom = r.geometry
        val cum = r.cumulative

        fun search(lo: Double, hi: Double): Match {
            var bestDist = Double.MAX_VALUE; var bestCum = progressM; var bestBearing = 0f; var bestProj = pos
            for (i in 0 until geom.size - 1) {
                if (cum[i + 1] < lo || cum[i] > hi) continue
                val (proj, t) = GeoPoint.projectOnSegment(pos, geom[i], geom[i + 1])
                val d = GeoPoint.distMeters(pos, proj)
                if (d < bestDist) {
                    bestDist = d
                    bestCum = cum[i] + GeoPoint.distMeters(geom[i], geom[i + 1]) * t
                    bestBearing = GeoPoint.bearing(geom[i], geom[i + 1]).toFloat()
                    bestProj = proj
                }
            }
            return Match(bestCum, bestDist, bestBearing, bestProj)
        }

        var m = search(progressM - 60.0, progressM + 1000.0)
        if (m.dist > 80.0) {
            val g = search(0.0, r.totalMeters)
            if (g.dist < m.dist) m = g
        }
        progressM = maxOf(progressM - 25.0, m.cum)

        val remaining = (r.totalMeters - progressM).coerceAtLeast(0.0)
        val upcoming = r.maneuvers.filter {
            it.cumulativeMeters > progressM + 1.0 && it.type != ManeuverType.DEPART
        }
        val nextMan = upcoming.firstOrNull()
        val secondaryMan = upcoming.drop(1).firstOrNull()
        val nextTurn = nextMan?.let { (it.cumulativeMeters - progressM).coerceAtLeast(0.0) } ?: remaining
        return EngineNavState(remaining, nextTurn, m.bearing, m.dist > 70.0, m.proj, m.dist, nextMan, secondaryMan)
    }

    private fun angleDelta(a: Float, b: Float): Float {
        val d = (((a - b) % 360f) + 540f) % 360f - 180f
        return kotlin.math.abs(d)
    }
}
