package io.github.saltmango.navbeam.dash

import android.content.Context

/**
 * Per-rider dash WiFi configuration, persisted on-device.
 *
 * Dash WiFi configuration, persisted on-device.
 *
 * Configures the wireless display hotspot connection parameters.
 * Out of the box we connect by prefix (see [DashWifiManager]) and then
 * remember the exact SSID for direct reconnects.
 *
 * Everything is overridable in Settings for dashes that don't fit the defaults.
 */
class DashConfig private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("dash_config", Context.MODE_PRIVATE)

    /** Match across dash variants; rider-overridable. */
    var ssidPrefix: String
        get() = prefs.getString(KEY_PREFIX, null)
            ?: io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value.network.defaultSsidPrefix
        set(v) = prefs.edit().putString(KEY_PREFIX, v).apply()

    /** The exact SSID once learned/entered. Empty = not yet known → discover by prefix. */
    var ssid: String
        get() = prefs.getString(KEY_SSID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SSID, v).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, null)
            ?: io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value.network.defaultPassword
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    /** True until a specific dash has been identified — connect by prefix discovery. */
    val needsDiscovery: Boolean get() = ssid.isBlank()

    /** Forget the learned dash so the next connect re-runs prefix discovery. */
    fun forgetDash() { ssid = "" }

    companion object {
        private const val KEY_PREFIX   = "ssid_prefix"
        private const val KEY_SSID     = "ssid"
        private const val KEY_PASSWORD = "password"

        @Volatile private var instance: DashConfig? = null
        fun get(context: Context): DashConfig =
            instance ?: synchronized(this) {
                instance ?: DashConfig(context).also { instance = it }
            }
    }
}
