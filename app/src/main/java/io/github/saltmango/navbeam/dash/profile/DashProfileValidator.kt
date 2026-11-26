package io.github.saltmango.navbeam.dash.profile

object DashProfileValidator {

    private val ID_REGEX = Regex("^[a-z0-9_-]{1,32}$")
    private val MANEUVER_KEY_REGEX = Regex("^[A-Za-z0-9_]{1,40}$")
    private val IP_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
    private val SUPPORTED_AUTH_TYPES = setOf("RSA_AES_256", "NONE")
    private val SUPPORTED_H264_LEVELS = setOf("3.0", "3", "3.1", "3.2", "4.0", "4", "4.1", "4.2", "5.0", "5", "5.1")

    val KNOWN_INPUT_ACTIONS = setOf(
        "callAnswer",
        "callReject",
        "mapZoomIn",
        "mapZoomOut",
        "mediaNext",
        "mediaPrev",
    )

    /**
     * Validates a [DashProfile] against security constraints and hardware operational bounds.
     * Throws [IllegalArgumentException] or [SecurityException] if validation fails.
     */
    fun validate(profile: DashProfile) {
        require(ID_REGEX.matches(profile.id)) {
            "Profile ID '${profile.id}' is invalid. Must be 1-32 lowercase alphanumeric characters, dashes, or underscores."
        }
        requireSafeText(profile.name, "name")
        require(profile.name.isNotBlank() && profile.name.length <= 40) {
            "Profile name must be 1-40 characters."
        }
        require(profile.version >= 1) {
            "Profile version must be >= 1."
        }
        requireSafeText(profile.description, "description")
        require(profile.description.length <= 120) {
            "Profile description cannot exceed 120 characters."
        }
        requireSafeText(profile.firmwareVersion, "firmwareVersion")
        require(profile.firmwareVersion.length <= 32) {
            "Firmware version cannot exceed 32 characters."
        }

        validateNetwork(profile.network)
        validateDisplay(profile.display)
        validateProtocol(profile.protocol)
        validateCommands(profile.commands)

        require(profile.maneuvers.size <= 128) { "Maneuver map cannot exceed 128 entries." }
        for ((key, code) in profile.maneuvers) {
            require(MANEUVER_KEY_REGEX.matches(key)) { "Maneuver key '$key' is invalid." }
            require(code in 0..255) { "Maneuver code for '$key' must be between 0 and 255 (got $code)." }
        }

        require(profile.inputs.size <= 50) { "Inputs map cannot exceed 50 entries." }
        for ((action, code) in profile.inputs) {
            require(action in KNOWN_INPUT_ACTIONS) {
                "Unknown input action '$action'. Must be one of $KNOWN_INPUT_ACTIONS."
            }
            require(code in 0..255) { "Input code for '$action' must be between 0 and 255 (got $code)." }
        }
    }

    private fun requireSafeText(value: String, fieldName: String) {
        for (ch in value) {
            val isControl = ch.code < 0x20 || ch.code == 0x7F
            val isBidiOrFormat = ch in '\u200B'..'\u200F' ||
                ch in '\u202A'..'\u202E' ||
                ch in '\u2066'..'\u2069' ||
                ch == '\uFEFF'
            if (isControl || isBidiOrFormat) {
                throw SecurityException("Field '$fieldName' contains disallowed control or formatting characters.")
            }
        }
    }

    private fun validateNetwork(network: NetworkConfig) {
        requireSafeText(network.defaultSsidPrefix, "defaultSsidPrefix")
        require(network.defaultSsidPrefix.isNotBlank() && network.defaultSsidPrefix.toByteArray(Charsets.UTF_8).size <= 32) {
            "SSID prefix must be 1-32 UTF-8 bytes."
        }
        requireSafeText(network.defaultPassword, "defaultPassword")
        require(network.defaultPassword.length in 8..64) {
            "Wi-Fi password must be between 8 and 64 characters."
        }

        validatePrivateIp(network.gatewayIp, "gatewayIp")

        network.broadcastIp?.takeIf { it.isNotBlank() }?.let { broadcast ->
            validatePrivateIp(broadcast, "broadcastIp", allowSubnetBroadcast = true)
        }

        require(network.controlTxPort in 1024..65535) {
            "controlTxPort (${network.controlTxPort}) must be an unprivileged UDP port in 1024..65535."
        }
        require(network.controlRxPort in 1024..65535) {
            "controlRxPort (${network.controlRxPort}) must be an unprivileged UDP port in 1024..65535."
        }
        require(network.videoRtpPort in 1024..65535) {
            "videoRtpPort (${network.videoRtpPort}) must be an unprivileged UDP port in 1024..65535."
        }

        val ports = setOf(network.controlTxPort, network.controlRxPort, network.videoRtpPort)
        require(ports.size == 3) {
            "controlTxPort (${network.controlTxPort}), controlRxPort (${network.controlRxPort}), and videoRtpPort (${network.videoRtpPort}) must all be distinct."
        }
    }

