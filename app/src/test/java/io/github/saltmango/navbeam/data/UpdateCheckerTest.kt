package io.github.saltmango.navbeam.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest

class UpdateCheckerTest {

    @Test
    fun isAllowedUpdateUrl_enforcesHttpsAndGitHubHostsOnly() {
        assertTrue(UpdateChecker.isAllowedUpdateUrl(URL("https://github.com/SaltMango/NavBeam/releases/download/v1.0/app.apk")))
        assertTrue(UpdateChecker.isAllowedUpdateUrl(URL("https://objects.githubusercontent.com/github-production-release-asset/123")))
        assertTrue(UpdateChecker.isAllowedUpdateUrl(URL("https://release-assets.githubusercontent.com/asset.apk")))
        assertTrue(UpdateChecker.isAllowedUpdateUrl(URL("https://github.com:443/SaltMango/NavBeam/releases/latest")))

        assertFalse(UpdateChecker.isAllowedUpdateUrl(URL("http://github.com/SaltMango/NavBeam/app.apk")))
        assertFalse(UpdateChecker.isAllowedUpdateUrl(URL("https://evil.example.com/app.apk")))
        assertFalse(UpdateChecker.isAllowedUpdateUrl(URL("https://github.com.evil.com/app.apk")))
        assertFalse(UpdateChecker.isAllowedUpdateUrl(URL("https://user:pass@github.com/app.apk")))
        assertFalse(UpdateChecker.isAllowedUpdateUrl(URL("https://github.com:8443/app.apk")))
    }

    @Test
    fun parseSha_preservesFullDigestAndVerifyChecksMatch() {
        val fullHex = "a".repeat(64)
        val parsedFull = UpdateChecker.parseSha("Release checksum:\nsha256: $fullHex")
        assertEquals(fullHex, parsedFull)
        assertTrue(UpdateChecker.verifySha256Digest(fullHex, parsedFull))
        assertFalse(UpdateChecker.verifySha256Digest("b".repeat(64), parsedFull))

        val shortParsed = UpdateChecker.parseSha("sha256: 1caad8bed911")
        assertEquals("1caad8bed911", shortParsed)
        assertTrue(UpdateChecker.verifySha256Digest("1caad8bed911" + "0".repeat(52), shortParsed))
        assertFalse(UpdateChecker.verifySha256Digest("2caad8bed911" + "0".repeat(52), shortParsed))
    }

    @Test
    fun downloadToFile_verifiesSha256AndRejectsTamperedPayload() {
        val payload = "synthetic-apk-bytes".toByteArray(Charsets.UTF_8)
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val tempDir = java.nio.file.Files.createTempDirectory("update-test").toFile()
        try {
            val dest = File(tempDir, "navbeam-update.apk")

            val ok = UpdateChecker.downloadToFile(
                url = "https://github.com/SaltMango/NavBeam/releases/download/v1.0/app.apk",
                destFile = dest,
                expectedSha256 = expectedHash,
                opener = { url ->
                    if (url.host == "github.com") {
                        UpdateChecker.HttpStreamResult(
                            statusCode = 302,
                            contentLength = -1L,
                            locationHeader = "https://objects.githubusercontent.com/asset.apk",
                            bodyStream = null,
                        )
                    } else {
                        UpdateChecker.HttpStreamResult(
                            statusCode = 200,
                            contentLength = payload.size.toLong(),
                            locationHeader = null,
                            bodyStream = ByteArrayInputStream(payload),
                        )
                    }
                },
                onProgress = {},
            )
            assertNotNull("Valid redirect and SHA-256 must succeed", ok)
            assertTrue(dest.exists())

            val badHash = UpdateChecker.downloadToFile(
                url = "https://github.com/SaltMango/NavBeam/releases/download/v1.0/app.apk",
                destFile = dest,
                expectedSha256 = "0".repeat(64),
                opener = {
                    UpdateChecker.HttpStreamResult(
                        statusCode = 200,
                        contentLength = payload.size.toLong(),
                        locationHeader = null,
                        bodyStream = ByteArrayInputStream(payload),
                    )
                },
                onProgress = {},
            )
            assertNull("SHA-256 mismatch must fail and delete file", badHash)
            assertFalse(dest.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun downloadToFile_blocksUntrustedAndExcessiveRedirects() {
        val tempDir = java.nio.file.Files.createTempDirectory("update-redirect-test").toFile()
        try {
            val dest = File(tempDir, "navbeam-update.apk")

            val evilRedirect = UpdateChecker.downloadToFile(
                url = "https://github.com/SaltMango/NavBeam/releases/download/v1.0/app.apk",
                destFile = dest,
                expectedSha256 = "",
                opener = {
                    UpdateChecker.HttpStreamResult(
                        statusCode = 302,
                        contentLength = -1L,
                        locationHeader = "https://attacker.example/evil.apk",
                        bodyStream = null,
                    )
                },
                onProgress = {},
            )
            assertNull("Redirect to untrusted host must be rejected", evilRedirect)
            assertFalse(dest.exists())

            val httpDowngrade = UpdateChecker.downloadToFile(
                url = "https://github.com/SaltMango/NavBeam/releases/download/v1.0/app.apk",
                destFile = dest,
                expectedSha256 = "",
                opener = {
                    UpdateChecker.HttpStreamResult(
                        statusCode = 302,
                        contentLength = -1L,
                        locationHeader = "http://objects.githubusercontent.com/asset.apk",
                        bodyStream = null,
                    )
                },
                onProgress = {},
            )
            assertNull("HTTP downgrade redirect must be rejected", httpDowngrade)

            val loopRedirect = UpdateChecker.downloadToFile(
                url = "https://github.com/SaltMango/NavBeam/releases/download/v1.0/app.apk",
                destFile = dest,
                expectedSha256 = "",
                opener = {
                    UpdateChecker.HttpStreamResult(
                        statusCode = 302,
                        contentLength = -1L,
                        locationHeader = "https://objects.githubusercontent.com/loop.apk",
                        bodyStream = null,
                    )
                },
                onProgress = {},
            )
            assertNull("Redirect loop exceeding max hops must fail", loopRedirect)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun signingCertDigestsMatch_requiresMatchingCertificate() {
        val certA = "cert-A-der-bytes".toByteArray()
        val certB = "cert-B-der-bytes".toByteArray()

        assertTrue(UpdateChecker.signingCertDigestsMatch(listOf(certA), listOf(certA)))
        assertFalse(UpdateChecker.signingCertDigestsMatch(listOf(certA), listOf(certB)))
        assertFalse(UpdateChecker.signingCertDigestsMatch(emptyList(), listOf(certA)))
        assertFalse(UpdateChecker.signingCertDigestsMatch(listOf(certA), emptyList()))
    }
}
