package io.github.saltmango.navbeam.dash.protocol

import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.dash.profile.TlvSpec
import io.github.saltmango.navbeam.dash.profile.TlvTag
import java.io.ByteArrayOutputStream

/**
 * Generic TLV control packet codec driven entirely by the active
 * [io.github.saltmango.navbeam.dash.profile.DashProfile].
 *
 * OUTGOING (app → display), big-endian:
 *   [0:2]                      outer_len    – total packet size including this field
 *   [2:4]                      seg_count    – 1 (fixed header segment) + N TLV segments
 *   [4 .. 4+fixedHeaderLen)    fixed_header – profile-defined header bytes
 *   [+0 .. +magicLen)          magic        – profile-defined ASCII magic bytes
 *   [+0]                       seq          – rolling 0–255, patched at send time (see [patchSeq])
 *   [+1 .. ]                   TLV entries  – (type:1)(sub:1)(len:2be)(value:len)
 *
 * INCOMING (display → app) starts parsing TLV segments at `protocol.incomingHeaderOffset`.
 */
data class Tlv(val type: Int, val sub: Int, val value: ByteArray = ByteArray(0)) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Tlv) return false
        return type == other.type && sub == other.sub && value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + sub
        result = 31 * result + value.contentHashCode()
        return result
    }
}

object DashPacket {
    fun activeMagic(): ByteArray = runCatching {
        DashProfileManager.activeProfile.value.protocol.magicBytes
    }.getOrDefault(ByteArray(0))

    fun activeFixedHeader(): ByteArray = runCatching {
        val proto = DashProfileManager.activeProfile.value.protocol
        proto.fixedHeaderBytes + proto.magicBytes
    }.getOrDefault(ByteArray(0))

    /** Patch active protocol fixed header and magic into packet. */
    fun patchHeader(pkt: ByteArray): ByteArray {
        val active = activeFixedHeader()
        if (active.isEmpty() || pkt.size < 4 + active.size) return pkt
        val out = pkt.copyOf()
        System.arraycopy(active, 0, out, 4, active.size)
        return out
    }

    /** Build an outgoing packet. Seq byte is left 0x00 — [patchSeq] sets it at send time. */
    fun build(vararg tlvs: Tlv): ByteArray = buildWithSegCount(1 + tlvs.size, *tlvs)

    /** Build an outgoing packet with an explicit segment count. */
    fun buildWithSegCount(segCount: Int, vararg tlvs: Tlv): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0); out.write(0) // outer_len placeholder
        out.write((segCount shr 8) and 0xFF)
        out.write(segCount and 0xFF)
        out.write(activeFixedHeader())
        out.write(0) // seq placeholder
        for (tlv in tlvs) {
            out.write(tlv.type and 0xFF)
            out.write(tlv.sub and 0xFF)
            out.write((tlv.value.size shr 8) and 0xFF)
            out.write(tlv.value.size and 0xFF)
            out.write(tlv.value)
        }

        val bytes = out.toByteArray()
        bytes[0] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes[1] = (bytes.size and 0xFF).toByte()
        return bytes
    }

    /** Patch rolling seq byte right after header+magic and fix outer_len. Returns a copy. */
    fun patchSeq(pkt: ByteArray, seq: Int): ByteArray {
        val out = patchHeader(pkt)
        val header = activeFixedHeader()
        val magic = activeMagic()
        val seqIdx = when {
            magic.isNotEmpty() -> {
                val k = indexOfBytes(out, magic)
                if (k >= 0) k + magic.size else 4 + header.size
            }
            header.isNotEmpty() -> 4 + header.size
            else -> -1
        }
        if (seqIdx in 4 until out.size) {
            out[seqIdx] = (seq and 0xFF).toByte()
        }
        if (out.size >= 2) {
            out[0] = ((out.size shr 8) and 0xFF).toByte()
            out[1] = (out.size and 0xFF).toByte()
        }
        return out
    }

    fun tlv(type: Int, sub: Int, vararg values: Int): Tlv =
        Tlv(type, sub, ByteArray(values.size) { values[it].toByte() })

    fun tlv(type: Int, sub: Int, value: ByteArray): Tlv = Tlv(type, sub, value)

    fun tlv(tag: TlvTag, vararg values: Int): Tlv = tlv(tag.type, tag.sub, *values)

    fun tlv(tag: TlvTag, value: ByteArray): Tlv = Tlv(tag.type, tag.sub, value)

    fun tlv(spec: TlvSpec): Tlv = Tlv(spec.type, spec.sub, spec.value)

    /** Parse a display → app packet starting at `protocol.incomingHeaderOffset`. */
    fun parseIncoming(
        data: ByteArray,
        headerOffset: Int = DashProfileManager.activeProfile.value.protocol.incomingHeaderOffset
    ): List<Tlv> {
        val tlvs = mutableListOf<Tlv>()
        val startOffset = headerOffset.coerceAtLeast(4)
        if (data.size < startOffset) return tlvs
        val segCount = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        var i = startOffset
        var n = 0
        while (n < segCount && i + 4 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val sub  = data[i + 1].toInt() and 0xFF
            val len  = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += 4
            val end = (i + len).coerceAtMost(data.size)
            tlvs += Tlv(type, sub, data.copyOfRange(i, end))
            i = end
            n++
        }
        return tlvs
    }

    fun indexOfBytes(b: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || b.size < target.size) return -1
        outer@ for (i in 0..b.size - target.size) {
            for (j in target.indices) if (b[i + j] != target[j]) continue@outer
            return i
        }
        return -1
    }
}
