package io.github.saltmango.navbeam.dash.video

import java.util.Random

/**
 * RFC 6184 H.264 RTP packetizer driven by the active display profile.
 *
 * - Single-NAL emission for NALs <= [maxPayload]
 * - FU-A (type 28) fragmentation for larger NALs
 * - Marker bit on the last RTP packet of each access unit
 * - 90 kHz RTP clock
 *
 * Uses a single pre-allocated [sendBuffer] for zero-allocation packet assembly.
 */
class RtpPacketizer(
    maxPayload: Int,
    payloadType: Int,
    private val onPacket: (ByteArray, Int) -> Unit,
) {
    private val maxPayload = maxPayload.coerceIn(256, 1460)
    private val pt = payloadType.coerceIn(96, 127)

    private val rng    = Random()
    private var seq    = rng.nextInt(0xFFFF)
    private val ssrc   = rng.nextInt()
    private val tsBase = rng.nextInt().toLong() and 0xFFFFFFFFL

    private val sendBuffer = ByteArray(12 + this.maxPayload)

    /**
     * Packetize a single NAL unit.
     *
     * @param nal         raw NAL bytes (no start code)
     * @param endOfAU     true if this is the last NAL in the access unit (triggers marker bit)
     * @param wallClockMs monotonic wall clock in milliseconds
     */
    fun packetize(nal: ByteArray, endOfAU: Boolean, wallClockMs: Long) {
        val ts = (tsBase + wallClockMs * 90L) and 0xFFFFFFFFL
        if (nal.size <= maxPayload) {
            emit(nal, offset = 0, length = nal.size, marker = endOfAU, ts = ts)
        } else {
            fuA(nal, endOfAU, ts)
        }
    }

    private fun fuA(nal: ByteArray, endOfAU: Boolean, ts: Long) {
        val nalType = nal[0].toInt() and 0x1F
        val fuInd   = ((nal[0].toInt() and 0xE0) or 28).toByte()
        var offset  = 1
        var isFirst = true
        while (offset < nal.size) {
            val remaining = nal.size - offset
            val chunkLen  = minOf(maxPayload - 2, remaining)
            val isLast    = chunkLen >= remaining

            val fuHeader = ((if (isFirst) 0x80 else 0) or
                            (if (isLast)  0x40 else 0) or nalType).toByte()

            sendBuffer[12] = fuInd
            sendBuffer[13] = fuHeader
            nal.copyInto(sendBuffer, destinationOffset = 14, startIndex = offset, endIndex = offset + chunkLen)

            emitFromBuffer(
                payloadOffset = 12,
                payloadLength = 2 + chunkLen,
                marker        = isLast && endOfAU,
                ts            = ts,
            )
            offset += chunkLen
            isFirst = false
        }
    }

    private fun emit(nal: ByteArray, offset: Int, length: Int, marker: Boolean, ts: Long) {
        nal.copyInto(sendBuffer, destinationOffset = 12, startIndex = offset, endIndex = offset + length)
        emitFromBuffer(payloadOffset = 12, payloadLength = length, marker = marker, ts = ts)
    }

    private fun emitFromBuffer(payloadOffset: Int, payloadLength: Int, marker: Boolean, ts: Long) {
        val totalLen = payloadOffset + payloadLength
        sendBuffer[0] = 0x80.toByte()
        sendBuffer[1] = ((if (marker) 0x80 else 0x00) or (pt and 0x7F)).toByte()
        sendBuffer[2] = ((seq shr 8) and 0xFF).toByte()
        sendBuffer[3] = (seq and 0xFF).toByte()
        sendBuffer[4] = ((ts shr 24) and 0xFF).toByte()
        sendBuffer[5] = ((ts shr 16) and 0xFF).toByte()
        sendBuffer[6] = ((ts shr  8) and 0xFF).toByte()
        sendBuffer[7] = (ts and 0xFF).toByte()
        val s = ssrc.toLong() and 0xFFFFFFFFL
        sendBuffer[8]  = ((s shr 24) and 0xFF).toByte()
        sendBuffer[9]  = ((s shr 16) and 0xFF).toByte()
        sendBuffer[10] = ((s shr  8) and 0xFF).toByte()
        sendBuffer[11] = (s and 0xFF).toByte()

        seq = (seq + 1) and 0xFFFF
        onPacket(sendBuffer, totalLen)
    }
}
