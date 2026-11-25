package io.github.saltmango.navbeam.dash.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * MediaCodec H.264 encoder configured entirely by the active display profile
 * (resolution, FPS hint, CBR bitrate, and AVC level).
 */
class DashEncoder(
    private val onEncodedData: (ByteArray, Boolean) -> Unit,
    private val forceSoftware: Boolean = false,
    val width: Int,
    val height: Int,
    val fps: Int,
    val initialBitrate: Int,
    val h264Level: String,
) : EncoderWrapper {
    companion object {
        const val ENCODER_RELEASE_TIMEOUT_MS = 15_000L

        private const val MIME             = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val TAG              = "DashEncoder"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    var encoderName: String = ""; private set

    private var currentBitrate = initialBitrate
    private val bitrateBundle  = Bundle(1)

    override fun prepare() {
        val baseFormat = buildFormat(includeLevel = true)
        val name = if (forceSoftware) selectSoftwareEncoder(baseFormat) else selectHardwareEncoder(baseFormat)
        val tryCreate: (String?, MediaFormat) -> MediaCodec = { codecName, fmt ->
            (if (codecName != null) MediaCodec.createByCodecName(codecName)
             else MediaCodec.createEncoderByType(MIME)).also { c ->
                encoderName = c.name
                Log.i(TAG, "Encoder: $encoderName (forcedSoftware=$forceSoftware)")
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
            }
        }

        codec = runCatching { tryCreate(name, baseFormat) }.getOrElse { e1 ->
            Log.w(TAG, "Encoder $name rejected format with level: ${e1.message}. Retrying without level...")
            runCatching { tryCreate(name, buildFormat(includeLevel = false)) }.getOrElse { e2 ->
                Log.w(TAG, "Encoder $name rejected level-less format: ${e2.message}. Falling back to default system encoder...")
                runCatching { tryCreate(null, baseFormat) }.getOrElse { e3 ->
                    Log.w(TAG, "Default encoder rejected format with level: ${e3.message}. Retrying without level...")
                    tryCreate(null, buildFormat(includeLevel = false))
                }
            }
        }
    }

    override fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release(); inputSurface = null
        currentBitrate = initialBitrate
    }

    override fun renderFrame(drawBlock: (Canvas) -> Unit) {
        val surface = inputSurface ?: return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (_: Exception) {
            return
        }
        try {
            drawBlock(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    override fun drain() {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx) ?: run {
                        codec.releaseOutputBuffer(idx, false); continue
                    }
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    if (info.size > 0) {
                        val data = ByteArray(info.size).also { buf.get(it) }
                        onEncodedData(data, isKey)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    override fun requestBitrate(bitrate: Int) {
        val c = codec ?: return
        if (bitrate == currentBitrate) return
        currentBitrate = bitrate
        bitrateBundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
        runCatching {
            c.setParameters(bitrateBundle)
        }.onFailure { Log.w(TAG, "requestBitrate($bitrate) failed: ${it.message}") }
    }

    override fun requestSyncFrame() {
        val c = codec ?: return
        runCatching {
            c.setParameters(Bundle(1).apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            Log.d(TAG, "IDR sync frame requested")
        }.onFailure { Log.w(TAG, "requestSyncFrame failed: ${it.message}") }
    }

    private fun buildFormat(includeLevel: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, initialBitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (includeLevel) {
                setInteger(MediaFormat.KEY_LEVEL, resolveAvcLevel(h264Level))
            }
        }

    private fun resolveAvcLevel(level: String): Int = when (level.trim()) {
        "3.0", "3" -> MediaCodecInfo.CodecProfileLevel.AVCLevel3
        "3.1"      -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
        "3.2"      -> MediaCodecInfo.CodecProfileLevel.AVCLevel32
        "4.0", "4" -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
        "4.1"      -> MediaCodecInfo.CodecProfileLevel.AVCLevel41
        "4.2"      -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
        "5.0", "5" -> MediaCodecInfo.CodecProfileLevel.AVCLevel5
        "5.1"      -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
        else       -> MediaCodecInfo.CodecProfileLevel.AVCLevel41
    }

    private fun selectHardwareEncoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME, true) }) continue
                if (info.isHardwareAccelerated && !info.isSoftwareOnly) return info.name
            }
        }
        return runCatching { list.findEncoderForFormat(format) }.getOrNull()
    }

    private fun selectSoftwareEncoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MIME, true) }) continue
            if (info.isSoftwareOnly || info.name.contains("google", ignoreCase = true) || info.name.contains("android", ignoreCase = true)) {
                return info.name
            }
        }
        return runCatching { list.findEncoderForFormat(format) }.getOrNull()
    }
}
