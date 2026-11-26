package io.github.saltmango.navbeam.dash.profile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DashProfileSecurityTest {

    private fun validCommands(): CommandProtocolConfig = CommandProtocolConfig(
        authRequest = TlvSpec(0x00, 0x00, byteArrayOf(0x01)),
        authSendKeyTag = TlvTag(0x00, 0x01),
        authModulusTag = TlvTag(0x00, 0x02),
        authExponentTag = TlvTag(0x00, 0x03),
        authStatusTag = TlvTag(0x00, 0x04),
        authConfirmByte = 0x01,
        hostnameTag = TlvTag(0x00, 0x05),
        timeSyncTag = TlvTag(0x00, 0x06),
        initialBurst = listOf(listOf(BurstItem.Hostname, BurstItem.TimeSync)),
        heartbeat = listOf(HeartbeatItem.Temperature, HeartbeatItem.Static(TlvSpec(0x00, 0x07, byteArrayOf(0x00)))),
        heartbeatTempTag = TlvTag(0x00, 0x08),
        heartbeatTempOffset = 0,
        heartbeatDefaultTempC = 25,
        navContext = emptyList(),
        emptyLists = emptyList(),
        navStart = listOf(TlvSpec(0x01, 0x01, byteArrayOf(0x01))),
        navPlaceholder = emptyList(),
        projectionFrame = listOf(TlvSpec(0x01, 0x02, byteArrayOf(0x01))),
        projectionOn = listOf(TlvSpec(0x01, 0x03, byteArrayOf(0x01))),
        projectionStop = listOf(TlvSpec(0x01, 0x02, byteArrayOf(0x00))),
        projectionOff = listOf(TlvSpec(0x01, 0x03, byteArrayOf(0x00))),
        frameDecodedIdrNotify = TlvSpec(0x02, 0x01, byteArrayOf(0x01)),
        frameDecodedIdrAck = listOf(TlvSpec(0x02, 0x02, byteArrayOf(0x01))),
        frameDecodedPNotify = TlvSpec(0x02, 0x03, byteArrayOf(0x01)),
        frameDecodedPAck = listOf(TlvSpec(0x02, 0x04, byteArrayOf(0x01))),
        buttonEventTag = TlvTag(0x03, 0x01),
        buttonAckTag = TlvTag(0x03, 0x02),
        telemetryEncryptedType = 0x0F,
        telemetryPlainType = 0x0C,
        nowPlayingTag = TlvTag(0x04, 0x01),
        callNotifyTag = TlvTag(0x04, 0x02),
        albumArtTag = TlvTag(0x04, 0x03),
        navTags = NavTagsConfig(
            titleTag = TlvTag(0x10, 0x01),
            primaryManeuverTag = TlvTag(0x10, 0x02),
            secondaryManeuverTag = TlvTag(0x10, 0x03),
            primaryDistTag = TlvTag(0x10, 0x04),
            secondaryDistTag = TlvTag(0x10, 0x05),
            primaryUnitTag = TlvTag(0x10, 0x06),
            secondaryUnitTag = TlvTag(0x10, 0x07),
            etaTag = TlvTag(0x10, 0x08),
            totalDistTag = TlvTag(0x10, 0x09),
            totalUnitTag = TlvTag(0x10, 0x0A),
            unitMetersByte = 0x01,
            unitKmTenthsByte = 0x02,
            projectionFlagTag = TlvTag(0x10, 0x0B),
            projectionFlagOnByte = 0x01,
            projectionFlagOffByte = 0x00,
        ),
    )

    private fun validProfile(): DashProfile = DashProfile(
        id = "test_profile",
        name = "Test Profile",
        version = 1,
        network = NetworkConfig(
            defaultSsidPrefix = "TEST_",
            defaultPassword = "password123",
            gatewayIp = "10.0.0.1",
            controlTxPort = 3000,
            controlRxPort = 3002,
            videoRtpPort = 6000,
        ),
        display = DisplayConfig(
            width = 640,
            height = 480,
            shape = DisplayShape.RECTANGLE,
            frameDurationActiveMs = 250L,
            frameDurationIdleMs = 500L,
            motionHoldMs = 1500L,
            activeBitrateBps = 200_000,
            idleBitrateBps = 100_000,
            h264Level = "4.1",
            prependSpsPpsToIdr = true,
            forceSpsBaseline = false,
            dropSeiAndAud = false,
            rtpMaxPayloadBytes = 1380,
            rtpPayloadType = 96,
        ),
        protocol = ProtocolConfig(
            magic = "TEST",
            fixedHeaderHex = "0102030405060708",
            incomingHeaderOffset = 8,
            authType = "RSA_AES_256",
            projKeepAliveMs = 250L,
            routeCardKeepAliveMs = 1000L,
            authTimeoutMs = 15000L,
            maxAuthAttempts = 4,
            burstPauseMs = 20L,
            rxWatchdogMs = 6000L,
            rsaCiphertextBytes = 128,
            maxRouteTitleBytes = 60,
            maxMediaFieldChars = 20,
        ),
        commands = validCommands(),
    )

    @Test
    fun rejectsSizeBombOver64KB() {
        val oversizedData = ByteArray(65537) { 'A'.code.toByte() }
        val stream = ByteArrayInputStream(oversizedData)
        val result = DashProfileJsonCodec.fromStream(stream)
        assertTrue("Oversized stream must fail", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun rejectsPublicWanIps() {
        listOf("8.8.8.8", "1.1.1.1", "142.250.190.46", "208.67.222.222").forEach { wanIp ->
            val profile = validProfile().copy(
                network = validProfile().network.copy(gatewayIp = wanIp)
            )
            val thrown = runCatching { DashProfileValidator.validate(profile) }
            assertTrue("Public WAN IP $wanIp must be rejected", thrown.isFailure)
            assertTrue(thrown.exceptionOrNull() is SecurityException)
        }
    }

    @Test
    fun rejectsHostnamesWithoutDnsLookup() {
        listOf("google.com", "dash.local", "http://10.0.0.1", "localhost").forEach { host ->
            val profile = validProfile().copy(
                network = validProfile().network.copy(gatewayIp = host)
            )
            val thrown = runCatching { DashProfileValidator.validate(profile) }
            assertTrue("Hostname '$host' must be rejected as invalid IPv4", thrown.isFailure)
            assertTrue(thrown.exceptionOrNull() is SecurityException)
        }
    }

    @Test
    fun rejectsCloudMetadataAndLinkLocal() {
        listOf("169.254.169.254", "169.254.1.1").forEach { ip ->
            val profile = validProfile().copy(
                network = validProfile().network.copy(gatewayIp = ip)
            )
            val thrown = runCatching { DashProfileValidator.validate(profile) }
            assertTrue("Link-local/metadata IP $ip must be rejected", thrown.isFailure)
            assertTrue(thrown.exceptionOrNull() is SecurityException)
        }
    }

    @Test
    fun rejectsZeroAndMulticastIps() {
        listOf("0.0.0.0", "224.0.0.1", "239.255.255.250").forEach { ip ->
            val profile = validProfile().copy(
                network = validProfile().network.copy(gatewayIp = ip)
            )
            val thrown = runCatching { DashProfileValidator.validate(profile) }
            assertTrue("IP $ip must be rejected", thrown.isFailure)
            assertTrue(thrown.exceptionOrNull() is SecurityException)
        }
    }

    @Test
    fun allowsValidPrivateIps() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.10.1", "127.0.0.1").forEach { ip ->
            val profile = validProfile().copy(
                network = validProfile().network.copy(gatewayIp = ip)
            )
            val result = runCatching { DashProfileValidator.validate(profile) }
            assertTrue("Private IP $ip must be allowed: ${result.exceptionOrNull()}", result.isSuccess)
        }
    }

    @Test
    fun rejectsPrivilegedPorts() {
        listOf(80, 443, 53, 22, 1023).forEach { port ->
            val profile = validProfile().copy(
                network = validProfile().network.copy(controlTxPort = port)
            )
            val thrown = runCatching { DashProfileValidator.validate(profile) }
            assertTrue("Privileged port $port must be rejected", thrown.isFailure)
        }
    }

    @Test
    fun rejectsDuplicatePorts() {
        val profile = validProfile().copy(
            network = validProfile().network.copy(controlTxPort = 3000, controlRxPort = 3000)
        )
        val thrown = runCatching { DashProfileValidator.validate(profile) }
        assertTrue("Duplicate ports must be rejected", thrown.isFailure)
    }

    @Test
    fun rejectsOddAndOutOfBoundsResolutions() {
        val oddWidth = validProfile().copy(display = validProfile().display.copy(width = 641))
        assertTrue("Odd width must be rejected", runCatching { DashProfileValidator.validate(oddWidth) }.isFailure)

        val oddHeight = validProfile().copy(display = validProfile().display.copy(height = 481))
        assertTrue("Odd height must be rejected", runCatching { DashProfileValidator.validate(oddHeight) }.isFailure)

        val tiny = validProfile().copy(display = validProfile().display.copy(width = 64, height = 64))
        assertTrue("Under-bound resolution must be rejected", runCatching { DashProfileValidator.validate(tiny) }.isFailure)

        val huge = validProfile().copy(display = validProfile().display.copy(width = 3840, height = 2160))
        assertTrue("Over-bound resolution must be rejected", runCatching { DashProfileValidator.validate(huge) }.isFailure)
    }

    @Test
    fun rejectsPathTraversalIds() {
        listOf("../evil", "../../etc/passwd", "bad/id", "id with spaces", "UPPERCASE_ID").forEach { badId ->
            val profile = validProfile().copy(id = badId)
            val thrown = runCatching { DashProfileValidator.validate(profile) }
            assertTrue("ID '$badId' must be rejected", thrown.isFailure)
        }
    }

    @Test
    fun rejectsInvalidMagicAndFixedHeader() {
        val badMagic = validProfile().copy(protocol = validProfile().protocol.copy(magic = "TST"))
        assertTrue("3-char magic must be rejected", runCatching { DashProfileValidator.validate(badMagic) }.isFailure)

        val badHeader = validProfile().copy(protocol = validProfile().protocol.copy(fixedHeaderHex = "ZZZZ"))
        assertTrue("Non-hex fixed header must be rejected", runCatching { DashProfileValidator.validate(badHeader) }.isFailure)
    }

    @Test
    fun rejectsInvalidRtpAndProtocolBounds() {
        val badPayload = validProfile().copy(display = validProfile().display.copy(rtpMaxPayloadBytes = 100))
        assertTrue("Under-bound rtpMaxPayloadBytes must be rejected", runCatching { DashProfileValidator.validate(badPayload) }.isFailure)

        val badPt = validProfile().copy(display = validProfile().display.copy(rtpPayloadType = 33))
        assertTrue("Non-dynamic rtpPayloadType must be rejected", runCatching { DashProfileValidator.validate(badPt) }.isFailure)

        val badOffset = validProfile().copy(protocol = validProfile().protocol.copy(incomingHeaderOffset = 2))
        assertTrue("incomingHeaderOffset < 4 must be rejected", runCatching { DashProfileValidator.validate(badOffset) }.isFailure)

        val badAuth = validProfile().copy(protocol = validProfile().protocol.copy(authType = "WEP"))
        assertTrue("Unsupported authType must be rejected", runCatching { DashProfileValidator.validate(badAuth) }.isFailure)
    }

    @Test
    fun rejectsInvalidCommandsAndTlvTags() {
        val emptyHeartbeat = validProfile().copy(commands = validCommands().copy(heartbeat = emptyList()))
        assertTrue("Empty heartbeat must be rejected", runCatching { DashProfileValidator.validate(emptyHeartbeat) }.isFailure)

        val badTag = validProfile().copy(commands = validCommands().copy(buttonEventTag = TlvTag(-1, 0)))
        assertTrue("Negative TLV tag type must be rejected", runCatching { DashProfileValidator.validate(badTag) }.isFailure)

        val emptyNavStart = validProfile().copy(commands = validCommands().copy(navStart = emptyList()))
        assertTrue("Empty navStart must be rejected", runCatching { DashProfileValidator.validate(emptyNavStart) }.isFailure)
    }

    @Test
    fun rejectsInvalidManeuverAndInputCodes() {
        val badManeuver = validProfile().copy(maneuvers = mapOf("TURN_LEFT" to 256))
        assertTrue("Maneuver code > 255 must be rejected", runCatching { DashProfileValidator.validate(badManeuver) }.isFailure)

        val badInput = validProfile().copy(inputs = mapOf("callAnswer" to -1))
        assertTrue("Negative input code must be rejected", runCatching { DashProfileValidator.validate(badInput) }.isFailure)

        val unknownAction = validProfile().copy(inputs = mapOf("unknownAction" to 1))
        assertTrue("Unknown action name must be rejected", runCatching { DashProfileValidator.validate(unknownAction) }.isFailure)
    }

    @Test
    fun rejectsNonUtf8BinaryStream() {
        val binaryBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xFF.toByte(), 0xFE.toByte())
        val result = DashProfileJsonCodec.fromStream(ByteArrayInputStream(binaryBytes))
        assertTrue("Invalid UTF-8 binary stream must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun rejectsNulControlAndBidiCharacters() {
        val withControl = validProfile().copy(name = "Evil\nDash")
        assertTrue(
            "Control chars in name must be rejected",
            runCatching { DashProfileValidator.validate(withControl) }.exceptionOrNull() is SecurityException
        )

        val withBidi = validProfile().copy(description = "Safe \u202Espoofed")
        assertTrue(
            "Unicode bidi override must be rejected",
            runCatching { DashProfileValidator.validate(withBidi) }.exceptionOrNull() is SecurityException
        )

        val withNulJson = "{\"id\":\"a\",\"\u0000\":\"b\"}"
        val nulResult = DashProfileJsonCodec.fromJson(withNulJson)
        assertTrue(nulResult.exceptionOrNull() is SecurityException)
    }

    @Test
    fun rejectsLeadingZeroOctetsAndOversizedSsidUtf8() {
        val octalIp = validProfile().copy(
            network = validProfile().network.copy(gatewayIp = "010.0.0.1")
        )
        assertTrue(
            "Leading-zero IPv4 octet must be rejected",
            runCatching { DashProfileValidator.validate(octalIp) }.exceptionOrNull() is SecurityException
        )

        val utf8Ssid = validProfile().copy(
            network = validProfile().network.copy(defaultSsidPrefix = "🔥".repeat(9)) // 36 UTF-8 bytes
        )
        assertTrue(
            "SSID > 32 UTF-8 bytes must be rejected",
            runCatching { DashProfileValidator.validate(utf8Ssid) }.isFailure
        )
    }
}
