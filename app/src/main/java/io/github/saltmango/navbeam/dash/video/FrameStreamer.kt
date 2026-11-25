package io.github.saltmango.navbeam.dash.video

import android.graphics.Bitmap
import io.github.saltmango.navbeam.dash.DashSession

data class StreamerConfig(
    val session: DashSession,
    val initialBitrate: Int,
    val initialFrameDurationMs: Long,
    val width: Int,
    val height: Int,
    val h264Level: String = "",
    val prependSpsPpsToIdr: Boolean = false,
    val forceSpsBaseline: Boolean = false,
    val dropSeiAndAud: Boolean = false,
    val rtpMaxPayloadBytes: Int = 1380,
    val rtpPayloadType: Int = 96,
    val forceSoftware: Boolean = false,
)

data class StreamerMetrics(
    val encoderTimeSum: Long,
    val encoderTimeCount: Int,
    val encoderSkippedCount: Int,
    val rtpLatencySum: Long,
    val rtpLatencyCount: Int,
    val totalBytesSent: Long,
    val encoderMode: String
)

interface StreamFailureListener {
    fun onStreamingFailed(cause: Throwable)
}

interface FrameStreamer {
    suspend fun start(config: StreamerConfig)
    suspend fun submitFrame(bitmap: Bitmap)
    fun setFrameDuration(durationMs: Long)
    fun stop()
    fun updateBitrate(bitrate: Int)
    fun setFailureListener(listener: StreamFailureListener)
    fun getMetrics(): StreamerMetrics
    fun isRunning(): Boolean
}

interface EncoderWrapper {
    fun prepare()
    fun renderFrame(drawBlock: (android.graphics.Canvas) -> Unit)
    fun drain()
    fun requestBitrate(bitrate: Int)
    fun requestSyncFrame()
    fun release()
}
