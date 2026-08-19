package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    fun `commit rejects invalid encrypted payload before it reaches disk`() {
        val invalidCrypto = object : SecureStoreCrypto {
            override fun encrypt(plaintext: ByteArray): EncryptedPayload =
                EncryptedPayload(ciphertext = ByteArray(15), iv = ByteArray(11))

            override fun decrypt(payload: EncryptedPayload): ByteArray = error("not used")
        }
        val store = SecureKeyValueStore(context, FILE_NAME, "unused", directory, invalidCrypto)

        assertThrows(IllegalArgumentException::class.java) {
            store.commit(mapOf("token" to "value"))
        }
        assertFalse(File(directory, FILE_NAME).exists())
    }

    @Test
    fun `commit rejects too many and oversized entries`() {
        val store = store()
        val tooMany = (1..33).associate { "key-$it" to "value-$it" }

        assertThrows(IllegalArgumentException::class.java) { store.commit(tooMany) }
        assertThrows(IllegalArgumentException::class.java) {
            store.commit(mapOf("large" to "x".repeat(16_385)))
        }
    }

    @Test
    fun `malformed decrypted payloads fail closed including duplicate keys`() {
        val store = store()
        writeEnvelope(plaintext = byteArrayOf(0, 1, 2, 3))

        assertThrows(IllegalStateException::class.java) { store.getString("token") }

        val duplicatePlaintext = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x56564650)
                output.writeInt(1)
                output.writeInt(2)
                output.writeUTF("duplicate")
                output.writeUTF("first")
                output.writeUTF("duplicate")
                output.writeUTF("second")
            }
            bytes.toByteArray()
        }
        writeEnvelope(plaintext = duplicatePlaintext)

        assertThrows(IllegalStateException::class.java) { store.getString("duplicate") }
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
    fun `legacy migration keeps an already authoritative secure target untouched`() {
        val target = store()
        target.commit(mapOf("access_token" to "current-token"))

        LegacyEncryptedPreferencesMigration.migrateIfNeeded(
            context = context,
            legacyName = "missing-legacy-${System.nanoTime()}",
            target = target,
            keys = setOf("access_token")
        )

        assertEquals("current-token", target.getString("access_token"))
    }

    @Test
    fun `legacy migration is a no-op when no legacy XML exists`() {
        val target = store()

        LegacyEncryptedPreferencesMigration.migrateIfNeeded(
            context = context,
            legacyName = "missing-legacy-${System.nanoTime()}",
            target = target,
            keys = setOf("access_token", "refresh_token")
        )

        assertFalse(target.containsStoreFile())
    }

    private fun store(): SecureKeyValueStore = SecureKeyValueStore(
        context = context,
        fileName = FILE_NAME,
        keyAlias = "unused",
        directory = directory,
        crypto = crypto
    )

    private fun writeEnvelope(plaintext: ByteArray) {
        val ciphertext = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        FileOutputStream(File(directory, FILE_NAME), false).use { stream ->
            DataOutputStream(stream).use { output ->
                output.writeInt(0x56564645)
                output.writeInt(1)
                output.writeInt(12)
                output.write(ByteArray(12))
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
        }
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

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
