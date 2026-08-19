package com.example.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    private object JvmPassphraseBase64Codec : PassphraseBase64Codec {
        override fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

        override fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}
