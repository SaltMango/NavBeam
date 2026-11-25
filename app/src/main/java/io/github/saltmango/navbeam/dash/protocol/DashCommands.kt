package io.github.saltmango.navbeam.dash.protocol

import io.github.saltmango.navbeam.dash.profile.BurstItem
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.dash.profile.HeartbeatItem
import java.io.ByteArrayOutputStream

/**
 * Control-plane command builders driven exclusively by the active
 * [io.github.saltmango.navbeam.dash.profile.DashProfile].
 * No vendor headers, magic bytes, TLV codes, or protocol payloads are hardcoded here.
 */
object DashCommands {

    private val profile get() = DashProfileManager.activeProfile.value
    private val cmd get() = profile.commands
    private val proto get() = profile.protocol
    private val nav get() = cmd.navTags

    fun maneuverCode(key: String, fallback: Int = 0): Int =
        profile.maneuvers[key] ?: fallback

    fun unitMeters(): Int = nav.unitMetersByte
    fun unitKmTenths(): Int = nav.unitKmTenthsByte

    // ── Auth ──────────────────────────────────────────────────────────────
    fun authRequest(): ByteArray =
        DashPacket.build(DashPacket.tlv(cmd.authRequest))

    fun authSendKey(ciphertext: ByteArray): ByteArray {
        val expected = proto.rsaCiphertextBytes
        require(expected <= 0 || ciphertext.size == expected) {
            "authSendKey expects ${expected}B ciphertext, got ${ciphertext.size}"
        }
        return DashPacket.build(DashPacket.tlv(cmd.authSendKeyTag, ciphertext))
    }

    // ── Initial burst ─────────────────────────────────────────────────────
    fun initialBurst(hostname: String = "NavBeam"): List<ByteArray> =
        cmd.initialBurst.map { pktSpec ->
            val tlvs = pktSpec.map { item ->
                when (item) {
                    BurstItem.AuthRequest -> DashPacket.tlv(cmd.authRequest)
                    BurstItem.Hostname -> hostnameTlv(hostname)
                    BurstItem.TimeSync -> timeSyncTlv()
                    is BurstItem.Static -> DashPacket.tlv(item.tlv)
                }
            }.toTypedArray()
            DashPacket.build(*tlvs)
        }

    fun timeSync(): ByteArray = DashPacket.build(timeSyncTlv())

