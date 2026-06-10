package com.nax.atsupager.data.network

import android.content.Context
import android.net.Uri
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.KeyStorageManager
import okio.Buffer
import okio.buffer
import okio.source
import java.io.File

/**
 * Optimized Fetcher for Coil. 
 * Uses high-speed direct RAM decryption to avoid slow streaming and GC thrashing.
 */
class EncryptedImageFetcher(
    private val context: Context,
    private val filePath: String,
    private val mimeType: String?,
    private val encryptionManager: EncryptionManager,
    private val keyStorageManager: KeyStorageManager,
    private val userRepository: UserRepository
) : Fetcher {

    override suspend fun fetch(): SourceResult? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null

            val source = if (filePath.endsWith(".enc")) {
                val currentUserId = userRepository.getCurrentUserId() ?: return null
                val key = keyStorageManager.getMediaEncryptionKey(currentUserId)
                
                // Use high-speed method: Decrypt whole file into RAM at once.
                // For photos (usually < 5MB), this is nearly instant (< 100ms).
                val decryptedBytes = encryptionManager.decryptFileToByteArray(file, key)
                    ?: return null
                
                val buffer = Buffer()
                buffer.write(decryptedBytes)
                buffer
            } else {
                if (filePath.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(filePath))?.source()?.buffer()
                } else {
                    file.source().buffer()
                }
            } ?: return null

            SourceResult(
                source = ImageSource(source = source, context = context),
                mimeType = mimeType,
                dataSource = DataSource.DISK
            )
        } catch (e: Exception) {
            Log.e("EncryptedImageFetcher", "Fetch failed for $filePath", e)
            null
        }
    }

    class MessageFactory(
        private val encryptionManager: EncryptionManager,
        private val keyStorageManager: KeyStorageManager,
        private val userRepository: UserRepository
    ) : Fetcher.Factory<ChatMessage> {
        override fun create(data: ChatMessage, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = data.localFilePath ?: return null
            return EncryptedImageFetcher(options.context, path, data.mimeType, encryptionManager, keyStorageManager, userRepository)
        }
    }

    class FileFactory(
        private val encryptionManager: EncryptionManager,
        private val keyStorageManager: KeyStorageManager,
        private val userRepository: UserRepository
    ) : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.name.endsWith(".enc")) {
                return EncryptedImageFetcher(options.context, data.absolutePath, null, encryptionManager, keyStorageManager, userRepository)
            }
            return null
        }
    }
}
