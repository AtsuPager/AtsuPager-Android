package com.nax.atsupager.data.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.nax.atsupager.security.EncryptionManager
import java.io.*

/**
 * Optimized DataSource for encrypted media.
 * For files up to 50MB, it decrypts into RAM to allow instant seeking (fixes slow GCM playback).
 * For larger files, it falls back to streaming.
 */
class EncryptedDataSource(
    private val encryptionManager: EncryptionManager,
    private val key: ByteArray,
    private val factory: EncryptedDataSourceFactory? = null
) : BaseDataSource(/* isNetwork = */ false) {

    private var inputStream: InputStream? = null
    private var opened = false
    private var bytesRemaining: Long = 0
    private var currentUri: Uri? = null
    
    private var memoryBuffer: ByteArray? = null
    private val MAX_RAM_CACHE_SIZE = 50 * 1024 * 1024 // 50MB

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        
        val path = dataSpec.uri.path ?: throw IOException("Invalid path")
        val file = File(path)
        if (!file.exists()) throw FileNotFoundException("File not found: $path")
        
        currentUri = dataSpec.uri

        // 1. Проверяем, есть ли уже дешифрованные данные в кэше фабрики
        var data = factory?.getCachedData(path)

        if (data == null && file.length() <= MAX_RAM_CACHE_SIZE) {
            // 2. Файл небольшой: дешифруем его целиком в RAM.
            // Это использует нативный BoringSSL и работает мгновенно (< 500мс для 13МБ)
            data = encryptionManager.decryptFileToByteArray(file, key)
            if (data != null) {
                factory?.setCachedData(path, data)
            }
        }

        if (data != null) {
            // Режим быстрого доступа из памяти
            memoryBuffer = data
            val bis = ByteArrayInputStream(data)
            if (dataSpec.position > 0) {
                bis.skip(dataSpec.position)
            }
            inputStream = bis
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                (data.size - dataSpec.position).toLong()
            }
        } else {
            // 3. Режим стриминга для очень больших файлов (> 50MB)
            val fis = FileInputStream(file)
            val bufferedFis = BufferedInputStream(fis, 128 * 1024)
            val decryptingStream = encryptionManager.getDecryptingStreamFromStorage(bufferedFis, key)
                ?: throw IOException("Failed to initialize decryption stream")

            if (dataSpec.position > 0) {
                var skippedTotal = 0L
                val skipBuf = ByteArray(32768)
                while (skippedTotal < dataSpec.position) {
                    val toRead = minOf(dataSpec.position - skippedTotal, skipBuf.size.toLong()).toInt()
                    val read = decryptingStream.read(skipBuf, 0, toRead)
                    if (read == -1) break
                    skippedTotal += read
                }
            }
            inputStream = decryptingStream
            
            val totalDecryptedSize = (file.length() - 12).coerceAtLeast(0)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                (totalDecryptedSize - dataSpec.position).coerceAtLeast(0)
            }
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }
        
        val read = try {
            inputStream?.read(buffer, offset, bytesToRead) ?: -1
        } catch (e: IOException) {
            throw e
        }

        if (read == -1) return C.RESULT_END_OF_INPUT

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        inputStream = null
        memoryBuffer = null 
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

class EncryptedDataSourceFactory(
    private val encryptionManager: EncryptionManager,
    private val key: ByteArray
) : DataSource.Factory {
    
    @Volatile
    private var cachedPath: String? = null
    @Volatile
    private var cachedData: ByteArray? = null

    override fun createDataSource(): DataSource = EncryptedDataSource(encryptionManager, key, this)

    @Synchronized
    fun getCachedData(path: String): ByteArray? {
        return if (cachedPath == path) cachedData else null
    }

    @Synchronized
    fun setCachedData(path: String, data: ByteArray) {
        cachedPath = path
        cachedData = data
    }
}
