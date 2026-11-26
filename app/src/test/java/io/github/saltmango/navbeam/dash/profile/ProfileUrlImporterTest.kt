package io.github.saltmango.navbeam.dash.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URL

class ProfileUrlImporterTest {

    private val validProfileJson = """
    {
      "id": "url_test_dash",
      "name": "URL Test Dash",
      "version": 1,
      "firmwareVersion": "1.0.0",
      "description": "Valid synthetic profile for URL importer tests",
      "network": {
        "defaultSsidPrefix": "TEST_",
        "defaultPassword": "password123",
        "gatewayIp": "10.0.0.1",
        "controlTxPort": 3000,
        "controlRxPort": 3002,
        "videoRtpPort": 6000
      },
      "display": {
        "width": 640,
        "height": 480,
        "shape": "CIRCLE",
        "frameDurationActiveMs": 250,
        "frameDurationIdleMs": 500,
        "motionHoldMs": 1500,
        "activeBitrateBps": 200000,
        "idleBitrateBps": 100000,
        "h264Level": "4.1",
        "prependSpsPpsToIdr": true,
        "forceSpsBaseline": false,
        "dropSeiAndAud": true,
        "rtpMaxPayloadBytes": 1380,
        "rtpPayloadType": 96
      },
      "protocol": {
        "magic": "TEST",
        "fixedHeaderHex": "0102030405060708",
        "incomingHeaderOffset": 8,
        "authType": "NONE",
        "projKeepAliveMs": 250,
        "routeCardKeepAliveMs": 1000,
        "authTimeoutMs": 15000,
        "maxAuthAttempts": 4,
        "burstPauseMs": 20,
        "rxWatchdogMs": 6000,
        "rsaCiphertextBytes": 128,
        "maxRouteTitleBytes": 60,
        "maxMediaFieldChars": 20
      },
      "commands": {
        "authRequest": "00:00:00",
        "authSendKeyTag": "00:01",
        "authModulusTag": "00:02",
        "authExponentTag": "00:03",
        "authStatusTag": "00:04",
        "authConfirmByte": "0x01",
        "hostnameTag": "00:05",
        "timeSyncTag": "00:06",
        "initialBurst": [["AUTH_REQUEST", "HOSTNAME", "TIME_SYNC"]],
        "heartbeat": ["TEMP", "00:07:00"],
        "heartbeatTempTag": "00:08",
        "heartbeatTempOffset": 0,
        "heartbeatDefaultTempC": 25,
        "navContext": ["01:00:01"],
        "emptyLists": [],
        "navStart": ["01:01:01"],
        "navPlaceholder": ["01:02:0000"],
        "projectionFrame": ["01:03:01"],
        "projectionOn": ["01:04:01"],
        "projectionStop": ["01:03:00"],
        "projectionOff": ["01:04:00"],
        "frameDecodedIdrNotify": "02:01:01",
        "frameDecodedIdrAck": ["02:02:01"],
        "frameDecodedPNotify": "02:03:01",
        "frameDecodedPAck": ["02:04:01"],
        "buttonEventTag": "03:01",
        "buttonAckTag": "03:02",
        "telemetryEncryptedType": "0x0F",
        "telemetryPlainType": "0x0C",
        "nowPlayingTag": "04:01",
        "callNotifyTag": "04:02",
        "albumArtTag": "04:03",
        "navTags": {
          "titleTag": "10:01",
          "primaryManeuverTag": "10:02",
          "secondaryManeuverTag": "10:03",
          "primaryDistTag": "10:04",
          "secondaryDistTag": "10:05",
          "primaryUnitTag": "10:06",
          "secondaryUnitTag": "10:07",
          "etaTag": "10:08",
          "totalDistTag": "10:09",
          "totalUnitTag": "10:0A",
          "unitMetersByte": "0x01",
          "unitKmTenthsByte": "0x02",
          "projectionFlagTag": "10:0B",
          "projectionFlagOnByte": "0x01",
          "projectionFlagOffByte": "0x00",
          "routeCardExtraTlvs": [],
          "activeNavExtraTlvs": []
        }
      }
    }
    """.trimIndent()

