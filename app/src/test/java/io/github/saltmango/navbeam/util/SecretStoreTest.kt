package io.github.saltmango.navbeam.util

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class SecretStoreTest {

    @Test
    fun encryptAndDecryptRoundTripAndRejectTampering() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secret = "AIzaSyExampleSecretApiKey_12345"

        val enc1 = SecretStore.encryptWithKey(secret, key)
        val enc2 = SecretStore.encryptWithKey(secret, key)
        assertTrue(enc1.startsWith("v1:"))
        assertNotEquals("Randomized IV must produce distinct ciphertexts", enc1, enc2)
        assertEquals(secret, SecretStore.decryptWithKey(enc1, key))
        assertEquals(secret, SecretStore.decryptWithKey(enc2, key))

        // Tampering with ciphertext or legacy unencrypted strings must fail cleanly
        val tampered = enc1.dropLast(2) + "AA"
        assertTrue(runCatching { SecretStore.decryptWithKey(tampered, key) }.isFailure)
        assertNull(SecretStore.decryptWithKey(secret, key))
    }
}
