package io.github.saltmango.navbeam.dash.profile

import io.github.saltmango.navbeam.util.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.channels.SocketChannel
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

fun interface DnsResolver {
    fun resolveAll(host: String): Array<InetAddress>
}

object SystemDnsResolver : DnsResolver {
    override fun resolveAll(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}

data class HttpFetchResponse(
    val statusCode: Int,
    val contentLength: Long,
    val locationHeader: String?,
    val bodyStream: InputStream?,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        try {
            bodyStream?.close()
        } catch (_: IOException) {
            // Best-effort stream close in cleanup path
        } finally {
            closeAction()
        }
    }
}

fun interface HttpUrlFetcher {
    fun fetch(
        url: URL,
        pinnedAddress: InetAddress,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpFetchResponse
}

object DefaultHttpUrlFetcher : HttpUrlFetcher {
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_HEADER_LINES = 100
    private const val MAX_CHUNK_LINE_CHARS = 128

    override fun fetch(
        url: URL,
        pinnedAddress: InetAddress,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpFetchResponse = fetchWithFactory(
        url = url,
        pinnedAddress = pinnedAddress,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        delegateSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory(),
        hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    )

    internal fun fetchWithFactory(
        url: URL,
        pinnedAddress: InetAddress,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        delegateSocketFactory: SSLSocketFactory,
        hostnameVerifier: HostnameVerifier,
    ): HttpFetchResponse {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw SecurityException("Only HTTPS connections are supported.")
        }
        val expectedHost = url.host.removePrefix("[").removeSuffix("]")
        val port = url.port.takeIf { it != -1 } ?: 443
        val factory = PinnedAddressSSLSocketFactory(
            delegate = delegateSocketFactory,
            expectedHost = expectedHost,
            pinnedAddress = pinnedAddress,
            connectTimeoutMs = connectTimeoutMs,
        )
        val sslSocket = factory.createSocket(expectedHost, port) as? SSLSocket
            ?: throw SecurityException("Expected SSLSocket from pinned factory.")

        var handedOff = false
        try {
            sslSocket.soTimeout = readTimeoutMs
            sslSocket.startHandshake()
            val session = sslSocket.session
            if (!hostnameVerifier.verify(expectedHost, session)) {
                throw SSLPeerUnverifiedException("Hostname verification failed for $expectedHost")
            }

            val targetUri = url.toURI()
            val rawTarget = buildString {
                val path = targetUri.rawPath
                append(if (path.isNullOrEmpty()) "/" else path)
                if (!targetUri.rawQuery.isNullOrEmpty()) {
                    append('?').append(targetUri.rawQuery)
                }
            }
            val hostHeader = formatHostHeader(expectedHost, port)
            val request = buildString {
                append("GET ").append(rawTarget).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("User-Agent: NavBeam-App\r\n")
                append("Accept: application/json, text/plain;q=0.9\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val out = sslSocket.outputStream
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()

            val response = parseHttpResponse(
                input = sslSocket.inputStream,
                closeAction = { sslSocket.close() },
            )
            handedOff = true
            return response
        } finally {
            if (!handedOff) {
                try {
                    sslSocket.close()
                } catch (_: IOException) {
                    // Best-effort close on setup/handshake failure
                }
            }
        }
    }

    internal fun parseHttpResponse(
        input: InputStream,
        closeAction: () -> Unit = {},
    ): HttpFetchResponse {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val headerBlock = readHeaderBlock(buffered)
        val lines = headerBlock.split("\r\n")
        if (lines.isEmpty()) {
            throw IOException("Malformed HTTP response: missing status line.")
        }
        val statusParts = lines[0].split(' ', limit = 3)
        if (statusParts.size < 2 || !statusParts[0].startsWith("HTTP/1.", ignoreCase = true)) {
            throw IOException("Unsupported HTTP status line.")
        }
        val statusCode = statusParts[1].toIntOrNull()
            ?: throw IOException("Invalid HTTP status code.")

        var contentLength = -1L
        var location: String? = null
        var isChunked = false
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "location" -> if (location == null) location = value
                "content-length" -> contentLength = value.toLongOrNull() ?: -1L
                "transfer-encoding" -> if (value.contains("chunked", ignoreCase = true)) isChunked = true
            }
        }

        val bodyStream: InputStream? = if (statusCode in 200..299) {
            when {
                isChunked -> ChunkedBodyInputStream(buffered)
                contentLength >= 0L -> BoundedLengthInputStream(buffered, contentLength)
                else -> buffered
            }
        } else {
            try {
                closeAction()
            } catch (_: IOException) {
                // Non-2xx response closes socket immediately
            }
            null
        }

        return HttpFetchResponse(
            statusCode = statusCode,
            contentLength = contentLength,
            locationHeader = location,
            bodyStream = bodyStream,
            closeAction = closeAction,
        )
    }

    private fun readHeaderBlock(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        var lineCount = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                throw EOFException("Unexpected end of stream while reading HTTP headers.")
            }
            if (out.size() >= MAX_HEADER_BYTES) {
                throw SecurityException("HTTP response headers exceed maximum allowed size.")
            }
            out.write(b)
            when (matched) {
                0 -> if (b == '\r'.code) matched = 1
                1 -> matched = if (b == '\n'.code) { lineCount++; 2 } else if (b == '\r'.code) 1 else 0
                2 -> matched = if (b == '\r'.code) 3 else 0
                3 -> matched = if (b == '\n'.code) { lineCount++; 4 } else if (b == '\r'.code) 1 else 0
            }
            if (lineCount > MAX_HEADER_LINES) {
                throw SecurityException("HTTP response contains too many header lines.")
            }
            if (matched == 4) break
        }
        return out.toByteArray().toString(Charsets.ISO_8859_1).trimEnd('\r', '\n')
    }

    private fun formatHostHeader(host: String, port: Int): String {
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return if (port == 443) formattedHost else "$formattedHost:$port"
    }

    private class BoundedLengthInputStream(
        private val delegate: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = delegate.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = delegate.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }
    }

    private class ChunkedBodyInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        private var remainingInChunk = 0
        private var eof = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (len == 0) return 0
            while (remainingInChunk == 0) {
                val sizeLine = readCrLfLine()
                    ?: throw EOFException("Unexpected EOF in chunked HTTP stream.")
                val hexPart = sizeLine.substringBefore(';').trim()
                val chunkSize = hexPart.toIntOrNull(16)
                    ?: throw IOException("Invalid HTTP chunk size header.")
                if (chunkSize < 0) {
                    throw IOException("Negative HTTP chunk size.")
                }
                if (chunkSize == 0) {
                    eof = true
                    return -1
                }
                remainingInChunk = chunkSize
            }
            val toRead = minOf(len, remainingInChunk)
            val n = delegate.read(b, off, toRead)
            if (n == -1) {
                throw EOFException("Unexpected EOF inside HTTP chunk.")
            }
            remainingInChunk -= n
            if (remainingInChunk == 0) {
                consumeCrLf()
            }
            return n
        }

        private fun readCrLfLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = delegate.read()
                if (b == -1) return if (sb.isEmpty()) null else throw EOFException("Truncated chunk line.")
                if (b == '\r'.code) {
                    val next = delegate.read()
                    if (next != '\n'.code) throw IOException("Invalid CRLF in chunked encoding.")
                    return sb.toString()
                }
                if (sb.length >= MAX_CHUNK_LINE_CHARS) {
                    throw SecurityException("HTTP chunk header line too long.")
                }
                sb.append(b.toChar())
            }
        }

        private fun consumeCrLf() {
            val cr = delegate.read()
            val lf = delegate.read()
            if (cr != '\r'.code || lf != '\n'.code) {
                throw IOException("Missing CRLF after HTTP chunk payload.")
            }
        }
    }
}

