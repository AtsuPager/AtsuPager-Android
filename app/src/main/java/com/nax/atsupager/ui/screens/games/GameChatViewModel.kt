package com.nax.atsupager.ui.screens.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.data.network.AudioPlayer
import com.nax.atsupager.data.network.AudioRecorder
import com.nax.atsupager.data.network.FileDownloader
import com.nax.atsupager.data.network.SignalRepository
import com.nax.atsupager.data.network.UserRepository
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
            // В мини-чате во время игры показываем только текст и аудио, 
            // чтобы не загромождать интерфейс сервисными сообщениями о звонках.
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
                sendAudioMessage(contactId, file, duration)
            }
        }
    }

    fun cancelRecording() {
        audioRecorder.stop()
    }

    fun getMaxAmplitude() = audioRecorder.getMaxAmplitude()

    private suspend fun sendAudioMessage(contactId: String, file: File, duration: Int) {
        val initialMsg = ChatMessage(
            fromUserId = currentUserId,
            toUserId = contactId,
            text = "",
            timestamp = System.currentTimeMillis(),
            isRead = true,
            type = MessageType.AUDIO,
            fileName = file.name,
            localFilePath = file.absolutePath,
            fileSize = file.length(),
            audioDuration = duration,
            mimeType = "audio/aac"
        )
        val messageId = messageDao.insertMessage(initialMsg)
        val localKey = keyStorageManager.getMediaEncryptionKey(currentUserId)
        signalRepository.uploadFile("Atsusend", file, contactId, localKey).fold(
            onSuccess = { result ->
                messageDao.updateMessageUrl(messageId, result.url)
                result.encryptedKey?.let { messageDao.updateFileEncryptionKey(messageId, it) }
                signalRepository.sendFileMessage(contactId, initialMsg.copy(id = messageId, fileUrl = result.url, fileEncryptionKey = result.encryptedKey))
            },
            onFailure = { 
                messageDao.deleteMessageById(messageId)
                file.delete() 
            }
        )
    }
}
