package io.github.saltmango.navbeam.dash.profile

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

object DashProfileJsonCodec {

    const val MAX_PROFILE_BYTES = 65536 // 64 KB size limit

    /**
     * Reads a UTF-8 stream with a strict 64 KB size guard and parses it into a validated [DashProfile].
     */
    fun fromStream(stream: InputStream): Result<DashProfile> = runCatching {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_PROFILE_BYTES) {
                throw SecurityException("Profile exceeds maximum size limit of 64 KB ($total bytes read).")
            }
            out.write(buffer, 0, read)
        }
        val jsonStr = out.toString(Charsets.UTF_8.name())
        fromJson(jsonStr).getOrThrow()
    }

    /**
     * Parses a JSON string into a validated [DashProfile]. All operational values must be
     * explicitly provided by the JSON document — no hardware defaults are embedded in code.
     */
    fun fromJson(jsonStr: String): Result<DashProfile> = runCatching {
        if (jsonStr.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES) {
            throw SecurityException("Profile JSON string exceeds 64 KB size limit.")
        }

        val root = JSONObject(jsonStr)

        val id = root.optNonNullString("id")
        val name = root.optNonNullString("name")
        val version = root.optInt("version", 0)
        val firmwareVersion = root.optNonNullString("firmwareVersion")
        val description = root.optNonNullString("description")

        val netObj = root.optJSONObject("network")
            ?: throw IllegalArgumentException("Missing required 'network' section.")
        val network = NetworkConfig(
            defaultSsidPrefix = netObj.optNonNullString("defaultSsidPrefix"),
            defaultPassword = netObj.optNonNullString("defaultPassword"),
            gatewayIp = netObj.optNonNullString("gatewayIp"),
            broadcastIp = netObj.optNonNullString("broadcastIp").takeIf { it.isNotBlank() },
            controlTxPort = netObj.optInt("controlTxPort", 0),
            controlRxPort = netObj.optInt("controlRxPort", 0),
            videoRtpPort = netObj.optInt("videoRtpPort", 0),
        )

        val dispObj = root.optJSONObject("display")
            ?: throw IllegalArgumentException("Missing required 'display' section.")
        val display = DisplayConfig(
            width = dispObj.optInt("width", 0),
            height = dispObj.optInt("height", 0),
            shape = DisplayShape.fromString(dispObj.optNonNullString("shape").takeIf { it.isNotBlank() }),
            frameDurationActiveMs = dispObj.optLong("frameDurationActiveMs", 0L),
            frameDurationIdleMs = dispObj.optLong("frameDurationIdleMs", 0L),
            motionHoldMs = dispObj.optLong("motionHoldMs", 0L),
            activeBitrateBps = dispObj.optInt("activeBitrateBps", 0),
            idleBitrateBps = dispObj.optInt("idleBitrateBps", 0),
            h264Level = dispObj.optNonNullString("h264Level"),
            prependSpsPpsToIdr = dispObj.requireBoolean("prependSpsPpsToIdr"),
            forceSpsBaseline = dispObj.requireBoolean("forceSpsBaseline"),
            dropSeiAndAud = dispObj.requireBoolean("dropSeiAndAud"),
            rtpMaxPayloadBytes = dispObj.optInt("rtpMaxPayloadBytes", 0),
            rtpPayloadType = dispObj.optInt("rtpPayloadType", 0),
        )

        val protoObj = root.optJSONObject("protocol")
            ?: throw IllegalArgumentException("Missing required 'protocol' section.")
        val protocol = ProtocolConfig(
            magic = protoObj.optNonNullString("magic"),
            fixedHeaderHex = protoObj.optNonNullString("fixedHeaderHex"),
            incomingHeaderOffset = protoObj.optInt("incomingHeaderOffset", 0),
            authType = protoObj.optNonNullString("authType"),
            projKeepAliveMs = protoObj.optLong("projKeepAliveMs", 0L),
            routeCardKeepAliveMs = protoObj.optLong("routeCardKeepAliveMs", 0L),
            authTimeoutMs = protoObj.optLong("authTimeoutMs", 0L),
            maxAuthAttempts = protoObj.optInt("maxAuthAttempts", 0),
            burstPauseMs = protoObj.optLong("burstPauseMs", -1L),
            rxWatchdogMs = protoObj.optLong("rxWatchdogMs", 0L),
            rsaCiphertextBytes = protoObj.optInt("rsaCiphertextBytes", 0),
            maxRouteTitleBytes = protoObj.optInt("maxRouteTitleBytes", 0),
            maxMediaFieldChars = protoObj.optInt("maxMediaFieldChars", 0),
        )

        val cmdObj = root.optJSONObject("commands")
            ?: throw IllegalArgumentException("Missing required 'commands' section.")
        val commands = parseCommands(cmdObj)

        val inputs = mutableMapOf<String, Int>()
        root.optJSONObject("inputs")?.let { inputObj ->
            for (key in inputObj.keys()) {
                inputs[key] = parseHexOrInt(inputObj.get(key))
            }
        }

        val maneuvers = mutableMapOf<String, Int>()
        root.optJSONObject("maneuvers")?.let { manObj ->
            for (key in manObj.keys()) {
                maneuvers[key] = parseHexOrInt(manObj.get(key))
            }
        }

        val profile = DashProfile(
            id = id,
            name = name,
            version = version,
            firmwareVersion = firmwareVersion,
            description = description,
            network = network,
            display = display,
            protocol = protocol,
            commands = commands,
            inputs = inputs,
            maneuvers = maneuvers,
        )

        DashProfileValidator.validate(profile)
        profile
    }

    private fun parseCommands(cmd: JSONObject): CommandProtocolConfig {
        val navObj = cmd.optJSONObject("navTags")
            ?: throw IllegalArgumentException("Missing required 'commands.navTags' section.")
        val navTags = NavTagsConfig(
            titleTag = cmdParseTag(navObj, "titleTag"),
            primaryManeuverTag = cmdParseTag(navObj, "primaryManeuverTag"),
            secondaryManeuverTag = cmdParseTag(navObj, "secondaryManeuverTag"),
            primaryDistTag = cmdParseTag(navObj, "primaryDistTag"),
            secondaryDistTag = cmdParseTag(navObj, "secondaryDistTag"),
            primaryUnitTag = cmdParseTag(navObj, "primaryUnitTag"),
            secondaryUnitTag = cmdParseTag(navObj, "secondaryUnitTag"),
            etaTag = cmdParseTag(navObj, "etaTag"),
            totalDistTag = cmdParseTag(navObj, "totalDistTag"),
            totalUnitTag = cmdParseTag(navObj, "totalUnitTag"),
            unitMetersByte = parseByteField(navObj, "unitMetersByte"),
            unitKmTenthsByte = parseByteField(navObj, "unitKmTenthsByte"),
            projectionFlagTag = cmdParseTag(navObj, "projectionFlagTag"),
            projectionFlagOnByte = parseByteField(navObj, "projectionFlagOnByte"),
            projectionFlagOffByte = parseByteField(navObj, "projectionFlagOffByte"),
            routeCardExtraTlvs = parseTlvSpecList(navObj, "routeCardExtraTlvs"),
            activeNavExtraTlvs = parseTlvSpecList(navObj, "activeNavExtraTlvs"),
        )

        val burstArr = cmd.optJSONArray("initialBurst")
            ?: throw IllegalArgumentException("Missing required 'commands.initialBurst' array.")
        val initialBurst = List(burstArr.length()) { pktIdx ->
            val pktArr = burstArr.optJSONArray(pktIdx)
                ?: throw IllegalArgumentException("commands.initialBurst[$pktIdx] must be a JSON array.")
            List(pktArr.length()) { itemIdx ->
                val raw = pktArr.getString(itemIdx).trim()
                when (raw) {
                    "AUTH_REQUEST" -> BurstItem.AuthRequest
                    "HOSTNAME" -> BurstItem.Hostname
                    "TIME_SYNC" -> BurstItem.TimeSync
                    else -> BurstItem.Static(parseTlvSpec(raw))
                }
            }
        }

        val hbArr = cmd.optJSONArray("heartbeat")
            ?: throw IllegalArgumentException("Missing required 'commands.heartbeat' array.")
        val heartbeat = List(hbArr.length()) { idx ->
            val raw = hbArr.getString(idx).trim()
            if (raw == "TEMP") HeartbeatItem.Temperature
            else HeartbeatItem.Static(parseTlvSpec(raw))
        }

        if (!cmd.has("heartbeatTempOffset") || cmd.isNull("heartbeatTempOffset")) {
            throw IllegalArgumentException("Missing required 'commands.heartbeatTempOffset'.")
        }
        if (!cmd.has("heartbeatDefaultTempC") || cmd.isNull("heartbeatDefaultTempC")) {
            throw IllegalArgumentException("Missing required 'commands.heartbeatDefaultTempC'.")
        }

        return CommandProtocolConfig(
            authRequest = parseTlvSpec(cmd.requireString("authRequest")),
            authSendKeyTag = cmdParseTag(cmd, "authSendKeyTag"),
            authModulusTag = cmdParseTag(cmd, "authModulusTag"),
            authExponentTag = cmdParseTag(cmd, "authExponentTag"),
            authStatusTag = cmdParseTag(cmd, "authStatusTag"),
            authConfirmByte = parseByteField(cmd, "authConfirmByte"),
            hostnameTag = cmdParseTag(cmd, "hostnameTag"),
            timeSyncTag = cmdParseTag(cmd, "timeSyncTag"),
            initialBurst = initialBurst,
            heartbeat = heartbeat,
            heartbeatTempTag = cmdParseTag(cmd, "heartbeatTempTag"),
            heartbeatTempOffset = cmd.getInt("heartbeatTempOffset"),
            heartbeatDefaultTempC = cmd.getInt("heartbeatDefaultTempC"),
            navContext = parseTlvSpecList(cmd, "navContext"),
            emptyLists = parseTlvSpecList(cmd, "emptyLists"),
            navStart = parseTlvSpecList(cmd, "navStart"),
            navPlaceholder = parseTlvSpecList(cmd, "navPlaceholder"),
            projectionFrame = parseTlvSpecList(cmd, "projectionFrame"),
            projectionOn = parseTlvSpecList(cmd, "projectionOn"),
            projectionStop = parseTlvSpecList(cmd, "projectionStop"),
            projectionOff = parseTlvSpecList(cmd, "projectionOff"),
            frameDecodedIdrNotify = parseTlvSpec(cmd.requireString("frameDecodedIdrNotify")),
            frameDecodedIdrAck = parseTlvSpecList(cmd, "frameDecodedIdrAck"),
            frameDecodedPNotify = parseTlvSpec(cmd.requireString("frameDecodedPNotify")),
            frameDecodedPAck = parseTlvSpecList(cmd, "frameDecodedPAck"),
            buttonEventTag = cmdParseTag(cmd, "buttonEventTag"),
            buttonAckTag = cmdParseTag(cmd, "buttonAckTag"),
            telemetryEncryptedType = parseByteField(cmd, "telemetryEncryptedType"),
            telemetryPlainType = parseByteField(cmd, "telemetryPlainType"),
            nowPlayingTag = cmdParseTag(cmd, "nowPlayingTag"),
            callNotifyTag = cmdParseTag(cmd, "callNotifyTag"),
            albumArtTag = cmdParseTag(cmd, "albumArtTag"),
            navTags = navTags,
        )
    }

    /**
     * Serializes a [DashProfile] into an indented JSON string for export.
     */
    fun toJson(profile: DashProfile, indent: Int = 2): String {
        val root = JSONObject()
        root.put("id", profile.id)
        root.put("name", profile.name)
        root.put("version", profile.version)
        if (profile.firmwareVersion.isNotBlank()) {
            root.put("firmwareVersion", profile.firmwareVersion)
        }
        root.put("description", profile.description)

        root.put("network", JSONObject().apply {
            put("defaultSsidPrefix", profile.network.defaultSsidPrefix)
            put("defaultPassword", profile.network.defaultPassword)
            put("gatewayIp", profile.network.gatewayIp)
            profile.network.broadcastIp?.let { put("broadcastIp", it) }
            put("controlTxPort", profile.network.controlTxPort)
            put("controlRxPort", profile.network.controlRxPort)
            put("videoRtpPort", profile.network.videoRtpPort)
        })

        root.put("display", JSONObject().apply {
            put("width", profile.display.width)
            put("height", profile.display.height)
            put("shape", (profile.display.shape ?: DisplayShape.RECTANGLE).name)
            put("frameDurationActiveMs", profile.display.frameDurationActiveMs)
            put("frameDurationIdleMs", profile.display.frameDurationIdleMs)
            put("motionHoldMs", profile.display.motionHoldMs)
            put("activeBitrateBps", profile.display.activeBitrateBps)
            put("idleBitrateBps", profile.display.idleBitrateBps)
            put("h264Level", profile.display.h264Level)
            put("prependSpsPpsToIdr", profile.display.prependSpsPpsToIdr)
            put("forceSpsBaseline", profile.display.forceSpsBaseline)
            put("dropSeiAndAud", profile.display.dropSeiAndAud)
            put("rtpMaxPayloadBytes", profile.display.rtpMaxPayloadBytes)
            put("rtpPayloadType", profile.display.rtpPayloadType)
        })

        root.put("protocol", JSONObject().apply {
            put("magic", profile.protocol.magic)
            put("fixedHeaderHex", profile.protocol.fixedHeaderHex)
            put("incomingHeaderOffset", profile.protocol.incomingHeaderOffset)
            put("authType", profile.protocol.authType)
            put("projKeepAliveMs", profile.protocol.projKeepAliveMs)
            put("routeCardKeepAliveMs", profile.protocol.routeCardKeepAliveMs)
            put("authTimeoutMs", profile.protocol.authTimeoutMs)
            put("maxAuthAttempts", profile.protocol.maxAuthAttempts)
            put("burstPauseMs", profile.protocol.burstPauseMs)
            put("rxWatchdogMs", profile.protocol.rxWatchdogMs)
            put("rsaCiphertextBytes", profile.protocol.rsaCiphertextBytes)
            put("maxRouteTitleBytes", profile.protocol.maxRouteTitleBytes)
            put("maxMediaFieldChars", profile.protocol.maxMediaFieldChars)
        })

        val c = profile.commands
        val nt = c.navTags
        root.put("commands", JSONObject().apply {
            put("authRequest", c.authRequest.format())
            put("authSendKeyTag", c.authSendKeyTag.format())
            put("authModulusTag", c.authModulusTag.format())
            put("authExponentTag", c.authExponentTag.format())
            put("authStatusTag", c.authStatusTag.format())
            put("authConfirmByte", "0x%02X".format(c.authConfirmByte))
            put("hostnameTag", c.hostnameTag.format())
            put("timeSyncTag", c.timeSyncTag.format())
            put("initialBurst", JSONArray().apply {
                c.initialBurst.forEach { pkt ->
                    put(JSONArray().apply {
                        pkt.forEach { item ->
                            put(
                                when (item) {
                                    BurstItem.AuthRequest -> "AUTH_REQUEST"
                                    BurstItem.Hostname -> "HOSTNAME"
                                    BurstItem.TimeSync -> "TIME_SYNC"
                                    is BurstItem.Static -> item.tlv.format()
                                }
                            )
                        }
                    })
                }
            })
            put("heartbeat", JSONArray().apply {
                c.heartbeat.forEach { item ->
                    put(
                        when (item) {
                            HeartbeatItem.Temperature -> "TEMP"
                            is HeartbeatItem.Static -> item.tlv.format()
                        }
                    )
                }
            })
            put("heartbeatTempTag", c.heartbeatTempTag.format())
            put("heartbeatTempOffset", c.heartbeatTempOffset)
            put("heartbeatDefaultTempC", c.heartbeatDefaultTempC)
            put("navContext", tlvListToJson(c.navContext))
            put("emptyLists", tlvListToJson(c.emptyLists))
            put("navStart", tlvListToJson(c.navStart))
            put("navPlaceholder", tlvListToJson(c.navPlaceholder))
            put("projectionFrame", tlvListToJson(c.projectionFrame))
            put("projectionOn", tlvListToJson(c.projectionOn))
            put("projectionStop", tlvListToJson(c.projectionStop))
            put("projectionOff", tlvListToJson(c.projectionOff))
            put("frameDecodedIdrNotify", c.frameDecodedIdrNotify.format())
            put("frameDecodedIdrAck", tlvListToJson(c.frameDecodedIdrAck))
            put("frameDecodedPNotify", c.frameDecodedPNotify.format())
            put("frameDecodedPAck", tlvListToJson(c.frameDecodedPAck))
            put("buttonEventTag", c.buttonEventTag.format())
            put("buttonAckTag", c.buttonAckTag.format())
            put("telemetryEncryptedType", "0x%02X".format(c.telemetryEncryptedType))
            put("telemetryPlainType", "0x%02X".format(c.telemetryPlainType))
            put("nowPlayingTag", c.nowPlayingTag.format())
            put("callNotifyTag", c.callNotifyTag.format())
            put("albumArtTag", c.albumArtTag.format())
            put("navTags", JSONObject().apply {
                put("titleTag", nt.titleTag.format())
                put("primaryManeuverTag", nt.primaryManeuverTag.format())
                put("secondaryManeuverTag", nt.secondaryManeuverTag.format())
                put("primaryDistTag", nt.primaryDistTag.format())
                put("secondaryDistTag", nt.secondaryDistTag.format())
                put("primaryUnitTag", nt.primaryUnitTag.format())
                put("secondaryUnitTag", nt.secondaryUnitTag.format())
                put("etaTag", nt.etaTag.format())
                put("totalDistTag", nt.totalDistTag.format())
                put("totalUnitTag", nt.totalUnitTag.format())
                put("unitMetersByte", "0x%02X".format(nt.unitMetersByte))
                put("unitKmTenthsByte", "0x%02X".format(nt.unitKmTenthsByte))
                put("projectionFlagTag", nt.projectionFlagTag.format())
                put("projectionFlagOnByte", "0x%02X".format(nt.projectionFlagOnByte))
                put("projectionFlagOffByte", "0x%02X".format(nt.projectionFlagOffByte))
                put("routeCardExtraTlvs", tlvListToJson(nt.routeCardExtraTlvs))
                put("activeNavExtraTlvs", tlvListToJson(nt.activeNavExtraTlvs))
            })
        })

        root.put("inputs", JSONObject().apply {
            profile.inputs.forEach { (k, v) -> put(k, "0x%02X".format(v)) }
        })

        root.put("maneuvers", JSONObject().apply {
            profile.maneuvers.forEach { (k, v) -> put(k, "0x%02X".format(v)) }
        })

        return root.toString(indent)
    }

    private fun tlvListToJson(list: List<TlvSpec>): JSONArray =
        JSONArray().apply { list.forEach { put(it.format()) } }

    private fun JSONObject.optNonNullString(key: String, fallback: String = ""): String =
        if (isNull(key)) fallback else optString(key, fallback)

    private fun JSONObject.requireString(key: String): String {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Missing required string field '$key'.")
        }
        return getString(key)
    }

    private fun JSONObject.requireBoolean(key: String): Boolean {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Missing required boolean field '$key'.")
        }
        return getBoolean(key)
    }

    private fun cmdParseTag(obj: JSONObject, key: String): TlvTag =
        parseTlvTag(obj.requireString(key))

    private fun parseByteField(obj: JSONObject, key: String): Int {
        if (!obj.has(key) || obj.isNull(key)) {
            throw IllegalArgumentException("Missing required byte field '$key'.")
        }
        return parseHexOrInt(obj.get(key))
    }

    private fun parseTlvSpecList(obj: JSONObject, key: String): List<TlvSpec> {
        val arr = obj.optJSONArray(key)
            ?: throw IllegalArgumentException("Missing required TLV array '$key'.")
        return List(arr.length()) { idx -> parseTlvSpec(arr.getString(idx)) }
    }

    private fun parseTlvTag(raw: String): TlvTag {
        val parts = raw.trim().split(':')
        require(parts.size == 2) { "Invalid TLV tag '$raw'. Expected 'TT:SS' hex format." }
        return TlvTag(
            type = parseHexByte(parts[0], raw),
            sub = parseHexByte(parts[1], raw),
        )
    }

    private fun parseTlvSpec(raw: String): TlvSpec {
        val parts = raw.trim().split(':')
        require(parts.size == 2 || parts.size == 3) {
            "Invalid TLV spec '$raw'. Expected 'TT:SS' or 'TT:SS:HEXVALUE'."
        }
        val type = parseHexByte(parts[0], raw)
        val sub = parseHexByte(parts[1], raw)
        val value = if (parts.size == 3 && parts[2].isNotBlank()) {
            val hex = parts[2].replace(" ", "")
            require(hex.length % 2 == 0) { "Invalid hex payload length in TLV '$raw'." }
            ByteArray(hex.length / 2) { idx ->
                hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        } else {
            ByteArray(0)
        }
        return TlvSpec(type = type, sub = sub, value = value)
    }

    private fun parseHexByte(part: String, fullRaw: String): Int {
        val clean = part.trim().removePrefix("0x").removePrefix("0X")
        require(clean.length in 1..2) { "Invalid hex byte '$part' in '$fullRaw'." }
        return clean.toInt(16)
    }

    private fun parseHexOrInt(value: Any): Int = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is String -> {
            val s = value.trim()
            if (s.startsWith("0x", ignoreCase = true)) {
                s.substring(2).toInt(16)
            } else {
                s.toIntOrNull() ?: s.toInt(16)
            }
        }
        else -> throw IllegalArgumentException("Cannot parse '$value' into byte integer.")
    }
}