internal class PinnedAddressSSLSocketFactory(
    private val delegate: SSLSocketFactory,
    private val expectedHost: String,
    private val pinnedAddress: InetAddress,
    private val connectTimeoutMs: Int,
) : SSLSocketFactory() {

    private val pinnedAddressWithHost: InetAddress =
        InetAddress.getByAddress(expectedHost, pinnedAddress.address)

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket {
        val inner = (delegate.createSocket() as? SSLSocket)
            ?: throw SecurityException("Expected SSLSocket from delegate factory.")
        applyTlsParameters(inner)
        return PinnedSSLSocket(
            delegate = inner,
            pinnedAddressWithHost = pinnedAddressWithHost,
            onBeforeHandshake = { applyTlsParameters(inner) },
        )
    }

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val matchesPinned = s.isConnected && s.inetAddress.address.contentEquals(pinnedAddress.address)
        val targetSocket = if (matchesPinned) {
            s
        } else {
            try {
                s.close()
            } catch (_: IOException) {
                // Best-effort close of unpinned socket
            }
            connectPinnedTcpSocket(port)
        }
        val wrapped = delegate.createSocket(targetSocket, expectedHost, port, if (matchesPinned) autoClose else true)
        if (wrapped is SSLSocket) {
            applyTlsParameters(wrapped)
        }
        return wrapped
    }

    override fun createSocket(host: String, port: Int): Socket {
        val rawSocket = connectPinnedTcpSocket(port)
        return wrapConnectedSocket(rawSocket, port)
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val rawSocket = connectPinnedTcpSocket(port, localHost, localPort)
        return wrapConnectedSocket(rawSocket, port)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val rawSocket = connectPinnedTcpSocket(port)
        return wrapConnectedSocket(rawSocket, port)
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket {
        val rawSocket = connectPinnedTcpSocket(port, localAddress, localPort)
        return wrapConnectedSocket(rawSocket, port)
    }

    private fun connectPinnedTcpSocket(
        port: Int,
        localAddress: InetAddress? = null,
        localPort: Int = 0,
    ): Socket {
        val socket = Socket()
        try {
            if (localAddress != null) {
                socket.bind(InetSocketAddress(localAddress, localPort))
            }
            socket.connect(InetSocketAddress(pinnedAddressWithHost, port), connectTimeoutMs)
            return socket
        } catch (t: Throwable) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Best-effort close on failed connect
            }
            throw t
        }
    }

    private fun wrapConnectedSocket(rawSocket: Socket, port: Int): Socket {
        val wrapped = delegate.createSocket(rawSocket, expectedHost, port, true)
        if (wrapped is SSLSocket) {
            applyTlsParameters(wrapped)
        }
        return wrapped
    }

    private fun applyTlsParameters(sslSocket: SSLSocket) {
        val params = sslSocket.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        if (!isNumericIpLiteral(expectedHost)) {
            params.serverNames = listOf(SNIHostName(expectedHost))
        }
        sslSocket.sslParameters = params
    }

    private fun isNumericIpLiteral(host: String): Boolean {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        return clean.contains(':') || clean.all { it in '0'..'9' || it == '.' }
    }
}