    private fun validatePrivateIp(ip: String, fieldName: String, allowSubnetBroadcast: Boolean = false) {
        val match = IP_REGEX.matchEntire(ip.trim())
            ?: throw SecurityException("Field '$fieldName' ($ip) is not a valid numeric IPv4 address.")

        val s0 = match.groupValues[1]
        val s1 = match.groupValues[2]
        val s2 = match.groupValues[3]
        val s3 = match.groupValues[4]
        if (listOf(s0, s1, s2, s3).any { it.length > 1 && it.startsWith('0') }) {
            throw SecurityException("Field '$fieldName' ($ip) cannot contain leading zeros in IPv4 octets.")
        }

        val o0 = s0.toInt()
        val o1 = s1.toInt()
        val o2 = s2.toInt()
        val o3 = s3.toInt()

        require(o0 in 0..255 && o1 in 0..255 && o2 in 0..255 && o3 in 0..255) {
            "Field '$fieldName' ($ip) octets out of 0..255 range."
        }

        if (o0 == 0 && o1 == 0 && o2 == 0 && o3 == 0) {
            throw SecurityException("Field '$fieldName' ($ip) cannot be 0.0.0.0.")
        }

        if (o0 == 169 && o1 == 254) {
            throw SecurityException("Field '$fieldName' ($ip) cannot be a link-local or cloud metadata address.")
        }

        if (o0 in 224..239) {
            throw SecurityException("Field '$fieldName' ($ip) cannot be a multicast address.")
        }

        if (allowSubnetBroadcast && o0 == 255 && o1 == 255 && o2 == 255 && o3 == 255) {
            return
        }

        val isRfc1918 = (o0 == 10) ||
            (o0 == 172 && o1 in 16..31) ||
            (o0 == 192 && o1 == 168)
        val isLoopback = (o0 == 127 && o1 == 0 && o2 == 0 && o3 == 1)

        if (!isRfc1918 && !isLoopback) {
            throw SecurityException("Field '$fieldName' ($ip) is not a permitted private IPv4 or loopback address.")
        }
    }

    private fun validateDisplay(display: DisplayConfig) {
        require(display.width in 128..1920) {
            "Display width (${display.width}) must be between 128 and 1920."
        }
        require(display.width % 2 == 0) {
            "Display width (${display.width}) must be an even integer for video codec compatibility."
        }
        require(display.height in 128..1080) {
            "Display height (${display.height}) must be between 128 and 1080."
        }
        require(display.height % 2 == 0) {
            "Display height (${display.height}) must be an even integer for video codec compatibility."
        }
        require(display.shape != null) {
            "Display shape must be 'CIRCLE' or 'RECTANGLE'."
        }
        require(display.frameDurationActiveMs in 50..2000) {
            "frameDurationActiveMs (${display.frameDurationActiveMs}) must be in 50..2000 ms."
        }
        require(display.frameDurationIdleMs in 100..5000) {
            "frameDurationIdleMs (${display.frameDurationIdleMs}) must be in 100..5000 ms."
        }
        require(display.motionHoldMs in 500..5000) {
            "motionHoldMs (${display.motionHoldMs}) must be in 500..5000 ms."
        }
        require(display.activeBitrateBps in 50_000..3_000_000) {
            "activeBitrateBps (${display.activeBitrateBps}) must be in 50,000..3,000,000 bps."
        }
        require(display.idleBitrateBps in 50_000..3_000_000) {
            "idleBitrateBps (${display.idleBitrateBps}) must be in 50,000..3,000,000 bps."
        }
        require(display.h264Level.trim() in SUPPORTED_H264_LEVELS) {
            "h264Level '${display.h264Level}' is invalid. Supported: $SUPPORTED_H264_LEVELS."
        }
        require(display.rtpMaxPayloadBytes in 256..1460) {
            "rtpMaxPayloadBytes (${display.rtpMaxPayloadBytes}) must be in 256..1460 bytes."
        }
        require(display.rtpPayloadType in 96..127) {
            "rtpPayloadType (${display.rtpPayloadType}) must be a dynamic RTP payload type in 96..127."
        }
    }

