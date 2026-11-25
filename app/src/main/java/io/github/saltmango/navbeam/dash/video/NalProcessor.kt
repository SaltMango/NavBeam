package io.github.saltmango.navbeam.dash.video

import io.github.saltmango.navbeam.util.Dbg

/**
 * Splits Annex-B H.264 output from MediaCodec into individual NAL units and
 * applies the active display profile's NAL framing rules (`prependSpsPpsToIdr`,
 * `forceSpsBaseline`, `dropSeiAndAud`, `h264LevelIdc`).
 */
class NalProcessor(
    private val onSpsProfileMismatch: () -> Unit = {},
    private val prependSpsPpsToIdr: Boolean,
    private val forceSpsBaseline: Boolean,
    private val dropSeiAndAud: Boolean,
    private val h264LevelIdc: Int,
    private val onNal: (ByteArray, Boolean) -> Unit
) {
    companion object {
        fun levelIdcFromString(level: String): Int = when (level.trim()) {
            "3.0", "3" -> 30
            "3.1"      -> 31
            "3.2"      -> 32
            "4.0", "4" -> 40
            "4.1"      -> 41
            "4.2"      -> 42
            "5.0", "5" -> 50
            "5.1"      -> 51
            else       -> 41
        }
    }

    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
    private val TAG = "NalProcessor"

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var loggedParams = false
    private var idrCount = 0

    fun process(annexB: ByteArray) {
        for (nal in split(annexB)) {
            if (nal.isEmpty()) continue
            when (val type = nal[0].toInt() and 0x1F) {
                7    -> {
                    val normalized = if (forceSpsBaseline) normalizeSpsBaseline(nal) else nal
                    sps = normalized
                    if (!prependSpsPpsToIdr) onNal(normalized, false)
                }
                8    -> {
                    pps = nal
                    if (!prependSpsPpsToIdr) onNal(nal, false)
                }
                5    -> emitIdr(nal)
                6, 9 -> if (!dropSeiAndAud) onNal(nal, false)
                else -> if (type in 1..4 || type in 10..12) onNal(nal, false)
            }
        }
    }

    /**
     * Normalize SPS profile/constraint/level bytes to Baseline with the profile's
     * target level IDC when required by the active display profile.
     */
    private fun normalizeSpsBaseline(sps: ByteArray): ByteArray {
        if (sps.size >= 4 && (sps[0].toInt() and 0x1F) == 7) {
            val profile = sps[1].toInt() and 0xFF
            if (profile != 0x42) {
                android.util.Log.w(TAG, "Detected non-Baseline SPS profile: 0x${profile.toString(16).uppercase()}. Triggering fallback...")
                onSpsProfileMismatch()
            }
            val out = sps.copyOf()
            out[1] = 0x42.toByte()
            out[2] = 0x00.toByte()
            out[3] = (h264LevelIdc and 0xFF).toByte()
            return out
        }
        return sps
    }

    private fun emitIdr(idr: ByteArray) {
        if (!prependSpsPpsToIdr) {
            if (++idrCount <= 3) android.util.Log.w(TAG, "emit IDR #$idrCount (${idr.size}B standalone)")
            onNal(idr, true)
            return
        }
        val s = sps; val p = pps
        if (!loggedParams && s != null && p != null) {
            loggedParams = true
            Dbg.i(TAG) { "SPS=${s.hex()} PPS=${p.hex()}" }
        }
        if (s == null || p == null) {
            android.util.Log.w(TAG, "IDR with no SPS/PPS cached — display may not decode")
        }
        val nal = if (s != null && p != null) {
            s + START_CODE_4 + p + START_CODE_4 + idr
        } else idr
        if (++idrCount <= 3) android.util.Log.w(TAG, "emit IDR #$idrCount (${nal.size}B bundled)")
        onNal(nal, true)
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    /** Split Annex-B stream on 4-byte (0x00000001) or 3-byte (0x000001) start codes. */
    private fun split(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i < data.size) {
            val sc4 = i + 3 < data.size &&
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            val sc3 = !sc4 && i + 2 < data.size &&
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                data[i+2] == 1.toByte()
            when {
                sc4 -> { if (start >= 0) nals += data.copyOfRange(start, i); start = i + 4; i += 4 }
                sc3 -> { if (start >= 0) nals += data.copyOfRange(start, i); start = i + 3; i += 3 }
                else -> i++
            }
        }
        if (start in 0 until data.size) nals += data.copyOfRange(start, data.size)
        return nals
    }
}
