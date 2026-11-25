package io.github.saltmango.navbeam.util

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 of the app's OWN installed APK — the ground-truth identity of the running build.
 *
 * Logged into every ride session and crash/diagnostic export so bug reports match 1:1
 * against the exact APK build. Computed once and cached.
 */
object BuildId {
    @Volatile private var cached: String? = null

    /** First 12 hex of SHA-256 over the installed APK. "unknown" if it can't be read. */
    fun sha12(context: Context): String =
        cached ?: synchronized(this) {
            cached ?: runCatching { compute(context) }.getOrDefault("unknown").also { cached = it }
        }

    /** Warm the cache off the main thread (call from Application.onCreate). */
    fun warm(context: Context) {
        val app = context.applicationContext
        Thread { sha12(app) }.apply { isDaemon = true }.start()
    }

    private fun compute(context: Context): String {
        val apk = File(context.applicationInfo.sourceDir)
        val md = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }
}
