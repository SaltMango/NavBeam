package io.github.saltmango.navbeam.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * In-app updater. On app open we ask GitHub for the latest release; if its version is newer
 * than what's installed, the UI shows a dialog with the changelog and a one-tap "Update" that
 * downloads the APK, verifies origin, digest, and signing certificate continuity, and hands it
 * to the system package installer.
 *
 * No backend, no Play Store — just the public GitHub Releases API.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/SaltMango/NavBeam/releases/latest"
    private const val RELEASES_PAGE =
        "https://github.com/SaltMango/NavBeam/releases/latest"

    private const val MAX_REDIRECTS = 5
    private const val MAX_RELEASE_JSON_BYTES = 1 * 1024 * 1024
    internal const val MAX_APK_BYTES = 150L * 1024L * 1024L

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val ALLOWED_UPDATE_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    internal data class HttpStreamResult(
        val statusCode: Int,
        val contentLength: Long,
        val locationHeader: String?,
        val bodyStream: InputStream?,
        val closeAction: () -> Unit = {},
    ) : AutoCloseable {
        override fun close() {
            runCatching { bodyStream?.close() }
            runCatching { closeAction() }
        }
    }

    internal fun interface UpdateHttpOpener {
        fun open(url: URL): HttpStreamResult
    }

    private val defaultHttpOpener = UpdateHttpOpener { url ->
        if (!isAllowedUpdateUrl(url)) {
            throw SecurityException("Disallowed update URL: ${url.host}")
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "NavBeam-App")
        }
        val code = conn.responseCode
        val location = conn.getHeaderField("Location")
        val stream = if (code in 200..299) conn.inputStream else null
        if (stream == null) {
            runCatching { conn.errorStream?.close() }
        }
        HttpStreamResult(
            statusCode = code,
            contentLength = conn.contentLengthLong,
            locationHeader = location,
            bodyStream = stream,
            closeAction = { conn.disconnect() },
        )
    }

    data class ReleaseInfo(
        val versionName: String,   // tag without a leading "v", e.g. "1.2"
        val title: String,         // release name, falls back to the tag
        val notes: String,         // release body (markdown changelog)
        val apkUrl: String,        // browser_download_url of the .apk asset
        val apkSize: Long,         // bytes, 0 if unknown
        val sha256: String = "",   // 12..64-hex APK checksum, if the release notes publish one
    )

    /** Validate that an APK download or redirect URL stays on trusted GitHub HTTPS hosts. */
    internal fun isAllowedUpdateUrl(url: URL): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        if (!url.userInfo.isNullOrEmpty()) return false
        if (url.port != -1 && url.port != 443) return false
        val host = url.host.lowercase(Locale.US)
        return host in ALLOWED_UPDATE_HOSTS
    }

    /** Pull a published APK checksum out of release notes, preserving full 64-hex when present. */
    internal fun parseSha(body: String): String =
        Regex("sha-?256[:\\s]+([0-9a-fA-F]{12,64})", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.lowercase(Locale.US).orEmpty()

    internal fun verifySha256Digest(actualHex: String, expectedHex: String): Boolean {
        val expected = expectedHex.trim().lowercase(Locale.US)
        if (expected.isEmpty()) return true
        if (expected.length !in 12..64 || !expected.all { it in '0'..'9' || it in 'a'..'f' }) {
            return false
        }
        val actual = actualHex.trim().lowercase(Locale.US)
        if (actual.length != 64) return false
        return if (expected.length == 64) actual == expected else actual.startsWith(expected)
    }

    /**
     * Decide whether to offer [remote] to the user — by CHECKSUM when the release publishes one
     * (offer iff the running APK's hash differs), else by version.
     */
    fun shouldOffer(context: Context, remote: ReleaseInfo): Boolean {
        val mine = io.github.saltmango.navbeam.util.BuildId.sha12(context)
        val expectedPrefix = remote.sha256.take(12)
        return if (expectedPrefix.length == 12 && mine != "unknown") mine != expectedPrefix
        else isNewer(remote.versionName, currentVersionName(context))
    }

    /** The version string the app was built with (from the installed package). */
    fun currentVersionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        }.getOrDefault("0")

    /** Fetches the latest published (non-draft, non-prerelease) release. Null on any failure. */
    fun fetchLatest(context: Context): ReleaseInfo? = runCatching {
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "NavBeam-App")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "release check HTTP ${conn.responseCode}")
                return null
            }
            val bodyText = readBoundedUtf8(conn.inputStream)
                ?: return null
            val json = JSONObject(bodyText)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

            val tag = json.optString("tag_name").ifBlank { return null }
            val versionName = tag.removePrefix("v").removePrefix("V").trim()

            // Find the first .apk asset on an allowed HTTPS GitHub host.
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        val candidateUrl = a.optString("browser_download_url")
                        val parsed = runCatching { URL(candidateUrl) }.getOrNull()
                        if (parsed != null && isAllowedUpdateUrl(parsed)) {
                            apkUrl = candidateUrl
                            apkSize = a.optLong("size", 0L)
                            break
                        } else {
                            Log.w(TAG, "Ignoring APK asset from disallowed URL")
                        }
                    }
                }
            }
            if (apkUrl.isBlank()) return null

            val body = json.optString("body").trim()
            ReleaseInfo(
                versionName = versionName,
                title = json.optString("name").ifBlank { tag },
                notes = body,
                apkUrl = apkUrl,
                apkSize = apkSize,
                sha256 = parseSha(body),
            )
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "release check failed: ${it.message}") }.getOrNull()

    /** True if [remote] is a strictly higher version than [installed] (dotted-number compare). */
    fun isNewer(remote: String, installed: String): Boolean {
        fun parts(v: String): List<Int> =
            v.trim().split('.', '-', '+')
                .mapNotNull { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() }
        val r = parts(remote)
        val i = parts(installed)
        if (r.isEmpty()) return false
        for (idx in 0 until maxOf(r.size, i.size)) {
            val rv = r.getOrElse(idx) { 0 }
            val iv = i.getOrElse(idx) { 0 }
            if (rv != iv) return rv > iv
        }
        return false
    }

    /**
     * Downloads the APK into cacheDir/updates, enforces allowed GitHub HTTPS hosts across
     * redirects, enforces max size, checks [expectedSha256] (when provided), and verifies
     * package name + signing certificate continuity against the installed app.
     */
    fun download(
        context: Context,
        url: String,
        expectedSha256: String = "",
        onProgress: (Float) -> Unit,
    ): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "navbeam-update.apk")
        val downloaded = downloadToFile(
            url = url,
            destFile = out,
            expectedSha256 = expectedSha256,
            opener = defaultHttpOpener,
            onProgress = onProgress,
        ) ?: return null

        if (!verifyApkSignature(context, downloaded)) {
            Log.w(TAG, "Downloaded APK failed package/signature verification — deleting")
            downloaded.delete()
            return null
        }
        return downloaded
    }

    internal fun downloadToFile(
        url: String,
        destFile: File,
        expectedSha256: String,
        opener: UpdateHttpOpener,
        onProgress: (Float) -> Unit,
    ): File? = runCatching {
        if (destFile.exists()) destFile.delete()
        var currentUrl = URL(url)
        var redirects = 0

        while (true) {
            if (!isAllowedUpdateUrl(currentUrl)) {
                throw SecurityException("Disallowed update URL host or scheme: ${currentUrl.host}")
            }
            opener.open(currentUrl).use { res ->
                if (res.statusCode in REDIRECT_CODES) {
                    if (redirects >= MAX_REDIRECTS) {
                        throw SecurityException("Too many update redirects")
                    }
                    val loc = res.locationHeader?.trim().orEmpty()
                    if (loc.isEmpty()) throw IllegalStateException("Redirect missing Location header")
                    currentUrl = currentUrl.toURI().resolve(loc).toURL()
                    redirects++
                } else {
                    if (res.statusCode !in 200..299) {
                        Log.w(TAG, "download HTTP ${res.statusCode}")
                        return null
                    }
                    if (res.contentLength > MAX_APK_BYTES) {
                        throw SecurityException("Update APK exceeds maximum allowed size")
                    }
                    val input = res.bodyStream ?: return null
                    val digest = MessageDigest.getInstance("SHA-256")
                    val total = res.contentLength
                    destFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            done += read
                            if (done > MAX_APK_BYTES) {
                                throw SecurityException("Update APK stream exceeded maximum allowed size")
                            }
                            digest.update(buf, 0, read)
                            output.write(buf, 0, read)
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!verifySha256Digest(actualHex, expectedSha256)) {
                        destFile.delete()
                        throw SecurityException("Downloaded APK SHA-256 mismatch")
                    }
                    return destFile
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }.onFailure {
        destFile.delete()
        Log.w(TAG, "download failed: ${it.message}")
    }.getOrNull()

    /**
     * Verifies that [apkFile] has the same package name and at least one matching signing
     * certificate as the currently installed app.
     */
    @Suppress("DEPRECATION")
    internal fun verifyApkSignature(context: Context, apkFile: File): Boolean = runCatching {
        if (!apkFile.isFile) return@runCatching false
        val pm = context.packageManager
        val installedInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return@runCatching false

        if (archiveInfo.packageName != context.packageName) return@runCatching false

        val installedSigners = installedInfo.signingInfo?.let { info ->
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        }?.map { it.toByteArray() }.orEmpty()

        val archiveSigners = archiveInfo.signingInfo?.let { info ->
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        }?.map { it.toByteArray() }.orEmpty()

        signingCertDigestsMatch(installedSigners, archiveSigners)
    }.getOrDefault(false)

    internal fun signingCertDigestsMatch(
        installedSigners: List<ByteArray>,
        archiveSigners: List<ByteArray>,
    ): Boolean {
        if (installedSigners.isEmpty() || archiveSigners.isEmpty()) return false
        val md = MessageDigest.getInstance("SHA-256")
        val installedHashes = installedSigners.map { s ->
            md.digest(s).joinToString("") { "%02x".format(it) }
        }.toSet()
        val archiveHashes = archiveSigners.map { s ->
            md.digest(s).joinToString("") { "%02x".format(it) }
        }.toSet()
        return installedHashes.any { it in archiveHashes }
    }

    /**
     * Hands the downloaded APK to the system package installer.
     *
     * @return false if verification failed or redirected to unknown-sources settings.
     */
    fun install(context: Context, file: File): Boolean {
        if (!verifyApkSignature(context, file)) {
            Log.w(TAG, "Refusing to install unverified update APK")
            file.delete()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { Log.w(TAG, "install intent failed: ${it.message}") }
        return true
    }

    /** Fallback: open the Releases page in a browser if the in-app download/install fails. */
    fun openReleasesPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun readBoundedUtf8(stream: InputStream): String? = stream.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            total += read
            if (total > MAX_RELEASE_JSON_BYTES) {
                Log.w(TAG, "Release metadata exceeded $MAX_RELEASE_JSON_BYTES bytes")
                return null
            }
            out.write(buf, 0, read)
        }
        out.toByteArray().toString(Charsets.UTF_8)
    }
}
