package io.github.saltmango.navbeam.dash

import android.util.Log
import io.github.saltmango.navbeam.dash.protocol.DashPacket
import io.github.saltmango.navbeam.util.Dbg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP sockets for the dash link:
 *   TX  – bound to profile controlTxPort, SO_BROADCAST, sends to broadcastIp:controlTxPort.
 *         The gateway IP is never used for the control plane.
 *   RX  – bound to profile controlRxPort. Must be open BEFORE the first TX packet, both to
 *         catch the early pubkey reply and because unanswered dash→phone
 *         packets generate ICMP port-unreachable, which confuses the dash's
 *         protocol state machine.
 *   RTP – ephemeral, sends H.264 to configured gateway IP and video RTP port.
 *
 * Every control packet gets the rolling sequence byte patched on send.
 *
 * ## Zero-allocation RTP path
 *
 * [sendRtp] reuses a single pre-allocated [DatagramPacket] via [setData] to
 * avoid per-packet object allocation during the projection loop.
 * The RTP packetizer passes `(ByteArray, length)` so the buffer is consumed
 * in-place without a copy.
 *
 * ## Adaptive SO_TIMEOUT
 *
 * [setReceiveTimeout] adjusts the control socket's read timeout based on the
 * current [MotionProfile]:
 *   HIGHWAY / CITY   → 500 ms (fast disconnect detection while moving)
 *   SLOW / STOPPED   → 1000 ms (fewer CPU wakeups at low speed)
 */
class DashSocket(
    private val network: android.net.Network? = null,
    val networkConfig: io.github.saltmango.navbeam.dash.profile.NetworkConfig =
        io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.value.network,
) : AutoCloseable {
    companion object {
        private const val BUF              = 65535
        private const val RECV_TIMEOUT_MS  = 500
        private const val TAG              = "DashSocket"
    }

    val dashIp: String get() = networkConfig.gatewayIp
    val broadcastIp: String get() = networkConfig.resolvedBroadcastIp
    val ctrlPort: Int get() = networkConfig.controlTxPort
    val rxPort: Int get() = networkConfig.controlRxPort
    val rtpPort: Int get() = networkConfig.videoRtpPort

    private val broadcastAddr: InetAddress = InetAddress.getByName(broadcastIp)
    private val dashAddr:      InetAddress = InetAddress.getByName(dashIp)
    private val txSocket:  DatagramSocket
    private val rxSocket:  DatagramSocket
    private val rtpSocket: DatagramSocket

    private val seq = AtomicInteger(0)

    // Pre-allocated RTP send packet — reused on every call to sendRtp().
    private val rtpSendPacket = DatagramPacket(ByteArray(0), 0, dashAddr, rtpPort)

    // Pre-allocated receive buffer — reused on every receive() call.
    private val rxBuf = DatagramPacket(ByteArray(BUF), BUF)

    init {
        require(dashIp.isNotBlank() && broadcastIp.isNotBlank()) {
            "DashSocket requires a configured network profile (gatewayIp/broadcastIp missing)"
        }
        require(ctrlPort in 1024..65535 && rxPort in 1024..65535 && rtpPort in 1024..65535) {
            "DashSocket requires valid unprivileged ports (got tx=$ctrlPort, rx=$rxPort, rtp=$rtpPort)"
        }
        var tx:  DatagramSocket? = null
        var rx:  DatagramSocket? = null
        var rtp: DatagramSocket? = null
        try {
            tx = DatagramSocket(null).also {
                it.reuseAddress = true
                it.broadcast = true
                it.bind(InetSocketAddress(ctrlPort))
                network?.bindSocket(it)
            }
            rx = DatagramSocket(null).also {
                it.reuseAddress = true
                it.soTimeout = RECV_TIMEOUT_MS
                it.bind(InetSocketAddress(rxPort))
                network?.bindSocket(it)
            }
            rtp = DatagramSocket().also { network?.bindSocket(it) }
            Log.i(TAG, "Sockets open — TX :$ctrlPort→$broadcastIp:$ctrlPort (broadcast), RX :$rxPort, RTP→$dashIp:$rtpPort")
            txSocket  = tx
            rxSocket  = rx
            rtpSocket = rtp
        } catch (e: Exception) {
            tx?.close(); rx?.close(); rtp?.close()
            throw e
        }
    }

    /** Send a control packet with the rolling sequence byte patched. */
    fun send(data: ByteArray) {
        val pkt = DashPacket.patchSeq(data, seq.getAndIncrement())
        Dbg.i(TAG) { "TX →$broadcastIp:$ctrlPort  ${pkt.size}B  ${pkt.hex()}" }
        try {
            txSocket.send(DatagramPacket(pkt, pkt.size, broadcastAddr, ctrlPort))
        } catch (e: Exception) {
            Log.w(TAG, "TX send failed (link down?): ${e.message}")
        }
    }

    val rtpSent = AtomicInteger(0)
    val rtpDropped = AtomicInteger(0)
    private val rtpBytes = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Send an RTP packet using the pre-allocated [rtpSendPacket].
     * [data] and [length] come directly from [RtpPacketizer.onPacket] —
     * no copy is made.
     */
    fun sendRtp(data: ByteArray, length: Int) {
        try {
            rtpSendPacket.setData(data, 0, length)
            rtpSocket.send(rtpSendPacket)
            rtpSent.incrementAndGet()
            rtpBytes.addAndGet(length.toLong())
        } catch (e: Exception) {
            Log.w(TAG, "RTP send failed (link down?): ${e.message}")
            rtpDropped.incrementAndGet()
        }
    }

    /** Returns (sentCount, droppedCount, avgBytes) and resets the counts. */
    fun getAndResetMetrics(): Triple<Int, Int, Int> {
        val s = rtpSent.getAndSet(0)
        val d = rtpDropped.getAndSet(0)
        val b = rtpBytes.getAndSet(0)
        val avg = if (s > 0) (b / s).toInt() else 0
        return Triple(s, d, avg)
    }

    /**
     * Convenience overload for callers that still pass a full ByteArray.
     * Used during the transition period before all callers adopt the
     * zero-allocation (ByteArray, Int) pattern.
     */
    fun sendRtp(data: ByteArray) = sendRtp(data, data.size)

    /**
     * Adjust the control socket SO_TIMEOUT to match the current motion profile.
     * Call this on every profile transition from DashViewModel.
     *
     * @param ms timeout in milliseconds (500 for moving, 1000 for slow/stopped)
     */
    fun setReceiveTimeout(ms: Int) {
        runCatching { rxSocket.soTimeout = ms }
    }

    /**
     * Blocks up to [rxSocket.soTimeout]; returns null on timeout.
     * Throws on a genuine socket error — the caller's receive loop catches
     * it and ends the session.
     */
    suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        // Reset the buffer length so receive() fills from the start.
        rxBuf.length = BUF
        return@withContext try {
            rxSocket.receive(rxBuf)
            val bytes = rxBuf.data.copyOf(rxBuf.length)
            Dbg.i(TAG) { "RX ←${rxBuf.address?.hostAddress}:${rxBuf.port}  ${bytes.size}B  ${bytes.hex()}" }
            bytes
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        Log.w(TAG, "Sockets closed")
        runCatching { txSocket.close() }
        runCatching { rxSocket.close() }
        runCatching { rtpSocket.close() }
    }
}

private fun ByteArray.hex(max: Int = 64): String =
    take(max).joinToString(" ") { "%02X".format(it) }.let {
        if (size > max) "$it …(+${size - max})" else it
    }
