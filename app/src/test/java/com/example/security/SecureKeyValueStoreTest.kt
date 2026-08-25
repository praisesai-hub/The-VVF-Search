package com.example.security

import android.content.Context
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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

    @Test
    fun `write rejects oversized entries and invalid encrypted bounds`() {
        val store = store()
        val tooMany = buildMap {
            repeat(33) { put("key-$it", "value") }
        }
        assertThrows(IllegalArgumentException::class.java) { store.commit(tooMany) }
        assertThrows(IllegalArgumentException::class.java) {
            store.commit(mapOf("large" to "x".repeat(16_385)))
        }

        val invalidCrypto = object : SecureStoreCrypto {
            override fun encrypt(plaintext: ByteArray): EncryptedPayload =
                EncryptedPayload(ByteArray(16), ByteArray(1))

            override fun decrypt(payload: EncryptedPayload): ByteArray = byteArrayOf()
        }
        val invalidStore = SecureKeyValueStore(context, FILE_NAME, "unused", directory, invalidCrypto)
        assertThrows(IllegalStateException::class.java) {
            invalidStore.commit(mapOf("key" to "value"))
        }
    }

    @Test
    fun `malformed plaintext payloads fail closed`() {
        val writer = store()
        writer.commit(mapOf("key" to "value"))
        val malformedPayloads = listOf(
            plainPayload(magic = 0, count = 0),
            plainPayload(version = 2, count = 0),
            plainPayload(count = 33),
            plainPayload(count = 2, entries = listOf("same" to "one", "same" to "two")),
        )

        malformedPayloads.forEach { malformed ->
            val failing = SecureKeyValueStore(
                context,
                FILE_NAME,
                "unused",
                directory,
                object : SecureStoreCrypto {
                    override fun encrypt(plaintext: ByteArray): EncryptedPayload =
                        EncryptedPayload(ByteArray(16), ByteArray(12))

                    override fun decrypt(payload: EncryptedPayload): ByteArray = malformed
                },
            )
            assertThrows(IllegalStateException::class.java) { failing.getString("key") }
        }
    }

    @Test
    fun `invalid outer envelope sizes fail closed`() {
        val file = File(directory, FILE_NAME)
        file.writeBytes(
            envelopeBytes(
                ivSize = 0,
                ciphertextSize = 16,
            ),
        )

        assertThrows(IllegalStateException::class.java) { store().getString("key") }
    }

    @Test
    fun `crypto write failure is wrapped and temporary file is removed`() {
        val failing = SecureKeyValueStore(
            context,
            FILE_NAME,
            "unused",
            directory,
            object : SecureStoreCrypto {
                override fun encrypt(plaintext: ByteArray): EncryptedPayload =
                    throw GeneralSecurityException("encryption failed")

                override fun decrypt(payload: EncryptedPayload): ByteArray = byteArrayOf()
            },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            failing.commit(mapOf("key" to "value"))
        }

        assertTrue(error.message!!.contains("durably write"))
        assertFalse(File(directory, "$FILE_NAME.tmp").exists())
    }

    @Test
    fun `filesystem write failure is wrapped`() {
        val blocker = Files.createTempFile("vvf-secure-store-blocker-", ".tmp").toFile()
        try {
            val failing = SecureKeyValueStore(context, FILE_NAME, "unused", blocker, crypto)

            val error = assertThrows(IllegalStateException::class.java) {
                failing.commit(mapOf("key" to "value"))
            }

            assertTrue(error.message!!.contains("durably write"))
        } finally {
            blocker.delete()
        }
    }

    private fun plainPayload(
        magic: Int = 0x56564650,
        version: Int = 1,
        count: Int,
        entries: List<Pair<String, String>> = emptyList(),
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(magic)
            output.writeInt(version)
            output.writeInt(count)
            entries.forEach { (key, value) ->
                output.writeUTF(key)
                output.writeUTF(value)
            }
        }
        bytes.toByteArray()
    }

    private fun envelopeBytes(ivSize: Int, ciphertextSize: Int): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x56564645)
                output.writeInt(1)
                output.writeInt(ivSize)
                output.write(ByteArray(maxOf(ivSize, 0)))
                output.writeInt(ciphertextSize)
                output.write(ByteArray(maxOf(ciphertextSize, 0)))
            }
            bytes.toByteArray()
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
