package com.example.security

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Minimal synchronous contract retained for the existing PIN and OAuth callers.
 * Implementations must fail closed when durable storage cannot be acknowledged.
 */
interface StringKeyValueStore {
    fun getString(key: String, defaultValue: String? = null): String?
    fun commit(values: Map<String, String?>): Boolean
}

/**
 * Adapter used only by tests and by legacy callers that explicitly inject ordinary
 * SharedPreferences. Production authentication and PIN state use SecureKeyValueStore.
 */
class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences
) : StringKeyValueStore {
    override fun getString(key: String, defaultValue: String?): String? =
        preferences.getString(key, defaultValue)

    override fun commit(values: Map<String, String?>): Boolean {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        return editor.commit()
    }
}

data class EncryptedPayload(val ciphertext: ByteArray, val iv: ByteArray)

interface SecureStoreCrypto {
    fun encrypt(plaintext: ByteArray): EncryptedPayload
    fun decrypt(payload: EncryptedPayload): ByteArray
}

class AndroidKeystoreCrypto(keyAlias: String) : SecureStoreCrypto {
    private val keystore = KeystoreVaultManager(keyAlias)

    override fun encrypt(plaintext: ByteArray): EncryptedPayload =
        keystore.encryptBytes(plaintext).let { EncryptedPayload(it.ciphertext, it.iv) }

    override fun decrypt(payload: EncryptedPayload): ByteArray =
        keystore.decryptBytes(payload.ciphertext, payload.iv)
}

/**
 * Encrypted, versioned, small key-value storage backed by the app's no-backup directory.
 *
 * The store is intentionally synchronous because the existing security-sensitive callers
 * require a durable acknowledgement from commit(). Writes use a temporary file, fsync,
 * and atomic rename. A malformed or undecryptable existing file is an error, never an
 * implicit reset of credentials.
 */
class SecureKeyValueStore(
    context: Context,
    private val fileName: String,
    keyAlias: String,
    private val directory: File = context.applicationContext.noBackupFilesDir,
    private val crypto: SecureStoreCrypto = AndroidKeystoreCrypto(keyAlias)
) : StringKeyValueStore {
    private val storeFile = File(directory, fileName)
    private val tempFile = File(directory, "$fileName.tmp")

    init {
        require(fileName.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid secure store file name" }
        check(directory.exists() || directory.mkdirs()) {
            "Unable to create secure storage directory"
        }
    }

    override fun getString(key: String, defaultValue: String?): String? = synchronized(this) {
        readValues().let { values ->
            if (values.containsKey(key)) values[key] else defaultValue
        }
    }

    override fun commit(values: Map<String, String?>): Boolean = synchronized(this) {
        val current = readValues().toMutableMap()
        values.forEach { (key, value) ->
            if (value == null) current.remove(key) else current[key] = value
        }
        writeValues(current)
        true
    }

    fun containsStoreFile(): Boolean = storeFile.isFile

    private fun readValues(): Map<String, String> {
        if (!storeFile.isFile) return emptyMap()
        return try {
            FileInputStream(storeFile).use { input ->
                DataInputStream(input).use { envelope ->
                    require(envelope.readInt() == ENVELOPE_MAGIC) { "Invalid secure store envelope" }
                    require(envelope.readInt() == FORMAT_VERSION) { "Unsupported secure store version" }
                    val iv = readBoundedBytes(envelope, MAX_IV_BYTES)
                    val ciphertext = readBoundedBytes(envelope, MAX_CIPHERTEXT_BYTES)
                    decodeValues(crypto.decrypt(EncryptedPayload(ciphertext, iv)))
                }
            }
        } catch (e: IOException) {
            secureReadFailure(e)
        } catch (e: GeneralSecurityException) {
            secureReadFailure(e)
        } catch (e: IllegalArgumentException) {
            secureReadFailure(e)
        } catch (e: IllegalStateException) {
            secureReadFailure(e)
        }
    }

    private fun writeValues(values: Map<String, String>) {
        val plaintext = encodeValues(values)
        val encrypted = crypto.encrypt(plaintext)
        require(encrypted.iv.size in MIN_IV_BYTES..MAX_IV_BYTES) { "Invalid AES-GCM IV" }
        require(encrypted.ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) { "Invalid ciphertext" }

        try {
            FileOutputStream(tempFile, false).use { output ->
                DataOutputStream(output).use { envelope ->
                    envelope.writeInt(ENVELOPE_MAGIC)
                    envelope.writeInt(FORMAT_VERSION)
                    envelope.writeInt(encrypted.iv.size)
                    envelope.write(encrypted.iv)
                    envelope.writeInt(encrypted.ciphertext.size)
                    envelope.write(encrypted.ciphertext)
                    envelope.flush()
                    output.fd.sync()
                }
            }
            if (!tempFile.renameTo(storeFile)) {
                throw IOException("Atomic secure store replacement failed")
            }
        } catch (e: IOException) {
            tempFile.delete()
            secureWriteFailure(e)
        } catch (e: GeneralSecurityException) {
            tempFile.delete()
            secureWriteFailure(e)
        } catch (e: IllegalArgumentException) {
            tempFile.delete()
            secureWriteFailure(e)
        } catch (e: IllegalStateException) {
            tempFile.delete()
            secureWriteFailure(e)
        }
    }

    private fun encodeValues(values: Map<String, String>): ByteArray {
        require(values.size <= MAX_ENTRIES) { "Too many secure preference entries" }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(PLAINTEXT_MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(values.size)
                values.toSortedMap().forEach { (key, value) ->
                    require(key.length <= MAX_STRING_LENGTH && value.length <= MAX_STRING_LENGTH) {
                        "Secure preference value is too large"
                    }
                    output.writeUTF(key)
                    output.writeUTF(value)
                }
                output.flush()
            }
            bytes.toByteArray()
        }
    }

    private fun decodeValues(bytes: ByteArray): Map<String, String> {
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "Secure preference payload is too large" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PLAINTEXT_MAGIC) { "Invalid secure preference payload" }
            require(input.readInt() == FORMAT_VERSION) { "Unsupported secure preference payload" }
            val count = input.readInt()
            require(count in 0..MAX_ENTRIES) { "Invalid secure preference entry count" }
            buildMap {
                repeat(count) {
                    val key = input.readUTF()
                    val value = input.readUTF()
                    require(key.length <= MAX_STRING_LENGTH && value.length <= MAX_STRING_LENGTH) {
                        "Secure preference value is too large"
                    }
                    require(put(key, value) == null) { "Duplicate secure preference key" }
                }
            }
        }
    }

    private fun secureReadFailure(cause: Throwable): Nothing =
        throw IllegalStateException("Unable to decrypt or parse secure preferences", cause)

    private fun secureWriteFailure(cause: Throwable): Nothing =
        throw IllegalStateException("Unable to durably write secure preferences", cause)

    private fun readBoundedBytes(input: DataInputStream, maxSize: Int): ByteArray {
        val size = input.readInt()
        require(size in 1..maxSize) { "Invalid secure preference payload size" }
        return ByteArray(size).also { input.readFully(it) }
    }

    private companion object {
        const val ENVELOPE_MAGIC = 0x56564645
        const val PLAINTEXT_MAGIC = 0x56564650
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 32
        const val MAX_STRING_LENGTH = 16_384
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
        const val MIN_CIPHERTEXT_BYTES = 16
        const val MAX_CIPHERTEXT_BYTES = 512 * 1024
        const val MAX_PLAINTEXT_BYTES = 512 * 1024
    }
}
