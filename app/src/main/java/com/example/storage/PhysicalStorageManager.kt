package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

data class VaultStorageResult(
    val vaultFilePath: String,
    val encryptedFileName: String,
    val iv: ByteArray
)

object PhysicalStorageManager {
    private const val TAG = "PhysicalStorageManager"

    fun safeTrashFileName(name: String): String {
        val basename = File(name).name
        val sanitized = basename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(128)
        return sanitized.ifBlank { "content.bin" }
    }

    fun getRecycleBinDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, ".recycle_bin")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getVaultDir(context: Context): File {
        val dir = File(context.filesDir, ".vault")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRestoredDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Restored")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getFileNameFromContentUri(context: Context, uri: Uri): String {
        try {
            val docName = DocumentFile.fromSingleUri(context, uri)?.name
            if (!docName.isNullOrBlank()) return safeTrashFileName(docName)
        } catch (_: Exception) {}
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { return safeTrashFileName(it) }
                }
            }
        } catch (_: Exception) {}
        uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let { return safeTrashFileName(it) }
        return "file_${System.currentTimeMillis()}"
    }

    fun getFileSizeFromContentUri(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1) return cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (pfd.statSize >= 0) return pfd.statSize
            }
        } catch (_: Exception) {}
        return -1L
    }

    fun getFileNameFromVaultPathOrUri(context: Context, vaultFilePath: String, uri: Uri): String {
        val name = File(vaultFilePath).name
        if (name.startsWith("ENC_") && name.endsWith(".vvf")) {
            val withoutPrefix = name.removePrefix("ENC_").removeSuffix(".vvf")
            val extractedName = withoutPrefix.substringAfter("_")
            if (extractedName.isNotBlank() && extractedName != withoutPrefix) return safeTrashFileName(extractedName)
        }
        return getFileNameFromContentUri(context, uri)
    }

    fun getFileNameFromTrashPathOrUri(context: Context, trashFileName: String, uri: Uri): String {
        if (trashFileName.contains("_")) {
            val extractedName = trashFileName.substringAfter("_")
            if (extractedName.isNotBlank() && extractedName != trashFileName) return safeTrashFileName(extractedName)
        }
        return getFileNameFromContentUri(context, uri)
    }

    fun renameFile(context: Context, oldPath: String, newName: String): Result<String> {
        val sanitizedName = File(newName).name
        if (sanitizedName.isBlank() || sanitizedName != newName) {
            return Result.failure(IllegalArgumentException("Invalid file name. Path traversal or directory changes are not allowed."))
        }
        if (oldPath.startsWith("content://")) {
            return try {
                val uri = Uri.parse(oldPath)
                val doc = DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
                if (doc != null && doc.exists()) {
                    if (doc.renameTo(newName)) Result.success(doc.uri.toString())
                    else Result.failure(java.io.IOException("SAF DocumentFile rename failed for $oldPath"))
                } else Result.failure(java.io.FileNotFoundException("Document not found for URI $oldPath"))
            } catch (e: Exception) {
                Log.e(TAG, "Error renaming content URI $oldPath: ${e.message}", e)
                Result.failure(e)
            }
        }
        return try {
            val oldFile = File(oldPath)
            val parentDir = oldFile.parentFile
            val newFile = if (parentDir != null) File(parentDir, newName) else File(newName)
            var success = false
            if (oldFile.exists()) {
                try { success = oldFile.renameTo(newFile) } catch (e: Exception) { Log.w(TAG, "Direct File.renameTo failed: ${e.message}") }
                if (!success) {
                    try {
                        oldFile.copyTo(newFile, overwrite = true)
                        if (oldFile.delete()) success = true else try { if (newFile.exists()) newFile.delete() } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallback copy-and-delete failed: ${e.message}")
                        try { if (newFile.exists()) newFile.delete() } catch (_: Exception) {}
                    }
                }
            } else return Result.failure(java.io.FileNotFoundException("Source file not found at $oldPath"))
            updateMediaStoreDisplayName(context, oldPath, newName)
            val finalPath = if (newFile.exists()) newFile.absolutePath else if (success) newFile.absolutePath else oldPath
            if (success || newFile.exists()) {
                notifyMediaStoreFileChanged(context, oldPath, finalPath)
                Result.success(finalPath)
            } else Result.failure(java.io.IOException("Failed to physically rename file $oldPath to $newName"))
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming file physically: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun deleteFile(context: Context, path: String): Boolean {
        if (path.startsWith("content://")) {
            return try {
                val uri = Uri.parse(path)
                val doc = DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
                if (doc != null && !doc.exists()) return true
                if (doc?.delete() == true) return true
                val rows = try { context.contentResolver.delete(uri, null, null) } catch (_: SecurityException) { throw java.security.GeneralSecurityException("Permission denied deleting $path") } catch (_: Exception) { 0 }
                if (rows > 0) return true
                val stillExists = try {
                    if (doc != null) doc.exists() else context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                } catch (_: Exception) { false }
                !stillExists
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete content URI $path: ${e.message}")
                false
            }
        }
        val file = File(path)
        var deleted = false
        if (file.exists()) try { deleted = file.delete() } catch (e: Exception) { Log.w(TAG, "Direct File.delete failed: ${e.message}") }
        if (deleteFromMediaStore(context, path)) deleted = true
        if (deleted) notifyMediaStoreFileDeleted(context, path)
        return deleted || !file.exists()
    }

    fun moveToTrash(context: Context, path: String): Result<String> {
        if (path.startsWith("content://")) {
            val trashDir = getRecycleBinDir(context)
            val uri = Uri.parse(path)
            val docName = try { DocumentFile.fromSingleUri(context, uri)?.name ?: "content_${System.currentTimeMillis()}.bin" } catch (_: Exception) { "content_${System.currentTimeMillis()}.bin" }
            val trashFile = File(trashDir, "${System.currentTimeMillis()}_${safeTrashFileName(docName)}")
            return try {
                val copied = context.contentResolver.openInputStream(uri)?.use { input -> trashFile.outputStream().use { output -> input.copyTo(output) }; true } ?: false
                if (!copied) { try { trashFile.delete() } catch (_: Exception) {}; return Result.failure(java.io.IOException("Failed to copy content URI to trash: $path")) }
                val doc = DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
                val originalDeleted = doc?.delete() ?: (context.contentResolver.delete(uri, null, null) > 0)
                if (originalDeleted) { notifyMediaStoreFileDeleted(context, path); Result.success(trashFile.absolutePath) }
                else { try { trashFile.delete() } catch (_: Exception) {}; Result.failure(java.io.IOException("Failed to delete original content URI after trash copy: $path")) }
            } catch (e: Exception) { try { trashFile.delete() } catch (_: Exception) {}; Result.failure(e) }
        }
        val srcFile = File(path)
        if (!srcFile.exists()) return Result.failure(java.io.FileNotFoundException("Source file not found at $path"))
        val trashFile = File(getRecycleBinDir(context), "${System.currentTimeMillis()}_${srcFile.name}")
        var moved = false
        try { moved = srcFile.renameTo(trashFile) } catch (e: Exception) { Log.w(TAG, "Direct rename to trash failed: ${e.message}") }
        if (!moved) {
            try {
                srcFile.copyTo(trashFile, overwrite = true)
                if (srcFile.delete() || deleteFromMediaStore(context, path)) moved = true else try { trashFile.delete() } catch (_: Exception) {}
            } catch (e: Exception) { Log.e(TAG, "Copy to trash failed: ${e.message}"); try { trashFile.delete() } catch (_: Exception) {} }
        }
        return if (moved && trashFile.exists()) { notifyMediaStoreFileDeleted(context, path); Result.success(trashFile.absolutePath) } else Result.failure(java.io.IOException("Failed to move file to trash: $path"))
    }

    fun restoreFromTrash(context: Context, trashPath: String, originalPath: String): Result<String> {
        if (originalPath.startsWith("content://")) {
            val trashFile = File(trashPath)
            if (!trashFile.exists()) return Result.failure(java.io.FileNotFoundException("Trash file not found at $trashPath"))
            return try {
                val uri = Uri.parse(originalPath)
                var writtenToOriginal = false
                try { context.contentResolver.openOutputStream(uri)?.use { output -> trashFile.inputStream().use { input -> input.copyTo(output) }; writtenToOriginal = true } } catch (e: Exception) { Log.w(TAG, "Could not write to original content URI $originalPath: ${e.message}") }
                if (writtenToOriginal) {
                    if (trashFile.delete()) { notifyMediaStoreFileChanged(context, "", originalPath); return Result.success(originalPath) }
                    return Result.failure(IllegalStateException("Restored original URI but failed to remove trash copy."))
                }
                val restoredFile = File(getRestoredDir(context), getFileNameFromTrashPathOrUri(context, trashFile.name, uri))
                trashFile.copyTo(restoredFile, overwrite = true)
                if (!restoredFile.exists() || restoredFile.length() == 0L) { try { restoredFile.delete() } catch (_: Exception) {}; return Result.failure(java.io.IOException("Failed to write restored file at ${restoredFile.absolutePath}")) }
                if (!trashFile.delete() && trashFile.exists()) { try { restoredFile.delete() } catch (_: Exception) {}; return Result.failure(IllegalStateException("Failed to delete trash file after restoration.")) }
                notifyMediaStoreFileChanged(context, "", restoredFile.absolutePath)
                Result.success(restoredFile.absolutePath)
            } catch (e: Exception) { Result.failure(e) }
        }
        val trashFile = File(trashPath)
        if (!trashFile.exists()) return Result.failure(java.io.FileNotFoundException("Trash file not found at $trashPath"))
        val targetFile = File(originalPath)
        targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        var restored = false
        try { restored = trashFile.renameTo(targetFile) } catch (e: Exception) { Log.w(TAG, "Direct rename from trash failed: ${e.message}") }
        if (!restored) {
            try { trashFile.copyTo(targetFile, overwrite = true); if (trashFile.delete()) restored = true else try { targetFile.delete() } catch (_: Exception) {} } catch (e: Exception) { Log.e(TAG, "Restore copy from trash failed: ${e.message}"); try { targetFile.delete() } catch (_: Exception) {} }
        }
        return if (restored && targetFile.exists()) { notifyMediaStoreFileChanged(context, "", targetFile.absolutePath); Result.success(targetFile.absolutePath) } else Result.failure(java.io.IOException("Failed to restore file from trash: $trashPath"))
    }

    fun decryptAndRestore(context: Context, vaultFilePath: String, originalPath: String, decryptAction: (ByteArray) -> ByteArray): Result<String> {
        if (originalPath.startsWith("content://")) {
            return try {
                val vaultFile = File(vaultFilePath)
                if (!vaultFile.exists()) return Result.failure(java.io.FileNotFoundException("Vault file not found at $vaultFilePath"))
                if (vaultFile.length() > 50 * 1024 * 1024L) return Result.failure(IllegalArgumentException("Vault file exceeds the maximum secure vault limit of 50MB."))
                val decryptedBytes = decryptAction(vaultFile.readBytes())
                val uri = Uri.parse(originalPath)
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(decryptedBytes) } ?: throw java.io.IOException("Unable to write original content URI")
                    if (!vaultFile.delete() && vaultFile.exists()) return Result.failure(IllegalStateException("Failed to delete encrypted vault source file."))
                    notifyMediaStoreFileChanged(context, "", originalPath)
                    return Result.success(originalPath)
                } catch (_: Exception) {
                    val restoredFile = File(getRestoredDir(context), getFileNameFromVaultPathOrUri(context, vaultFilePath, uri))
                    FileOutputStream(restoredFile).use { it.write(decryptedBytes) }
                    if (!restoredFile.exists() || restoredFile.length() == 0L) { try { restoredFile.delete() } catch (_: Exception) {}; return Result.failure(java.io.IOException("Failed to write restored file")) }
                    if (!vaultFile.delete() && vaultFile.exists()) { try { restoredFile.delete() } catch (_: Exception) {}; return Result.failure(IllegalStateException("Failed to delete encrypted vault source file.")) }
                    notifyMediaStoreFileChanged(context, "", restoredFile.absolutePath)
                    Result.success(restoredFile.absolutePath)
                }
            } catch (e: javax.crypto.AEADBadTagException) { Result.failure(java.security.GeneralSecurityException("Decryption failed: Incorrect PIN or tampered vault data.", e)) }
            catch (e: OutOfMemoryError) { System.gc(); Result.failure(e) }
            catch (e: Exception) { Log.e(TAG, "Failed to decrypt and restore: ${e.message}", e); Result.failure(e) }
        }
        return try {
            val vaultFile = File(vaultFilePath)
            if (!vaultFile.exists()) return Result.failure(java.io.FileNotFoundException("Vault file not found at $vaultFilePath"))
            if (vaultFile.length() > 50 * 1024 * 1024L) return Result.failure(IllegalArgumentException("Vault file exceeds the maximum secure vault limit of 50MB."))
            val targetFile = File(originalPath)
            targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val decryptedBytes = decryptAction(vaultFile.readBytes())
            FileOutputStream(targetFile).use { it.write(decryptedBytes) }
            if (!vaultFile.delete() && vaultFile.exists()) { try { targetFile.delete() } catch (_: Exception) {}; return Result.failure(IllegalStateException("Failed to delete encrypted vault source file.")) }
            notifyMediaStoreFileChanged(context, "", targetFile.absolutePath)
            Result.success(targetFile.absolutePath)
        } catch (e: javax.crypto.AEADBadTagException) { Result.failure(java.security.GeneralSecurityException("Decryption failed: Incorrect PIN or tampered vault data.", e)) }
        catch (e: OutOfMemoryError) { System.gc(); Result.failure(e) }
        catch (e: Exception) { Log.e(TAG, "Failed to decrypt and restore: ${e.message}", e); Result.failure(e) }
    }

    fun decryptAndRestore(context: Context, vaultFilePath: String, originalPath: String, iv: ByteArray, keystoreVaultManager: com.example.security.KeystoreVaultManager): Result<String> {
        if (originalPath.startsWith("content://")) {
            return try {
                val vaultFile = File(vaultFilePath)
                if (!vaultFile.exists()) return Result.failure(java.io.FileNotFoundException("Vault file not found at $vaultFilePath"))
                val uri = Uri.parse(originalPath)
                fun decryptTo(output: java.io.OutputStream) {
                    val cipher = keystoreVaultManager.getDecryptionCipher(iv)
                    java.io.FileInputStream(vaultFile).use { fis ->
                        javax.crypto.CipherInputStream(fis, cipher).use { input ->
                            val buffer = ByteArray(65536)
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) output.write(buffer, 0, n)
                        }
                    }
                }
                try {
                    context.contentResolver.openOutputStream(uri)?.use { decryptTo(it) } ?: throw java.io.IOException("Unable to write original content URI")
                    if (!vaultFile.delete() && vaultFile.exists()) return Result.failure(IllegalStateException("Failed to delete encrypted vault source file."))
                    notifyMediaStoreFileChanged(context, "", originalPath)
                    Result.success(originalPath)
                } catch (_: Exception) {
                    val restoredFile = File(getRestoredDir(context), getFileNameFromVaultPathOrUri(context, vaultFilePath, uri))
                    FileOutputStream(restoredFile).use { decryptTo(it) }
                    if (!restoredFile.exists() || restoredFile.length() == 0L) { try { restoredFile.delete() } catch (_: Exception) {}; return Result.failure(java.io.IOException("Failed to write restored file")) }
                    if (!vaultFile.delete() && vaultFile.exists()) { try { restoredFile.delete() } catch (_: Exception) {}; return Result.failure(IllegalStateException("Failed to delete encrypted vault source file.")) }
                    notifyMediaStoreFileChanged(context, "", restoredFile.absolutePath)
                    Result.success(restoredFile.absolutePath)
                }
            } catch (e: javax.crypto.AEADBadTagException) { Result.failure(java.security.GeneralSecurityException("Decryption failed: Incorrect PIN or tampered vault data.", e)) }
            catch (e: OutOfMemoryError) { System.gc(); Result.failure(e) }
            catch (e: Exception) { Log.e(TAG, "Failed to decrypt and restore Stream: ${e.message}", e); Result.failure(e) }
        }
        return try {
            val vaultFile = File(vaultFilePath)
            if (!vaultFile.exists()) return Result.failure(java.io.FileNotFoundException("Vault file not found at $vaultFilePath"))
            val targetFile = File(originalPath)
            targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val cipher = keystoreVaultManager.getDecryptionCipher(iv)
            java.io.FileInputStream(vaultFile).use { fis ->
                javax.crypto.CipherInputStream(fis, cipher).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(65536)
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) output.write(buffer, 0, n)
                    }
                }
            }
            if (!vaultFile.delete() && vaultFile.exists()) { try { targetFile.delete() } catch (_: Exception) {}; return Result.failure(IllegalStateException("Failed to delete encrypted vault source file.")) }
            notifyMediaStoreFileChanged(context, "", targetFile.absolutePath)
            Result.success(targetFile.absolutePath)
        } catch (e: javax.crypto.AEADBadTagException) { Result.failure(java.security.GeneralSecurityException("Decryption failed: Incorrect PIN or tampered vault data.", e)) }
        catch (e: OutOfMemoryError) { System.gc(); Result.failure(e) }
        catch (e: Exception) { Log.e(TAG, "Failed to decrypt and restore Stream: ${e.message}", e); Result.failure(e) }
    }

    fun encryptAndWipeSource(context: Context, srcPath: String, encryptAction: (ByteArray) -> Pair<ByteArray, ByteArray>): Result<VaultStorageResult> {
        if (srcPath.startsWith("content://")) {
            return try {
                val uri = Uri.parse(srcPath)
                val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return Result.failure(java.io.FileNotFoundException("Unable to open stream for content URI: $srcPath"))
                if (fileBytes.size > 50 * 1024 * 1024) return Result.failure(IllegalArgumentException("File exceeds the maximum secure vault limit of 50MB."))
                val docName = getFileNameFromContentUri(context, uri)
                val (encryptedBytes, iv) = encryptAction(fileBytes)
                val vaultFile = File(getVaultDir(context), "ENC_${System.currentTimeMillis()}_${docName}.vvf")
                try { FileOutputStream(vaultFile).use { it.write(encryptedBytes) } } catch (e: Exception) { try { vaultFile.delete() } catch (_: Exception) {}; throw e }
                if (!vaultFile.exists() || vaultFile.length() == 0L) { try { vaultFile.delete() } catch (_: Exception) {}; return Result.failure(java.io.IOException("Vault file creation failed.")) }
                val doc = DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
                val originalDeleted = doc?.delete() ?: (context.contentResolver.delete(uri, null, null) > 0)
                if (originalDeleted) { notifyMediaStoreFileDeleted(context, srcPath); Result.success(VaultStorageResult(vaultFile.absolutePath, vaultFile.name, iv)) }
                else { try { vaultFile.delete() } catch (_: Exception) {}; Result.failure(java.io.IOException("Failed to delete original content URI after vault encryption: $srcPath")) }
            } catch (e: Exception) { Log.e(TAG, "Error encrypting content URI $srcPath: ${e.message}", e); Result.failure(e) }
        }
        val srcFile = File(srcPath)
        return try {
            if (!srcFile.exists() || !srcFile.canRead()) return Result.failure(java.io.FileNotFoundException("Source file not found or unreadable at $srcPath"))
            if (srcFile.length() > 50 * 1024 * 1024L) return Result.failure(IllegalArgumentException("File exceeds the maximum secure vault limit of 50MB."))
            val (encryptedBytes, iv) = encryptAction(srcFile.readBytes())
            val vaultFile = File(getVaultDir(context), "ENC_${System.currentTimeMillis()}_${srcFile.name}.vvf")
            try { FileOutputStream(vaultFile).use { it.write(encryptedBytes) } } catch (e: Exception) { try { vaultFile.delete() } catch (_: Exception) {}; throw e }
            if (!secureWipeFile(context, srcFile)) { try { vaultFile.delete() } catch (_: Exception) {}; return Result.failure(java.io.IOException("Failed to securely remove source file after vault encryption: $srcPath")) }
            Result.success(VaultStorageResult(vaultFile.absolutePath, vaultFile.name, iv))
        } catch (e: javax.crypto.AEADBadTagException) { Result.failure(java.security.GeneralSecurityException("Encryption failed: Incorrect key or tampered data.", e)) }
        catch (e: OutOfMemoryError) { System.gc(); Result.failure(e) }
        catch (e: Exception) { Log.e(TAG, "Failed to encrypt and wipe source: ${e.message}", e); Result.failure(e) }
    }

    fun encryptAndWipeSource(context: Context, srcPath: String, keystoreVaultManager: com.example.security.KeystoreVaultManager): Result<VaultStorageResult> {
        if (srcPath.startsWith("content://")) {
            return try {
                val uri = Uri.parse(srcPath)
                val docName = getFileNameFromContentUri(context, uri)
                val vaultFile = File(getVaultDir(context), "ENC_${System.currentTimeMillis()}_${docName}.vvf")
                val cipher = keystoreVaultManager.getEncryptionCipher()
                val iv = cipher.iv
                val inputStream = context.contentResolver.openInputStream(uri) ?: return Result.failure(java.io.FileNotFoundException("Unable to open stream for content URI: $srcPath"))
                try {
                    inputStream.use { fis ->
                        FileOutputStream(vaultFile).use { fos ->
                            javax.crypto.CipherOutputStream(fos, cipher).use { output ->
                                val buffer = ByteArray(65536)
                                var n: Int
                                while (fis.read(buffer).also { n = it } != -1) output.write(buffer, 0, n)
                            }
                        }
                    }
                } catch (e: Exception) { try { vaultFile.delete() } catch (_: Exception) {}; throw e }
                if (!vaultFile.exists() || vaultFile.length() == 0L) { try { vaultFile.delete() } catch (_: Exception) {}; return Result.failure(java.io.IOException("Vault file creation failed or empty.")) }
                val doc = DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
                val originalDeleted = doc?.delete() ?: (context.contentResolver.delete(uri, null, null) > 0)
                if (originalDeleted) { notifyMediaStoreFileDeleted(context, srcPath); Result.success(VaultStorageResult(vaultFile.absolutePath, vaultFile.name, iv)) }
                else { try { vaultFile.delete() } catch (_: Exception) {}; Result.failure(java.io.IOException("Failed to delete original content URI after vault encryption: $srcPath")) }
            } catch (e: Exception) { Log.e(TAG, "Error encrypting content URI $srcPath: ${e.message}", e); Result.failure(e) }
        }
        val srcFile = File(srcPath)
        return try {
            if (!srcFile.exists() || !srcFile.canRead()) return Result.failure(java.io.FileNotFoundException("Source file not found or unreadable at $srcPath"))
            val vaultFile = File(getVaultDir(context), "ENC_${System.currentTimeMillis()}_${srcFile.name}.vvf")
            val cipher = keystoreVaultManager.getEncryptionCipher()
            val iv = cipher.iv
            try {
                java.io.FileInputStream(srcFile).use { fis ->
                    FileOutputStream(vaultFile).use { fos ->
                        javax.crypto.CipherOutputStream(fos, cipher).use { output ->
                            val buffer = ByteArray(65536)
                            var n: Int
                            while (fis.read(buffer).also { n = it } != -1) output.write(buffer, 0, n)
                        }
                    }
                }
            } catch (e: Exception) { try { vaultFile.delete() } catch (_: Exception) {}; throw e }
            if (!secureWipeFile(context, srcFile)) { try { vaultFile.delete() } catch (_: Exception) {}; return Result.failure(java.io.IOException("Failed to securely remove source file after vault encryption: $srcPath")) }
            Result.success(VaultStorageResult(vaultFile.absolutePath, vaultFile.name, iv))
        } catch (e: javax.crypto.AEADBadTagException) { Result.failure(java.security.GeneralSecurityException("Encryption failed: Incorrect key or tampered data.", e)) }
        catch (e: OutOfMemoryError) { System.gc(); Result.failure(e) }
        catch (e: Exception) { Log.e(TAG, "Failed to encrypt and wipe source Stream: ${e.message}", e); Result.failure(e) }
    }

    private fun secureWipeFile(context: Context, file: File): Boolean {
        if (!file.exists()) return true
        if (!file.canWrite()) return false
        return try {
            val length = file.length()
            if (length > 0) {
                val secureRandom = java.security.SecureRandom()
                val buffer = ByteArray(65536)
                val passes = listOf("random", "zeros", "random")
                for (pass in passes) {
                    java.io.RandomAccessFile(file, "rws").use { raf ->
                        raf.seek(0)
                        var remaining = length
                        while (remaining > 0) {
                            val toWrite = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                            if (pass == "zeros") buffer.fill(0) else secureRandom.nextBytes(buffer)
                            raf.write(buffer, 0, toWrite)
                            remaining -= toWrite
                        }
                        try { raf.fd.sync() } catch (_: Exception) {}
                    }
                }
            }
            try { deleteFile(context, file.absolutePath) } catch (e: Exception) { Log.e(TAG, "Failed to delete source after overwrite: ${e.message}", e) }
            !file.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to securely overwrite file contents: ${e.message}", e)
            false
        }
    }

    private fun updateMediaStoreDisplayName(context: Context, oldPath: String, newName: String): Boolean {
        return try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")
            val values = ContentValues().apply { put(MediaStore.Files.FileColumns.DISPLAY_NAME, newName) }
            context.contentResolver.update(collection, values, "${MediaStore.Files.FileColumns.DATA} = ?", arrayOf(oldPath)) > 0
        } catch (e: Exception) { Log.w(TAG, "MediaStore update failed: ${e.message}"); false }
    }

    private fun deleteFromMediaStore(context: Context, path: String): Boolean {
        return try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")
            context.contentResolver.delete(collection, "${MediaStore.Files.FileColumns.DATA} = ?", arrayOf(path)) > 0
        } catch (e: Exception) { Log.w(TAG, "MediaStore delete failed: ${e.message}"); false }
    }

    private fun notifyMediaStoreFileChanged(context: Context, oldPath: String, newPath: String) {
        if (newPath.startsWith("content://")) return
        try {
            val file = File(newPath)
            if (file.exists()) android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { path, uri -> Log.d(TAG, "Scanned $path -> $uri") }
        } catch (e: Exception) { Log.w(TAG, "Failed to notify media scanner: ${e.message}") }
    }

    private fun notifyMediaStoreFileDeleted(context: Context, path: String) { deleteFromMediaStore(context, path) }
}
