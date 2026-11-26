package io.github.saltmango.navbeam.data

import android.content.Context

object SettingsPrefs {
    private const val PREFS = "navbeam_settings"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_SCREEN_OFF = "screen_off"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val KEY_UNITS = "units"
    private const val KEY_THEME = "theme"
    private const val KEY_STREAM_QUALITY = "stream_quality"
    private const val KEY_NAV_MODE = "nav_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAutoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, value).apply()
    }

    fun getScreenOff(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCREEN_OFF, true)

    fun setScreenOff(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCREEN_OFF, value).apply()
    }

    fun getKeepAwake(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_AWAKE, true)

    fun setKeepAwake(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_AWAKE, value).apply()
    }

    fun getUnits(context: Context): String =
        prefs(context).getString(KEY_UNITS, "Kilometres") ?: "Kilometres"

    fun setUnits(context: Context, value: String) {
        prefs(context).edit().putString(KEY_UNITS, value).apply()
    }

    fun getTheme(context: Context): String =
        prefs(context).getString(KEY_THEME, "System") ?: "System"

    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_THEME, value).apply()
    }

    fun getStreamQuality(context: Context): String =
        prefs(context).getString(KEY_STREAM_QUALITY, "Balanced") ?: "Balanced"

    fun setStreamQuality(context: Context, value: String) {
        prefs(context).edit().putString(KEY_STREAM_QUALITY, value).apply()
    }

    fun getNavMode(context: Context): String =
        prefs(context).getString(KEY_NAV_MODE, "Map") ?: "Map"

    fun setNavMode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_NAV_MODE, value).apply()
    }

    private const val KEY_MAPS_API_KEY = "maps_api_key_enc"
    private const val KEY_JOYSTICK_CALL_ANSWER = "joystick_call_answer"

    fun getMapsApiKey(context: Context): String {
        val raw = prefs(context).getString(KEY_MAPS_API_KEY, null) ?: return ""
        return io.github.saltmango.navbeam.util.SecretStore.decrypt(raw).orEmpty()
    }

    fun setMapsApiKey(context: Context, value: String) {
        val editor = prefs(context).edit()
        if (value.isBlank()) {
            editor.remove(KEY_MAPS_API_KEY).apply()
        } else {
            val encrypted = io.github.saltmango.navbeam.util.SecretStore.encrypt(value) ?: return
            editor.putString(KEY_MAPS_API_KEY, encrypted).apply()
        }
    }

    /** Explicit opt-in required before joystick button events may answer incoming calls. */
    fun getJoystickCallAnswerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_JOYSTICK_CALL_ANSWER, false)

    fun setJoystickCallAnswerEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_JOYSTICK_CALL_ANSWER, value).apply()
    }

    private const val KEY_BIKE_NAME = "bike_name"

    fun getBikeName(context: Context): String =
        prefs(context).getString(KEY_BIKE_NAME, "") ?: ""

    fun setBikeName(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BIKE_NAME, value).apply()
    }

    private const val KEY_ROUTE_COLOR = "route_color"
    private const val KEY_ROUTE_COLOR_CUSTOM = "route_color_custom"

    fun getRouteColor(context: Context): String =
        prefs(context).getString(KEY_ROUTE_COLOR, "Laser Purple") ?: "Laser Purple"

    fun setRouteColor(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ROUTE_COLOR, value).apply()
    }

    fun getCustomRouteColorHex(context: Context): String =
        prefs(context).getString(KEY_ROUTE_COLOR_CUSTOM, "#FF00FF") ?: "#FF00FF"

    fun setCustomRouteColorHex(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ROUTE_COLOR_CUSTOM, value).apply()
    }

    private const val KEY_ROUTE_WIDTH = "route_width"

    fun getRouteLineWidth(context: Context): Float =
        prefs(context).getFloat(KEY_ROUTE_WIDTH, 8f)

    fun setRouteLineWidth(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_ROUTE_WIDTH, value).apply()
    }

    fun getCustomFps(context: Context, profile: String): Int = when (profile) {
        "HIGHWAY" -> prefs(context).getInt("custom_fps_highway", 4)
        "CITY" -> prefs(context).getInt("custom_fps_city", 3)
        "SLOW" -> prefs(context).getInt("custom_fps_slow", 2)
        "STOPPED" -> prefs(context).getInt("custom_fps_stopped", 1)
        "CORNERING" -> prefs(context).getInt("custom_fps_cornering", 6)
        else -> 4
    }

    fun setCustomFps(context: Context, profile: String, value: Int) {
        val key = when (profile) {
            "HIGHWAY" -> "custom_fps_highway"
            "CITY" -> "custom_fps_city"
            "SLOW" -> "custom_fps_slow"
            "STOPPED" -> "custom_fps_stopped"
            "CORNERING" -> "custom_fps_cornering"
            else -> return
        }
        prefs(context).edit().putInt(key, value).apply()
    }

    fun getCustomBitrate(context: Context, profile: String): Int = when (profile) {
        "HIGHWAY" -> prefs(context).getInt("custom_bitrate_highway", 800)
        "CITY" -> prefs(context).getInt("custom_bitrate_city", 500)
        "SLOW" -> prefs(context).getInt("custom_bitrate_slow", 350)
        "STOPPED" -> prefs(context).getInt("custom_bitrate_stopped", 200)
        "CORNERING" -> prefs(context).getInt("custom_bitrate_cornering", 800)
        else -> 800
    }

    fun setCustomBitrate(context: Context, profile: String, value: Int) {
        val key = when (profile) {
            "HIGHWAY" -> "custom_bitrate_highway"
            "CITY" -> "custom_bitrate_city"
            "SLOW" -> "custom_bitrate_slow"
            "STOPPED" -> "custom_bitrate_stopped"
            "CORNERING" -> "custom_bitrate_cornering"
            else -> return
        }
        prefs(context).edit().putInt(key, value).apply()
    }
}
