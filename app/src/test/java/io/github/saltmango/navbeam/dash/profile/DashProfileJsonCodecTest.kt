package io.github.saltmango.navbeam.dash.profile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DashProfileJsonCodecTest {

    private val commandsJsonBlock = """
      "commands": {
        "authRequest": "00:00:00",
        "authSendKeyTag": "00:01",
        "authModulusTag": "00:02",
        "authExponentTag": "00:03",
        "authStatusTag": "00:04",
        "authConfirmByte": "0x01",
        "hostnameTag": "00:05",
        "timeSyncTag": "00:06",
        "initialBurst": [
          ["AUTH_REQUEST", "HOSTNAME", "TIME_SYNC"],
          ["00:0A:55"]
        ],
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
    """.trimIndent()

    private val sampleJson = """
    {
      "id": "synthetic_dash",
      "name": "Synthetic Test Dash",
      "version": 1,
      "firmwareVersion": "1.0.0",
      "description": "Synthetic profile fixture for codec unit tests",
      "network": {
        "defaultSsidPrefix": "SYNTH_",
        "defaultPassword": "synthetic_pw",
        "gatewayIp": "10.0.0.1",
        "broadcastIp": "10.0.0.255",
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
        "magic": "SYNT",
        "fixedHeaderHex": "0102030405060708",
        "incomingHeaderOffset": 8,
        "authType": "RSA_AES_256",
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
      $commandsJsonBlock,
      "inputs": {
        "callAnswer": "0x11",
        "callReject": "0x12",
        "mapZoomIn": "0x21",
        "mapZoomOut": "0x22",
        "mediaNext": "0x31",
        "mediaPrev": "0x32"
      },
      "maneuvers": {
        "DESTINATION": "0x00",
        "TURN_LEFT": "0x01",
        "TURN_RIGHT": "0x02",
        "ROUNDABOUT_1": "0x03",
        "DEPART": "0x04"
      }
    }
    """.trimIndent()

    @Test
    fun parseSampleJson_success() {
        val result = DashProfileJsonCodec.fromJson(sampleJson)
        assertTrue("Parsing should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val profile = result.getOrThrow()

        assertEquals("synthetic_dash", profile.id)
        assertEquals("Synthetic Test Dash", profile.name)
        assertEquals("1.0.0", profile.firmwareVersion)
        assertEquals("10.0.0.1", profile.network.gatewayIp)
        assertEquals("10.0.0.255", profile.network.resolvedBroadcastIp)
        assertEquals(3000, profile.network.controlTxPort)
        assertEquals(3002, profile.network.controlRxPort)
        assertEquals(6000, profile.network.videoRtpPort)

        assertEquals(640, profile.display.width)
        assertEquals(480, profile.display.height)
        assertEquals(DisplayShape.CIRCLE, profile.display.shape)
        assertEquals(250L, profile.display.frameDurationActiveMs)
        assertFalse(profile.display.forceSpsBaseline)
        assertTrue(profile.display.dropSeiAndAud)
        assertEquals(1380, profile.display.rtpMaxPayloadBytes)
        assertEquals(96, profile.display.rtpPayloadType)

        assertEquals("SYNT", profile.protocol.magic)
        assertEquals("0102030405060708", profile.protocol.fixedHeaderHex)
        assertEquals(8, profile.protocol.incomingHeaderOffset)
        assertEquals(20L, profile.protocol.burstPauseMs)
        assertEquals(6000L, profile.protocol.rxWatchdogMs)

        assertEquals(2, profile.commands.initialBurst.size)
        assertEquals(TlvTag(0x10, 0x02), profile.commands.navTags.primaryManeuverTag)

        assertEquals(0x11, profile.inputs["callAnswer"])
        assertEquals(0x21, profile.inputs["mapZoomIn"])
        assertEquals(0x00, profile.maneuvers["DESTINATION"])
        assertEquals(0x01, profile.maneuvers["TURN_LEFT"])
    }

    @Test
    fun roundTripSerialization_matchesOriginal() {
        val original = DashProfileJsonCodec.fromJson(sampleJson).getOrThrow()
        val jsonOutput = DashProfileJsonCodec.toJson(original)

        val deserialized = DashProfileJsonCodec.fromJson(jsonOutput).getOrThrow()
        assertEquals(original.id, deserialized.id)
        assertEquals(original.name, deserialized.name)
        assertEquals(original.network, deserialized.network)
        assertEquals(original.display, deserialized.display)
        assertEquals(original.protocol, deserialized.protocol)
        assertEquals(original.commands, deserialized.commands)
        assertEquals(original.inputs, deserialized.inputs)
        assertEquals(original.maneuvers, deserialized.maneuvers)
    }

    @Test
    fun fromStream_readsCorrectly() {
        val stream = ByteArrayInputStream(sampleJson.toByteArray(Charsets.UTF_8))
        val result = DashProfileJsonCodec.fromStream(stream)
        assertTrue(result.isSuccess)
        assertEquals("synthetic_dash", result.getOrThrow().id)
    }

    @Test
    fun jsonNullOptionalStrings_doNotBecomeLiteralNull() {
        val jsonWithNulls = """
        {
          "id": "null_fields_dash",
          "name": "Null Fields Dash",
          "version": 1,
          "firmwareVersion": null,
          "description": null,
          "network": {
            "defaultSsidPrefix": "DEV_",
            "defaultPassword": "password123",
            "gatewayIp": "10.0.0.1",
            "broadcastIp": null,
            "controlTxPort": 3000,
            "controlRxPort": 3002,
            "videoRtpPort": 6000
          },
          "display": {
            "width": 640,
            "height": 480,
            "shape": "RECTANGLE",
            "frameDurationActiveMs": 250,
            "frameDurationIdleMs": 500,
            "motionHoldMs": 1500,
            "activeBitrateBps": 200000,
            "idleBitrateBps": 100000,
            "h264Level": "4.1",
            "prependSpsPpsToIdr": true,
            "forceSpsBaseline": false,
            "dropSeiAndAud": false,
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
          $commandsJsonBlock
        }
        """.trimIndent()

        val result = DashProfileJsonCodec.fromJson(jsonWithNulls)
        assertTrue("Parsing JSON with explicit null metadata must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val profile = result.getOrThrow()

        assertEquals("", profile.firmwareVersion)
        assertEquals("", profile.description)
        assertNull(profile.network.broadcastIp)
        assertEquals("10.0.0.255", profile.network.resolvedBroadcastIp)
        assertEquals(DisplayShape.RECTANGLE, profile.display.shape)
        assertEquals("NONE", profile.protocol.authType)
    }

    @Test
    fun missingRequiredOperationalSections_failWithoutSilentDefaults() {
        val minimalJson = """
        {
          "id": "incomplete_dash",
          "name": "Incomplete Dash",
          "version": 1,
          "network": {
            "defaultSsidPrefix": "DEV_",
            "defaultPassword": "password123",
            "gatewayIp": "10.0.0.1",
            "controlTxPort": 3000,
            "controlRxPort": 3002,
            "videoRtpPort": 6000
          },
          "display": {
            "width": 640,
            "height": 480
          },
          "protocol": {
            "magic": "TEST",
            "fixedHeaderHex": "0102030405060708"
          }
        }
        """.trimIndent()

        val result = DashProfileJsonCodec.fromJson(minimalJson)
        assertTrue("Incomplete profile missing explicit operational config must be rejected", result.isFailure)
    }

    @Test
    fun parseRootTemplateProfile_success() {
        val file = java.io.File("../DashProfile.json").takeIf { it.exists() }
            ?: java.io.File("DashProfile.json")
        assertTrue("DashProfile.json must exist at project root", file.exists())
        val result = DashProfileJsonCodec.fromJson(file.readText())
        assertTrue("Parsing DashProfile.json must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals("template_profile", profile.id)
        assertTrue("Expected full maneuver list", profile.maneuvers.size >= 66)
    }

    @Test
    fun parseWithUtf8Bom_succeeds() {
        val withBom = "\uFEFF$sampleJson"
        val result = DashProfileJsonCodec.fromStream(ByteArrayInputStream(withBom.toByteArray(Charsets.UTF_8)))
        assertTrue("UTF-8 BOM JSON must parse cleanly: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("synthetic_dash", result.getOrThrow().id)
    }

    @Test
    fun rejectsTrailingGarbageAndWrongJsonTypes() {
        val trailing = "$sampleJson trailing_garbage }"
        assertTrue("Trailing data after JSON object must fail", DashProfileJsonCodec.fromJson(trailing).isFailure)

        val jsonArray = "[$sampleJson]"
        assertTrue("Top-level JSON array must fail", DashProfileJsonCodec.fromJson(jsonArray).isFailure)

        val wrongPortType = sampleJson.replace("\"controlTxPort\": 3000", "\"controlTxPort\": \"3000\"")
        assertTrue("String instead of integer port must fail", DashProfileJsonCodec.fromJson(wrongPortType).isFailure)
    }
}
