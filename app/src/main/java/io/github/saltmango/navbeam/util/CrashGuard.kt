package io.github.saltmango.navbeam.util

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.saltmango.navbeam.BuildConfig
import io.github.saltmango.navbeam.data.RideDiagnostics
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncaught-exception handler that writes stack traces to local `diag/crash-*.log` files
 * before delegating to the system handler. Zero network calls, zero servers.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val KEEP_FILES = 20

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Best-effort, fully synchronous — the process is about to die.
            runCatching { writeCrash(app, thread, error) }
                .onFailure { Log.w(TAG, "failed to persist crash: ${it.message}") }
            previous?.uncaughtException(thread, error)
        }
        Log.i(TAG, "Uncaught-exception trace installed")
    }

    private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
        val dir = RideDiagnostics.diagDir(context)
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))

        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = io.github.saltmango.navbeam.data.DiagReporter.redact(
            buildString {
                appendLine("=== NavBeam crash ===")
                appendLine("when=$stamp ($now)")
                appendLine("thread=${thread.name}")
                appendLine("exception=${error.javaClass.name}: ${error.message}")
                appendLine("apk=${BuildId.sha12(context)}")
                appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
                appendLine()
                append(stack)
            }
        )
        File(dir, "crash-$stamp.log").writeText(text)
        rotate(dir)
    }

    private fun rotate(dir: File) {
        val logs = dir.listFiles { f -> f.name.startsWith("crash-") && !f.name.startsWith("crash-exit-") && f.name.endsWith(".log") } ?: return
        if (logs.size <= KEEP_FILES) return
        logs.sortedBy { it.lastModified() }.dropLast(KEEP_FILES).forEach { runCatching { it.delete() } }
    }
}
