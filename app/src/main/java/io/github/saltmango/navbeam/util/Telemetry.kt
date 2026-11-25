package io.github.saltmango.navbeam.util

import android.util.Log
import io.github.saltmango.navbeam.data.RideDiagnostics

/**
 * 100% local, zero-server-cost diagnostics & telemetry facade.
 *
 * Replaces cloud analytics/crash reporting: events and handled exceptions stay strictly on-device
 * inside [RideDiagnostics] logs and are only ever shared if the rider explicitly exports a
 * diagnostics bundle from Menu → Diagnostics & Bug Reports.
 */
object Telemetry {

    private const val TAG = "Telemetry"

    /** Debug-only screen navigation trace. */
    fun logScreen(name: String) {
        Dbg.d(TAG) { "screen: $name" }
    }

    /** Record a local diagnostic/ride event into [RideDiagnostics]. */
    fun logEvent(name: String, vararg params: Pair<String, Any?>) = runCatching {
        val details = params
            .filter { it.second != null }
            .joinToString(", ") { (k, v) -> "$k=$v" }
        RideDiagnostics.logEvent(name, details)
        Dbg.d(TAG) { if (details.isEmpty()) "event: $name" else "event: $name ($details)" }
    }.onFailure { Log.v(TAG, "logEvent failed: ${it.message}") }.let { Unit }

    /** Record a handled exception into local non-fatal + ride logs so it's never silently swallowed. */
    fun recordNonFatal(t: Throwable, where: String? = null) = runCatching {
        Log.w(TAG, where ?: t.message ?: t.javaClass.simpleName, t)
        RideDiagnostics.appendNonFatal(where, t)
    }.onFailure { Log.v(TAG, "recordNonFatal failed: ${it.message}") }.let { Unit }
}
