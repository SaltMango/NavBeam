package io.github.saltmango.navbeam.dash

import io.github.saltmango.navbeam.dash.profile.CommandProtocolConfig
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.dash.protocol.DashCommands
import io.github.saltmango.navbeam.dash.protocol.Tlv
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/** Result of feeding one incoming TLV into the auth state machine. */
sealed class AuthEvent {
    /** Both pubkey halves received — send session key packet now. */
    data class SendKey(val packet: ByteArray) : AuthEvent()
    /** Display confirmed authentication. */
    object Confirmed : AuthEvent()
    /** Display rejected authentication — resend authRequest if retries remain. */
    object Rejected : AuthEvent()
    object None : AuthEvent()
}

/**
 * RSA + AES-256 handshake state machine driven by [CommandProtocolConfig] TLV tags.
 *
 * The display sends modulus and exponent — possibly in SEPARATE packets — so
 * state accumulates across calls. The session-key packet is emitted once per
 * attempt; [reset] re-arms it after a rejection.
 */
class DashAuth(
    private val ssid: String,
    private val cmdConfig: CommandProtocolConfig = DashProfileManager.activeProfile.value.commands,
) {
    private var modulus: BigInteger? = null
    private var exponent: BigInteger? = null
    private var keySent = false

    var sessionKey: ByteArray? = null
        private set

    fun ingest(tlv: Tlv): AuthEvent {
        when {
            tlv.type == cmdConfig.authModulusTag.type && tlv.sub == cmdConfig.authModulusTag.sub ->
                modulus = BigInteger(1, tlv.value)
            tlv.type == cmdConfig.authExponentTag.type && tlv.sub == cmdConfig.authExponentTag.sub ->
                exponent = BigInteger(1, tlv.value)
            tlv.type == cmdConfig.authStatusTag.type && tlv.sub == cmdConfig.authStatusTag.sub ->
                return if (tlv.value.firstOrNull() == cmdConfig.authConfirmByte.toByte())
                    AuthEvent.Confirmed else AuthEvent.Rejected
            else -> return AuthEvent.None
        }

        val m = modulus; val e = exponent
        if (!keySent && m != null && e != null) {
            keySent = true
            return AuthEvent.SendKey(buildKeyPacket(m, e))
        }
        return AuthEvent.None
    }

    /** Re-arm after a rejection so the display can re-offer its pubkey. */
    fun reset() {
        modulus = null
        exponent = null
        keySent = false
    }

    private fun buildKeyPacket(modulus: BigInteger, exponent: BigInteger): ByteArray {
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sessionKey = aes

        val payload = ssid.toByteArray(Charsets.UTF_8) + aes
        val pubKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        return DashCommands.authSendKey(cipher.doFinal(payload))
    }
}
