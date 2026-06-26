/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.data.network

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.nax.atsupager.data.db.*
import com.nax.atsupager.data.manager.SessionManager
import com.nax.atsupager.data.model.User
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.security.MediaSanitizer
import com.nax.atsupager.ui.screens.main.MainUiUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatMediaSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val groupDao: GroupDao,
    private val authRepository: AuthRepository,
    private val encryptionManager: EncryptionManager,
    private val keyStorageManager: KeyStorageManager,
    private val sessionManager: SessionManager,
    private val mediaSanitizer: MediaSanitizer,
    private val fileUploader: FileUploader,
    private val signalRepository: SignalRepository
) {

    suspend fun forwardMessage(message: ChatMessage, targetIds: List<String>) {
        if (targetIds.isEmpty()) return
        val currentUserId = authRepository.getCurrentUserId() ?: return
        val localKey = keyStorageManager.getMediaEncryptionKey(currentUserId)

        when (message.type) {
            MessageType.TEXT -> {
                for (targetId in targetIds) {
                    val isGroup = groupDao.getGroupById(targetId) != null
                    val forwardMsg = message.copy(
                        id = 0, remoteId = UUID.randomUUID().toString(),
                        fromUserId = currentUserId, toUserId = if (isGroup) "" else targetId,
                        groupId = if (isGroup) targetId else null, timestamp = System.currentTimeMillis(), isRead = true
                    )
                    val newId = messageDao.insertMessage(forwardMsg)
                    if (isGroup) signalRepository.sendGroupMessage(targetId, forwardMsg.copy(id = newId))
                    else signalRepository.sendFileMessage(targetId, forwardMsg.copy(id = newId))
                }
            }
            MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.FILE -> {
                val sourcePath = message.localFilePath ?: return
                val sourceFile = File(sourcePath)
                if (!sourceFile.exists()) return

                val hasGroup = targetIds.any { groupDao.getGroupById(it) != null }
                val useGroupStyle = targetIds.size > 1 || hasGroup
                
                val transportResult = if (useGroupStyle) {
                    signalRepository.prepareGroupTransportEncryption()
                } else {
                    signalRepository.prepareTransportEncryption(targetIds.first())
                }
                if (transportResult == null) return

                val uploadFile = File(sessionManager.getMediaDir("cache", "fwd"), "fwd_${UUID.randomUUID()}.tmp")
                val reEncSuccess = withContext(Dispatchers.IO) {
                    encryptionManager.reEncryptFile(sourceFile, localKey, uploadFile, transportResult)
                }
                if (!reEncSuccess) return

                val uploadResult = fileUploader.uploadReadyFile("Atsusend", uploadFile, transportResult.encryptedKeyBase64)
                uploadFile.delete()

                val resultData = uploadResult.getOrNull()
                if (resultData != null) {
                    for (targetId in targetIds) {
                        val isTargetGroup = groupDao.getGroupById(targetId) != null
                        val finalKey = if (useGroupStyle && resultData.encryptedKey != null && !resultData.encryptedKey.startsWith("AES:")) {
                            "AES:${resultData.encryptedKey}"
                        } else resultData.encryptedKey

                        val forwardMsg = message.copy(
                            id = 0, remoteId = UUID.randomUUID().toString(),
                            fromUserId = currentUserId, toUserId = if (isTargetGroup) "" else targetId,
                            groupId = if (isTargetGroup) targetId else null, timestamp = System.currentTimeMillis(),
                            isRead = true, fileUrl = resultData.url, fileEncryptionKey = finalKey
                        )
                        val newMessageId = messageDao.insertMessage(forwardMsg)
                        if (isTargetGroup) signalRepository.sendGroupMessage(targetId, forwardMsg.copy(id = newMessageId))
                        else signalRepository.sendFileMessage(targetId, forwardMsg.copy(id = newMessageId))
                    }
                }
            }
            else -> {}
        }
    }

    suspend fun sendMediaFile(uri: Uri, caption: String, targetId: String, isGroup: Boolean, replyingTo: ChatMessage?, memberNames: Map<String, String>, user: User?): Long {
        val currentUserId = authRepository.getCurrentUserId() ?: return -1
        val localKey = keyStorageManager.getMediaEncryptionKey(currentUserId)
        
        val metadata = MainUiUtils.getFileMetadata(context, uri) ?: return -1
        val messageType = MainUiUtils.determineMessageType(metadata.third)
        val dimensions = if (messageType == MessageType.IMAGE) MainUiUtils.getImageDimensions(context, uri) else null
        val rotation = if (messageType == MessageType.IMAGE) MainUiUtils.getImageRotation(context, uri) else 0

        val targetDir = sessionManager.getMediaDir(targetId, "outgoing")
        val secureFile = File(targetDir, "sent_${UUID.randomUUID()}.enc")
        val uploadFile = File(targetDir, "upload_${UUID.randomUUID()}.tmp")

        val transportResult = if (isGroup) signalRepository.prepareGroupTransportEncryption() else signalRepository.prepareTransportEncryption(targetId)
        if (transportResult == null) return -1

        val success = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(secureFile).use { fosStorage ->
                        FileOutputStream(uploadFile).use { fosUpload ->
                            encryptionManager.getDualEncryptingStream(fosStorage, localKey, fosUpload, transportResult)?.use { dualOut ->
                                when (messageType) {
                                    MessageType.IMAGE -> mediaSanitizer.sanitizeImage(input, dualOut, rotation)
                                    MessageType.VIDEO -> mediaSanitizer.sanitizeVideo(input, dualOut)
                                    else -> input.copyTo(dualOut)
                                }
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                secureFile.delete(); uploadFile.delete(); false
            }
        }
        if (!success) return -1

        val replyToName = replyingTo?.let { if (isGroup) memberNames[it.fromUserId] else user?.username } ?: replyingTo?.fromUserId?.takeLast(4)

        val initialMsg = ChatMessage(
            fromUserId = currentUserId, toUserId = if (isGroup) "" else targetId, groupId = if (isGroup) targetId else null,
            text = caption, timestamp = System.currentTimeMillis(), isRead = true, type = messageType,
            fileName = metadata.first, fileSize = metadata.second, mimeType = metadata.third,
            width = dimensions?.first, height = dimensions?.second, localFilePath = secureFile.absolutePath,
            replyToId = replyingTo?.remoteId, replyToName = replyToName, replyToText = replyingTo?.text, replyToType = replyingTo?.type
        )

        val messageId = messageDao.insertMessage(initialMsg)
        
        val uploadResult = fileUploader.uploadReadyFile("Atsusend", uploadFile, transportResult.encryptedKeyBase64)
        uploadFile.delete()

        val resultData = uploadResult.getOrNull()
        if (resultData != null) {
            messageDao.updateMessageUrl(messageId, resultData.url)
            resultData.encryptedKey?.let { messageDao.updateFileEncryptionKey(messageId, it) }
            val finalMsg = initialMsg.copy(id = messageId, fileUrl = resultData.url, fileEncryptionKey = resultData.encryptedKey)
            if (isGroup) signalRepository.sendGroupMessage(targetId, finalMsg) else signalRepository.sendFileMessage(targetId, finalMsg)
        } else {
            messageDao.deleteMessageById(messageId)
            secureFile.delete()
        }
        return messageId
    }

    suspend fun sendCapturedImage(imageData: ByteArray, caption: String, targetId: String, isGroup: Boolean, replyingTo: ChatMessage?, memberNames: Map<String, String>, user: User?): Long {
        val currentUserId = authRepository.getCurrentUserId() ?: return -1
        val localKey = keyStorageManager.getMediaEncryptionKey(currentUserId)
        
        val targetDir = sessionManager.getMediaDir(targetId, "outgoing")
        val secureFile = File(targetDir, "sent_${UUID.randomUUID()}.enc")
        val uploadFile = File(targetDir, "upload_${UUID.randomUUID()}.tmp")

        val transportResult = if (isGroup) signalRepository.prepareGroupTransportEncryption() else signalRepository.prepareTransportEncryption(targetId)
        if (transportResult == null) return -1

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, opts)
        val rotation = MainUiUtils.getImageRotationFromBytes(imageData)

        val success = withContext(Dispatchers.IO) {
            try {
                FileOutputStream(secureFile).use { fosStorage ->
                    FileOutputStream(uploadFile).use { fosUpload ->
                        encryptionManager.getDualEncryptingStream(fosStorage, localKey, fosUpload, transportResult)?.use { dualOut ->
                            mediaSanitizer.sanitizeImage(ByteArrayInputStream(imageData), dualOut, rotation)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                secureFile.delete(); uploadFile.delete(); false
            }
        }
        if (!success) return -1

        val replyToName = replyingTo?.let { if (isGroup) memberNames[it.fromUserId] else user?.username } ?: replyingTo?.fromUserId?.takeLast(4)

        val initialMsg = ChatMessage(
            fromUserId = currentUserId, toUserId = if (isGroup) "" else targetId, groupId = if (isGroup) targetId else null,
            text = caption, timestamp = System.currentTimeMillis(), isRead = true, type = MessageType.IMAGE,
            fileName = "Captured.jpg", fileSize = imageData.size.toLong(), mimeType = "image/jpeg",
            width = if (opts.outWidth > 0) opts.outWidth else null, height = if (opts.outHeight > 0) opts.outHeight else null,
            localFilePath = secureFile.absolutePath, replyToId = replyingTo?.remoteId, replyToName = replyToName,
            replyToText = replyingTo?.text, replyToType = replyingTo?.type
        )

        val messageId = messageDao.insertMessage(initialMsg)
        val uploadResult = fileUploader.uploadReadyFile("Atsusend", uploadFile, transportResult.encryptedKeyBase64)
        uploadFile.delete()

        val resultData = uploadResult.getOrNull()
        if (resultData != null) {
            messageDao.updateMessageUrl(messageId, resultData.url)
            resultData.encryptedKey?.let { messageDao.updateFileEncryptionKey(messageId, it) }
            val finalMsg = initialMsg.copy(id = messageId, fileUrl = resultData.url, fileEncryptionKey = resultData.encryptedKey)
            if (isGroup) signalRepository.sendGroupMessage(targetId, finalMsg) else signalRepository.sendFileMessage(targetId, finalMsg)
        } else {
            messageDao.deleteMessageById(messageId)
            secureFile.delete()
        }
        return messageId
    }

    suspend fun sendVoiceMessage(file: File, duration: Int, targetId: String, isGroup: Boolean, replyingTo: ChatMessage?, memberNames: Map<String, String>, user: User?): Long {
        val currentUserId = authRepository.getCurrentUserId() ?: return -1
        val localKey = keyStorageManager.getMediaEncryptionKey(currentUserId)
        val targetDir = sessionManager.getMediaDir(targetId, "outgoing")
        val uploadFile = File(targetDir, "upload_voice_${UUID.randomUUID()}.tmp")

        val transportResult = if (isGroup) signalRepository.prepareGroupTransportEncryption() else signalRepository.prepareTransportEncryption(targetId)
        if (transportResult == null) return -1

        val reEncSuccess = withContext(Dispatchers.IO) {
            encryptionManager.reEncryptFile(file, localKey, uploadFile, transportResult)
        }
        if (!reEncSuccess) return -1

        val replyToName = replyingTo?.let { if (isGroup) memberNames[it.fromUserId] else user?.username } ?: replyingTo?.fromUserId?.takeLast(4)

        val initialMessage = ChatMessage(
            fromUserId = currentUserId, toUserId = if (isGroup) "" else targetId, groupId = if (isGroup) targetId else null,
            text = "", timestamp = System.currentTimeMillis(), isRead = true, type = MessageType.AUDIO,
            fileName = file.name, localFilePath = file.absolutePath, fileSize = file.length(),
            audioDuration = duration, mimeType = "audio/aac",
            replyToId = replyingTo?.remoteId, replyToName = replyToName, replyToText = replyingTo?.text, replyToType = replyingTo?.type
        )
        val messageId = messageDao.insertMessage(initialMessage)
        val uploadResult = fileUploader.uploadReadyFile("Atsusend", uploadFile, transportResult.encryptedKeyBase64)
        uploadFile.delete()

        val resultData = uploadResult.getOrNull()
        if (resultData != null) {
            messageDao.updateMessageUrl(messageId, resultData.url)
            resultData.encryptedKey?.let { messageDao.updateFileEncryptionKey(messageId, it) }
            val finalMsg = initialMessage.copy(id = messageId, fileUrl = resultData.url, fileEncryptionKey = resultData.encryptedKey)
            if (isGroup) signalRepository.sendGroupMessage(targetId, finalMsg) else signalRepository.sendFileMessage(targetId, finalMsg)
        } else {
            messageDao.deleteMessageById(messageId)
        }
        return messageId
    }
}
