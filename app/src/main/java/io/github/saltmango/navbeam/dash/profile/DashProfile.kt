package io.github.saltmango.navbeam.dash.profile

enum class DisplayShape {
    CIRCLE,
    RECTANGLE;

    companion object {
        fun fromString(value: String?): DisplayShape? = when (value?.trim()?.uppercase()) {
            "CIRCLE" -> CIRCLE
            "RECTANGLE", "RECT" -> RECTANGLE
            else -> null
        }
    }
}

data class TlvTag(
    val type: Int = -1,
    val sub: Int = -1,
) {
    fun format(): String = "%02X:%02X".format(type and 0xFF, sub and 0xFF)
}

data class TlvSpec(
    val type: Int = -1,
    val sub: Int = -1,
    val value: ByteArray = ByteArray(0),
) {
    fun format(): String {
        val prefix = "%02X:%02X".format(type and 0xFF, sub and 0xFF)
        return if (value.isEmpty()) prefix
        else "$prefix:${value.joinToString("") { "%02X".format(it) }}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TlvSpec) return false
        return type == other.type && sub == other.sub && value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + sub
        result = 31 * result + value.contentHashCode()
        return result
    }
}

sealed class BurstItem {
    object AuthRequest : BurstItem()
    object Hostname : BurstItem()
    object TimeSync : BurstItem()
    data class Static(val tlv: TlvSpec) : BurstItem()
}

sealed class HeartbeatItem {
    object Temperature : HeartbeatItem()
    data class Static(val tlv: TlvSpec) : HeartbeatItem()
}

data class NetworkConfig(
    val defaultSsidPrefix: String = "",
    val defaultPassword: String = "",
    val gatewayIp: String = "",
    val broadcastIp: String? = null,
    val controlTxPort: Int = 0,
    val controlRxPort: Int = 0,
    val videoRtpPort: Int = 0,
) {
    /** Returns explicit broadcastIp or derives subnet broadcast from gatewayIp. */
    val resolvedBroadcastIp: String
        get() = broadcastIp?.takeIf { it.isNotBlank() }
            ?: (if (gatewayIp.contains('.')) "${gatewayIp.substringBeforeLast('.')}.255" else "")
}

data class DisplayConfig(
    val width: Int = 0,
    val height: Int = 0,
    val shape: DisplayShape? = null,
    val frameDurationActiveMs: Long = 0L,
    val frameDurationIdleMs: Long = 0L,
    val motionHoldMs: Long = 0L,
    val activeBitrateBps: Int = 0,
    val idleBitrateBps: Int = 0,
    val h264Level: String = "",
    val prependSpsPpsToIdr: Boolean = false,
    val forceSpsBaseline: Boolean = false,
    val dropSeiAndAud: Boolean = false,
    val rtpMaxPayloadBytes: Int = 0,
    val rtpPayloadType: Int = 0,
)

data class ProtocolConfig(
    val magic: String = "",
    val fixedHeaderHex: String = "",
    val incomingHeaderOffset: Int = 0,
    val authType: String = "",
    val projKeepAliveMs: Long = 0L,
    val routeCardKeepAliveMs: Long = 0L,
    val authTimeoutMs: Long = 0L,
    val maxAuthAttempts: Int = 0,
    val burstPauseMs: Long = 0L,
    val rxWatchdogMs: Long = 0L,
    val rsaCiphertextBytes: Int = 0,
    val maxRouteTitleBytes: Int = 0,
    val maxMediaFieldChars: Int = 0,
) {
    val magicBytes: ByteArray get() = magic.toByteArray(Charsets.US_ASCII)
    val fixedHeaderBytes: ByteArray
        get() = runCatching {
            if (fixedHeaderHex.length >= 2 && fixedHeaderHex.length % 2 == 0) {
                fixedHeaderHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else ByteArray(0)
        }.getOrDefault(ByteArray(0))
}

data class NavTagsConfig(
    val titleTag: TlvTag = TlvTag(),
    val primaryManeuverTag: TlvTag = TlvTag(),
    val secondaryManeuverTag: TlvTag = TlvTag(),
    val primaryDistTag: TlvTag = TlvTag(),
    val secondaryDistTag: TlvTag = TlvTag(),
    val primaryUnitTag: TlvTag = TlvTag(),
    val secondaryUnitTag: TlvTag = TlvTag(),
    val etaTag: TlvTag = TlvTag(),
    val totalDistTag: TlvTag = TlvTag(),
    val totalUnitTag: TlvTag = TlvTag(),
    val unitMetersByte: Int = -1,
    val unitKmTenthsByte: Int = -1,
    val projectionFlagTag: TlvTag = TlvTag(),
    val projectionFlagOnByte: Int = -1,
    val projectionFlagOffByte: Int = -1,
    val routeCardExtraTlvs: List<TlvSpec> = emptyList(),
    val activeNavExtraTlvs: List<TlvSpec> = emptyList(),
)

data class CommandProtocolConfig(
    val authRequest: TlvSpec = TlvSpec(),
    val authSendKeyTag: TlvTag = TlvTag(),
    val authModulusTag: TlvTag = TlvTag(),
    val authExponentTag: TlvTag = TlvTag(),
    val authStatusTag: TlvTag = TlvTag(),
    val authConfirmByte: Int = -1,
    val hostnameTag: TlvTag = TlvTag(),
    val timeSyncTag: TlvTag = TlvTag(),
    val initialBurst: List<List<BurstItem>> = emptyList(),
    val heartbeat: List<HeartbeatItem> = emptyList(),
    val heartbeatTempTag: TlvTag = TlvTag(),
    val heartbeatTempOffset: Int = 0,
    val heartbeatDefaultTempC: Int = 0,
    val navContext: List<TlvSpec> = emptyList(),
    val emptyLists: List<TlvSpec> = emptyList(),
    val navStart: List<TlvSpec> = emptyList(),
    val navPlaceholder: List<TlvSpec> = emptyList(),
    val projectionFrame: List<TlvSpec> = emptyList(),
    val projectionOn: List<TlvSpec> = emptyList(),
    val projectionStop: List<TlvSpec> = emptyList(),
    val projectionOff: List<TlvSpec> = emptyList(),
    val frameDecodedIdrNotify: TlvSpec = TlvSpec(),
    val frameDecodedIdrAck: List<TlvSpec> = emptyList(),
    val frameDecodedPNotify: TlvSpec = TlvSpec(),
    val frameDecodedPAck: List<TlvSpec> = emptyList(),
    val buttonEventTag: TlvTag = TlvTag(),
    val buttonAckTag: TlvTag = TlvTag(),
    val telemetryEncryptedType: Int = -1,
    val telemetryPlainType: Int = -1,
    val nowPlayingTag: TlvTag = TlvTag(),
    val callNotifyTag: TlvTag = TlvTag(),
    val albumArtTag: TlvTag = TlvTag(),
    val navTags: NavTagsConfig = NavTagsConfig(),
)

data class DashProfile(
    val id: String = "",
    val name: String = "",
    val version: Int = 0,
    val firmwareVersion: String = "",
    val description: String = "",
    val network: NetworkConfig = NetworkConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val protocol: ProtocolConfig = ProtocolConfig(),
    val commands: CommandProtocolConfig = CommandProtocolConfig(),
    val inputs: Map<String, Int> = emptyMap(),
    val maneuvers: Map<String, Int> = emptyMap(),
) {
    val isConfigured: Boolean get() = id.isNotBlank()
}