private class PinnedSSLSocket(
    private val delegate: SSLSocket,
    private val pinnedAddressWithHost: InetAddress,
    private val onBeforeHandshake: () -> Unit,
) : SSLSocket() {

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val inetEndpoint = endpoint as? InetSocketAddress
            ?: throw IOException("Unsupported socket address type.")
        onBeforeHandshake()
        delegate.connect(InetSocketAddress(pinnedAddressWithHost, inetEndpoint.port), timeout)
    }

    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
    override fun getInetAddress(): InetAddress = delegate.inetAddress
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun getChannel(): SocketChannel? = delegate.channel
    override fun getInputStream(): InputStream = delegate.inputStream
    override fun getOutputStream(): OutputStream = delegate.outputStream
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
    override fun getSoLinger(): Int = delegate.soLinger
    override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)
    override fun setOOBInline(on: Boolean) { delegate.oobInline = on }
    override fun getOOBInline(): Boolean = delegate.oobInline
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun close() = delegate.close()
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
        delegate.setPerformancePreferences(connectionTime, latency, bandwidth)

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = delegate.enabledCipherSuites
    override fun setEnabledCipherSuites(suites: Array<out String>?) { delegate.enabledCipherSuites = suites }
    override fun getSupportedProtocols(): Array<String> = delegate.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = delegate.enabledProtocols
    override fun setEnabledProtocols(protocols: Array<out String>?) { delegate.enabledProtocols = protocols }
    override fun getSession(): SSLSession = delegate.session
    override fun getHandshakeSession(): SSLSession? = delegate.handshakeSession
    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) =
        delegate.addHandshakeCompletedListener(listener)
    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) =
        delegate.removeHandshakeCompletedListener(listener)
    override fun startHandshake() {
        onBeforeHandshake()
        delegate.startHandshake()
    }
    override fun setUseClientMode(mode: Boolean) { delegate.useClientMode = mode }
    override fun getUseClientMode(): Boolean = delegate.useClientMode
    override fun setNeedClientAuth(need: Boolean) { delegate.needClientAuth = need }
    override fun getNeedClientAuth(): Boolean = delegate.needClientAuth
    override fun setWantClientAuth(want: Boolean) { delegate.wantClientAuth = want }
    override fun getWantClientAuth(): Boolean = delegate.wantClientAuth
    override fun setEnableSessionCreation(flag: Boolean) { delegate.enableSessionCreation = flag }
    override fun getEnableSessionCreation(): Boolean = delegate.enableSessionCreation
    override fun getSSLParameters(): SSLParameters = delegate.sslParameters
    override fun setSSLParameters(params: SSLParameters?) { delegate.sslParameters = params }
}

