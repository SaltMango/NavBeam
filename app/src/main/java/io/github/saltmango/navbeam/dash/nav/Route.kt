package io.github.saltmango.navbeam.dash.nav

/** Maneuver glyph codes are dynamically mapped by the active profile. */
enum class ManeuverType { CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, DEPART, ARRIVE;

    companion object {
        /** Map an OSRM or Google step maneuver (type + modifier) to our enum. */
        fun fromOsrm(type: String?, modifier: String?): ManeuverType = when (type) {
            "depart"   -> DEPART
            "arrive"   -> ARRIVE
            "roundabout", "rotary" -> ROUNDABOUT
            "fork", "end of road", "turn", "new name", "continue", "merge", "on ramp", "off ramp" ->
                when (modifier) {
                    "left"         -> TURN_LEFT
                    "right"        -> TURN_RIGHT
                    "slight left"  -> SLIGHT_LEFT
                    "slight right" -> SLIGHT_RIGHT
                    "sharp left"   -> SHARP_LEFT
                    "sharp right"  -> SHARP_RIGHT
                    "uturn"        -> UTURN
                    else           -> CONTINUE
                }
            else -> CONTINUE
        }

        fun fromGoogle(maneuver: String?, instruction: String? = null): ManeuverType {
            val token = maneuver?.lowercase()?.replace("_", "-")
            if (token != null) {
                val matched = when (token) {
                    "turn-left" -> TURN_LEFT
                    "turn-right" -> TURN_RIGHT
                    "turn-slight-left" -> SLIGHT_LEFT
                    "turn-slight-right" -> SLIGHT_RIGHT
                    "turn-sharp-left" -> SHARP_LEFT
                    "turn-sharp-right" -> SHARP_RIGHT
                    "uturn-left", "uturn-right" -> UTURN
                    "roundabout-left", "roundabout-right" -> ROUNDABOUT
                    "ramp-left" -> SLIGHT_LEFT
                    "ramp-right" -> SLIGHT_RIGHT
                    "merge" -> CONTINUE
                    "straight" -> CONTINUE
                    "fork-left" -> SLIGHT_LEFT
                    "fork-right" -> SLIGHT_RIGHT
                    "destination" -> ARRIVE
                    else -> null
                }
                if (matched != null) return matched
            }

            // Fallback: parse instruction string
            if (instruction != null) {
                val lower = instruction.lowercase()
                return when {
                    "destination" in lower || "arrive" in lower || "reached" in lower -> ARRIVE
                    "u-turn" in lower || "uturn" in lower -> UTURN
                    "roundabout" in lower || "rotary" in lower -> ROUNDABOUT
                    "slight left" in lower -> SLIGHT_LEFT
                    "slight right" in lower -> SLIGHT_RIGHT
                    "sharp left" in lower -> SHARP_LEFT
                    "sharp right" in lower -> SHARP_RIGHT
                    "turn left" in lower || "take left" in lower || "bear left" in lower || "keep left" in lower -> TURN_LEFT
                    "turn right" in lower || "take right" in lower || "bear right" in lower || "keep right" in lower -> TURN_RIGHT
                    else -> CONTINUE
                }
            }
            return CONTINUE
        }
    }
}

