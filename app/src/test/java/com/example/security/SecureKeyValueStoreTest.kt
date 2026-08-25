package com.example.security

import android.content.Context
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecureKeyValueStoreTest {
    private lateinit var directory: File
    private lateinit var crypto: SecureStoreCrypto

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("vvf-secure-store-").toFile()
        crypto = ReversibleCrypto()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `missing store reads defaults and first commit persists values`() {
        val store = store()

        assertEquals("fallback", store.getString("missing", "fallback"))
        assertTrue(store.commit(mapOf("access" to "token", "email" to "user@example.com")))
        assertEquals("token", store.getString("access"))
        assertEquals("user@example.com", store.getString("email"))
        assertTrue(File(directory, FILE_NAME).isFile)
    }

    @Test
    fun `commit updates existing values and removes null values`() {
        val store = store()
        store.commit(mapOf("a" to "one", "b" to "two"))

        store.commit(mapOf("a" to "updated", "b" to null))

        assertEquals("updated", store.getString("a"))
        assertNull(store.getString("b"))
    }

    @Test
    fun `stored empty string is not replaced by the supplied default`() {
        val store = store()
        store.commit(mapOf("empty" to ""))

        assertEquals("", store.getString("empty", "fallback"))
    }

    @Test
    fun `malformed envelope fails closed instead of returning defaults`() {
        val store = store()
        store.commit(mapOf("pin" to "hash"))
        File(directory, FILE_NAME).writeBytes(byteArrayOf(1, 2, 3, 4))

        assertThrows(IllegalStateException::class.java) { store.getString("pin") }
    }

    @Test
    fun `decrypt failure fails closed`() {
        val writer = store()
        writer.commit(mapOf("token" to "secret"))
        val failing = SecureKeyValueStore(
            context = context,
            fileName = FILE_NAME,
            keyAlias = "unused",
            directory = directory,
            crypto = object : SecureStoreCrypto {
                override fun encrypt(plaintext: ByteArray): EncryptedPayload =
                    error("not used")

                override fun decrypt(payload: EncryptedPayload): ByteArray =
                    error("tamper")
            }
        )

        assertThrows(IllegalStateException::class.java) { failing.getString("token") }
    }

    @Test
    fun `invalid file name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureKeyValueStore(context, "../unsafe", "unused", directory, crypto)
        }
    }

    @Test
    fun `migration copies only allow-listed non-null entries`() {
        val target = RecordingStore()

        assertTrue(
            LegacyEncryptedPreferencesMigration.migrateEntries(
                target = target,
                legacyEntries = mapOf(
                    "access_token" to "access",
                    "email" to "user@example.com",
                    "refresh_token" to null,
                    "unrelated" to "must-not-copy"
                ),
                keys = setOf("access_token", "refresh_token", "email")
            )
        )

        assertEquals(mapOf("access_token" to "access", "email" to "user@example.com"), target.values)
    }

    @Test
    fun `migration propagates non durable acknowledgement`() {
        val target = object : StringKeyValueStore {
            override fun getString(key: String, defaultValue: String?): String? = defaultValue
            override fun commit(values: Map<String, String?>): Boolean = false
        }

        assertFalse(
            LegacyEncryptedPreferencesMigration.migrateEntries(
                target,
                mapOf("pin" to "hash"),
                setOf("pin")
            )
        )
    }

    private fun store(): SecureKeyValueStore = SecureKeyValueStore(
        context = context,
        fileName = FILE_NAME,
        keyAlias = "unused",
        directory = directory,
        crypto = crypto
    )

    private val context: Context = mockk(relaxed = true)

    private class ReversibleCrypto : SecureStoreCrypto {
        override fun encrypt(plaintext: ByteArray): EncryptedPayload =
            EncryptedPayload(plaintext.map { (it.toInt() xor MASK).toByte() }.toByteArray(), IV)

        override fun decrypt(payload: EncryptedPayload): ByteArray =
            payload.ciphertext.map { (it.toInt() xor MASK).toByte() }.toByteArray()

        private companion object {
            const val MASK = 0x5A
            val IV = ByteArray(12) { it.toByte() }
        }
    }

    private class RecordingStore : StringKeyValueStore {
        var values: Map<String, String?> = emptyMap()
            private set

        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

        override fun commit(values: Map<String, String?>): Boolean {
            this.values = values
            return true
        }
    }

    private companion object {
        const val FILE_NAME = "secure-test-store"
    }
}
