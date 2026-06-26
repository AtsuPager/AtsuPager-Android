/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.data.network

import android.content.Context
import android.util.Base64
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.StreamingEncryptionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileUploader @Inject constructor(
    private val fileApiService: FileApiService,
    private val encryptionManager: EncryptionManager,
    private val userRepository: UserRepository,
    @ApplicationContext private val context: Context
) {

    suspend fun uploadFile(folder: String, file: File, targetUserId: String, localKey: ByteArray? = null): Result<FileUploadResult> {
        return withContext(Dispatchers.IO) {
            try {
                val currentUserId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(Exception("Unauthorized"))
                val pubKey = userRepository.getUser(targetUserId)?.publicKey ?: return@withContext Result.failure(Exception("No public key"))
                val transportEnc = encryptionManager.prepareStreamingEncryption(pubKey, currentUserId) ?: return@withContext Result.failure(Exception("Transport enc failed"))
                
                val encryptedRequestBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun writeTo(sink: BufferedSink) {
                        sink.write(transportEnc.iv)
                        val rawInput = FileInputStream(file)
                        val input = if (localKey != null && file.name.endsWith(".enc")) {
                            encryptionManager.getDecryptingStreamFromStorage(rawInput, localKey)
                        } else {
                            rawInput
                        }
                        
                        input?.use { i ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (i.read(buffer).also { read = it } != -1) {
                                transportEnc.cipher.update(buffer, 0, read)?.let { sink.write(it) }
                            }
                            transportEnc.cipher.doFinal()?.let { sink.write(it) }
                        }
                    }
                }

                val response = fileApiService.uploadFile(
                    folder.toRequestBody("text/plain".toMediaTypeOrNull()),
                    MultipartBody.Part.createFormData("file", file.name, encryptedRequestBody)
                )

                if (response.isSuccessful && response.body() != null) {
                    Result.success(FileUploadResult(response.body()!!.url, transportEnc.encryptedKeyBase64))
                } else {
                    Result.failure(IOException("Upload failed with code: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun uploadGroupFile(folder: String, file: File, localKey: ByteArray? = null): Result<FileUploadResult> {
        return withContext(Dispatchers.IO) {
            try {
                val fileKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
                val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, iv))

                val encryptedRequestBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun writeTo(sink: BufferedSink) {
                        sink.write(iv)
                        val rawInput = FileInputStream(file)
                        val input = if (localKey != null && file.name.endsWith(".enc")) {
                            encryptionManager.getDecryptingStreamFromStorage(rawInput, localKey)
                        } else {
                            rawInput
                        }
                        
                        input?.use { i ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (i.read(buffer).also { read = it } != -1) {
                                val out = cipher.update(buffer, 0, read)
                                if (out != null) sink.write(out)
                            }
                            val final = cipher.doFinal()
                            if (final != null) sink.write(final)
                        }
                    }
                }

                val response = fileApiService.uploadFile(
                    folder.toRequestBody("text/plain".toMediaTypeOrNull()),
                    MultipartBody.Part.createFormData("file", file.name, encryptedRequestBody)
                )

                if (response.isSuccessful && response.body() != null) {
                    Result.success(FileUploadResult(response.body()!!.url, Base64.encodeToString(fileKey, Base64.NO_WRAP)))
                } else {
                    Result.failure(IOException("Group upload failed with code: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun uploadReadyFile(folder: String, file: File, transportKeyBase64: String?): Result<FileUploadResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encryptedRequestBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun writeTo(sink: BufferedSink) {
                        FileInputStream(file).use { input ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                }

                val response = fileApiService.uploadFile(
                    folder.toRequestBody("text/plain".toMediaTypeOrNull()),
                    MultipartBody.Part.createFormData("file", file.name, encryptedRequestBody)
                )

                if (response.isSuccessful && response.body() != null) {
                    Result.success(FileUploadResult(response.body()!!.url, transportKeyBase64))
                } else {
                    Result.failure(IOException("Upload failed with code: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
