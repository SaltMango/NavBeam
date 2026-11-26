package io.github.saltmango.navbeam.data

import android.content.Context
import android.os.Build
import io.github.saltmango.navbeam.BuildConfig
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.util.BuildId
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local-only ride, crash, and diagnostics store.
 *
 * Writes append-only, PII-free logs under `<filesDir>/diag` (internal app-private storage)
 * so riders can report issues or export a `.zip` bundle directly from Menu → Diagnostics & Bug Reports.
 *
 * Every write is synchronous/guarded so logging can never crash a ride, and no telemetry ever
 * leaves the device unless the user explicitly shares or opens an issue report.
 */
object RideDiagnostics {
    private const val KEEP_RIDE_FILES = 12
    private const val MAX_LOG_BYTES = 512L * 1024L
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val lock = Any()

    @Volatile private var dir: File? = null
    @Volatile private var file: File? = null
    @Volatile private var sessionStartMs = 0L

    data class DiagSummary(
        val fileCount: Int,
        val crashCount: Int,
        val rideCount: Int,
        val totalBytes: Long,
    )

    fun diagDir(context: Context): File =
        dir ?: synchronized(lock) {
            dir ?: File(context.applicationContext.filesDir, "diag")
                .apply { mkdirs() }
                .also { dir = it }
        }

    /** Point the logger at <externalFilesDir>/diag. Safe to call multiple times. */
    fun init(context: Context) {
        runCatching { diagDir(context) }
    }

    /** Open a fresh ride session file and rotate old ones. */
    fun start(reason: String) {
        val d = dir ?: return
        synchronized(lock) {
            sessionStartMs = System.currentTimeMillis()
            val stamp = dayStamp.format(Date(sessionStartMs))
            file = File(d, "ride-$stamp.log")
            rotateRides(d)
            raw("==== session start: $reason — ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ====")
        }
    }

    /** Record one event, stamped with wall clock + offset from active ride session start. */
    fun log(tag: String, msg: String) {
        if (file == null) return
        val clean = DiagReporter.redact(msg)
        synchronized(lock) {
            val rel = if (sessionStartMs > 0) "+%6dms".format(System.currentTimeMillis() - sessionStartMs) else "         "
            raw("$rel  [$tag] $clean")
        }
    }

    fun stop(reason: String) {
        if (file == null) return
        synchronized(lock) {
            raw("==== session end: $reason ====")
            file = null
        }
    }

    /** Record an app/lifecycle event into the active ride log and rolling `events.log`. */
    fun logEvent(name: String, details: String = "") {
        val cleanDetails = DiagReporter.redact(details)
        val line = if (cleanDetails.isBlank()) name else "$name ($cleanDetails)"
        log("event", line)
        val d = dir ?: return
        synchronized(lock) {
            appendRolling(File(d, "events.log"), "${timestamp()}  $line\n")
        }
    }

    /** Record a handled exception into the active ride log and rolling `nonfatal.log`. */
    fun appendNonFatal(where: String?, t: Throwable) {
        val safeMsg = DiagReporter.redact(t.message.orEmpty())
        val summary = "${where ?: "caught"}: ${t.javaClass.name}: $safeMsg"
        log("nonfatal", summary)
        val d = dir ?: return
        val stack = DiagReporter.redact(StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString())
        synchronized(lock) {
            val entry = buildString {
                appendLine("=== ${timestamp()} | $summary ===")
                appendLine(stack.trimEnd())
                appendLine()
            }
            appendRolling(File(d, "nonfatal.log"), entry)
        }
    }

    fun summary(context: Context): DiagSummary = runCatching {
        val d = diagDir(context)
        val files = d.listFiles()?.filter { (it.isFile && it.length() > 0L) }.orEmpty()
        var crashes = 0
        var rides = 0
        var bytes = 0L
        for (f in files) {
            bytes += f.length()
            when {
                f.name.startsWith("crash-") -> crashes++
                f.name.startsWith("ride-") -> rides++
            }
        }
        DiagSummary(
            fileCount = files.size,
            crashCount = crashes,
            rideCount = rides,
            totalBytes = bytes,
        )
    }.getOrDefault(DiagSummary(0, 0, 0, 0L))

    /**
     * Bundle anonymized device metadata, ride performance CSV, and all local diagnostic/crash/ride logs
     * into `<cacheDir>/diag/navbeam-diag-<stamp>.zip` for sharing via `FileProvider` or `Downloads/NavBeam/`.
     */
    fun exportZip(context: Context): File? = runCatching {
        val app = context.applicationContext
        val d = diagDir(app)
        val exportDir = File(app.cacheDir, "diag").apply { mkdirs() }
        exportDir.listFiles()?.forEach { runCatching { it.delete() } }

        val stamp = dayStamp.format(Date())
        val zipFile = File(exportDir, "navbeam-diag-$stamp.zip")
        val logFiles = synchronized(lock) {
            d.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.length() > 0L }
                ?.sortedBy { it.name }
                ?.toList()
                .orEmpty()
        }

        val profile = DashProfileManager.activeProfile.value
        val deviceSummary = buildString {
            appendLine("NavBeam Diagnostic Bundle (Anonymized)")
            appendLine("exported_at=${timestamp()}")
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}")
            appendLine("apk_sha12=${BuildId.sha12(app)}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine(
                "profile_configured=${profile.isConfigured} dims=${profile.display.width}x${profile.display.height} " +
                    "shape=${profile.display.shape} auth=${profile.protocol.authType}"
            )
            appendLine("files=${logFiles.size}")
        }
        val perfCsv = DiagReporter.buildAnonymousPerfCsv(app)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.putEntryText("device-summary.txt", deviceSummary)
            if (perfCsv.isNotBlank()) {
                zos.putEntryText("ride-metrics.csv", perfCsv)
            }
            for (f in logFiles) {
                zos.putNextEntry(ZipEntry(f.name))
                if (f.name.endsWith(".log") || f.name.endsWith(".txt")) {
                    val redacted = DiagReporter.redact(f.readText(Charsets.UTF_8))
                    zos.write(redacted.toByteArray(Charsets.UTF_8))
                } else {
                    f.inputStream().use { it.copyTo(zos) }
                }
                zos.closeEntry()
            }
        }
        zipFile
    }.getOrNull()

    /** Delete all stored diagnostic, ride, crash, and exported zip files. */
    fun clearLogs(context: Context): Int = synchronized(lock) {
        file = null
        var deleted = 0
        val d = diagDir(context)
        d.listFiles()?.forEach { f ->
            if (f.isFile && runCatching { f.delete() }.getOrDefault(defaultValue = false)) deleted++
        }
        File(context.applicationContext.cacheDir, "diag").listFiles()?.forEach { f ->
            if (f.isFile) runCatching { f.delete() }
        }
        deleted
    }

    private fun ZipOutputStream.putEntryText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun raw(line: String) {
        val f = file ?: return
        runCatching { f.appendText("${clock.format(Date())}  $line\n") }
    }

    private fun appendRolling(target: File, text: String) {
        runCatching {
            if (target.exists() && target.length() >= MAX_LOG_BYTES) {
                val backup = File(target.parentFile, target.name.replace(".log", ".prev.log"))
                backup.delete()
                target.renameTo(backup)
            }
            target.appendText(text)
        }
    }

    private fun rotateRides(d: File) {
        val logs = d.listFiles { x -> x.name.startsWith("ride-") && x.name.endsWith(".log") } ?: return
        if (logs.size <= KEEP_RIDE_FILES) return
        logs.sortedBy { it.lastModified() }.dropLast(KEEP_RIDE_FILES).forEach { runCatching { it.delete() } }
    }
}
