package io.github.saltmango.navbeam.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.saltmango.navbeam.BuildConfig
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.util.BuildId
import java.io.File
import java.util.Locale

/**
 * Privacy-preserving bug report & diagnostics dispatcher (zero servers, zero user PII).
 *
 * Guarantees:
 * - Never includes names, emails, GPS coordinates, routes, destinations, Wi-Fi SSIDs,
 *   passwords, API keys, profile names, phone hostnames, caller names, or media titles.
 * - Detects unseen crashes (`crash-*.log` / `crash-exit-*.log`) for a one-time post-crash prompt.
 * - Builds a pre-filled GitHub Issue URL (`github.com/SaltMango/NavBeam/issues/new`) containing
 *   only technical environment info, stack trace excerpt, and aggregate numeric ride performance KPIs.
 * - Optionally saves the full diagnostic `.zip` into `Downloads/NavBeam/` via `MediaStore.Downloads`
 *   (zero runtime permissions required on API 29+) so the user can attach it if requested.
 */
object DiagReporter {
    private const val PREFS = "navbeam_diag_prefs"
    private const val KEY_LAST_PROMPTED_CRASH_MS = "last_prompted_crash_ms"
    private const val GITHUB_NEW_ISSUE_URL = "https://github.com/SaltMango/NavBeam/issues/new"
    private const val MAX_BODY_CHARS = 4200

    private val coordRegex = Regex("""-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}""")
    private val ssidRegex = Regex("""ssid\s*=\s*['"]?[^'"\s)]+['"]?""", RegexOption.IGNORE_CASE)

    /** Scrub accidental coordinates or SSIDs from diagnostic text before persistence/export. */
    fun redact(text: String): String =
        text.replace(coordRegex, "[redacted-coord]")
            .replace(ssidRegex, "ssid=[redacted]")

