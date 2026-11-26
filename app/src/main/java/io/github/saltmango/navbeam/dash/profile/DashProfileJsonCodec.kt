package io.github.saltmango.navbeam.dash.profile

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object DashProfileJsonCodec {

    const val MAX_PROFILE_BYTES = 65536 // 64 KB size limit

    /**
     * Reads a strict UTF-8 stream (used for both `.json` and `.navbeam` profile files)
     * with a 64 KB size guard and parses it into a validated [DashProfile].
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
        val bytes = out.toByteArray()
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val jsonStr = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw SecurityException("Selected file is not valid UTF-8 text.")
        }
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
        if (jsonStr.indexOf('\u0000') >= 0) {
            throw SecurityException("Profile JSON contains invalid NUL bytes.")
        }

        val cleaned = jsonStr.removePrefix("\uFEFF").trim()
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("Profile JSON is empty.")
        }
        if (!cleaned.startsWith("{") || !cleaned.endsWith("}")) {
            throw IllegalArgumentException("Profile must be a single JSON object starting with '{' and ending with '}'.")
        }

        val tokener = JSONTokener(cleaned)
        val root = tokener.nextValue() as? JSONObject
            ?: throw IllegalArgumentException("Profile root must be a JSON object.")
        if (tokener.nextClean() != '\u0000') {
            throw IllegalArgumentException("Unexpected trailing content after JSON object.")
        }

        val id = root.optNonNullString("id").trim()
        val name = root.optNonNullString("name").trim()
        val version = root.optStrictInt("version", 0)
        val firmwareVersion = root.optNonNullString("firmwareVersion").trim()
        val description = root.optNonNullString("description")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

        val netObj = root.optJSONObject("network")
            ?: throw IllegalArgumentException("Missing required 'network' section.")
        val network = NetworkConfig(
            defaultSsidPrefix = netObj.optNonNullString("defaultSsidPrefix"),
            defaultPassword = netObj.optNonNullString("defaultPassword"),
            gatewayIp = netObj.optNonNullString("gatewayIp").trim(),
            broadcastIp = netObj.optNonNullString("broadcastIp").trim().takeIf { it.isNotBlank() },
            controlTxPort = netObj.optStrictInt("controlTxPort", 0),
            controlRxPort = netObj.optStrictInt("controlRxPort", 0),
            videoRtpPort = netObj.optStrictInt("videoRtpPort", 0),
        )

        val dispObj = root.optJSONObject("display")
            ?: throw IllegalArgumentException("Missing required 'display' section.")
        val display = DisplayConfig(
            width = dispObj.optStrictInt("width", 0),
            height = dispObj.optStrictInt("height", 0),
            shape = DisplayShape.fromString(dispObj.optNonNullString("shape").takeIf { it.isNotBlank() }),
            frameDurationActiveMs = dispObj.optStrictLong("frameDurationActiveMs", 0L),
            frameDurationIdleMs = dispObj.optStrictLong("frameDurationIdleMs", 0L),
            motionHoldMs = dispObj.optStrictLong("motionHoldMs", 0L),
            activeBitrateBps = dispObj.optStrictInt("activeBitrateBps", 0),
            idleBitrateBps = dispObj.optStrictInt("idleBitrateBps", 0),
            h264Level = dispObj.optNonNullString("h264Level").trim(),
            prependSpsPpsToIdr = dispObj.requireBoolean("prependSpsPpsToIdr"),
            forceSpsBaseline = dispObj.requireBoolean("forceSpsBaseline"),
            dropSeiAndAud = dispObj.requireBoolean("dropSeiAndAud"),
            rtpMaxPayloadBytes = dispObj.optStrictInt("rtpMaxPayloadBytes", 0),
            rtpPayloadType = dispObj.optStrictInt("rtpPayloadType", 0),
        )

        val protoObj = root.optJSONObject("protocol")
            ?: throw IllegalArgumentException("Missing required 'protocol' section.")
        val protocol = ProtocolConfig(
            magic = protoObj.optNonNullString("magic"),
            fixedHeaderHex = protoObj.optNonNullString("fixedHeaderHex").trim(),
            incomingHeaderOffset = protoObj.optStrictInt("incomingHeaderOffset", 0),
            authType = protoObj.optNonNullString("authType").trim(),
            projKeepAliveMs = protoObj.optStrictLong("projKeepAliveMs", 0L),
            routeCardKeepAliveMs = protoObj.optStrictLong("routeCardKeepAliveMs", 0L),
            authTimeoutMs = protoObj.optStrictLong("authTimeoutMs", 0L),
            maxAuthAttempts = protoObj.optStrictInt("maxAuthAttempts", 0),
            burstPauseMs = protoObj.optStrictLong("burstPauseMs", -1L),
            rxWatchdogMs = protoObj.optStrictLong("rxWatchdogMs", 0L),
            rsaCiphertextBytes = protoObj.optStrictInt("rsaCiphertextBytes", 0),
            maxRouteTitleBytes = protoObj.optStrictInt("maxRouteTitleBytes", 0),
            maxMediaFieldChars = protoObj.optStrictInt("maxMediaFieldChars", 0),
        )

        val cmdObj = root.optJSONObject("commands")
            ?: throw IllegalArgumentException("Missing required 'commands' section.")
        val commands = parseCommands(cmdObj)

        val inputs = mutableMapOf<String, Int>()
        if (root.has("inputs") && !root.isNull("inputs")) {
            val inputObj = root.optJSONObject("inputs")
                ?: throw IllegalArgumentException("'inputs' must be a JSON object.")
            require(inputObj.length() <= 50) { "Inputs map cannot exceed 50 entries." }
            for (key in inputObj.keys()) {
                inputs[key] = parseHexOrInt(inputObj.get(key))
            }
        }

        val maneuvers = mutableMapOf<String, Int>()
        if (root.has("maneuvers") && !root.isNull("maneuvers")) {
            val manObj = root.optJSONObject("maneuvers")
                ?: throw IllegalArgumentException("'maneuvers' must be a JSON object.")
            require(manObj.length() <= 128) { "Maneuver map cannot exceed 128 entries." }
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
        require(burstArr.length() <= 32) { "commands.initialBurst cannot exceed 32 packets." }
        val initialBurst = List(burstArr.length()) { pktIdx ->
            val pktArr = burstArr.optJSONArray(pktIdx)
                ?: throw IllegalArgumentException("commands.initialBurst[$pktIdx] must be a JSON array.")
            require(pktArr.length() <= 32) { "commands.initialBurst[$pktIdx] cannot exceed 32 entries." }
            List(pktArr.length()) { itemIdx ->
                val raw = (pktArr.get(itemIdx) as? String
                    ?: throw IllegalArgumentException("commands.initialBurst[$pktIdx][$itemIdx] must be a string.")).trim()
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
        require(hbArr.length() in 1..32) { "commands.heartbeat must contain 1..32 entries." }
        val heartbeat = List(hbArr.length()) { idx ->
            val raw = (hbArr.get(idx) as? String
                ?: throw IllegalArgumentException("commands.heartbeat[$idx] must be a string.")).trim()
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
            heartbeatTempOffset = cmd.requireInt("heartbeatTempOffset"),
            heartbeatDefaultTempC = cmd.requireInt("heartbeatDefaultTempC"),
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

    private fun JSONObject.optNonNullString(key: String, fallback: String = ""): String {
        if (!has(key) || isNull(key)) return fallback
        return get(key) as? String
            ?: throw IllegalArgumentException("Field '$key' must be a string.")
    }

    private fun JSONObject.requireString(key: String): String {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Missing required string field '$key'.")
        }
        return get(key) as? String
            ?: throw IllegalArgumentException("Field '$key' must be a string.")
    }

    private fun JSONObject.requireBoolean(key: String): Boolean {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Missing required boolean field '$key'.")
        }
        return get(key) as? Boolean
            ?: throw IllegalArgumentException("Field '$key' must be a boolean.")
    }

    private fun JSONObject.optStrictInt(key: String, fallback: Int): Int {
        if (!has(key) || isNull(key)) return fallback
        return when (val v = get(key)) {
            is Int -> v
            is Long -> {
                if (v !in Int.MIN_VALUE..Int.MAX_VALUE) {
                    throw IllegalArgumentException("Field '$key' integer out of range.")
                }
                v.toInt()
            }
            else -> throw IllegalArgumentException("Field '$key' must be an integer.")
        }
    }

    private fun JSONObject.requireInt(key: String): Int {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Missing required integer field '$key'.")
        }
        return optStrictInt(key, 0)
    }

    private fun JSONObject.optStrictLong(key: String, fallback: Long): Long {
        if (!has(key) || isNull(key)) return fallback
        return when (val v = get(key)) {
            is Int -> v.toLong()
            is Long -> v
            else -> throw IllegalArgumentException("Field '$key' must be an integer.")
        }
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
        require(arr.length() <= 32) { "TLV list '$key' cannot exceed 32 entries." }
        return List(arr.length()) { idx ->
            val raw = arr.get(idx) as? String
                ?: throw IllegalArgumentException("TLV entry '$key[$idx]' must be a string.")
            parseTlvSpec(raw)
        }
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
        is Long -> {
            if (value !in Int.MIN_VALUE..Int.MAX_VALUE) {
                throw IllegalArgumentException("Byte integer '$value' out of range.")
            }
            value.toInt()
        }
        is String -> {
            val s = value.trim()
            require(s.isNotEmpty()) { "Byte value cannot be empty." }
            if (s.startsWith("0x", ignoreCase = true)) {
                val hex = s.substring(2)
                require(hex.isNotEmpty() && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    "Invalid hex byte '$s'."
                }
                hex.toInt(16)
            } else {
                s.toIntOrNull()
                    ?: s.takeIf { it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }?.toIntOrNull(16)
                    ?: throw IllegalArgumentException("Cannot parse '$s' into byte integer.")
            }
        }
        else -> throw IllegalArgumentException("Cannot parse '$value' into byte integer.")
    }
}