    private fun timeSyncTlv(): Tlv {
        val cal = java.util.Calendar.getInstance()
        return DashPacket.tlv(
            cmd.timeSyncTag,
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    fun hostnameAnnounce(hostname: String = "NavBeam"): ByteArray =
        DashPacket.build(hostnameTlv(hostname))

    private fun hostnameTlv(hostname: String): Tlv {
        val raw = hostname.toByteArray(Charsets.UTF_8).let {
            if (it.size > 200) it.copyOf(200) else it
        }
        return DashPacket.tlv(cmd.hostnameTag, raw + 0x00.toByte())
    }

    // ── Navigation mode & projection control ──────────────────────────────
    fun navContext(): ByteArray =
        DashPacket.build(*cmd.navContext.map { DashPacket.tlv(it) }.toTypedArray())

    fun emptyLists(): ByteArray =
        DashPacket.build(*cmd.emptyLists.map { DashPacket.tlv(it) }.toTypedArray())

    fun navStart(): ByteArray =
        DashPacket.build(*cmd.navStart.map { DashPacket.tlv(it) }.toTypedArray())

    fun navPlaceholder(): ByteArray =
        DashPacket.build(*cmd.navPlaceholder.map { DashPacket.tlv(it) }.toTypedArray())

    fun projectionFrame(): ByteArray =
        DashPacket.build(*cmd.projectionFrame.map { DashPacket.tlv(it) }.toTypedArray())

    fun projectionOn(): ByteArray =
        DashPacket.build(*cmd.projectionOn.map { DashPacket.tlv(it) }.toTypedArray())

    fun projectionStop(): ByteArray =
        DashPacket.build(*cmd.projectionStop.map { DashPacket.tlv(it) }.toTypedArray())

    fun projectionOff(): ByteArray =
        DashPacket.build(*cmd.projectionOff.map { DashPacket.tlv(it) }.toTypedArray())

    // ── Frame-decoded acknowledgements & button acks ──────────────────────
    fun frameDecodedIdr(): ByteArray =
        DashPacket.build(*cmd.frameDecodedIdrAck.map { DashPacket.tlv(it) }.toTypedArray())

    fun frameDecodedP(): ByteArray =
        DashPacket.build(*cmd.frameDecodedPAck.map { DashPacket.tlv(it) }.toTypedArray())

    fun buttonAck(code: Byte): ByteArray =
        DashPacket.build(DashPacket.tlv(cmd.buttonAckTag, code.toInt() and 0xFF))

    // ── Status heartbeat ──────────────────────────────────────────────────
    fun heartbeat(tempC: Int = cmd.heartbeatDefaultTempC): ByteArray {
        val tlvs = cmd.heartbeat.map { item ->
            when (item) {
                HeartbeatItem.Temperature -> DashPacket.tlv(
                    cmd.heartbeatTempTag,
                    (tempC + cmd.heartbeatTempOffset) and 0xFF
                )
                is HeartbeatItem.Static -> DashPacket.tlv(item.tlv)
            }
        }.toTypedArray()
        return DashPacket.build(*tlvs)
    }

    // ── Route card & live navigation info ─────────────────────────────────
    fun routeCard(
        title: String,
        projectionOn: Boolean = false,
        maneuver: Int? = null,
        secondaryManeuver: Int? = null,
        secondaryDist: Int? = null,
        secondaryUnit: Int? = null,
        primaryDist: Int? = null,
        primaryUnit: Int? = null,
        totalDist: Int? = null,
        totalUnit: Int? = null,
        etaHHMM: String? = null,
    ): ByteArray {
        val maxTitle = proto.maxRouteTitleBytes.coerceAtLeast(1)
        val rt = title.toByteArray(Charsets.UTF_8).let {
            if (it.size > maxTitle) it.copyOf(maxTitle) else it
        } + 0x00.toByte()

        val primManeuver = maneuver ?: maneuverCode("DEPART")
        val secManeuver = secondaryManeuver ?: maneuverCode("BLANK")
        val secDist = secondaryDist ?: 0
        val tDist = totalDist ?: 0
        val pUnit = primaryUnit ?: nav.unitMetersByte
        val sUnit = secondaryUnit ?: nav.unitMetersByte
        val tUnit = totalUnit ?: nav.unitKmTenthsByte
        val etaBytes = if (etaHHMM != null && etaHHMM.length == 4) {
            etaHHMM.toByteArray(Charsets.US_ASCII)
        } else {
            "0000".toByteArray(Charsets.US_ASCII)
        }

        val tlvs = mutableListOf(
            DashPacket.tlv(nav.titleTag, rt),
            DashPacket.tlv(nav.primaryManeuverTag, primManeuver and 0xFF),
            DashPacket.tlv(nav.secondaryManeuverTag, secManeuver and 0xFF),
        )
        if (primaryDist != null) {
            tlvs += DashPacket.tlv(nav.primaryDistTag, (primaryDist shr 8) and 0xFF, primaryDist and 0xFF)
        }
        tlvs += listOf(
            DashPacket.tlv(nav.secondaryDistTag, (secDist shr 8) and 0xFF, secDist and 0xFF),
            DashPacket.tlv(nav.primaryUnitTag, pUnit and 0xFF),
            DashPacket.tlv(nav.secondaryUnitTag, sUnit and 0xFF),
            DashPacket.tlv(nav.etaTag, etaBytes),
            DashPacket.tlv(nav.totalDistTag, (tDist shr 8) and 0xFF, tDist and 0xFF),
            DashPacket.tlv(nav.totalUnitTag, tUnit and 0xFF),
            DashPacket.tlv(
                nav.projectionFlagTag,
                (if (projectionOn) nav.projectionFlagOnByte else nav.projectionFlagOffByte) and 0xFF
            ),
        )
        nav.routeCardExtraTlvs.forEach { tlvs += DashPacket.tlv(it) }
        return DashPacket.build(*tlvs.toTypedArray())
    }

    fun activeNavPacket(
        maneuver: Int = maneuverCode("STRAIGHT"),
        primaryDist: Int = 0,
        primaryUnit: Int = nav.unitMetersByte,
        totalDist: Int = 0,
        totalUnit: Int = nav.unitMetersByte,
        secondaryManeuver: Int = maneuverCode("BLANK"),
        secondaryDist: Int = 0,
        secondaryUnit: Int = nav.unitMetersByte,
        projectionOn: Boolean = true,
        roadName: String? = null,
    ): ByteArray {
        val maxTitle = proto.maxRouteTitleBytes.coerceAtLeast(1)
        val tlvs = mutableListOf<Tlv>()
        if (roadName != null) {
            val rt = roadName.toByteArray(Charsets.UTF_8).let {
                if (it.size > maxTitle) it.copyOf(maxTitle) else it
            } + 0x00.toByte()
            tlvs += DashPacket.tlv(nav.titleTag, rt)
        }

        tlvs += DashPacket.tlv(nav.primaryManeuverTag, maneuver and 0xFF)
        tlvs += DashPacket.tlv(nav.primaryDistTag, (primaryDist shr 8) and 0xFF, primaryDist and 0xFF)
        tlvs += DashPacket.tlv(nav.primaryUnitTag, primaryUnit and 0xFF)

        if (!projectionOn) {
            tlvs += DashPacket.tlv(nav.secondaryManeuverTag, secondaryManeuver and 0xFF)
            tlvs += DashPacket.tlv(nav.secondaryDistTag, (secondaryDist shr 8) and 0xFF, secondaryDist and 0xFF)
            tlvs += DashPacket.tlv(nav.secondaryUnitTag, secondaryUnit and 0xFF)
        }

        tlvs += DashPacket.tlv(nav.totalDistTag, (totalDist shr 8) and 0xFF, totalDist and 0xFF)
        tlvs += DashPacket.tlv(nav.totalUnitTag, totalUnit and 0xFF)
        tlvs += DashPacket.tlv(
            nav.projectionFlagTag,
            (if (projectionOn) nav.projectionFlagOnByte else nav.projectionFlagOffByte) and 0xFF
        )
        nav.activeNavExtraTlvs.forEach { tlvs += DashPacket.tlv(it) }

        return DashPacket.build(*tlvs.toTypedArray())
    }

    // ── Media now-playing + telephony ─────────────────────────────────────
    private fun mediaField(s: String): ByteArray {
        val maxLen = proto.maxMediaFieldChars.coerceAtLeast(1)
        val t = if (s.length > maxLen) s.substring(0, maxLen) else s
        return t.toByteArray(Charsets.UTF_8)
    }

    fun nowPlaying(title: String, album: String, artist: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(mediaField(title)); out.write(0x00)
        out.write(mediaField(album)); out.write(0x00)
        out.write(mediaField(artist))
        return DashPacket.build(DashPacket.tlv(cmd.nowPlayingTag, out.toByteArray()))
    }

    fun callNotify(callerName: String): ByteArray {
        val name = mediaField(callerName)
        return DashPacket.build(DashPacket.tlv(cmd.callNotifyTag, name + 0x00.toByte()))
    }

    fun callClear(): ByteArray =
        DashPacket.build(DashPacket.tlv(cmd.callNotifyTag, byteArrayOf(0x00)))

    fun albumArtFrames(jpeg: ByteArray, chunk: Int = 1000): List<ByteArray> =
        jpeg.toList().chunked(chunk).map { c ->
            DashPacket.build(DashPacket.tlv(cmd.albumArtTag, c.toByteArray()))
        }
}
