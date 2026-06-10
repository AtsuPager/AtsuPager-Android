package com.nax.atsupager.security

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.nax.atsupager.data.network.UserRepository
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

class DecryptedFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val path = uri.path ?: return null
        val file = File(path)
        
        if (!file.exists()) return null

        // If the file is not encrypted, return a standard descriptor
        if (!path.endsWith(".enc")) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProviderEntryPoint::class.java
        )
        val encryptionManager = entryPoint.encryptionManager()
        val keyStorageManager = entryPoint.keyStorageManager()
        val userRepository = entryPoint.userRepository()

        val currentUserId = userRepository.getCurrentUserId() ?: return null
        val key = keyStorageManager.getMediaEncryptionKey(currentUserId)

        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            return null
        }
        
        val readSide = pipe[0]
        val writeSide = pipe[1]

        thread {
            try {
                FileOutputStream(writeSide.fileDescriptor).use { output ->
                    FileInputStream(file).use { input ->
                        val decryptedStream = encryptionManager.getDecryptingStreamFromStorage(input, key)
                        decryptedStream?.use { it.copyTo(output) }
                    }
                }
            } catch (e: Exception) {
                Log.e("DecryptedFileProvider", "Streaming failed for $path", e)
            } finally {
                try {
                    writeSide.close()
                } catch (_: Exception) {}
            }
        }

        return readSide
    }

    override fun getType(uri: Uri): String? {
        val path = uri.path ?: return null
        val cleanPath = path.removeSuffix(".enc")
        val extension = cleanPath.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val path = uri.path ?: return null
        val file = File(path)
        if (!file.exists()) return null

        val columnNames = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        )

        val cursor = MatrixCursor(columnNames)
        val row = cursor.newRow()

        for (column in columnNames) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> {
                    row.add(file.name.removeSuffix(".enc"))
                }
                OpenableColumns.SIZE -> {
                    row.add(file.length())
                }
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface ProviderEntryPoint {
        fun encryptionManager(): EncryptionManager
        fun keyStorageManager(): KeyStorageManager
        fun userRepository(): UserRepository
    }
}