    private fun validateProtocol(protocol: ProtocolConfig) {
        require(protocol.magic.length == 4 && protocol.magic.all { it.code in 32..126 }) {
            "Protocol magic must be exactly 4 printable ASCII characters."
        }
        require(protocol.fixedHeaderHex.length in 2..64 && protocol.fixedHeaderHex.length % 2 == 0 && HEX_REGEX.matches(protocol.fixedHeaderHex)) {
            "Protocol fixedHeaderHex must be 2..64 even hexadecimal characters (1..32 bytes)."
        }
        require(protocol.incomingHeaderOffset in 4..64) {
            "Protocol incomingHeaderOffset (${protocol.incomingHeaderOffset}) must be in 4..64 bytes."
        }
        require(protocol.authType in SUPPORTED_AUTH_TYPES) {
            "Protocol authType '${protocol.authType}' must be one of $SUPPORTED_AUTH_TYPES."
        }
        require(protocol.projKeepAliveMs in 50..5000) {
            "projKeepAliveMs (${protocol.projKeepAliveMs}) must be in 50..5000 ms."
        }
        require(protocol.routeCardKeepAliveMs in 100..10000) {
            "routeCardKeepAliveMs (${protocol.routeCardKeepAliveMs}) must be in 100..10000 ms."
        }
        require(protocol.authTimeoutMs in 1000..60000) {
            "authTimeoutMs (${protocol.authTimeoutMs}) must be in 1000..60000 ms."
        }
        require(protocol.maxAuthAttempts in 1..10) {
            "maxAuthAttempts (${protocol.maxAuthAttempts}) must be in 1..10."
        }
        require(protocol.burstPauseMs in 0..1000) {
            "burstPauseMs (${protocol.burstPauseMs}) must be in 0..1000 ms."
        }
        require(protocol.rxWatchdogMs in 1000..60000) {
            "rxWatchdogMs (${protocol.rxWatchdogMs}) must be in 1000..60000 ms."
        }
        require(protocol.rsaCiphertextBytes in 64..512) {
            "rsaCiphertextBytes (${protocol.rsaCiphertextBytes}) must be in 64..512 bytes."
        }
        require(protocol.maxRouteTitleBytes in 8..256) {
            "maxRouteTitleBytes (${protocol.maxRouteTitleBytes}) must be in 8..256 bytes."
        }
        require(protocol.maxMediaFieldChars in 4..128) {
            "maxMediaFieldChars (${protocol.maxMediaFieldChars}) must be in 4..128 chars."
        }
    }