/** One routing instruction located at a point along the geometry. */
data class Maneuver(
    val type: ManeuverType,
    val instruction: String,
    val location: GeoPoint,
    /** Cumulative distance (m) from the route start to this maneuver's location. */
    val cumulativeMeters: Double,
    val rawGoogleManeuver: String? = null
) {
    /**
     * Dash glyph byte sent in the profile's primary maneuver TLV.
     *
     * Maps the routing maneuver code to the display hardware glyph ID from the active profile.
     */
    val dashCode: Int get() {
        val profile = io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value
        return getRawDashCode(profile)
    }

    private fun getRawDashCode(profile: io.github.saltmango.navbeam.dash.profile.DashProfile): Int {
        fun lookup(key: String, vararg fallbackKeys: String): Int =
            profile.maneuvers[key]
                ?: fallbackKeys.firstNotNullOfOrNull { profile.maneuvers[it] }
                ?: profile.maneuvers["STRAIGHT"]
                ?: profile.maneuvers["UNKNOWN"]
                ?: 0

        val googleManeuver = rawGoogleManeuver?.uppercase()?.replace("-", "_")
        val lowerInstr = instruction.lowercase()
        val exitNumber = when {
            "1st exit" in lowerInstr || "first exit" in lowerInstr -> 1
            "2nd exit" in lowerInstr || "second exit" in lowerInstr -> 2
            "3rd exit" in lowerInstr || "third exit" in lowerInstr -> 3
            "4th exit" in lowerInstr || "fourth exit" in lowerInstr -> 4
            "5th exit" in lowerInstr || "fifth exit" in lowerInstr -> 5
            "6th exit" in lowerInstr || "sixth exit" in lowerInstr -> 6
            "7th exit" in lowerInstr || "seventh exit" in lowerInstr -> 7
            "8th exit" in lowerInstr || "eighth exit" in lowerInstr -> 8
            "9th exit" in lowerInstr || "ninth exit" in lowerInstr -> 9
            else -> 0
        }

        if (googleManeuver != null) {
            when (googleManeuver) {
                "DESTINATION" -> return profile.maneuvers["DESTINATION"] ?: 0
                "DESTINATION_LEFT" -> return lookup("DESTINATION_LEFT", "DESTINATION")
                "DESTINATION_RIGHT" -> return lookup("DESTINATION_RIGHT", "DESTINATION")
                "MERGE_RIGHT" -> {
                    return when {
                        "smooth" in lowerInstr -> lookup("MERGE_RIGHT_SMOOTH", "MERGE_RIGHT")
                        "smoother" in lowerInstr -> lookup("MERGE_RIGHT_SMOOTHER", "MERGE_RIGHT")
                        "sharp" in lowerInstr -> lookup("MERGE_RIGHT_SHARP", "MERGE_RIGHT")
                        "onto main" in lowerInstr -> lookup("MERGE_RIGHT_MAIN", "MERGE_RIGHT")
                        else -> lookup("MERGE_RIGHT")
                    }
                }
                "MERGE_LEFT" -> {
                    return when {
                        "smooth" in lowerInstr -> lookup("MERGE_LEFT_SMOOTH", "MERGE_LEFT")
                        "smoother" in lowerInstr -> lookup("MERGE_LEFT_SMOOTHER", "MERGE_LEFT")
                        "sharp" in lowerInstr -> lookup("MERGE_LEFT_SHARP", "MERGE_LEFT")
                        "onto main" in lowerInstr -> lookup("MERGE_LEFT_MAIN", "MERGE_LEFT")
                        else -> lookup("MERGE_LEFT")
                    }
                }
                "FORK_RIGHT" -> {
                    return when {
                        "curved" in lowerInstr -> lookup("FORK_RIGHT_CURVED", "FORK_RIGHT")
                        "sharp" in lowerInstr -> lookup("FORK_RIGHT_SHARP", "FORK_RIGHT")
                        "parallel" in lowerInstr -> lookup("FORK_RIGHT_PARALLEL", "FORK_RIGHT")
                        "90" in lowerInstr || "right angle" in lowerInstr -> lookup("FORK_RIGHT_90", "FORK_RIGHT")
                        else -> lookup("FORK_RIGHT")
                    }
                }
                "OFF_RAMP_RIGHT", "OFF_RAMP_KEEP_RIGHT", "TURN_KEEP_RIGHT", "ON_RAMP_KEEP_RIGHT" ->
                    return lookup(googleManeuver, "OFF_RAMP_RIGHT")
                "FORK_LEFT" -> {
                    return when {
                        "curved" in lowerInstr -> lookup("FORK_LEFT_CURVED", "FORK_LEFT")
                        "sharp" in lowerInstr -> lookup("FORK_LEFT_SHARP", "FORK_LEFT")
                        "parallel" in lowerInstr -> lookup("FORK_LEFT_PARALLEL", "FORK_LEFT")
                        "90" in lowerInstr || "right angle" in lowerInstr -> lookup("FORK_LEFT_90", "FORK_LEFT")
                        else -> lookup("FORK_LEFT")
                    }
                }
                "OFF_RAMP_LEFT", "OFF_RAMP_KEEP_LEFT", "TURN_KEEP_LEFT", "ON_RAMP_KEEP_LEFT" ->
                    return lookup(googleManeuver, "OFF_RAMP_LEFT")
                "STRAIGHT", "NAME_CHANGE" -> return lookup(googleManeuver, "STRAIGHT")
                
                // Roundabout Clockwise exit matching
                "ROUNDABOUT_CLOCKWISE",
                "ROUNDABOUT_EXIT_CLOCKWISE",
                "ROUNDABOUT_LEFT_CLOCKWISE",
                "ROUNDABOUT_RIGHT_CLOCKWISE",
                "ROUNDABOUT_SHARP_LEFT_CLOCKWISE",
                "ROUNDABOUT_SHARP_RIGHT_CLOCKWISE",
                "ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE",
                "ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE",
                "ROUNDABOUT_STRAIGHT_CLOCKWISE",
                "ROUNDABOUT_U_TURN_CLOCKWISE",
                "ROUNDABOUT_RIGHT",
                "ROUNDABOUT" -> {
                    return when (exitNumber) {
                        in 1..9 -> lookup("ROUNDABOUT_$exitNumber", "ROUNDABOUT_2")
                        else -> lookup("ROUNDABOUT_2", "ROUNDABOUT_1")
                    }
                }

                "TURN_LEFT", "ON_RAMP_LEFT" -> return lookup(googleManeuver, "TURN_LEFT")
                "TURN_RIGHT", "ON_RAMP_RIGHT" -> return lookup(googleManeuver, "TURN_RIGHT")
                "TURN_SHARP_LEFT", "OFF_RAMP_SHARP_LEFT", "ON_RAMP_SHARP_LEFT" -> return lookup(googleManeuver, "TURN_SHARP_LEFT", "TURN_LEFT")
                "TURN_SHARP_RIGHT", "OFF_RAMP_SHARP_RIGHT", "ON_RAMP_SHARP_RIGHT" -> return lookup(googleManeuver, "TURN_SHARP_RIGHT", "TURN_RIGHT")
                "TURN_SLIGHT_LEFT", "OFF_RAMP_SLIGHT_LEFT", "ON_RAMP_SLIGHT_LEFT" -> return lookup(googleManeuver, "TURN_SLIGHT_LEFT", "TURN_LEFT")
                "TURN_SLIGHT_RIGHT", "OFF_RAMP_SLIGHT_RIGHT", "ON_RAMP_SLIGHT_RIGHT" -> return lookup(googleManeuver, "TURN_SLIGHT_RIGHT", "TURN_RIGHT")
                "TURN_U_TURN_CLOCKWISE", "OFF_RAMP_U_TURN_CLOCKWISE", "ON_RAMP_U_TURN_CLOCKWISE" -> return lookup(googleManeuver, "UTURN")
                "MERGE_UNSPECIFIED", "OFF_RAMP_UNSPECIFIED", "ON_RAMP_UNSPECIFIED" -> return lookup(googleManeuver, "STRAIGHT")
                "UNKNOWN" -> return lookup("UNKNOWN")

                "MERGE_RIGHT_SMOOTH" -> return lookup("MERGE_RIGHT_SMOOTH", "MERGE_RIGHT")
                "MERGE_LEFT_SMOOTH" -> return lookup("MERGE_LEFT_SMOOTH", "MERGE_LEFT")
                "MERGE_RIGHT_SMOOTHER" -> return lookup("MERGE_RIGHT_SMOOTHER", "MERGE_RIGHT")
                "MERGE_LEFT_SMOOTHER" -> return lookup("MERGE_LEFT_SMOOTHER", "MERGE_LEFT")
                "MERGE_RIGHT_SHARP" -> return lookup("MERGE_RIGHT_SHARP", "MERGE_RIGHT")
                "MERGE_LEFT_SHARP" -> return lookup("MERGE_LEFT_SHARP", "MERGE_LEFT")
                "MERGE_RIGHT_MAIN" -> return lookup("MERGE_RIGHT_MAIN", "MERGE_RIGHT")
                "MERGE_LEFT_MAIN" -> return lookup("MERGE_LEFT_MAIN", "MERGE_LEFT")
                "MERGE_RIGHT_MAIN_SHARP" -> return lookup("MERGE_RIGHT_MAIN_SHARP", "MERGE_RIGHT")
                "MERGE_LEFT_MAIN_SHARP" -> return lookup("MERGE_LEFT_MAIN_SHARP", "MERGE_LEFT")
                "FORK_RIGHT_CURVED" -> return lookup("FORK_RIGHT_CURVED", "FORK_RIGHT")
                "FORK_LEFT_CURVED" -> return lookup("FORK_LEFT_CURVED", "FORK_LEFT")
                "FORK_RIGHT_SHARP" -> return lookup("FORK_RIGHT_SHARP", "FORK_RIGHT")
                "FORK_LEFT_SHARP" -> return lookup("FORK_LEFT_SHARP", "FORK_LEFT")
                "LANE_GUIDANCE_LEFT" -> return lookup("LANE_GUIDANCE_LEFT")
                "LANE_GUIDANCE_RIGHT" -> return lookup("LANE_GUIDANCE_RIGHT")
                "FORK_RIGHT_PARALLEL" -> return lookup("FORK_RIGHT_PARALLEL", "FORK_RIGHT")
                "FORK_LEFT_PARALLEL" -> return lookup("FORK_LEFT_PARALLEL", "FORK_LEFT")
                "FORK_RIGHT_90" -> return lookup("FORK_RIGHT_90", "FORK_RIGHT")
                "FORK_LEFT_90" -> return lookup("FORK_LEFT_90", "FORK_LEFT")

                "ROUNDABOUT_COUNTERCLOCKWISE",
                "ROUNDABOUT_EXIT_COUNTERCLOCKWISE",
                "ROUNDABOUT_LEFT_COUNTERCLOCKWISE",
                "ROUNDABOUT_RIGHT_COUNTERCLOCKWISE",
                "ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE",
                "ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE",
                "ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE",
                "ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE",
                "ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE",
                "ROUNDABOUT_U_TURN_COUNTERCLOCKWISE",
                "ROUNDABOUT_LEFT" -> {
                    return when (exitNumber) {
                        0 -> lookup("ROUNDABOUT_CCW")
                        in 1..9 -> lookup("ROUNDABOUT_CCW_$exitNumber", "ROUNDABOUT_CCW")
                        else -> lookup("ROUNDABOUT_CCW")
                    }
                }

                "DEPART" -> return lookup("DEPART")
                "TURN_U_TURN_COUNTERCLOCKWISE",
                "OFF_RAMP_U_TURN_COUNTERCLOCKWISE",
                "ON_RAMP_U_TURN_COUNTERCLOCKWISE" -> return lookup(googleManeuver, "UTURN")
                "FERRY_BOAT" -> return lookup("FERRY_BOAT")
                "FERRY_TRAIN" -> return lookup("FERRY_TRAIN")
                "WALKING" -> return lookup("WALKING")
                "BLANK" -> return lookup("BLANK")
                "OFFLINE" -> return lookup("OFFLINE")
            }
        }

        // Fallback to legacy ManeuverType mapping if rawGoogleManeuver is null/unrecognized
        return when (type) {
            ManeuverType.ARRIVE -> profile.maneuvers["DESTINATION"] ?: 0
            ManeuverType.SLIGHT_LEFT -> lookup("TURN_SLIGHT_LEFT", "TURN_LEFT")
            ManeuverType.SLIGHT_RIGHT -> lookup("TURN_SLIGHT_RIGHT", "TURN_RIGHT")
            ManeuverType.TURN_LEFT -> lookup("TURN_LEFT")
            ManeuverType.TURN_RIGHT -> lookup("TURN_RIGHT")
            ManeuverType.SHARP_LEFT -> lookup("TURN_SHARP_LEFT", "TURN_LEFT")
            ManeuverType.SHARP_RIGHT -> lookup("TURN_SHARP_RIGHT", "TURN_RIGHT")
            ManeuverType.UTURN -> lookup("UTURN", "TURN_U_TURN_CLOCKWISE")
            ManeuverType.ROUNDABOUT -> {
                when (exitNumber) {
                    in 1..9 -> lookup("ROUNDABOUT_$exitNumber", "ROUNDABOUT_2")
                    else -> lookup("ROUNDABOUT_2", "ROUNDABOUT_1")
                }
            }
            ManeuverType.DEPART -> lookup("DEPART")
            else -> lookup("STRAIGHT")
        }
    }

    /**
     * Roundabout exit number for roundabout glyphs (0 = not a roundabout).
     * Parses from the instruction to select the matching exit in the active profile.
     */
    val dashRoundaboutExit: Int get() {
        val lowerInstr = instruction.lowercase()
        return when {
            "1st exit" in lowerInstr || "first exit" in lowerInstr -> 1
            "2nd exit" in lowerInstr || "second exit" in lowerInstr -> 2
            "3rd exit" in lowerInstr || "third exit" in lowerInstr -> 3
            "4th exit" in lowerInstr || "fourth exit" in lowerInstr -> 4
            "5th exit" in lowerInstr || "fifth exit" in lowerInstr -> 5
            "6th exit" in lowerInstr || "sixth exit" in lowerInstr -> 6
            "7th exit" in lowerInstr || "seventh exit" in lowerInstr -> 7
            "8th exit" in lowerInstr || "eighth exit" in lowerInstr -> 8
            "9th exit" in lowerInstr || "ninth exit" in lowerInstr -> 9
            else -> 0
        }
    }
}

/** A computed road route from origin to destination. */
data class Route(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalMeters: Double,
    val totalSeconds: Double,      // actual travel time (with traffic if available)
    val staticSeconds: Double = totalSeconds, // baseline travel time (no traffic)
    /** Cumulative distance (m) at each geometry vertex — same length as [geometry]. */
    val cumulative: DoubleArray,
) {
    val destination: GeoPoint? get() = geometry.lastOrNull()
}
