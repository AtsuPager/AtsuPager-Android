/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.data.network.*
import com.nax.atsupager.security.KeyStorageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import javax.inject.Inject

@HiltViewModel
class GameChatViewModel @Inject constructor(
    private val messageDao: MessageDao,
    private val signalRepository: SignalRepository,
    private val chatMediaSender: ChatMediaSender,
    private val userRepository: UserRepository,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val fileDownloader: FileDownloader,
    private val keyStorageManager: KeyStorageManager
) : ViewModel() {

    private val _contactId = MutableStateFlow<String?>(null)
    
    val currentUserId = userRepository.getCurrentUserId() ?: ""

    val messages = _contactId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else messageDao.getMessagesForChat(currentUserId, id).map { list ->
            list.filter { it.type == MessageType.TEXT || it.type == MessageType.AUDIO }
                .takeLast(10)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contact = _contactId.map { id ->
        if (id == null) null else userRepository.getUser(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val unreadCount = _contactId.flatMapLatest { id ->
        if (id == null) flowOf(0)
        else messageDao.getUnreadCountFromUser(currentUserId, id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val downloadingIds = fileDownloader.downloadingMessageIds

    fun initChat(contactId: String) {
        _contactId.value = contactId
    }

    fun markAsRead() {
        val id = _contactId.value ?: return
        viewModelScope.launch {
            messageDao.markMessagesAsRead(currentUserId, id)
        }
    }

    fun sendMessage(text: String) {
        val contactId = _contactId.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            signalRepository.sendMessage(contactId, text)
            messageDao.insertMessage(
                ChatMessage(
                    fromUserId = currentUserId,
                    toUserId = contactId,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    isRead = true,
                    type = MessageType.TEXT
                )
            )
        }
    }

    fun playAudio(message: ChatMessage) {
        if (message.localFilePath != null) {
            audioPlayer.play(message)
        } else {
            fileDownloader.downloadFile(message)
        }
    }

    fun startRecording(contactId: String): Boolean {
        val localKey = keyStorageManager.getMediaEncryptionKey(currentUserId)
        val outputFile = File(audioRecorder.getBaseDir(), "media/$contactId/outgoing/voice_${UUID.randomUUID()}.enc").apply { parentFile?.mkdirs() }
        return audioRecorder.start(outputFile, localKey)
    }

    fun stopRecording(contactId: String, duration: Int) {
        val file = audioRecorder.stop()
        if (file != null && duration > 0) {
            viewModelScope.launch {
                chatMediaSender.sendVoiceMessage(file, duration, contactId, false, null, emptyMap(), null)
            }
        }
    }

    fun cancelRecording() {
        audioRecorder.stop()
    }

    fun getMaxAmplitude() = audioRecorder.getMaxAmplitude()
}
