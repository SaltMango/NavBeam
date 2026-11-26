package io.github.saltmango.navbeam.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts sensitive local preferences (API keys, Wi-Fi passwords) using an Android Keystore
 * AES-256-GCM key so values are never stored in plaintext XML.
 */
object SecretStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "navbeam_prefs_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PREFIX = "v1:"

    private val rng = SecureRandom()

    fun encrypt(plaintext: String): String? {
        if (plaintext.isEmpty()) return ""
        return runCatching {
            encryptWithKey(plaintext, getOrCreateKey(), rng)
        }.getOrNull()
    }

    fun decrypt(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        return runCatching {
            decryptWithKey(encoded, getOrCreateKey())
        }.getOrNull()
    }

    internal fun encryptWithKey(
        plaintext: String,
        key: SecretKey,
        random: SecureRandom = rng,
    ): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(combined, destinationOffset = 0)
        ciphertext.copyInto(combined, destinationOffset = iv.size)
        return PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    internal fun decryptWithKey(encoded: String, key: SecretKey): String? {
        if (!encoded.startsWith(PREFIX)) return null
        val payload = Base64.getDecoder().decode(encoded.removePrefix(PREFIX))
        if (payload.size <= IV_BYTES + 16) return null
        val iv = payload.copyOfRange(0, IV_BYTES)
        val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }
}
