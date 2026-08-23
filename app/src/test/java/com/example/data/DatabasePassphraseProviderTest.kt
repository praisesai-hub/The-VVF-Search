package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.StringKeyValueStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabasePassphraseProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun firstReadGeneratesDurableKeyAndLaterReadsReuseIt() {
        val store = MemoryStore()
        val first = DatabasePassphraseProvider(context, store).getPassphrase()
        val second = DatabasePassphraseProvider(context, store).getPassphrase()

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
        assertEquals(1, store.commitCount)
        first.fill(0)
        second.fill(0)
    }

    @Test
    fun malformedPersistedKeyFailsClosed() {
        val store = MemoryStore(mapOf("database_passphrase_v1" to "not-a-valid-32-byte-key"))

        assertThrows(IllegalArgumentException::class.java) {
            DatabasePassphraseProvider(context, store).getPassphrase()
        }
    }

    private class MemoryStore(initial: Map<String, String?> = emptyMap()) : StringKeyValueStore {
        private val values = initial.toMutableMap()
        var commitCount = 0

        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

        override fun commit(values: Map<String, String?>): Boolean {
            commitCount++
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
            return true
        }
    }
}