    private fun publicIp(host: String = "example.com"): Array<InetAddress> =
        arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))

    private fun importerWith(
        dns: DnsResolver = DnsResolver { host -> publicIp(host) },
        fetcher: HttpUrlFetcher = HttpUrlFetcher { _, _, _ ->
            HttpFetchResponse(
                statusCode = 200,
                contentLength = validProfileJson.toByteArray(Charsets.UTF_8).size.toLong(),
                locationHeader = null,
                bodyStream = ByteArrayInputStream(validProfileJson.toByteArray(Charsets.UTF_8)),
            )
        },
    ): ProfileUrlImporter = ProfileUrlImporter(
        dnsResolver = dns,
        httpFetcher = fetcher,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun rejectsDisallowedSchemes() {
        val importer = importerWith()
        val badUrls = listOf(
            "http://example.com/profile.navbeam",
            "file:///etc/passwd",
            "content://com.example.provider/profile.navbeam",
            "javascript:alert(1)",
            "data:application/json,%7B%7D",
            "ftp://example.com/profile.navbeam",
            "",
            "   ",
        )
        for (url in badUrls) {
            val res = importer.validateAndNormalizeUrl(url)
            assertTrue("Expected '$url' to be rejected", res.isFailure)
        }
    }

    @Test
    fun rejectsUserInfoAndNonStandardPorts() {
        val importer = importerWith()
        val badUrls = listOf(
            "https://user:pass@example.com/profile.navbeam",
            "https://user@example.com/profile.navbeam",
            "https://example.com:8443/profile.navbeam",
            "https://example.com:80/profile.navbeam",
            "https://example.com:0/profile.navbeam",
        )
        for (url in badUrls) {
            val res = importer.validateAndNormalizeUrl(url)
            assertTrue("Expected '$url' to be rejected", res.isFailure)
            assertTrue(res.exceptionOrNull() is SecurityException)
        }

        assertTrue(importer.validateAndNormalizeUrl("  https://example.com:443/profile.navbeam  ").isSuccess)
    }

    @Test
    fun rejectsOversizedUrls() {
        val importer = importerWith()
        val prefix = "https://example.com/"
        val suffix = ".navbeam"
        val exactMax = prefix + "a".repeat(ProfileUrlImporter.MAX_URL_LENGTH - prefix.length - suffix.length) + suffix
        assertEquals(ProfileUrlImporter.MAX_URL_LENGTH, exactMax.length)
        assertTrue(importer.validateAndNormalizeUrl(exactMax).isSuccess)

        val tooLong = prefix + "a".repeat(ProfileUrlImporter.MAX_URL_LENGTH - prefix.length - suffix.length + 1) + suffix
        val res = importer.validateAndNormalizeUrl(tooLong)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun rejectsNonJsonOrNavbeamUrls() {
        val importer = importerWith()
        val disallowedUrls = listOf(
            "https://example.com/",
            "https://example.com/profile",
            "https://example.com/notes.txt",
            "https://example.com/page.html",
            "https://example.com/archive.zip",
            "https://example.com/app.apk",
            "https://example.com/image.png",
            "https://example.com/.json",
            "https://example.com/.navbeam",
            "https://github.com/SaltMango/NavBeam/blob/main/README.md",
            "https://github.com/SaltMango/NavBeam/blob/main/scripts/deploy.sh",
        )
        for (url in disallowedUrls) {
            val res = importer.validateAndNormalizeUrl(url)
            assertTrue("Expected non-profile URL '$url' to be rejected", res.isFailure)
            assertTrue(res.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun convertsGitHubBlobUrlsAndRejectsTraversal() {
        val importer = importerWith()

        val converted = importer.validateAndNormalizeUrl(
            "https://github.com/SaltMango/NavBeam/blob/main/DashProfile.json"
        ).getOrThrow()
        assertEquals(
            URL("https://raw.githubusercontent.com/SaltMango/NavBeam/main/DashProfile.json"),
            converted,
        )

        val nestedConverted = importer.validateAndNormalizeUrl(
            "https://github.com/owner/repo/blob/v1.2.3/profiles/bike_dash.navbeam?plain=1#L1"
        ).getOrThrow()
        assertEquals(
            URL("https://raw.githubusercontent.com/owner/repo/v1.2.3/profiles/bike_dash.navbeam"),
            nestedConverted,
        )

        val badGitHubUrls = listOf(
            "https://github.com/owner/repo/blob/main",
            "https://github.com/owner/repo/blob/main/",
            "https://github.com/owner/repo/blob//file.navbeam",
            "https://github.com/owner/repo/blob/main/../secret.json",
            "https://github.com/owner/repo/blob/main/%2e%2e/secret.json",
            "https://github.com/owner/repo/blob/main/%2E%2E%2Fsecret.json",
            "https://example.com/dir/../secret.json",
            "https://example.com/dir/%2e%2e/secret.json",
            "https://example.com/dir\\secret.json",
        )
        for (url in badGitHubUrls) {
            val res = importer.validateAndNormalizeUrl(url)
            assertTrue("Expected malformed/traversal URL '$url' to be rejected", res.isFailure)
        }
    }

    @Test
    fun ssrfRejectsPrivateLoopbackLinkLocalMulticastAndUlaAddresses() = runBlocking {
        val blockedIps = listOf(
            "127.0.0.1" to byteArrayOf(127, 0, 0, 1),
            "127.0.1.1" to byteArrayOf(127, 0, 1, 1),
            "10.0.0.1" to byteArrayOf(10, 0, 0, 1),
            "172.16.0.1" to byteArrayOf(172.toByte(), 16, 0, 1),
            "192.168.1.1" to byteArrayOf(192.toByte(), 168.toByte(), 1, 1),
            "169.254.169.254" to byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()),
            "0.0.0.0" to byteArrayOf(0, 0, 0, 0),
            "224.0.0.1" to byteArrayOf(224.toByte(), 0, 0, 1),
            "::1" to ByteArray(16).apply { this[15] = 1 },
            "::" to ByteArray(16),
            "fc00::1" to ByteArray(16).apply { this[0] = 0xFC.toByte(); this[15] = 1 },
            "fd12:3456:789a::1" to ByteArray(16).apply { this[0] = 0xFD.toByte(); this[15] = 1 },
            "fe80::1" to ByteArray(16).apply { this[0] = 0xFE.toByte(); this[1] = 0x80.toByte(); this[15] = 1 },
            "ff02::1" to ByteArray(16).apply { this[0] = 0xFF.toByte(); this[15] = 1 },
        )

        for ((label, rawBytes) in blockedIps) {
            var fetched = false
            val importer = importerWith(
                dns = { host -> arrayOf(InetAddress.getByAddress(host, rawBytes)) },
                fetcher = { _, _, _ ->
                    fetched = true
                    error("Fetcher should never be called for SSRF address $label")
                },
            )
            val result = importer.importFromUrl("https://evil.example/profile.navbeam")
            assertTrue("Expected SSRF IP $label to be rejected", result.isFailure)
            assertTrue("Expected SecurityException for $label", result.exceptionOrNull() is SecurityException)
            assertFalse("Fetcher must not be invoked for $label", fetched)
        }

        // Literal localhost hostname must be rejected even without DNS resolution
        val localhostImporter = importerWith(
            dns = { error("DNS should not even be queried for localhost") },
        )
        val localhostRes = localhostImporter.importFromUrl("https://localhost/profile.navbeam")
        assertTrue(localhostRes.isFailure)
        assertTrue(localhostRes.exceptionOrNull() is SecurityException)

        // Mixed DNS results (1 public IP + 1 private IP) must be rejected
        val mixedImporter = importerWith(
            dns = DnsResolver { host ->
                arrayOf(
                    InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)),
                    InetAddress.getByAddress(host, byteArrayOf(10, 0, 0, 5)),
                )
            },
        )
        assertTrue(mixedImporter.importFromUrl("https://rebind.example/profile.navbeam").isFailure)
    }

    @Test
    fun validatesRedirectsAndEnforcesMax2Hops() = runBlocking {
        // 1. Valid 2-hop HTTPS redirect succeeds
        val requestedUrls = mutableListOf<String>()
        val twoHopImporter = importerWith(
            fetcher = HttpUrlFetcher { url, _, _ ->
                requestedUrls += url.toString()
                when (requestedUrls.size) {
                    1 -> HttpFetchResponse(302, -1L, "https://cdn.example.com/step2.navbeam", null)
                    2 -> HttpFetchResponse(301, -1L, "/final/profile.navbeam", null)
                    else -> HttpFetchResponse(
                        200,
                        validProfileJson.toByteArray(Charsets.UTF_8).size.toLong(),
                        null,
                        ByteArrayInputStream(validProfileJson.toByteArray(Charsets.UTF_8)),
                    )
                }
            },
        )
        val okResult = twoHopImporter.importFromUrl("https://example.com/start.navbeam")
        assertTrue("2-hop valid redirect must succeed: ${okResult.exceptionOrNull()}", okResult.isSuccess)
        assertEquals(3, requestedUrls.size)
        assertEquals("cdn.example.com/final/profile.navbeam", okResult.getOrThrow().sourceLabel)

        // 2. 3-hop redirect exceeds MAX_REDIRECTS (2) and fails
        val threeHopImporter = importerWith(
            fetcher = HttpUrlFetcher { _, _, _ ->
                HttpFetchResponse(302, -1L, "https://cdn.example.com/next.navbeam", null)
            },
        )
        val tooManyHops = threeHopImporter.importFromUrl("https://example.com/start.navbeam")
        assertTrue(tooManyHops.isFailure)
        assertTrue(tooManyHops.exceptionOrNull() is SecurityException)

        // 3. Redirect to HTTP is rejected
        val downgradeImporter = importerWith(
            fetcher = HttpUrlFetcher { _, _, _ ->
                HttpFetchResponse(302, -1L, "http://cdn.example.com/profile.navbeam", null)
            },
        )
        val downgrade = downgradeImporter.importFromUrl("https://example.com/start.navbeam")
        assertTrue(downgrade.isFailure)
        assertTrue(downgrade.exceptionOrNull() is SecurityException)

        // 4. Redirect to SSRF target is rejected before fetching redirect target
        val ssrfRedirectImporter = importerWith(
            dns = DnsResolver { host ->
                if (host == "127.0.0.1" || host == "internal.example") {
                    arrayOf(InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 168.toByte(), 1, 10)))
                } else {
                    publicIp(host)
                }
            },
            fetcher = HttpUrlFetcher { url, _, _ ->
                if (url.host == "internal.example") {
                    error("Must never connect to internal redirect target")
                }
                HttpFetchResponse(302, -1L, "https://internal.example/secret.json", null)
            },
        )
        val ssrfRedirect = ssrfRedirectImporter.importFromUrl("https://example.com/start.navbeam")
        assertTrue(ssrfRedirect.isFailure)
        assertTrue(ssrfRedirect.exceptionOrNull() is SecurityException)

        // 5. Redirect to non-.json/.navbeam target is rejected
        val nonProfileRedirectImporter = importerWith(
            fetcher = { _, _, _ ->
                HttpFetchResponse(302, -1L, "https://cdn.example.com/payload.apk", null)
            },
        )
        val nonProfileRedirect = nonProfileRedirectImporter.importFromUrl("https://example.com/start.navbeam")
        assertTrue(nonProfileRedirect.isFailure)
        assertTrue(nonProfileRedirect.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun enforces64KbContentLengthAndStreamingByteCap() = runBlocking {
        // 1. Oversized Content-Length rejected before reading stream
        var streamReadAttempted = false
        val headerCapImporter = importerWith(
            fetcher = HttpUrlFetcher { _, _, _ ->
                HttpFetchResponse(
                    statusCode = 200,
                    contentLength = (DashProfileJsonCodec.MAX_PROFILE_BYTES + 1).toLong(),
                    locationHeader = null,
                    bodyStream = object : ByteArrayInputStream(validProfileJson.toByteArray()) {
                        override fun read(b: ByteArray?, off: Int, len: Int): Int {
                            streamReadAttempted = true
                            return super.read(b, off, len)
                        }
                    },
                )
            },
        )
        val headerResult = headerCapImporter.importFromUrl("https://example.com/huge.navbeam")
        assertTrue(headerResult.isFailure)
        assertTrue(headerResult.exceptionOrNull() is SecurityException)
        assertFalse("Stream should not be read when Content-Length exceeds 64 KB", streamReadAttempted)

        // 2. Chunked response (Content-Length = -1) > 64 KB throws SecurityException during stream read
        val oversizedChunk = ByteArray(DashProfileJsonCodec.MAX_PROFILE_BYTES + 1) { ' '.code.toByte() }
        val streamCapImporter = importerWith(
            fetcher = HttpUrlFetcher { _, _, _ ->
                HttpFetchResponse(
                    statusCode = 200,
                    contentLength = -1L,
                    locationHeader = null,
                    bodyStream = ByteArrayInputStream(oversizedChunk),
                )
            },
        )
        val streamResult = streamCapImporter.importFromUrl("https://example.com/chunked.navbeam")
        assertTrue(streamResult.isFailure)
        assertTrue(streamResult.exceptionOrNull() is SecurityException)
    }

    @Test
    fun validatesImportedProfileThroughCodecAndValidator() = runBlocking {
        val importer = importerWith()
        val ok = importer.importFromUrl("https://github.com/SaltMango/NavBeam/blob/main/DashProfile.json")
        assertTrue("Valid GitHub blob URL should import cleanly: ${ok.exceptionOrNull()}", ok.isSuccess)
        assertEquals("url_test_dash", ok.getOrThrow().profile.id)
        assertEquals("raw.githubusercontent.com/SaltMango/NavBeam/main/DashProfile.json", ok.getOrThrow().sourceLabel)

        // Invalid profile JSON (public WAN gateway IP) must fail DashProfileValidator
        val maliciousJson = validProfileJson.replace("\"gatewayIp\": \"10.0.0.1\"", "\"gatewayIp\": \"8.8.8.8\"")
        val badProfileImporter = importerWith(
            fetcher = HttpUrlFetcher { _, _, _ ->
                HttpFetchResponse(
                    statusCode = 200,
                    contentLength = maliciousJson.toByteArray(Charsets.UTF_8).size.toLong(),
                    locationHeader = null,
                    bodyStream = ByteArrayInputStream(maliciousJson.toByteArray(Charsets.UTF_8)),
                )
            },
        )
        val badRes = badProfileImporter.importFromUrl("https://example.com/evil.navbeam")
        assertTrue(badRes.isFailure)
        assertTrue(badRes.exceptionOrNull() is SecurityException)
    }
}
