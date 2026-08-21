package com.example.security

import android.content.Context
import android.content.pm.ApplicationInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.GeneralSecurityException
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
    private lateinit var context: Context

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("vvf-secure-store-").toFile()
        crypto = ReversibleCrypto()
        val applicationInfo = ApplicationInfo().apply { dataDir = directory.absolutePath }
        context = mockk()
        every { context.applicationInfo } returns applicationInfo
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
    fun `shared preferences adapter removes null values before durable updates`() {
        val preferences = mockk<android.content.SharedPreferences>()
        val editor = mockk<android.content.SharedPreferences.Editor>()
        every { preferences.edit() } returns editor
        every { editor.remove("expired") } returns editor
        every { editor.putString("active", "value") } returns editor
        every { editor.commit() } returns false
        every { preferences.getString("missing", "fallback") } returns "fallback"
        val store = SharedPreferencesKeyValueStore(preferences)

        assertFalse(store.commit(mapOf("active" to "value", "expired" to null)))
        assertEquals("fallback", store.getString("missing", "fallback"))
        verifyOrder {
            preferences.edit()
            editor.remove("expired")
            editor.putString("active", "value")
            editor.commit()
        }
    }

    @Test
    fun `android keystore crypto delegates encrypted payload conversion`() {
        val manager = mockk<KeystoreVaultManager>()
        val plaintext = "secure value".encodeToByteArray()
        val ciphertext = byteArrayOf(8, 7, 6)
        val iv = byteArrayOf(1, 2, 3, 4)
        every { manager.encryptBytes(plaintext) } returns KeystoreVaultManager.EncryptedResult(ciphertext, iv)
        every { manager.decryptBytes(ciphertext, iv) } returns plaintext
        val crypto = AndroidKeystoreCrypto(manager)

        assertEquals(EncryptedPayload(ciphertext, iv), crypto.encrypt(plaintext))
        assertEquals(plaintext.toList(), crypto.decrypt(EncryptedPayload(ciphertext, iv)).toList())
        verify(exactly = 1) { manager.encryptBytes(plaintext) }
        verify(exactly = 1) { manager.decryptBytes(ciphertext, iv) }
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
    fun `invalid envelope version and bounded payload sizes fail closed`() {
        val store = store()
        val malformedEnvelopes = listOf(
            envelopeBytes(version = 2, ivSize = 12, ciphertextSize = 16),
            envelopeBytes(ivSize = 0, ciphertextSize = 16),
            envelopeBytes(ivSize = 33, ciphertextSize = 16),
            envelopeBytes(ivSize = 12, ciphertextSize = 0),
            envelopeBytes(ivSize = 12, ciphertextSize = 524_289),
        )

        malformedEnvelopes.forEach { envelope ->
            File(directory, FILE_NAME).writeBytes(envelope)
            assertThrows(IllegalStateException::class.java) { store.getString("pin") }
        }
    }

    @Test
    fun `malformed decrypted payloads fail closed`() {
        val store = store()
        val malformedPayloads = listOf(
            plaintextBytes(magic = 0, version = 1, count = 0),
            plaintextBytes(magic = PLAINTEXT_MAGIC, version = 2, count = 0),
            plaintextBytes(magic = PLAINTEXT_MAGIC, version = 1, count = -1),
            plaintextBytes(magic = PLAINTEXT_MAGIC, version = 1, count = 2) {
                writeUTF("duplicate")
                writeUTF("first")
                writeUTF("duplicate")
                writeUTF("second")
            },
        )

        malformedPayloads.forEach { payload ->
            writeEncryptedEnvelope(payload)
            assertThrows(IllegalStateException::class.java) { store.getString("pin") }
        }
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
    fun `general security decrypt failure fails closed`() {
        val writer = store()
        writer.commit(mapOf("token" to "secret"))
        val failing = SecureKeyValueStore(
            context = context,
            fileName = FILE_NAME,
            keyAlias = "unused",
            directory = directory,
            crypto = object : SecureStoreCrypto {
                override fun encrypt(plaintext: ByteArray): EncryptedPayload = error("not used")

                override fun decrypt(payload: EncryptedPayload): ByteArray {
                    throw GeneralSecurityException("tamper")
                }
            }
        )

        assertThrows(IllegalStateException::class.java) { failing.getString("token") }
    }

    @Test
    fun `atomic write failure fails closed and does not create the destination store`() {
        val fileName = "blocked-store"
        assertTrue(File(directory, "$fileName.tmp").mkdir())
        val blocked = SecureKeyValueStore(
            context = context,
            fileName = fileName,
            keyAlias = "unused",
            directory = directory,
            crypto = crypto
        )

        assertThrows(IllegalStateException::class.java) {
            blocked.commit(mapOf("token" to "secret"))
        }
        assertFalse(File(directory, fileName).exists())
    }

    @Test
    fun `atomic replacement fails closed when the destination path is a directory`() {
        val fileName = "destination-directory"
        val destination = File(directory, fileName)
        assertTrue(destination.mkdir())
        val store = SecureKeyValueStore(
            context = context,
            fileName = fileName,
            keyAlias = "unused",
            directory = directory,
            crypto = crypto
        )

        val error = assertThrows(IllegalStateException::class.java) {
            store.commit(mapOf("token" to "secret"))
        }

        assertEquals("Unable to durably write secure preferences", error.message)
        assertTrue(destination.isDirectory)
        assertFalse(File(directory, "$fileName.tmp").exists())
    }

    @Test
    fun `crypto provider failures fail closed before any durable store is created`() {
        val failures = listOf<Throwable>(
            GeneralSecurityException("keystore unavailable"),
            IllegalStateException("keystore state invalid"),
        )

        failures.forEachIndexed { index, failure ->
            val fileName = "failing-crypto-$index"
            val failingStore = SecureKeyValueStore(
                context = context,
                fileName = fileName,
                keyAlias = "unused",
                directory = directory,
                crypto = object : SecureStoreCrypto {
                    override fun encrypt(plaintext: ByteArray): EncryptedPayload = throw failure

                    override fun decrypt(payload: EncryptedPayload): ByteArray = error("not used")
                }
            )

            val exception = assertThrows(IllegalStateException::class.java) {
                failingStore.commit(mapOf("token" to "secret"))
            }
            assertEquals("Unable to durably write secure preferences", exception.message)
            assertEquals(failure, exception.cause)
            assertFalse(File(directory, fileName).exists())
            assertFalse(File(directory, "$fileName.tmp").exists())
        }
    }

    @Test
    fun `invalid file name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureKeyValueStore(context, "../unsafe", "unused", directory, crypto)
        }
    }

    @Test
    fun `impossible secure storage directory fails closed during construction`() {
        val regularFileParent = File(directory, "not-a-directory").apply { writeText("fixture") }

        val error = assertThrows(IllegalStateException::class.java) {
            SecureKeyValueStore(
                context = context,
                fileName = "unwritable.secure",
                keyAlias = "unused",
                directory = File(regularFileParent, "nested"),
                crypto = crypto
            )
        }

        assertEquals("Unable to create secure storage directory", error.message)
    }

    @Test
    fun `overlong decrypted entry fails closed instead of being returned`() {
        val store = store()
        writeEncryptedEnvelope(
            plaintextBytes(magic = PLAINTEXT_MAGIC, version = 1, count = 1) {
                writeUTF("key")
                writeUTF("x".repeat(16_385))
            }
        )

        assertThrows(IllegalStateException::class.java) { store.getString("key") }
    }

    @Test
    fun `contains store file reflects durable state and oversized values are rejected`() {
        val store = store()

        assertFalse(store.containsStoreFile())
        assertTrue(store.commit(mapOf("state" to "stored")))
        assertTrue(store.containsStoreFile())
        assertThrows(IllegalArgumentException::class.java) {
            store.commit(mapOf("oversized" to "x".repeat(16_385)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.commit((1..33).associate { "key-$it" to "value-$it" })
        }
    }

    @Test
    fun `invalid crypto output fails closed before any encrypted envelope is written`() {
        val invalidIvStore = SecureKeyValueStore(
            context = context,
            fileName = "invalid-iv-store",
            keyAlias = "unused",
            directory = directory,
            crypto = object : SecureStoreCrypto {
                override fun encrypt(plaintext: ByteArray) = EncryptedPayload(ByteArray(11), ByteArray(11))
                override fun decrypt(payload: EncryptedPayload): ByteArray = error("not used")
            }
        )
        val invalidCiphertextStore = SecureKeyValueStore(
            context = context,
            fileName = "invalid-ciphertext-store",
            keyAlias = "unused",
            directory = directory,
            crypto = object : SecureStoreCrypto {
                override fun encrypt(plaintext: ByteArray) = EncryptedPayload(ByteArray(0), ByteArray(12))
                override fun decrypt(payload: EncryptedPayload): ByteArray = error("not used")
            }
        )

        assertThrows(IllegalStateException::class.java) {
            invalidIvStore.commit(mapOf("token" to "value"))
        }
        assertThrows(IllegalStateException::class.java) {
            invalidCiphertextStore.commit(mapOf("token" to "value"))
        }
        assertFalse(File(directory, "invalid-iv-store").exists())
        assertFalse(File(directory, "invalid-ciphertext-store").exists())
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
    fun `migration is a no-op when secure state already exists or legacy file is absent`() {
        val migrated = store()
        migrated.commit(mapOf("access_token" to "current"))
        LegacyEncryptedPreferencesMigration.migrateIfNeeded(
            context = context,
            legacyName = "missing-legacy-${System.nanoTime()}",
            target = migrated,
            keys = setOf("access_token")
        )
        assertEquals("current", migrated.getString("access_token"))

        val emptyTarget = SecureKeyValueStore(
            context = context,
            fileName = "empty-target-${System.nanoTime()}",
            keyAlias = "unused",
            directory = directory,
            crypto = crypto
        )
        LegacyEncryptedPreferencesMigration.migrateIfNeeded(
            context = context,
            legacyName = "missing-legacy-${System.nanoTime()}",
            target = emptyTarget,
            keys = setOf("access_token")
        )
        assertFalse(emptyTarget.containsStoreFile())
    }

    private fun store(): SecureKeyValueStore = SecureKeyValueStore(
        context = context,
        fileName = FILE_NAME,
        keyAlias = "unused",
        directory = directory,
        crypto = crypto
    )

    private fun envelopeBytes(
        magic: Int = ENVELOPE_MAGIC,
        version: Int = 1,
        ivSize: Int,
        ciphertextSize: Int,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(magic)
            output.writeInt(version)
            output.writeInt(ivSize)
            if (ivSize in 1..32) output.write(ByteArray(ivSize))
            output.writeInt(ciphertextSize)
            if (ciphertextSize in 1..512 * 1024) output.write(ByteArray(ciphertextSize))
        }
        bytes.toByteArray()
    }

    private fun plaintextBytes(
        magic: Int,
        version: Int,
        count: Int,
        values: DataOutputStream.() -> Unit = {},
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(magic)
            output.writeInt(version)
            output.writeInt(count)
            output.values()
        }
        bytes.toByteArray()
    }

    private fun writeEncryptedEnvelope(plaintext: ByteArray) {
        val encrypted = crypto.encrypt(plaintext)
        File(directory, FILE_NAME).writeBytes(
            envelopeBytes(
                ivSize = encrypted.iv.size,
                ciphertextSize = encrypted.ciphertext.size,
            ).let { header ->
                header.dropLast(encrypted.ciphertext.size).toByteArray() + encrypted.ciphertext
            }
        )
    }

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
        const val ENVELOPE_MAGIC = 0x56564645
        const val PLAINTEXT_MAGIC = 0x56564650
    }
}
