package io.github.saltmango.navbeam.dash.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.nio.channels.SocketChannel
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
        fetcher: HttpUrlFetcher = HttpUrlFetcher { _, _, _, _ ->
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
            "192.0.0.1" to byteArrayOf(192.toByte(), 0, 0, 1),
            "192.0.2.1" to byteArrayOf(192.toByte(), 0, 2, 1),
            "198.18.0.1" to byteArrayOf(198.toByte(), 18, 0, 1),
            "198.51.100.1" to byteArrayOf(198.toByte(), 51, 100, 1),
            "203.0.113.1" to byteArrayOf(203.toByte(), 0, 113, 1),
            "64:ff9b::10.0.0.1" to ByteArray(16).apply {
                this[1] = 0x64; this[2] = 0xFF.toByte(); this[3] = 0x9B.toByte()
                this[12] = 10; this[15] = 1
            },
            "64:ff9b:1::1" to ByteArray(16).apply {
                this[1] = 0x64; this[2] = 0xFF.toByte(); this[3] = 0x9B.toByte()
                this[5] = 1; this[15] = 1
            },
            "2001:0000::1" to ByteArray(16).apply {
                this[0] = 0x20; this[1] = 0x01; this[15] = 1
            },
            "2002:c0a8:0101::1" to ByteArray(16).apply {
                this[0] = 0x20; this[1] = 0x02; this[2] = 0xC0.toByte(); this[3] = 0xA8.toByte()
                this[15] = 1
            },
            "2001:db8::1" to ByteArray(16).apply {
                this[0] = 0x20; this[1] = 0x01; this[2] = 0x0D; this[3] = 0xB8.toByte(); this[15] = 1
            },
            "100::1" to ByteArray(16).apply { this[0] = 0x01; this[15] = 1 },
        )

        for ((label, rawBytes) in blockedIps) {
            var fetched = false
            val importer = importerWith(
                dns = { host -> arrayOf(InetAddress.getByAddress(host, rawBytes)) },
                fetcher = { _, _, _, _ ->
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
    fun passesValidatedPinnedAddressToFetcherIncludingRedirects() = runBlocking {
        val originIp = InetAddress.getByAddress("origin.example", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))
        val cdnIp = InetAddress.getByAddress("cdn.example", byteArrayOf(151.toByte(), 101.toByte(), 1.toByte(), 69))
        val observed = mutableListOf<Pair<String, InetAddress>>()

        val importer = importerWith(
            dns = { host ->
                when (host) {
                    "origin.example" -> arrayOf(originIp)
                    "cdn.example" -> arrayOf(cdnIp)
                    else -> error("Unexpected DNS lookup for $host")
                }
            },
            fetcher = { url, pinnedAddress, _, _ ->
                observed += url.host to pinnedAddress
                if (url.host == "origin.example") {
                    HttpFetchResponse(302, -1L, "https://cdn.example/profile.navbeam", null)
                } else {
                    HttpFetchResponse(
                        200,
                        validProfileJson.toByteArray(Charsets.UTF_8).size.toLong(),
                        null,
                        ByteArrayInputStream(validProfileJson.toByteArray(Charsets.UTF_8)),
                    )
                }
            },
        )

        val result = importer.importFromUrl("https://origin.example/start.navbeam")
        assertTrue(result.isSuccess)
        assertEquals(listOf("origin.example" to originIp, "cdn.example" to cdnIp), observed)
    }

    @Test
    fun defaultHttpUrlFetcherRejectsNonHttpsUrls() {
        val dummyPinned = InetAddress.getByAddress("example.com", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))
        val thrown = runCatching {
            DefaultHttpUrlFetcher.fetch(
                url = URL("http://example.com/profile.navbeam"),
                pinnedAddress = dummyPinned,
                connectTimeoutMs = 1000,
                readTimeoutMs = 1000,
            )
        }
        assertTrue(thrown.isFailure)
        assertTrue(thrown.exceptionOrNull() is SecurityException)
    }

    @Test
    fun defaultHttpUrlFetcherParsesContentLengthChunkedAndRedirectResponses() {
        val rawFixed = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 5\r\n" +
                "Content-Type: application/json\r\n\r\n" +
                "helloEXTRA"
            ).toByteArray(Charsets.ISO_8859_1)
        DefaultHttpUrlFetcher.parseHttpResponse(ByteArrayInputStream(rawFixed)).use { resp ->
            assertEquals(200, resp.statusCode)
            assertEquals(5L, resp.contentLength)
            assertEquals("hello", resp.bodyStream!!.readBytes().toString(Charsets.UTF_8))
        }

        val rawChunked = (
            "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "5\r\nhello\r\n" +
                "7;ext=1\r\n world!\r\n" +
                "0\r\n\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        DefaultHttpUrlFetcher.parseHttpResponse(ByteArrayInputStream(rawChunked)).use { resp ->
            assertEquals(200, resp.statusCode)
            assertEquals("hello world!", resp.bodyStream!!.readBytes().toString(Charsets.UTF_8))
        }

        var closed = false
        val rawRedirect = (
            "HTTP/1.1 302 Found\r\n" +
                "Location: https://cdn.example.com/next.navbeam\r\n\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        val redirectResp = DefaultHttpUrlFetcher.parseHttpResponse(
            ByteArrayInputStream(rawRedirect),
            closeAction = { closed = true },
        )
        assertEquals(302, redirectResp.statusCode)
        assertEquals("https://cdn.example.com/next.navbeam", redirectResp.locationHeader)
        assertNull(redirectResp.bodyStream)
        assertTrue("Non-2xx response must close connection immediately", closed)
    }

    @Test
    fun defaultHttpUrlFetcherConnectsDirectlyViaPinnedSocketAndVerifiesHostname() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            val capturedRequest = StringBuilder()
            val serverThread = Thread {
                server.accept().use { client ->
                    val reader = client.getInputStream()
                    val buf = ByteArray(1024)
                    val read = reader.read(buf)
                    if (read > 0) {
                        capturedRequest.append(String(buf, 0, read, Charsets.US_ASCII))
                    }
                    val body = "{\"ok\":true}".toByteArray(Charsets.UTF_8)
                    val header = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                    client.getOutputStream().write(header)
                    client.getOutputStream().write(body)
                    client.getOutputStream().flush()
                }
            }.apply { start() }

            val fakeDelegateFactory = object : SSLSocketFactory() {
                override fun getDefaultCipherSuites(): Array<String> = emptyArray()
                override fun getSupportedCipherSuites(): Array<String> = emptyArray()
                override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
                    FakeSSLSocket(s)
                override fun createSocket(host: String, port: Int): Socket = error("Unused")
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = error("Unused")
                override fun createSocket(host: InetAddress, port: Int): Socket = error("Unused")
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = error("Unused")
            }

            DefaultHttpUrlFetcher.fetchWithFactory(
                url = URL("https://raw.githubusercontent.com:${server.localPort}/SaltMango/NavBeam/main/DashProfile.json"),
                pinnedAddress = loopback,
                connectTimeoutMs = 1000,
                readTimeoutMs = 1000,
                delegateSocketFactory = fakeDelegateFactory,
                hostnameVerifier = HostnameVerifier { host, _ -> host == "raw.githubusercontent.com" },
            ).use { resp ->
                assertEquals(200, resp.statusCode)
                assertEquals("{\"ok\":true}", resp.bodyStream!!.readBytes().toString(Charsets.UTF_8))
            }
            serverThread.join(2000)

            val reqText = capturedRequest.toString()
            assertTrue(reqText.startsWith("GET /SaltMango/NavBeam/main/DashProfile.json HTTP/1.1\r\n"))
            assertTrue(reqText.contains("Host: raw.githubusercontent.com:${server.localPort}\r\n"))
            assertTrue(reqText.contains("Accept-Encoding: identity\r\n"))
        }

        // Hostname verification failure must close socket and throw SSLPeerUnverifiedException
        ServerSocket(0, 1, loopback).use { server ->
            val acceptThread = Thread {
                runCatching { server.accept().close() }
            }.apply { start() }

            val fakeDelegateFactory = object : SSLSocketFactory() {
                override fun getDefaultCipherSuites(): Array<String> = emptyArray()
                override fun getSupportedCipherSuites(): Array<String> = emptyArray()
                override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
                    FakeSSLSocket(s)
                override fun createSocket(host: String, port: Int): Socket = error("Unused")
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = error("Unused")
                override fun createSocket(host: InetAddress, port: Int): Socket = error("Unused")
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = error("Unused")
            }

            val rejected = runCatching {
                DefaultHttpUrlFetcher.fetchWithFactory(
                    url = URL("https://raw.githubusercontent.com:${server.localPort}/SaltMango/NavBeam/main/DashProfile.json"),
                    pinnedAddress = loopback,
                    connectTimeoutMs = 1000,
                    readTimeoutMs = 1000,
                    delegateSocketFactory = fakeDelegateFactory,
                    hostnameVerifier = HostnameVerifier { _, _ -> false },
                )
            }
            acceptThread.join(2000)
            assertTrue(rejected.isFailure)
            assertTrue(rejected.exceptionOrNull() is SSLPeerUnverifiedException)
        }
    }

    @Test
    fun pinnedAddressSslSocketFactoryConnectsToPinnedIpAndPreservesSni() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            val recordedHostArgs = mutableListOf<String>()
            var recordedWrappedSocket: Socket? = null
            var recordedParams: SSLParameters? = null

            val recordingDelegate = object : SSLSocketFactory() {
                override fun getDefaultCipherSuites(): Array<String> = emptyArray()
                override fun getSupportedCipherSuites(): Array<String> = emptyArray()
                override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                    recordedHostArgs += host
                    recordedWrappedSocket = s
                    return FakeSSLSocket(s).also { recordedParams = it.sslParameters }
                }
                override fun createSocket(host: String, port: Int): Socket = error("Unused")
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = error("Unused")
                override fun createSocket(host: InetAddress, port: Int): Socket = error("Unused")
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = error("Unused")
            }

            val factory = PinnedAddressSSLSocketFactory(
                delegate = recordingDelegate,
                expectedHost = "raw.githubusercontent.com",
                pinnedAddress = loopback,
                connectTimeoutMs = 1000,
            )

            // 1. Direct host/port call connects to pinned loopback IP without resolving raw.githubusercontent.com
            factory.createSocket("raw.githubusercontent.com", server.localPort).use { sock ->
                assertTrue(sock is SSLSocket)
                assertEquals(loopback, recordedWrappedSocket?.inetAddress)
                assertEquals(listOf("raw.githubusercontent.com"), recordedHostArgs)
                assertEquals("HTTPS", recordedParams?.endpointIdentificationAlgorithm)
                assertEquals(listOf(SNIHostName("raw.githubusercontent.com")), recordedParams?.serverNames)
            }
            server.accept().close()

            // 2. Rebound socket connected to a different address is closed and replaced with pinned loopback socket
            val unpinnedSocket = object : Socket() {
                var closedByFactory = false
                override fun isConnected(): Boolean = true
                override fun getInetAddress(): InetAddress =
                    InetAddress.getByAddress("evil", byteArrayOf(10, 0, 0, 99))
                override fun close() {
                    closedByFactory = true
                }
            }
            factory.createSocket(unpinnedSocket, "raw.githubusercontent.com", server.localPort, true).use {
                assertTrue("Unpinned rebound socket must be closed", unpinnedSocket.closedByFactory)
                assertEquals(loopback, recordedWrappedSocket?.inetAddress)
            }
            server.accept().close()
        }
    }

    @Test
    fun validatesRedirectsAndEnforcesMax2Hops() = runBlocking {
        // 1. Valid 2-hop HTTPS redirect succeeds
        val requestedUrls = mutableListOf<String>()
        val twoHopImporter = importerWith(
            fetcher = HttpUrlFetcher { url, _, _, _ ->
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
            fetcher = HttpUrlFetcher { _, _, _, _ ->
                HttpFetchResponse(302, -1L, "https://cdn.example.com/next.navbeam", null)
            },
        )
        val tooManyHops = threeHopImporter.importFromUrl("https://example.com/start.navbeam")
        assertTrue(tooManyHops.isFailure)
        assertTrue(tooManyHops.exceptionOrNull() is SecurityException)

        // 3. Redirect to HTTP is rejected
        val downgradeImporter = importerWith(
            fetcher = HttpUrlFetcher { _, _, _, _ ->
                HttpFetchResponse(302, -1L, "http://cdn.example.com/profile.navbeam", null)
            },
        )
        val downgrade = downgradeImporter.importFromUrl("https://example.com/start.navbeam")
        assertTrue(downgrade.isFailure)
        assertTrue(downgrade.exceptionOrNull() is SecurityException)

        // 4. Redirect to SSRF target is rejected before fetching redirect target
        val ssrfRedirectImporter = importerWith(
            dns = { host ->
                if (host == "127.0.0.1" || host == "internal.example") {
                    arrayOf(InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 168.toByte(), 1, 10)))
                } else {
                    publicIp(host)
                }
            },
            fetcher = { url, _, _, _ ->
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
            fetcher = { _, _, _, _ ->
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
            fetcher = HttpUrlFetcher { _, _, _, _ ->
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
            fetcher = HttpUrlFetcher { _, _, _, _ ->
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
            fetcher = HttpUrlFetcher { _, _, _, _ ->
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

    private class FakeSSLSocket(private val wrapped: Socket) : SSLSocket() {
        private var params = SSLParameters()
        override fun close() = wrapped.close()
        override fun connect(endpoint: SocketAddress?) {}
        override fun connect(endpoint: SocketAddress?, timeout: Int) {}
        override fun getInputStream(): InputStream = wrapped.getInputStream()
        override fun getOutputStream(): OutputStream = wrapped.getOutputStream()
        override fun getChannel(): SocketChannel? = null
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<out String>?) {}
        override fun getSession(): SSLSession = FakeSslSession()
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun startHandshake() {}
        override fun setUseClientMode(mode: Boolean) {}
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) {}
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) {}
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation(): Boolean = true
        override fun getSSLParameters(): SSLParameters = params
        override fun setSSLParameters(params: SSLParameters?) {
            if (params != null) this.params = params
        }
    }

    private class FakeSslSession : SSLSession {
        override fun getId(): ByteArray = ByteArray(0)
        override fun getSessionContext() = null
        override fun getCreationTime(): Long = 0L
        override fun getLastAccessedTime(): Long = 0L
        override fun invalidate() {}
        override fun isValid(): Boolean = true
        override fun putValue(name: String?, value: Any?) {}
        override fun getValue(name: String?): Any? = null
        override fun removeValue(name: String?) {}
        override fun getValueNames(): Array<String> = emptyArray()
        override fun getPeerCertificates() = emptyArray<java.security.cert.Certificate>()
        @Suppress("DEPRECATION")
        override fun getPeerCertificateChain() = emptyArray<javax.security.cert.X509Certificate>()
        override fun getLocalCertificates() = null
        override fun getPeerPrincipal() = null
        override fun getLocalPrincipal() = null
        override fun getCipherSuite(): String = "TLS_AES_128_GCM_SHA256"
        override fun getProtocol(): String = "TLSv1.3"
        override fun getPeerHost(): String = "raw.githubusercontent.com"
        override fun getPeerPort(): Int = 443
        override fun getPacketBufferSize(): Int = 16384
        override fun getApplicationBufferSize(): Int = 16384
    }
}