    private fun validateCommands(c: CommandProtocolConfig) {
        validateTlvSpec(c.authRequest, "commands.authRequest")
        validateTag(c.authSendKeyTag, "commands.authSendKeyTag")
        validateTag(c.authModulusTag, "commands.authModulusTag")
        validateTag(c.authExponentTag, "commands.authExponentTag")
        validateTag(c.authStatusTag, "commands.authStatusTag")
        require(c.authConfirmByte in 0..255) { "commands.authConfirmByte must be in 0..255." }

        validateTag(c.hostnameTag, "commands.hostnameTag")
        validateTag(c.timeSyncTag, "commands.timeSyncTag")

        require(c.initialBurst.size <= 32) { "commands.initialBurst cannot exceed 32 packets." }
        c.initialBurst.forEachIndexed { pktIdx, pkt ->
            require(pkt.isNotEmpty() && pkt.size <= 32) {
                "commands.initialBurst[$pktIdx] must contain 1..32 entries."
            }
            pkt.forEach { item ->
                if (item is BurstItem.Static) {
                    validateTlvSpec(item.tlv, "commands.initialBurst[$pktIdx]")
                }
            }
        }

        require(c.heartbeat.size in 1..32) { "commands.heartbeat must contain 1..32 entries." }
        c.heartbeat.forEach { item ->
            if (item is HeartbeatItem.Static) {
                validateTlvSpec(item.tlv, "commands.heartbeat")
            }
        }
        validateTag(c.heartbeatTempTag, "commands.heartbeatTempTag")
        require(c.heartbeatTempOffset in -100..200) { "commands.heartbeatTempOffset must be in -100..200." }
        require(c.heartbeatDefaultTempC in -40..100) { "commands.heartbeatDefaultTempC must be in -40..100." }

        validateTlvSpecList(c.navContext, "commands.navContext", minSize = 0)
        validateTlvSpecList(c.emptyLists, "commands.emptyLists", minSize = 0)
        validateTlvSpecList(c.navStart, "commands.navStart", minSize = 1)
        validateTlvSpecList(c.navPlaceholder, "commands.navPlaceholder", minSize = 0)
        validateTlvSpecList(c.projectionFrame, "commands.projectionFrame", minSize = 1)
        validateTlvSpecList(c.projectionOn, "commands.projectionOn", minSize = 1)
        validateTlvSpecList(c.projectionStop, "commands.projectionStop", minSize = 1)
        validateTlvSpecList(c.projectionOff, "commands.projectionOff", minSize = 1)

        validateTlvSpec(c.frameDecodedIdrNotify, "commands.frameDecodedIdrNotify")
        validateTlvSpecList(c.frameDecodedIdrAck, "commands.frameDecodedIdrAck", minSize = 1)
        validateTlvSpec(c.frameDecodedPNotify, "commands.frameDecodedPNotify")
        validateTlvSpecList(c.frameDecodedPAck, "commands.frameDecodedPAck", minSize = 1)

        validateTag(c.buttonEventTag, "commands.buttonEventTag")
        validateTag(c.buttonAckTag, "commands.buttonAckTag")
        require(c.telemetryEncryptedType in 0..255) { "commands.telemetryEncryptedType must be in 0..255." }
        require(c.telemetryPlainType in 0..255) { "commands.telemetryPlainType must be in 0..255." }

        validateTag(c.nowPlayingTag, "commands.nowPlayingTag")
        validateTag(c.callNotifyTag, "commands.callNotifyTag")
        validateTag(c.albumArtTag, "commands.albumArtTag")

        val nt = c.navTags
        validateTag(nt.titleTag, "commands.navTags.titleTag")
        validateTag(nt.primaryManeuverTag, "commands.navTags.primaryManeuverTag")
        validateTag(nt.secondaryManeuverTag, "commands.navTags.secondaryManeuverTag")
        validateTag(nt.primaryDistTag, "commands.navTags.primaryDistTag")
        validateTag(nt.secondaryDistTag, "commands.navTags.secondaryDistTag")
        validateTag(nt.primaryUnitTag, "commands.navTags.primaryUnitTag")
        validateTag(nt.secondaryUnitTag, "commands.navTags.secondaryUnitTag")
        validateTag(nt.etaTag, "commands.navTags.etaTag")
        validateTag(nt.totalDistTag, "commands.navTags.totalDistTag")
        validateTag(nt.totalUnitTag, "commands.navTags.totalUnitTag")
        require(nt.unitMetersByte in 0..255) { "commands.navTags.unitMetersByte must be in 0..255." }
        require(nt.unitKmTenthsByte in 0..255) { "commands.navTags.unitKmTenthsByte must be in 0..255." }
        validateTag(nt.projectionFlagTag, "commands.navTags.projectionFlagTag")
        require(nt.projectionFlagOnByte in 0..255) { "commands.navTags.projectionFlagOnByte must be in 0..255." }
        require(nt.projectionFlagOffByte in 0..255) { "commands.navTags.projectionFlagOffByte must be in 0..255." }
        validateTlvSpecList(nt.routeCardExtraTlvs, "commands.navTags.routeCardExtraTlvs", minSize = 0)
        validateTlvSpecList(nt.activeNavExtraTlvs, "commands.navTags.activeNavExtraTlvs", minSize = 0)
    }

    private fun validateTag(tag: TlvTag, field: String) {
        require(tag.type in 0..255 && tag.sub in 0..255) {
            "TLV tag '$field' must have valid 0..255 type and sub bytes."
        }
    }

    private fun validateTlvSpec(spec: TlvSpec, field: String) {
        require(spec.type in 0..255 && spec.sub in 0..255) {
            "TLV spec '$field' must have valid 0..255 type and sub bytes."
        }
        require(spec.value.size <= 256) {
            "TLV spec '$field' value length (${spec.value.size}) cannot exceed 256 bytes."
        }
    }

    private fun validateTlvSpecList(list: List<TlvSpec>, field: String, minSize: Int) {
        require(list.size in minSize..32) {
            "TLV list '$field' must contain $minSize..32 entries."
        }
        list.forEach { validateTlvSpec(it, field) }
    }
}
