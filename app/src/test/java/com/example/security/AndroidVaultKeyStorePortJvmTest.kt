package com.example.security

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVaultKeyStorePortJvmTest {
    @Test
    fun delegatesAliasLookupSecretKeyLookupAndDeletionToInjectedKeyStore() {
        val keyStore = mockk<KeyStore>()
        val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        every { keyStore.containsAlias("present") } returns true
        every { keyStore.containsAlias("missing") } returns false
        every { keyStore.getEntry("present", null) } returns KeyStore.SecretKeyEntry(key)
        every { keyStore.getEntry("missing", null) } returns null
        justRun { keyStore.deleteEntry("present") }
        val port = AndroidVaultKeyStorePort.fromKeyStore(keyStore)

        assertTrue(port.containsAlias("present"))
        assertFalse(port.containsAlias("missing"))
        assertEquals(key, port.getSecretKey("present"))
        assertNull(port.getSecretKey("missing"))

        port.deleteKey("present")

        verify(exactly = 1) { keyStore.deleteEntry("present") }
    }

    @Test
    fun returnsNullWhenAliasEntryIsNotASecretKey() {
        val keyStore = mockk<KeyStore>()
        every { keyStore.getEntry("certificate", null) } returns mockk<KeyStore.PrivateKeyEntry>()
        val port = AndroidVaultKeyStorePort.fromKeyStore(keyStore)

        assertNull(port.getSecretKey("certificate"))
    }
}
