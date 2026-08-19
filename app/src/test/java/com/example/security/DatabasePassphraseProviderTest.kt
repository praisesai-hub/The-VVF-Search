package com.example.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class DatabasePassphraseProviderTest {
    @Test
    fun getOrCreate_persistsOneThirtyTwoBytePassphrase() {
        val store = InMemoryStore()
        val provider = DatabasePassphraseProvider(store, JvmPassphraseBase64Codec)

        val first = provider.getOrCreate()
        val second = provider.getOrCreate()

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
        assertEquals(1, store.values.size)
    }

    @Test
    fun getOrCreate_failsClosedWhenPersistedPassphraseIsMalformed() {
        val store = InMemoryStore().apply {
            values["sqlcipher_passphrase_v1"] = "not-valid-base64"
        }

        try {
            DatabasePassphraseProvider(store, JvmPassphraseBase64Codec).getOrCreate()
            fail("A malformed persisted passphrase must not be replaced silently")
        } catch (error: IllegalStateException) {
            assertEquals("Unable to read encrypted database key", error.message)
        }
    }

    @Test
    fun getOrCreate_failsClosedWhenPassphraseWriteIsNotDurable() {
        val provider = DatabasePassphraseProvider(RejectingStore(), JvmPassphraseBase64Codec)

        try {
            provider.getOrCreate()
            fail("A rejected passphrase write must prevent database startup")
        } catch (error: IllegalStateException) {
            assertEquals("Unable to create encrypted database key", error.message)
        }
    }

    private class InMemoryStore : StringKeyValueStore {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String, defaultValue: String?): String? =
            values[key] ?: defaultValue

        override fun commit(values: Map<String, String?>): Boolean {
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
            return true
        }
    }

    private class RejectingStore : StringKeyValueStore {
        override fun getString(key: String, defaultValue: String?): String? = defaultValue

        override fun commit(values: Map<String, String?>): Boolean = false
    }

    private object JvmPassphraseBase64Codec : PassphraseBase64Codec {
        override fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

        override fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}