class ProfileUrlImporter(
    private val dnsResolver: DnsResolver = SystemDnsResolver,
    private val httpFetcher: HttpUrlFetcher = DefaultHttpUrlFetcher,
    private val ioDispatcher: CoroutineDispatcher = AppDispatchers.networking,
) {
    companion object {
        const val MAX_URL_LENGTH = 2048
        const val MAX_REDIRECTS = 2
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val GITHUB_SEGMENT_REGEX = Regex("^[A-Za-z0-9._-]+$")
        private val defaultInstance = ProfileUrlImporter()

        suspend fun importFromUrl(rawUrl: String): Result<ImportedProfilePayload> =
            defaultInstance.importFromUrl(rawUrl)

        fun validateAndNormalizeUrl(rawUrl: String): Result<URL> =
            defaultInstance.validateAndNormalizeUrl(rawUrl)
    }

    fun validateAndNormalizeUrl(rawUrl: String): Result<URL> = runCatching {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Please enter a profile URL.")
        }
        if (trimmed.length > MAX_URL_LENGTH) {
            throw IllegalArgumentException("URL exceeds maximum length of $MAX_URL_LENGTH characters.")
        }
        if (trimmed.any { it.code < 0x20 || it.code == 0x7F || it.isWhitespace() }) {
            throw IllegalArgumentException("URL contains invalid whitespace or control characters.")
        }
        if (trimmed.contains('\\')) {
            throw SecurityException("URL contains disallowed backslash characters.")
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid URL format.")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            throw SecurityException("Only HTTPS URLs are allowed.")
        }
        if (uri.isOpaque) {
            throw IllegalArgumentException("Invalid HTTPS URL.")
        }
        if (uri.userInfo != null || uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true) {
            throw SecurityException("URLs with embedded credentials are not allowed.")
        }

        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("URL must include a valid hostname.")

        if (uri.port != -1 && uri.port != 443) {
            throw SecurityException("Only standard HTTPS port (443) is allowed.")
        }

        val rawPath = uri.rawPath.orEmpty()
        validatePathSegments(rawPath)

        val normalizedUri = if (host == "github.com" || host == "www.github.com") {
            convertGitHubBlobUriIfApplicable(rawPath, uri) ?: uri
        } else {
            uri
        }

        val targetRawPath = normalizedUri.rawPath.orEmpty()
        val lastRawSegment = targetRawPath.substringAfterLast('/')
        val targetFileName = if (lastRawSegment.isNotEmpty()) decodePathSegment(lastRawSegment) else ""
        ProfileFileImporter.validateSupportedExtension(targetFileName)

        URL(normalizedUri.toASCIIString())
    }

    fun validateHostAddress(host: String): InetAddress {
        val lookupHost = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (lookupHost.isEmpty()) {
            throw IllegalArgumentException("URL must include a valid hostname.")
        }
        if (lookupHost == "localhost" ||
            lookupHost.endsWith(".localhost") ||
            lookupHost.endsWith(".local") ||
            lookupHost.endsWith(".internal")
        ) {
            throw SecurityException("Local and private network hosts are not allowed.")
        }

        val addresses = try {
            dnsResolver.resolveAll(lookupHost)
        } catch (_: UnknownHostException) {
            throw IOException("Unable to resolve host '$lookupHost'.")
        }

        if (addresses.isEmpty()) {
            throw SecurityException("Host '$lookupHost' did not resolve to a valid IP address.")
        }

        for (addr in addresses) {
            if (isDisallowedAddress(addr)) {
                throw SecurityException("Connections to private, loopback, link-local, or multicast addresses are not allowed.")
            }
        }
        return addresses.first()
    }

    suspend fun importFromUrl(rawUrl: String): Result<ImportedProfilePayload> = withContext(ioDispatcher) {
        runCatching {
            var currentUrl = validateAndNormalizeUrl(rawUrl).getOrThrow()
            var redirects = 0

            while (true) {
                val pinnedAddress = validateHostAddress(currentUrl.host)

                val nextUrl: URL? = try {
                    httpFetcher.fetch(
                        url = currentUrl,
                        pinnedAddress = pinnedAddress,
                        connectTimeoutMs = CONNECT_TIMEOUT_MS,
                        readTimeoutMs = READ_TIMEOUT_MS,
                    ).use { response ->
                        val status = response.statusCode
                        if (status in REDIRECT_CODES) {
                            if (redirects >= MAX_REDIRECTS) {
                                throw SecurityException("Too many redirects (maximum $MAX_REDIRECTS allowed).")
                            }
                            val location = response.locationHeader?.trim().orEmpty()
                            if (location.isEmpty()) {
                                throw IllegalStateException("Redirect response missing Location header.")
                            }
                            val resolvedLocation = try {
                                currentUrl.toURI().resolve(location).toString()
                            } catch (_: Exception) {
                                throw IllegalArgumentException("Invalid redirect target URL.")
                            }
                            validateAndNormalizeUrl(resolvedLocation).getOrThrow()
                        } else {
                            if (status !in 200..299) {
                                throw IllegalStateException("Download failed (HTTP $status).")
                            }
                            if (response.contentLength > DashProfileJsonCodec.MAX_PROFILE_BYTES) {
                                throw SecurityException(
                                    "Remote profile is too large (${response.contentLength} bytes). Maximum allowed size is 64 KB."
                                )
                            }
                            val stream = response.bodyStream
                                ?: throw IllegalStateException("Server returned an empty response.")

                            val boundedBytes = readBoundedBytes(stream)
                            val profile = DashProfileJsonCodec.fromStream(ByteArrayInputStream(boundedBytes)).getOrThrow()
                            return@withContext Result.success(
                                ImportedProfilePayload(
                                    profile = profile,
                                    sourceLabel = buildSourceLabel(currentUrl),
                                )
                            )
                        }
                    }
                } catch (e: SecurityException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: IllegalStateException) {
                    throw e
                } catch (_: SocketTimeoutException) {
                    throw IOException("Connection timed out while fetching profile.")
                } catch (_: UnknownHostException) {
                    throw IOException("Unable to reach host. Check the URL and your connection.")
                } catch (_: SSLException) {
                    throw SecurityException("Secure HTTPS connection failed.")
                } catch (e: IOException) {
                    if (e.message?.startsWith("Unable to resolve host") == true) throw e
                    throw IOException("Unable to download profile. Check the URL and your connection.")
                }

                if (nextUrl != null) {
                    currentUrl = nextUrl
                    redirects++
                }
            }

            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }
    }

    private fun validatePathSegments(rawPath: String) {
        if (rawPath.isEmpty()) return
        val rawSegments = rawPath.split('/')
        for (rawSeg in rawSegments) {
            if (rawSeg == "." || rawSeg == "..") {
                throw SecurityException("URL path traversal segments are not allowed.")
            }
            val decoded = decodePathSegment(rawSeg)
            if (decoded == "." || decoded == ".." || decoded.contains('/') || decoded.contains('\\')) {
                throw SecurityException("URL path traversal segments are not allowed.")
            }
            if (decoded.any { it.code < 0x20 || it.code == 0x7F }) {
                throw SecurityException("URL path contains disallowed control characters.")
            }
        }
    }

    private fun convertGitHubBlobUriIfApplicable(rawPath: String, originalUri: URI): URI? {
        val segments = rawPath.trimStart('/').split('/')
        if (segments.size < 3 || segments[2] != "blob") {
            return null
        }
        // Must have <owner>/<repo>/blob/<ref>/<path...> with no empty segments
        if (segments.size < 5 || segments.any { it.isEmpty() }) {
            throw IllegalArgumentException(
                "Incomplete GitHub file URL. Expected https://github.com/<owner>/<repo>/blob/<ref>/<path>."
            )
        }
        val owner = decodePathSegment(segments[0])
        val repo = decodePathSegment(segments[1])
        if (!GITHUB_SEGMENT_REGEX.matches(owner) || owner == "." || owner == ".." ||
            !GITHUB_SEGMENT_REGEX.matches(repo) || repo == "." || repo == ".."
        ) {
            throw IllegalArgumentException("Invalid GitHub repository owner or name.")
        }

        val rawTargetPath = "/" + listOf(segments[0], segments[1]).plus(segments.drop(3)).joinToString("/")
        return URI(
            "https",
            null,
            "raw.githubusercontent.com",
            originalUri.port,
            rawTargetPath,
            null,
            null,
        )
    }

    private fun decodePathSegment(segment: String): String {
        if (!segment.contains('%')) return segment
        val bytes = ByteArrayOutputStream(segment.length)
        var i = 0
        while (i < segment.length) {
            val ch = segment[i]
            if (ch == '%') {
                if (i + 2 >= segment.length) {
                    throw IllegalArgumentException("Malformed percent-encoding in URL path.")
                }
                val hi = segment[i + 1].digitToIntOrNull(16)
                    ?: throw IllegalArgumentException("Malformed percent-encoding in URL path.")
                val lo = segment[i + 2].digitToIntOrNull(16)
                    ?: throw IllegalArgumentException("Malformed percent-encoding in URL path.")
                bytes.write((hi shl 4) or lo)
                i += 3
            } else {
                val utf8 = ch.toString().toByteArray(Charsets.UTF_8)
                bytes.write(utf8)
                i++
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun readBoundedBytes(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            total += read
            if (total > DashProfileJsonCodec.MAX_PROFILE_BYTES) {
                throw SecurityException("Remote profile exceeds maximum size limit of 64 KB.")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun buildSourceLabel(url: URL): String {
        val path = url.path.orEmpty().ifBlank { "/" }
        return "${url.host}$path"
    }

    private fun isDisallowedAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        return isDisallowedIpBytes(addr.address)
    }

    private fun isDisallowedIpBytes(bytes: ByteArray): Boolean {
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            return when {
                b0 == 0 -> true                           // 0.0.0.0/8 unspecified
                b0 == 10 -> true                          // 10.0.0.0/8 private
                b0 == 127 -> true                         // 127.0.0.0/8 loopback
                b0 == 169 && b1 == 254 -> true            // 169.254.0.0/16 link-local
                b0 == 172 && b1 in 16..31 -> true         // 172.16.0.0/12 private
                b0 == 192 && b1 == 0 && b2 == 0 -> true   // 192.0.0.0/24 IETF protocol assignments
                b0 == 192 && b1 == 0 && b2 == 2 -> true   // 192.0.2.0/24 TEST-NET-1
                b0 == 192 && b1 == 168 -> true            // 192.168.0.0/16 private
                b0 == 198 && b1 in 18..19 -> true         // 198.18.0.0/15 benchmarking
                b0 == 198 && b1 == 51 && b2 == 100 -> true // 198.51.100.0/24 TEST-NET-2
                b0 == 203 && b1 == 0 && b2 == 113 -> true // 203.0.113.0/24 TEST-NET-3
                b0 == 100 && (b1 and 0xC0) == 64 -> true  // 100.64.0.0/10 CGNAT
                b0 in 224..239 -> true                    // 224.0.0.0/4 multicast
                b0 >= 240 -> true                         // 240.0.0.0/4 reserved & broadcast
                else -> false
            }
        }
        if (bytes.size == 16) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF
            val first15Zero = (0 until 15).all { bytes[it].toInt() == 0 }
            if (first15Zero && (bytes[15].toInt() == 0 || bytes[15].toInt() == 1)) {
                return true // :: and ::1
            }
            val first10Zero = (0 until 10).all { bytes[it].toInt() == 0 }
            val isV4Mapped = first10Zero && (bytes[10].toInt() and 0xFF) == 0xFF && (bytes[11].toInt() and 0xFF) == 0xFF
            val isV4Compat = first10Zero && bytes[10].toInt() == 0 && bytes[11].toInt() == 0
            if (isV4Mapped || isV4Compat) {
                return isDisallowedIpBytes(bytes.copyOfRange(12, 16))
            }
            // NAT64: 64:ff9b::/96 and 64:ff9b:1::/48
            if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B) {
                val bytes4To11Zero = (4 until 12).all { bytes[it].toInt() == 0 }
                val isLocalNat64 = (bytes[4].toInt() and 0xFF) == 0x00 && (bytes[5].toInt() and 0xFF) == 0x01
                if (bytes4To11Zero || isLocalNat64) return true
            }
            // Teredo: 2001:0000::/32
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return true
            // 6to4: 2002::/16
            if (b0 == 0x20 && b1 == 0x02) return true
            // Documentation: 2001:0db8::/32
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0D && b3 == 0xB8) return true
            // Discard-only: 0100::/64
            if (b0 == 0x01 && b1 == 0x00 && (2 until 8).all { bytes[it].toInt() == 0 }) return true
            if ((b0 and 0xFE) == 0xFC) return true                // fc00::/7 ULA
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true  // fe80::/10 link-local
            if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return true  // fec0::/10 site-local
            if (b0 == 0xFF) return true                           // ff00::/8 multicast
            return false
        }
        return true
    }
}
