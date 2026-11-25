package io.github.saltmango.navbeam.dash.video

import android.graphics.Bitmap
import io.github.saltmango.navbeam.dash.DashSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FrameStreamerTest {

    private class FakeEncoder : EncoderWrapper {
        var prepareCount = 0
        var renderFrameCount = 0
        var releaseCount = 0
        var requestBitrateCount = 0
        var lastBitrate = 0
        var throwOnError = false

        override fun prepare() {
            prepareCount++
        }

        override fun renderFrame(drawBlock: (android.graphics.Canvas) -> Unit) {
            renderFrameCount++
            if (throwOnError) {
                throw IllegalStateException("Simulated MediaCodec failure")
            }
        }

        override fun requestBitrate(bitrate: Int) {
            requestBitrateCount++
            lastBitrate = bitrate
        }

        override fun requestSyncFrame() {}

        override fun drain() {}

        override fun release() {
            releaseCount++
        }
    }

    private fun createFakeBitmap(): Bitmap {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    @Test
    fun testBasicStreamingAndPts() = runBlocking {
        var lastForceSoftware = false
        val encoders = mutableListOf<FakeEncoder>()
        val factory = { _: (ByteArray, Boolean) -> Unit, forceSoftware: Boolean ->
            val enc = FakeEncoder()
            lastForceSoftware = forceSoftware
            encoders.add(enc)
            enc
        }

        val streamer = DefaultFrameStreamer(factory)
        val session = DashSession(this)
        
        val config = StreamerConfig(
            session = session,
            initialBitrate = 250_000,
            initialFrameDurationMs = 250L,
            width = 640,
            height = 480,
            h264Level = "4.0",
            rtpMaxPayloadBytes = 1380,
            rtpPayloadType = 96,
        )

        streamer.start(config)

        assertEquals(1, encoders.size)
        val firstEnc = encoders[0]
        assertEquals(1, firstEnc.prepareCount)
        assertFalse(lastForceSoftware)

        val bitmap = createFakeBitmap()

        streamer.submitFrame(bitmap)
        assertEquals(1, firstEnc.renderFrameCount)

        streamer.stop()
        
        var stopped = false
        for (i in 1..50) {
            if (firstEnc.releaseCount == 1) {
                stopped = true
                break
            }
            Thread.sleep(10)
        }
        assertTrue("Streamer stop timed out", stopped)
    }

    @Test
    fun testFailureRecoveryAndListener() = runBlocking {
        val encoders = mutableListOf<FakeEncoder>()

        val factory = { _: (ByteArray, Boolean) -> Unit, _: Boolean ->
            val enc = FakeEncoder()
            encoders.add(enc)
            enc
        }

        val streamer = DefaultFrameStreamer(factory)
        val session = DashSession(this)

        var failed = false
        streamer.setFailureListener(object : StreamFailureListener {
            override fun onStreamingFailed(cause: Throwable) {
                failed = true
            }
        })

        val config = StreamerConfig(
            session = session,
            initialBitrate = 250_000,
            initialFrameDurationMs = 250L,
            width = 640,
            height = 480,
            h264Level = "4.0",
            rtpMaxPayloadBytes = 1380,
            rtpPayloadType = 96,
        )

        streamer.start(config)

        val firstEnc = encoders[0]
        firstEnc.throwOnError = true

        val bitmap = createFakeBitmap()

        // First frame: throws, rebuilds encoder (synchronously inside submitFrame)
        streamer.submitFrame(bitmap)
        assertEquals(2, encoders.size)
        assertFalse(failed)

        // Make the new encoder throw too
        encoders[1].throwOnError = true

        // Second frame: throws, rebuilds again
        streamer.submitFrame(bitmap)
        assertEquals(3, encoders.size)
        assertFalse(failed)

        // Make third encoder throw
        encoders[2].throwOnError = true

        // Third frame: throws, failures reaches 3 -> reports failure to listener
        streamer.submitFrame(bitmap)
        assertTrue(failed)
    }
}