    fun pendingCrashReport(context: Context): File? = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastPrompted = prefs.getLong(KEY_LAST_PROMPTED_CRASH_MS, 0L)
        val dir = RideDiagnostics.diagDir(context)
        dir.listFiles { f ->
            f.isFile && f.length() > 0L && f.name.startsWith("crash-") && f.name.endsWith(".log")
        }
            ?.filter { it.lastModified() > lastPrompted }
            ?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    fun markCrashPrompted(context: Context, crashFile: File? = null) {
        val ts = maxOf(System.currentTimeMillis(), crashFile?.lastModified() ?: 0L)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putLong(KEY_LAST_PROMPTED_CRASH_MS, ts)
            }
    }

    /** Short one-line crash summary for UI dialogs (no PII). */
    fun summarizeCrash(crashFile: File): String = runCatching {
        val lines = crashFile.readLines()
        val ex = lines.firstOrNull { it.startsWith("exception=") }?.removePrefix("exception=")?.trim()
        ex?.takeIf { it.isNotBlank() }?.take(140) ?: crashFile.name
    }.getOrDefault("Unexpected app termination")

    /**
     * Copy a generated diagnostic zip into `Downloads/NavBeam/` using Scoped Storage (`MediaStore`).
     * Requires zero storage permissions on Android 10+ (`minSdk = 29`).
     */
    fun saveZipToDownloads(context: Context, zipFile: File): String? = runCatching {
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, zipFile.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NavBeam")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out ->
            zipFile.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "Downloads/NavBeam/${zipFile.name}"
    }.getOrNull()

    /**
     * Build a pre-filled GitHub Issue URI with technical environment, crash excerpt, and
     * anonymous ride performance metrics. Zero user/location/account data is ever added.
     */
    fun buildGitHubIssueUri(
        context: Context,
        crashFile: File? = null,
        savedZipDisplayPath: String? = null,
    ): Uri {
        val app = context.applicationContext
        val targetCrash = crashFile ?: latestCrashOrExitFile(app)
        val profile = DashProfileManager.activeProfile.value

        val title = if (targetCrash != null) {
            "[Crash] ${summarizeCrash(targetCrash)}".take(90)
        } else {
            "[Diagnostics] ${Build.MANUFACTURER} ${Build.MODEL} (v${BuildConfig.VERSION_NAME})".take(90)
        }

        val labels = if (targetCrash != null) "bug" else "bug,performance"

        val envBlock = buildString {
            appendLine("### Environment")
            appendLine("- **App:** v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, debug=${BuildConfig.DEBUG})")
            appendLine("- **APK SHA-12:** `${BuildId.sha12(app)}`")
            appendLine("- **Device:** ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- **Android:** ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(
                "- **Display Profile:** configured=${profile.isConfigured}, " +
                    "${profile.display.width}x${profile.display.height}, auth=${profile.protocol.authType}"
            )
        }

        val perfBlock = buildAnonymousPerfSummary(app)

        val attachBlock = buildString {
            if (!savedZipDisplayPath.isNullOrBlank()) {
                appendLine()
                appendLine("### Diagnostic Bundle")
                appendLine("Full anonymized logs saved on device at `$savedZipDisplayPath` (attach manually if needed).")
            }
            appendLine()
            appendLine("_Generated locally by NavBeam. Zero personal, location, or account data included._")
        }

        val staticLen = envBlock.length + perfBlock.length + attachBlock.length + 120
        val traceBudget = (MAX_BODY_CHARS - staticLen).coerceAtLeast(600)
        val traceBlock = buildTraceExcerpt(app, targetCrash, traceBudget)

        val body = buildString {
            appendLine("### What happened?")
            appendLine("_Describe what you were doing (optional):_")
            appendLine()
            append(envBlock)
            if (traceBlock.isNotBlank()) {
                appendLine()
                append(traceBlock)
            }
            if (perfBlock.isNotBlank()) {
                appendLine()
                append(perfBlock)
            }
            append(attachBlock)
        }.take(MAX_BODY_CHARS)

        return GITHUB_NEW_ISSUE_URL.toUri()
            .buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("labels", labels)
            .appendQueryParameter("body", body)
            .build()
    }

    fun openGitHubIssue(context: Context, uri: Uri): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun shareZipIntent(context: Context, zipFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NavBeam Diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share NavBeam Diagnostics")
    }

    /**
     * Build strictly anonymous performance metrics from local SQLite rides.
     * Uses only aggregate numeric/enum KPIs — never locations, routes, tracks, or timestamps.
     */
    fun buildAnonymousPerfCsv(context: Context): String = runCatching {
        val rides = NavBeamDb.get(context).rides().take(25)
        buildString {
            appendLine(
                "duration_s,distance_km,avg_fps,skipped_frames,avg_encode_ms," +
                    "avg_net_latency_ms,avg_bitrate_kbps,battery_used_pct,battery_per_min," +
                    "start_temp_c,end_temp_c,thermal_throttled,reconnect_count," +
                    "gps_accuracy_avg_m,gps_drops,recalc_count,encoder_mode,nav_mode,stream_quality"
            )
            for (r in rides) {
                appendLine(
                    String.format(
                        Locale.US,
                        "%d,%.2f,%.2f,%d,%.2f,%.2f,%.1f,%d,%.3f,%.1f,%.1f,%b,%d,%.1f,%d,%d,%s,%s,%s",
                        r.durationSec,
                        r.distanceKm,
                        r.avgFps,
                        r.skippedFrames,
                        r.avgEncodeTimeMs,
                        r.avgNetworkLatencyMs,
                        r.avgBitrateKbps,
                        r.batteryUsed,
                        r.batteryUsedPerMin,
                        r.startBatteryTemp,
                        r.endBatteryTemp,
                        r.thermalThrottled,
                        r.reconnectCount,
                        r.gpsAccuracyAvg,
                        r.gpsDropsCount,
                        r.recalcCount,
                        sanitizeCsvEnum(r.encoderMode),
                        sanitizeCsvEnum(r.navMode),
                        sanitizeCsvEnum(r.streamQuality),
                    )
                )
            }
        }
    }.getOrDefault("")

    private fun buildAnonymousPerfSummary(context: Context): String = runCatching {
        val recent = NavBeamDb.get(context).rides().take(5)
        if (recent.isEmpty()) return@runCatching ""
        val latest = recent.first()
        val avgFps = recent.map { it.avgFps }.average()
        val avgEncode = recent.map { it.avgEncodeTimeMs }.average()
        val avgNet = recent.map { it.avgNetworkLatencyMs }.average()
        val avgBatRate = recent.map { it.batteryUsedPerMin }.average()
        val anyThermal = recent.any { it.thermalThrottled }
        val totalReconnects = recent.sumOf { it.reconnectCount }
        val totalSkipped = recent.sumOf { it.skippedFrames }
        buildString {
            appendLine("### Performance Metrics (last ${recent.size} ride(s), anonymized)")
            appendLine(
                String.format(
                    Locale.US,
                    "- **Video/Encode:** %.1f fps avg, %.1f ms encode, %.1f ms net, %.0f kbps, %d skipped (%s / %s / %s)",
                    avgFps,
                    avgEncode,
                    avgNet,
                    latest.avgBitrateKbps,
                    totalSkipped,
                    sanitizeCsvEnum(latest.encoderMode).ifBlank { "-" },
                    sanitizeCsvEnum(latest.navMode).ifBlank { "-" },
                    sanitizeCsvEnum(latest.streamQuality).ifBlank { "-" },
                )
            )
            appendLine(
                String.format(
                    Locale.US,
                    "- **Battery/Thermal:** %.2f %%/min avg, %.1f°C end temp, thermal_throttled=%b",
                    avgBatRate,
                    latest.endBatteryTemp,
                    anyThermal,
                )
            )
            appendLine("- **Link/GPS:** reconnects=$totalReconnects, latest_gps_drops=${latest.gpsDropsCount}, recalcs=${latest.recalcCount}")
        }
    }.getOrDefault("")

    private fun latestCrashOrExitFile(context: Context): File? = runCatching {
        RideDiagnostics.diagDir(context)
            .listFiles { f -> f.isFile && f.length() > 0L && f.name.startsWith("crash-") && f.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    private fun buildTraceExcerpt(context: Context, crashFile: File?, maxChars: Int): String = runCatching {
        val src = crashFile ?: File(RideDiagnostics.diagDir(context), "nonfatal.log").takeIf { it.exists() && it.length() > 0L }
            ?: return@runCatching ""
        val lines = src.readLines()
        val excerpt = if (src.name.startsWith("crash-")) {
            lines.take(32)
        } else {
            lines.takeLast(25)
        }.joinToString("\n").let(::redact)
        val trimmed = if (excerpt.length > maxChars) excerpt.take(maxChars) + "\n... (truncated)" else excerpt
        val heading = if (src.name.startsWith("crash-")) "### Crash / Exit Trace (`${src.name}`)" else "### Recent Non-Fatal Errors"
        "$heading\n```text\n$trimmed\n```\n"
    }.getOrDefault("")

    private fun sanitizeCsvEnum(value: String): String =
        value.replace(Regex("[^A-Za-z0-9 _.-]"), "").take(24)
}
