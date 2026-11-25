package io.github.saltmango.navbeam.dash.video

import android.graphics.Bitmap
import io.github.saltmango.navbeam.dash.DashSession
import io.github.saltmango.navbeam.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultFrameStreamer(
    private val encoderFactory: (((ByteArray, Boolean) -> Unit, Boolean) -> EncoderWrapper)? = null
) : FrameStreamer {

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.encoder)

    private var session: DashSession? = null
    private var encoder: EncoderWrapper? = null
    private var config: StreamerConfig? = null
    private var failureListener: StreamFailureListener? = null

    private var useSoftwareEncoder = false
    private var videoPtsMs = 0L
    private var frameDurationMs = 0L

    @Volatile private var encoderTimeSum = 0L
    @Volatile private var encoderTimeCount = 0
    @Volatile private var encoderSkippedCount = 0
    @Volatile private var rtpLatencySum = 0L
    @Volatile private var rtpLatencyCount = 0
    @Volatile private var failures = 0
    @Volatile private var loggedFirstFrame = false
    @Volatile private var totalBytesSent = 0L

    override suspend fun start(config: StreamerConfig) = withContext(AppDispatchers.encoder) {
        this@DefaultFrameStreamer.config = config
        this@DefaultFrameStreamer.session = config.session
        this@DefaultFrameStreamer.frameDurationMs = config.initialFrameDurationMs
        this@DefaultFrameStreamer.useSoftwareEncoder = config.forceSoftware
        this@DefaultFrameStreamer.failures = 0
        this@DefaultFrameStreamer.loggedFirstFrame = false
        this@DefaultFrameStreamer.videoPtsMs = 0L
        this@DefaultFrameStreamer.totalBytesSent = 0L

        rebuildEncoder(config.forceSoftware)

        scope.launch {
            if (config.session.state.value != io.github.saltmango.navbeam.dash.DashState.IDLE) {
                while (config.session.state.value != io.github.saltmango.navbeam.dash.DashState.PROJECTING &&
                       config.session.state.value != io.github.saltmango.navbeam.dash.DashState.STREAMING) {
                    kotlinx.coroutines.delay(50)
                }
            }
            config.session.startStreaming()
            config.session.updateNowPlaying(null, "", "")
        }
        Unit
    }

    override suspend fun submitFrame(bitmap: Bitmap) {
        withContext(AppDispatchers.encoder) {
            val enc = encoder
            if (enc != null) {
                try {
                    val start = System.currentTimeMillis()
                    enc.renderFrame { canvas -> canvas.drawBitmap(bitmap, 0f, 0f, null) }
                    enc.drain()
                    val duration = System.currentTimeMillis() - start
                    encoderTimeSum += duration
                    encoderTimeCount++
                    videoPtsMs += frameDurationMs
                } catch (e: Exception) {
                    failures++
                    logE("Frame render/encode failed (failure $failures/3)", e)
                    if (failures >= 3) {
                        logE("Persistent encoding failures — aborting stream", e)
                        failureListener?.onStreamingFailed(e)
                    } else {
                        rebuildEncoder(useSoftwareEncoder)
                    }
                }
            } else {
                encoderSkippedCount++
            }
        }
    }

    override fun setFrameDuration(durationMs: Long) {
        scope.launch {
            frameDurationMs = durationMs
        }
    }

    override fun stop() {
        scope.launch {
            runCatching { encoder?.release() }
            encoder = null
            session = null
            config = null
        }
    }

    override fun updateBitrate(bitrate: Int) {
        scope.launch {
            encoder?.requestBitrate(bitrate)
        }
    }

    override fun setFailureListener(listener: StreamFailureListener) {
        this.failureListener = listener
    }

    override fun getMetrics(): StreamerMetrics {
        return StreamerMetrics(
            encoderTimeSum = encoderTimeSum,
            encoderTimeCount = encoderTimeCount,
            encoderSkippedCount = encoderSkippedCount,
            rtpLatencySum = rtpLatencySum,
            rtpLatencyCount = rtpLatencyCount,
            totalBytesSent = totalBytesSent,
            encoderMode = if (useSoftwareEncoder) "Software" else "Hardware"
        )
    }

    override fun isRunning(): Boolean {
        return config != null
    }

    private fun rebuildEncoder(forceSoftware: Boolean) {
        val oldEncoder = encoder
        encoder = null
        try {
            oldEncoder?.release()

            val cfg = config ?: throw IllegalStateException("StreamerConfig missing")
            val disp = io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value.display

            val maxPayload = if (cfg.rtpMaxPayloadBytes > 0) cfg.rtpMaxPayloadBytes else disp.rtpMaxPayloadBytes
            val payloadType = if (cfg.rtpPayloadType > 0) cfg.rtpPayloadType else disp.rtpPayloadType
            val h264Level = cfg.h264Level.ifBlank { disp.h264Level }
            val prependSpsPps = if (disp.width > 0) disp.prependSpsPpsToIdr else cfg.prependSpsPpsToIdr
            val forceBaseline = if (disp.width > 0) disp.forceSpsBaseline else cfg.forceSpsBaseline
            val dropSeiAud = if (disp.width > 0) disp.dropSeiAndAud else cfg.dropSeiAndAud

            val packetizer = RtpPacketizer(
                maxPayload = maxPayload,
                payloadType = payloadType,
            ) { buf, len ->
                val rtpStart = System.currentTimeMillis()
                session?.sendRtp(buf, len)
                val rtpDuration = System.currentTimeMillis() - rtpStart
                rtpLatencySum += rtpDuration
                rtpLatencyCount++
                totalBytesSent += len
            }

            val nalProc = NalProcessor(
                onSpsProfileMismatch = {
                    scope.launch {
                        if (!useSoftwareEncoder) {
                            useSoftwareEncoder = true
                            logW("SPS profile mismatch detected — switching to software encoder fallback")
                            rebuildEncoder(true)
                        }
                    }
                },
                prependSpsPpsToIdr = prependSpsPps,
                forceSpsBaseline = forceBaseline,
                dropSeiAndAud = dropSeiAud,
                h264LevelIdc = NalProcessor.levelIdcFromString(h264Level),
            ) { nal, _ ->
                packetizer.packetize(nal, endOfAU = true, wallClockMs = videoPtsMs)
            }

            val onEncoded: (ByteArray, Boolean) -> Unit = { annexB, isKey ->
                nalProc.process(annexB)
                if (!loggedFirstFrame) {
                    loggedFirstFrame = true
                    try {
                        io.github.saltmango.navbeam.data.RideDiagnostics.log("stream", "first video frame sent (key=$isKey, ${annexB.size}B)")
                    } catch (e: Throwable) {
                        // ignore diagnostics write on unit tests
                    }
                }
            }

            val activeWidth = if (cfg.width > 0) cfg.width else disp.width
            val activeHeight = if (cfg.height > 0) cfg.height else disp.height
            val activeBitrate = if (cfg.initialBitrate > 0) cfg.initialBitrate else disp.activeBitrateBps
            val activeFps = (1000L / cfg.initialFrameDurationMs.coerceAtLeast(50L)).toInt().coerceIn(1, 20)

            val enc = encoderFactory?.invoke(onEncoded, forceSoftware) ?: DashEncoder(
                onEncodedData = onEncoded,
                forceSoftware = forceSoftware,
                width = activeWidth,
                height = activeHeight,
                fps = activeFps,
                initialBitrate = activeBitrate,
                h264Level = h264Level,
            )
            encoder = enc.also { it.prepare() }
        } catch (e: Exception) {
            logE("rebuildEncoder failed", e)
            failureListener?.onStreamingFailed(e)
        }
    }

    private fun logE(msg: String, tr: Throwable? = null) {
        try {
            android.util.Log.e("DefaultFrameStreamer", msg, tr)
        } catch (e: Throwable) {
            System.err.println("[DefaultFrameStreamer] ERROR: $msg")
            tr?.printStackTrace()
        }
    }

    private fun logW(msg: String) {
        try {
            android.util.Log.w("DefaultFrameStreamer", msg)
        } catch (e: Throwable) {
            println("[DefaultFrameStreamer] WARN: $msg")
        }
    }
}
