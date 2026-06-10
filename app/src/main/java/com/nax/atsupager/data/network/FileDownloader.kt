package com.nax.atsupager.data.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.di.FileOkHttpClient
import com.nax.atsupager.data.manager.SessionManager
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.security.SecureDataHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FileDownloader"
private const val TEMP_FILE_PREFIX = "temp_atsu_"
private const val DEFAULT_BUFFER_SIZE = 65536 

enum class DownloadStatus {
    DOWNLOADING, DECRYPTING
}

data class DownloadProgress(val progress: Float, val status: DownloadStatus)

@Singleton
class FileDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val encryptionManager: EncryptionManager,
    private val userRepository: UserRepository,
    private val keyStorageManager: KeyStorageManager,
    private val sessionManager: SessionManager,
    @FileOkHttpClient private val okHttpClient: OkHttpClient
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadingMessageIds = MutableStateFlow<Set<Long>>(emptySet())
    val downloadingMessageIds = _downloadingMessageIds.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _downloadError = MutableSharedFlow<String>()
    val downloadError = _downloadError.asSharedFlow()

    init {
        clearCache()
    }

    private fun clearCache() {
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { if (it.name.startsWith(TEMP_FILE_PREFIX)) it.delete() }
        } catch (e: Exception) { }
    }

    fun downloadFile(message: ChatMessage) {
        if (downloadingMessageIds.value.contains(message.id)) return
        val url = message.fileUrl ?: return

        coroutineScope.launch {
            _downloadingMessageIds.update { it + message.id }
            _downloadProgress.update { it + (message.id to DownloadProgress(0f, DownloadStatus.DOWNLOADING)) }
            
            var localKey: ByteArray? = null
            try {
                val currentUserId = userRepository.getCurrentUserId() ?: throw Exception("Unauthorized")
                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                
                if (!response.isSuccessful) throw IOException("Download failed")
                val responseBody = response.body ?: throw IOException("Empty body")
                
                val totalBytes = if (responseBody.contentLength() > 0) responseBody.contentLength() else (message.fileSize ?: -1L)
                val networkInput = responseBody.byteStream()
                val progressInput = createProgressInputStream(networkInput, totalBytes) { p ->
                    _downloadProgress.update { it + (message.id to DownloadProgress(p, DownloadStatus.DOWNLOADING)) }
                }

                val transportStream = if (!message.fileEncryptionKey.isNullOrEmpty()) {
                    val partnerPubKey = userRepository.getUser(message.fromUserId)?.publicKey 
                        ?: throw Exception("Partner public key not found for ${message.fromUserId}")
                    
                    encryptionManager.getDecryptingStreamForTransport(progressInput, message.fileEncryptionKey!!, currentUserId, partnerPubKey)
                } else {
                    progressInput
                } ?: throw Exception("Decryption setup failed")

                val digest = MessageDigest.getInstance("SHA-256")
                val digestingInput = DigestInputStream(transportStream, digest)

                val chatDir = sessionManager.getMediaDir(message.fromUserId, "incoming")
                val secureFile = File(chatDir, "file_${message.id}.enc")
                
                localKey = keyStorageManager.getMediaEncryptionKey(currentUserId)
                
                FileOutputStream(secureFile).use { fos ->
                    val localEncryptingStream = encryptionManager.getEncryptingStreamForStorage(fos, localKey!!)
                        ?: throw Exception("Local encryption failed")
                    
                    digestingInput.use { input ->
                        localEncryptingStream.use { output ->
                            input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        }
                    }
                }

                if (message.fileHash != null) {
                    val actualHash = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
                    if (actualHash != message.fileHash) {
                        secureFile.delete()
                        throw Exception("Integrity check failed!")
                    }
                }

                _downloadProgress.update { it + (message.id to DownloadProgress(1f, DownloadStatus.DOWNLOADING)) }
                messageDao.updateLocalFilePath(message.id, secureFile.absolutePath)
                confirmDownload(url)

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _downloadError.emit(e.message ?: "Download error")
            } finally {
                SecureDataHandler.wipe(localKey)
                _downloadingMessageIds.update { it - message.id }
                _downloadProgress.update { it - message.id }
            }
        }
    }

    private suspend fun saveToPublicMediaStore(input: InputStream, fileName: String, mimeType: String?): Uri? = withContext(Dispatchers.IO) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: "application/octet-stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AtsuPager")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                }
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        } else null
    }

    fun downloadToPublicFolder(message: ChatMessage) {
        if (downloadingMessageIds.value.contains(message.id)) return
        val url = message.fileUrl ?: return

        coroutineScope.launch {
            _downloadingMessageIds.update { it + message.id }
            _downloadProgress.update { it + (message.id to DownloadProgress(0f, DownloadStatus.DOWNLOADING)) }
            
            try {
                val currentUserId = userRepository.getCurrentUserId() ?: throw Exception("Unauthorized")
                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                
                if (!response.isSuccessful) throw IOException("Download failed")
                val responseBody = response.body ?: throw IOException("Empty body")
                
                val totalBytes = if (responseBody.contentLength() > 0) responseBody.contentLength() else (message.fileSize ?: -1L)
                val networkInput = responseBody.byteStream()
                val progressInput = createProgressInputStream(networkInput, totalBytes) { p ->
                    _downloadProgress.update { it + (message.id to DownloadProgress(p, DownloadStatus.DOWNLOADING)) }
                }

                val transportStream = if (!message.fileEncryptionKey.isNullOrEmpty()) {
                    val partnerPubKey = userRepository.getUser(message.fromUserId)?.publicKey 
                        ?: throw Exception("Partner public key not found")
                    encryptionManager.getDecryptingStreamForTransport(progressInput, message.fileEncryptionKey!!, currentUserId, partnerPubKey)
                } else {
                    progressInput
                } ?: throw Exception("Decryption failed")

                val digest = MessageDigest.getInstance("SHA-256")
                val digestingInput = DigestInputStream(transportStream, digest)

                val savedUri = saveToPublicMediaStore(
                    digestingInput, 
                    message.fileName ?: "file_${message.id}", 
                    message.mimeType
                )
                
                if (savedUri != null) {
                    if (message.fileHash != null) {
                        val actualHash = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
                        if (actualHash != message.fileHash) {
                            context.contentResolver.delete(savedUri, null, null)
                            throw Exception("Integrity check failed!")
                        }
                    }
                    _downloadProgress.update { it + (message.id to DownloadProgress(1f, DownloadStatus.DOWNLOADING)) }
                    messageDao.updateLocalFilePath(message.id, savedUri.toString())
                    confirmDownload(url)
                } else {
                    throw Exception("Failed to save to MediaStore")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Public download failed", e)
                _downloadError.emit(e.message ?: "Failed to save to public folder")
            } finally {
                _downloadingMessageIds.update { it - message.id }
                _downloadProgress.update { it - message.id }
            }
        }
    }

    suspend fun exportEncryptedFile(message: ChatMessage): Uri? = withContext(Dispatchers.IO) {
        val localPath = message.localFilePath ?: return@withContext null
        if (!localPath.endsWith(".enc")) return@withContext null

        val sourceFile = File(localPath)
        if (!sourceFile.exists()) return@withContext null

        _downloadProgress.update { it + (message.id to DownloadProgress(0f, DownloadStatus.DECRYPTING)) }

        var localKey: ByteArray? = null
        try {
            val currentUserId = userRepository.getCurrentUserId() ?: return@withContext null
            localKey = keyStorageManager.getMediaEncryptionKey(currentUserId)
            val totalLength = sourceFile.length()
            
            val uri = FileInputStream(sourceFile).use { fis ->
                val progressInput = createProgressInputStream(fis, totalLength) { p ->
                    _downloadProgress.update { it + (message.id to DownloadProgress(p, DownloadStatus.DECRYPTING)) }
                }
                
                val bufferedInput = BufferedInputStream(progressInput, DEFAULT_BUFFER_SIZE)
                val decryptedStream = encryptionManager.getDecryptingStreamFromStorage(bufferedInput, localKey!!) 
                    ?: return@withContext null
                
                saveToPublicMediaStore(
                    decryptedStream, 
                    message.fileName ?: "exported_${message.id}", 
                    message.mimeType
                )
            }

            _downloadProgress.update { it + (message.id to DownloadProgress(1f, DownloadStatus.DECRYPTING)) }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            null
        } finally {
            SecureDataHandler.wipe(localKey)
            _downloadProgress.update { it - message.id }
        }
    }

    fun confirmDownload(downloadUrl: String) {
        coroutineScope.launch {
            try {
                val rawName = downloadUrl.substringAfterLast("/")
                val fileName = rawName.substringBefore("?")
                
                val baseUrl = if (downloadUrl.contains("/uploads")) {
                    downloadUrl.substringBefore("/uploads")
                } else {
                    downloadUrl.substringBeforeLast("/")
                }
                
                val deleteUrl = "$baseUrl/delete-file"
                val formBody = FormBody.Builder().add("fileName", fileName).build()
                val request = Request.Builder().url(deleteUrl).post(formBody).build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully deleted from server: $fileName")
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun createProgressInputStream(input: InputStream, totalBytes: Long, onProgress: (Float) -> Unit): InputStream {
        return object : InputStream() {
            private var bytesRead: Long = 0
            private var lastP: Float = -2f

            override fun read(): Int {
                val b = input.read()
                if (b != -1) updateProgress(1)
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val read = input.read(b, off, len)
                if (read != -1) updateProgress(read.toLong())
                return read
            }

            private fun updateProgress(read: Long) {
                bytesRead += read
                if (totalBytes > 0) {
                    val p = bytesRead.toFloat() / totalBytes
                    if (p - lastP > 0.01f || p >= 1f) {
                        onProgress(p)
                        lastP = p
                    }
                }
            }
        }
    }
}
