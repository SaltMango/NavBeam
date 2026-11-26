package io.github.saltmango.navbeam.dash

import io.github.saltmango.navbeam.dash.nav.GeoPoint
import io.github.saltmango.navbeam.dash.nav.Maneuver
import io.github.saltmango.navbeam.dash.nav.ManeuverType
import io.github.saltmango.navbeam.dash.profile.BurstItem
import io.github.saltmango.navbeam.dash.profile.CommandProtocolConfig
import io.github.saltmango.navbeam.dash.profile.DashProfile
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.dash.profile.DisplayConfig
import io.github.saltmango.navbeam.dash.profile.DisplayShape
import io.github.saltmango.navbeam.dash.profile.HeartbeatItem
import io.github.saltmango.navbeam.dash.profile.NavTagsConfig
import io.github.saltmango.navbeam.dash.profile.NetworkConfig
import io.github.saltmango.navbeam.dash.profile.ProtocolConfig
import io.github.saltmango.navbeam.dash.profile.TlvSpec
import io.github.saltmango.navbeam.dash.profile.TlvTag
import io.github.saltmango.navbeam.dash.protocol.DashCommands
import io.github.saltmango.navbeam.dash.protocol.DashPacket
import io.github.saltmango.navbeam.dash.video.NalProcessor
import io.github.saltmango.navbeam.dash.video.RtpPacketizer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DashProtocolAndVideoTest {

    private fun testProfile(): DashProfile = DashProfile(
        id = "proto_test",
        name = "Protocol Test Profile",
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
            forceSpsBaseline = true,
            dropSeiAndAud = true,
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
        commands = CommandProtocolConfig(
            authRequest = TlvSpec(0x00, 0x00, byteArrayOf(0x01)),
            authSendKeyTag = TlvTag(0x00, 0x01),
            authModulusTag = TlvTag(0x00, 0x02),
            authExponentTag = TlvTag(0x00, 0x03),
            authStatusTag = TlvTag(0x00, 0x04),
            authConfirmByte = 0x01,
            hostnameTag = TlvTag(0x00, 0x05),
            timeSyncTag = TlvTag(0x00, 0x06),
            initialBurst = listOf(
                listOf(BurstItem.AuthRequest, BurstItem.Hostname),
                listOf(BurstItem.Static(TlvSpec(0x00, 0x09, byteArrayOf(0x7F))))
            ),
            heartbeat = listOf(
                HeartbeatItem.Temperature,
                HeartbeatItem.Static(TlvSpec(0x00, 0x07, byteArrayOf(0x42)))
            ),
            heartbeatTempTag = TlvTag(0x00, 0x08),
            heartbeatTempOffset = 10,
            heartbeatDefaultTempC = 25,
            navContext = listOf(TlvSpec(0x01, 0x00, byteArrayOf(0x01))),
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
        ),
        maneuvers = mapOf(
            "DESTINATION" to 0x00,
            "STRAIGHT" to 0x10,
            "TURN_LEFT" to 0x11,
            "TURN_RIGHT" to 0x12,
            "UTURN" to 0x13,
            "DEPART" to 0x14,
            "BLANK" to 0x15,
            "UNKNOWN" to 0x16,
        ),
    )

    @After
    fun tearDown() {
        DashProfileManager.setActiveProfileForTest(DashProfile())
    }

    @Test
    fun packetBuildAndPatchSeq_useActiveProfileHeaderAndMagic() {
        DashProfileManager.setActiveProfileForTest(testProfile())

        val pkt = DashPacket.build(DashPacket.tlv(0x08, 0x04, 0x01))
        val patched = DashPacket.patchSeq(pkt, 0x2A)

        // outer_len (2B) + seg_count (2B) + fixedHeader (8B) + magic (4B) + seq (1B) + TLV (5B) = 22B
        assertEquals(22, patched.size)
        assertEquals(0x00, patched[0].toInt() and 0xFF)
        assertEquals(22, patched[1].toInt() and 0xFF)
        assertEquals(0x00, patched[2].toInt() and 0xFF)
        assertEquals(2, patched[3].toInt() and 0xFF)

        // fixedHeaderHex = 0102030405060708
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            patched.copyOfRange(4, 12)
        )
        // magic = "TEST"
        assertEquals("TEST", String(patched.copyOfRange(12, 16), Charsets.US_ASCII))
        // seq = 0x2A right after magic
        assertEquals(0x2A, patched[16].toInt() and 0xFF)
    }

    @Test
    fun parseIncoming_usesConfiguredHeaderOffset() {
        val raw = byteArrayOf(
            0x00, 15, // outer len
            0x00, 1,  // 1 TLV segment
            0xAA.toByte(), 0xBB.toByte(), // custom 2B header -> total offset 6
            0x03, 0x01, 0x00, 0x01, 0x14 // TLV 03:01 = 0x14
        )
        val tlvs = DashPacket.parseIncoming(raw, headerOffset = 6)
        assertEquals(1, tlvs.size)
        assertEquals(0x03, tlvs[0].type)
        assertEquals(0x01, tlvs[0].sub)
        assertArrayEquals(byteArrayOf(0x14), tlvs[0].value)
    }

    @Test
    fun commandsAndRouteCard_drivenByProfileConfig() {
        DashProfileManager.setActiveProfileForTest(testProfile())

        val burst = DashCommands.initialBurst(hostname = "RiderPhone")
        assertEquals(2, burst.size)
        assertTrue(DashPacket.indexOfBytes(burst[1], byteArrayOf(0x00, 0x09, 0x00, 0x01, 0x7F)) >= 0)

        val hb = DashCommands.heartbeat(tempC = 25)
        // temp byte = 25 + 10 = 35 (0x23) under tag 00:08
        assertTrue(DashPacket.indexOfBytes(hb, byteArrayOf(0x00, 0x08, 0x00, 0x01, 0x23)) >= 0)

        val pkt = DashCommands.routeCard("TestDest", projectionOn = true, primaryDist = 0x0123)
        // primaryDistTag = 10:04 -> 10 04 00 02 01 23
        val target = byteArrayOf(0x10, 0x04, 0x00, 0x02, 0x01, 0x23)
        assertTrue(
            "routeCard with primaryDist must use configured primaryDistTag",
            DashPacket.indexOfBytes(pkt, target) >= 0
        )
    }

    @Test
    fun maneuverLookup_fallsBackGracefullyForPartialMaps() {
        DashProfileManager.setActiveProfileForTest(testProfile())

        val slightLeft = Maneuver(
            type = ManeuverType.SLIGHT_LEFT,
            instruction = "Turn slight left",
            location = GeoPoint(0.0, 0.0),
            cumulativeMeters = 100.0,
            rawGoogleManeuver = "TURN_SLIGHT_LEFT"
        )
        assertEquals(0x11, slightLeft.dashCode)

        val onRampRight = Maneuver(
            type = ManeuverType.SLIGHT_RIGHT,
            instruction = "Take ramp right",
            location = GeoPoint(0.0, 0.0),
            cumulativeMeters = 200.0,
            rawGoogleManeuver = "ON_RAMP_RIGHT"
        )
        assertEquals(0x12, onRampRight.dashCode)

        val arrive = Maneuver(
            type = ManeuverType.ARRIVE,
            instruction = "Arrive",
            location = GeoPoint(0.0, 0.0),
            cumulativeMeters = 300.0,
            rawGoogleManeuver = "DESTINATION"
        )
        assertEquals(0x00, arrive.dashCode)
    }

    @Test
    fun nalProcessor_respectsFlagsAndLevelIdc() {
        val bundledOut = mutableListOf<Pair<ByteArray, Boolean>>()
        val bundledProc = NalProcessor(
            prependSpsPpsToIdr = true,
            forceSpsBaseline = true,
            dropSeiAndAud = true,
            h264LevelIdc = NalProcessor.levelIdcFromString("3.1"),
        ) { nal, isKey -> bundledOut += nal to isKey }

        // Annex-B with SEI (6), AUD (9), SPS (7), PPS (8), IDR (5)
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x06, 0x01,
            0, 0, 0, 1, 0x09, 0x10,
            0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29,
            0, 0, 0, 1, 0x68, 0xCE.toByte(),
            0, 0, 0, 1, 0x65, 0x88.toByte(),
        )
        bundledProc.process(stream)
        assertEquals(1, bundledOut.size)
        assertTrue(bundledOut[0].second)
        // Rewritten constraint byte = 0x00, level_idc = 31 (0x1F)
        assertEquals(0x00, bundledOut[0].first[2].toInt() and 0xFF)
        assertEquals(31, bundledOut[0].first[3].toInt() and 0xFF)

        // When flags are false, SEI + AUD + SPS + PPS + IDR are preserved individually
        val standaloneOut = mutableListOf<Pair<ByteArray, Boolean>>()
        val standaloneProc = NalProcessor(
            prependSpsPpsToIdr = false,
            forceSpsBaseline = false,
            dropSeiAndAud = false,
            h264LevelIdc = NalProcessor.levelIdcFromString("4.0"),
        ) { nal, isKey -> standaloneOut += nal to isKey }

        standaloneProc.process(stream)
        assertEquals(5, standaloneOut.size)
        // Original SPS constraint_set byte 0xC0 and level 0x29 preserved when forceSpsBaseline = false
        assertEquals(0xC0, standaloneOut[2].first[2].toInt() and 0xFF)
        assertEquals(0x29, standaloneOut[2].first[3].toInt() and 0xFF)
        assertTrue(standaloneOut[4].second)
    }

    @Test
    fun rtpPacketizer_usesConfiguredPayloadTypeAndMtu() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer(maxPayload = 300, payloadType = 102) { buf, len ->
            packets += buf.copyOf(len)
        }
        val nal = ByteArray(700) { idx -> (idx and 0xFF).toByte() }.apply {
            this[0] = 0x65 // IDR slice
        }
        packetizer.packetize(nal, endOfAU = true, wallClockMs = 100L)

        // 700B NAL split into FU-A chunks of <= 298B payload (maxPayload=300 minus 2B FU header) -> 3 packets
        assertEquals(3, packets.size)
        // First two packets: marker=0, pt=102
        assertEquals(102, packets[0][1].toInt() and 0xFF)
        assertEquals(102, packets[1][1].toInt() and 0xFF)
        // Final packet: marker=1 (0x80) | pt=102
        assertEquals(0x80 or 102, packets[2][1].toInt() and 0xFF)
        assertTrue(packets.all { it.size <= 312 })
    }

    @Test
    fun dashSocket_trustedControlSenderMatchesExactDashIpOnly() {
        val dash = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val sameDash = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val otherLan = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 42))
        val loopback = java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val unspecified = java.net.InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))

        assertTrue(DashSocket.isTrustedControlSender(sameDash, dash))
        assertFalse(DashSocket.isTrustedControlSender(otherLan, dash))
        assertFalse(DashSocket.isTrustedControlSender(loopback, dash))
        assertFalse(DashSocket.isTrustedControlSender(unspecified, dash))
        assertFalse(DashSocket.isTrustedControlSender(null, dash))
    }

    @Test
    fun dashAuth_rejectsWeakMalformedAndOversizedRsaKeys() {
        DashProfileManager.setActiveProfileForTest(testProfile())
        val auth = DashAuth("TEST_BIKE")

        // Valid 1024-bit odd modulus + e=65537 passes parameter check
        val kpg1024 = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
        val pub1024 = kpg1024.generateKeyPair().public as java.security.interfaces.RSAPublicKey
        assertTrue(DashAuth.isValidRsaParameters(pub1024.modulus, pub1024.publicExponent))

        // Weak 512-bit modulus rejected
        val kpg512 = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(512) }
        val pub512 = kpg512.generateKeyPair().public as java.security.interfaces.RSAPublicKey
        assertFalse(DashAuth.isValidRsaParameters(pub512.modulus, pub512.publicExponent))

        // Feed weak 512-bit modulus + exponent through DashAuth.ingest -> must NOT emit SendKey or set sessionKey
        val modEvent = auth.ingest(DashPacket.tlv(0x00, 0x02, pub512.modulus.toByteArray()))
        val expEvent = auth.ingest(DashPacket.tlv(0x00, 0x03, pub512.publicExponent.toByteArray()))
        assertTrue(modEvent is AuthEvent.None)
        assertTrue(expEvent is AuthEvent.None)
        assertNull(auth.sessionKey)

        // Even modulus / e=1 / oversized modulus rejected
        val evenMod = java.math.BigInteger.ONE.shiftLeft(1024)
        assertFalse(DashAuth.isValidRsaParameters(evenMod, java.math.BigInteger.valueOf(65537L)))
        assertFalse(DashAuth.isValidRsaParameters(pub1024.modulus, java.math.BigInteger.ONE))
        val hugeMod = java.math.BigInteger.ONE.shiftLeft(4096).add(java.math.BigInteger.ONE)
        assertFalse(DashAuth.isValidRsaParameters(hugeMod, java.math.BigInteger.valueOf(65537L)))

        // Valid 1024-bit key succeeds and produces encrypted session key packet
        assertTrue(auth.ingest(DashPacket.tlv(0x00, 0x02, pub1024.modulus.toByteArray())) is AuthEvent.None)
        val validSend = auth.ingest(DashPacket.tlv(0x00, 0x03, pub1024.publicExponent.toByteArray()))
        assertTrue(validSend is AuthEvent.SendKey)
        assertEquals(32, auth.sessionKey?.size)
    }

    @Test
    fun parseIncoming_capsMaximumParsedTlvs() {
        val tlvCount = 100
        val out = java.io.ByteArrayOutputStream()
        out.write(0); out.write(0)
        out.write(0); out.write(tlvCount)
        out.write(ByteArray(4)) // headerOffset=8 -> 4 bytes after segCount
        repeat(tlvCount) {
            out.write(0x03); out.write(0x01); out.write(0x00); out.write(0x01); out.write(0x14)
        }
        val parsed = DashPacket.parseIncoming(out.toByteArray(), headerOffset = 8)
        assertEquals(DashPacket.MAX_INCOMING_TLVS, parsed.size)
    }
}
