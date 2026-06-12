package com.nax.atsupager.ui.screens.main

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.data.db.ContactDao
import com.nax.atsupager.data.model.User
import com.nax.atsupager.data.network.*
import com.nax.atsupager.data.manager.SessionManager
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.security.MessageLifecycleManager
import com.nax.atsupager.ui.screens.contacts.ContactsRepository
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import com.nax.atsupager.ui.screens.settings.AccessStatus
import com.nax.atsupager.webrtc.ActiveCallInfo
import com.nax.atsupager.webrtc.CallStatusManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.math.pow
import java.util.Calendar

data class CallNavigationState(val userId: String, val isVideo: Boolean)

data class MainUiState(
    val user: User? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentUserId: String,
    val navigateToCall: CallNavigationState? = null,
    val playbackState: PlaybackState = PlaybackState(),
    val isRecording: Boolean = false,
    val isAudioReadyToSend: Boolean = false,
    val recordingTime: String = "00:00",
    val amplitudes: List<Float> = emptyList(),
    val downloadingMessageIds: Set<Long> = emptySet(),
    val uploadingMessageIds: Set<Long> = emptySet(),
    val downloadProgress: Map<Long, DownloadProgress> = emptyMap(),
    val activeCallInfo: ActiveCallInfo? = null,
    val activeCallUser: User? = null,
    val callDuration: Long = 0,
    val mediaMessages: List<ChatMessage> = emptyList(),
    val initialMediaIndex: Int = 0,
    val isMediaViewerOpen: Boolean = false,
    val audioToPlay: ChatMessage? = null,
    val decryptedPaths: Map<Long, String> = emptyMap(),
    val isContact: Boolean = true,
    val selectedMessageIds: Set<Long> = emptySet(),
    val selectingTextMsgId: Long? = null,
    val accessStatus: AccessStatus = AccessStatus.ACTIVE,
    val showAccessDialog: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val contactsRepository: ContactsRepository,
    private val contactDao: ContactDao,
    private val signalRepository: SignalRepository,
    private val messageDao: MessageDao,
    private val fileDownloader: FileDownloader,
    private val audioRecorder: AudioRecorder,
    val audioPlayer: AudioPlayer,
    private val sharedPreferences: android.content.SharedPreferences,
    private val callStatusManager: CallStatusManager,
    val encryptionManager: EncryptionManager,
    val keyStorageManager: KeyStorageManager,
    private val messageLifecycleManager: MessageLifecycleManager,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val contactId: String = savedStateHandle.get<String>("userId")!!
    val currentUserId: String = sharedPreferences.getString(AuthRepository.KEY_USER_ID, "")!!

    private val _uiState = MutableStateFlow(MainUiState(currentUserId = currentUserId))
    val uiState = _uiState.asStateFlow()

    val contacts: Flow<List<User>> = contactsRepository.getContactsFlow()
    
    private var recordingStartTime: Long = 0
    private var recordingJob: Job? = null
    private var recordedFile: File? = null
    private val _messageWaitingForDownload = MutableStateFlow<Long?>(null)

    val localKey: ByteArray by lazy {
        keyStorageManager.getMediaEncryptionKey(currentUserId)
    }

    init {
        loadInitialData()
        listenForMessages()
        observeExternalStates()
        observeContactStatus()
        startVisualCleanupTimer()
        signalRepository.clearUserNotifications(contactId)
        
        // Initial access check for auto-popup on relaunch
        checkAccessOnStart()
        
        // Listen for real-time access expiration
        signalRepository.accessRequiredEvent
            .onEach { _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.EXPIRED) } }
            .launchIn(viewModelScope)
    }

    private fun checkAccessOnStart() {
        val storageUserId = currentUserId
        val expiry = sharedPreferences.getLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$storageUserId", 0L)
        val isActiveStatus = when {
            expiry == -1L -> true
            expiry > System.currentTimeMillis() -> true
            else -> false
        }
        
        if (!isActiveStatus) {
            _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.NO_ACCESS) }
        } else {
            _uiState.update { it.copy(accessStatus = AccessStatus.ACTIVE) }
        }
    }

    fun checkAccess(onAuthorized: () -> Unit) {
        val storageUserId = currentUserId
        val expiry = sharedPreferences.getLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$storageUserId", 0L)
        val isActiveStatus = when {
            expiry == -1L -> true
            expiry > System.currentTimeMillis() -> true
            else -> false
        }
        
        if (isActiveStatus) {
            _uiState.update { it.copy(accessStatus = AccessStatus.ACTIVE) }
            onAuthorized()
        } else {
            _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.EXPIRED) }
        }
    }

    fun applyAccessCode(code: String, onResult: (Boolean, String?) -> Unit) {
        val cleanCode = code.replace(Regex("[^A-Z0-9]"), "").uppercase()
        if (cleanCode.length != 16) {
            onResult(false, context.getString(R.string.invalid_code))
            return
        }

        viewModelScope.launch {
            signalRepository.verifyAccessCode(cleanCode) { success, error, expiry ->
                viewModelScope.launch {
                    if (success && expiry != null) {
                        onAccessActivated(expiry)
                        onResult(true, null)
                    } else {
                        onResult(false, error ?: context.getString(R.string.invalid_code))
                    }
                }
            }
        }
    }

    fun closeAccessDialog() {
        _uiState.update { it.copy(showAccessDialog = false) }
    }

    private fun onAccessActivated(newExpiry: Long) {
        val storageUserId = currentUserId
        sharedPreferences.edit().putLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$storageUserId", newExpiry).apply()
        _uiState.update { it.copy(showAccessDialog = false, accessStatus = AccessStatus.ACTIVE) }
    }

    private fun startVisualCleanupTimer() {
        viewModelScope.launch {
            while (isActive) {
                messageLifecycleManager.deleteExpiredOnly()
                delay(30000)
            }
        }
    }

    private fun observeContactStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(isContact = userRepository.isContact(contactId)) }
        }
    }

    private fun observeExternalStates() {
        viewModelScope.launch {
            audioPlayer.playbackState.collect { state ->
                _uiState.update { it.copy(playbackState = state) }
            }
        }
        
        viewModelScope.launch {
            callStatusManager.activeCall.collect { call ->
                _uiState.update { it.copy(activeCallInfo = call) }
                if (call != null && call.userId != contactId) {
                    val callUser = userRepository.getUser(call.userId)
                    _uiState.update { it.copy(activeCallUser = callUser) }
                } else {
                    _uiState.update { it.copy(activeCallUser = null) }
                }
            }
        }

        viewModelScope.launch {
            callStatusManager.callDuration.collect { duration ->
                _uiState.update { it.copy(callDuration = duration) }
            }
        }

        viewModelScope.launch {
            fileDownloader.downloadingMessageIds.collect { ids ->
                _uiState.update { it.copy(downloadingMessageIds = ids) }
            }
        }

        viewModelScope.launch {
            fileDownloader.downloadProgress.collect { progress ->
                _uiState.update { it.copy(downloadProgress = progress) }
            }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val isContact = userRepository.isContact(contactId)
            _uiState.update { it.copy(isContact = isContact, isLoading = true) }

            userRepository.getUser(contactId)?.let { cached ->
                _uiState.update { it.copy(user = cached, isLoading = false) }
            }
            
            try {
                userRepository.forceRefreshContact(contactId)
                userRepository.getUser(contactId)?.let { updated ->
                    _uiState.update { it.copy(user = updated, isLoading = false) }
                }
            } catch (_: Exception) {
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun listenForMessages() {
        messageDao.getMessagesForChat(currentUserId, contactId)
            .onEach { messages ->
                _uiState.update { state ->
                    val mediaOnly = messages.filter { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
                    state.copy(
                        messages = messages,
                        mediaMessages = mediaOnly,
                        isMediaViewerOpen = state.isMediaViewerOpen && mediaOnly.isNotEmpty()
                    )
                }
                checkPendingDownloads(messages)
                
                val unreadFromContact = messages.filter { !it.isRead && it.fromUserId == contactId }
                if (unreadFromContact.isNotEmpty()) {
                    messageDao.markMessagesAsRead(currentUserId, contactId)
                    signalRepository.clearUserNotifications(contactId)
                    
                    if (sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) {
                        unreadFromContact.forEach { msg ->
                            if (msg.type == MessageType.TEXT) {
                                signalRepository.sendReceipt(contactId, msg.remoteId, SignalType.MSG_READ)
                            }
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun checkPendingDownloads(messages: List<ChatMessage>) {
        val waitingId = _messageWaitingForDownload.value ?: return
        messages.find { it.id == waitingId && it.localFilePath != null }?.let { message ->
            when (message.type) {
                MessageType.VIDEO -> openMediaViewer(message)
                MessageType.AUDIO -> playAudio(message)
                MessageType.FILE -> if (message.mimeType?.startsWith("audio/") == true) playAudio(message)
                else -> Unit
            }
            _messageWaitingForDownload.value = null
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        checkAccess {
            viewModelScope.launch {
                val message = ChatMessage(
                    fromUserId = currentUserId, 
                    toUserId = contactId, 
                    text = text, 
                    timestamp = System.currentTimeMillis(), 
                    isRead = true, 
                    type = MessageType.TEXT
                )
                val id = messageDao.insertMessage(message)
                signalRepository.sendFileMessage(contactId, message.copy(id = id))
            }
        }
    }

    fun sendFile(uri: Uri) {
        checkAccess {
            viewModelScope.launch {
                val metadata = getFileMetadata(uri) ?: return@launch
                val messageType = determineMessageType(metadata.third)
                val dimensions = if (messageType == MessageType.IMAGE) getImageDimensions(uri) else null

                val targetDir = sessionManager.getMediaDir(contactId, "outgoing")
                val secureFile = File(targetDir, "sent_${UUID.randomUUID()}.enc")
                
                val encryptionSuccess = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            secureFile.outputStream().use { fos ->
                                encryptionManager.getEncryptingStreamForStorage(fos, localKey)?.use { cipherOut ->
                                    input.copyTo(cipherOut)
                                }
                            }
                        }
                        true
                    } catch (e: Exception) {
                        secureFile.delete()
                        false
                    }
                }

                if (!encryptionSuccess) {
                    _uiState.update { it.copy(error = "Encryption failed") }
                    return@launch
                }

                val initialMsg = ChatMessage(
                    fromUserId = currentUserId, toUserId = contactId, text = "",
                    timestamp = System.currentTimeMillis(), isRead = true, type = messageType,
                    fileName = metadata.first, fileSize = metadata.second, mimeType = metadata.third,
                    width = dimensions?.first, height = dimensions?.second, localFilePath = secureFile.absolutePath
                )
                
                val messageId = messageDao.insertMessage(initialMsg)
                _uiState.update { it.copy(uploadingMessageIds = it.uploadingMessageIds + messageId) }

                signalRepository.uploadFile("Atsusend", secureFile, contactId, localKey).fold(
                    onSuccess = { result ->
                        messageDao.updateMessageUrl(messageId, result.url)
                        result.encryptedKey?.let { messageDao.updateFileEncryptionKey(messageId, it) }
                        signalRepository.sendFileMessage(contactId, initialMsg.copy(id = messageId, fileUrl = result.url, fileEncryptionKey = result.encryptedKey))
                    },
                    onFailure = { 
                        _uiState.update { s -> s.copy(error = "Upload failed") }
                        messageDao.deleteMessageById(messageId)
                        secureFile.delete()
                    }
                )
                _uiState.update { it.copy(uploadingMessageIds = it.uploadingMessageIds - messageId) }
            }
        }
    }

    fun startRecording() {
        checkAccess {
            val bitrate = sharedPreferences.getInt(SettingsViewModel.PREF_AUDIO_BITRATE, 64000)
            val targetDir = sessionManager.getMediaDir(contactId, "outgoing")
            val outputFile = File(targetDir, "voice_${UUID.randomUUID()}.enc")
            
            if (audioRecorder.start(outputFile, localKey, bitrate)) {
                recordedFile = outputFile
                recordingStartTime = System.currentTimeMillis()
                _uiState.update { it.copy(isRecording = true, isAudioReadyToSend = false, amplitudes = emptyList()) }
                startRecordingTimer()
            }
        }
    }

    private fun startRecordingTimer() {
        recordingJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                val timeStr = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                val amp = (audioRecorder.getMaxAmplitude().toFloat() / 32767f).pow(0.3f)
                _uiState.update { it.copy(recordingTime = timeStr, amplitudes = (it.amplitudes + amp).takeLast(100)) }
                delay(100)
            }
        }
    }

    fun stopRecording() { 
        recordingJob?.cancel()
        audioRecorder.stop()
        _uiState.update { it.copy(isRecording = false, isAudioReadyToSend = true) } 
    }

    fun cancelRecording() {
        recordingJob?.cancel()
        audioRecorder.stop()
        recordedFile?.delete()
        recordedFile = null
        _uiState.update { it.copy(isRecording = false, isAudioReadyToSend = false) }
    }

    fun sendRecordedAudio() {
        val file = recordedFile ?: return
        val duration = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
        if (duration > 0) {
            viewModelScope.launch { uploadAndSendAudio(file, duration) }
        } else file.delete()
        recordedFile = null
        _uiState.update { it.copy(isAudioReadyToSend = false) }
    }

    private suspend fun uploadAndSendAudio(file: File, duration: Int) {
        val initialMessage = ChatMessage(
            fromUserId = currentUserId, toUserId = contactId, text = "",
            timestamp = System.currentTimeMillis(), isRead = true, type = MessageType.AUDIO,
            fileName = file.name, localFilePath = file.absolutePath, fileSize = file.length(),
            audioDuration = duration, mimeType = "audio/aac"
        )
        val messageId = messageDao.insertMessage(initialMessage)
        
        _uiState.update { it.copy(uploadingMessageIds = it.uploadingMessageIds + messageId) }
        
        signalRepository.uploadFile("Atsusend", file, contactId, localKey).fold(
            onSuccess = { result ->
                messageDao.updateMessageUrl(messageId, result.url)
                result.encryptedKey?.let { messageDao.updateFileEncryptionKey(messageId, it) }
                val finalMsg = initialMessage.copy(id = messageId, fileUrl = result.url, fileEncryptionKey = result.encryptedKey)
                signalRepository.sendFileMessage(contactId, finalMsg)
            },
            onFailure = { 
                _uiState.update { s -> s.copy(error = "Upload failed") }
                messageDao.deleteMessageById(messageId)
                file.delete()
            }
        )
        _uiState.update { it.copy(uploadingMessageIds = it.uploadingMessageIds - messageId) }
    }

    fun downloadFile(message: ChatMessage, toPublicFolder: Boolean) {
        if (toPublicFolder) fileDownloader.downloadToPublicFolder(message)
        else fileDownloader.downloadFile(message)
        
        if (sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) {
            signalRepository.sendReceipt(contactId, message.remoteId, SignalType.FILE_DOWNLOADED)
        }
    }

    fun playAudio(message: ChatMessage) {
        if (message.localFilePath != null) {
            audioPlayer.play(message)
            if (message.fromUserId == contactId && sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) {
                signalRepository.sendReceipt(contactId, message.remoteId, SignalType.FILE_DOWNLOADED)
            }
        } else { 
            _messageWaitingForDownload.value = message.id
            fileDownloader.downloadFile(message) 
        }
    }

    fun seekAudio(progress: Float) = audioPlayer.seekTo(progress)

    fun onPlayVideo(message: ChatMessage) {
        if (message.localFilePath != null) openMediaViewer(message)
        else { _messageWaitingForDownload.value = message.id; fileDownloader.downloadFile(message) }
    }

    fun onPlayAudio(message: ChatMessage) = playAudio(message)

    fun onViewImage(message: ChatMessage) {
        if (message.localFilePath != null) openMediaViewer(message)
        else fileDownloader.downloadFile(message)
    }

    fun openMediaViewer(message: ChatMessage) {
        val mediaOnly = _uiState.value.messages.filter { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
        val index = mediaOnly.indexOfFirst { it.id == message.id }
        _uiState.update { it.copy(mediaMessages = mediaOnly, initialMediaIndex = index.coerceAtLeast(0), isMediaViewerOpen = true) }
        
        if (message.fromUserId == contactId && sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) {
            signalRepository.sendReceipt(contactId, message.remoteId, SignalType.FILE_DOWNLOADED)
        }
    }

    fun closeMediaViewer() = _uiState.update { it.copy(isMediaViewerOpen = false) }

    fun addToContacts() {
        viewModelScope.launch {
            userRepository.addContact(contactId)
            _uiState.update { it.copy(isContact = true) }
        }
    }

    fun toggleMessageSelection(messageId: Long) {
        _uiState.update { state ->
            val newSelection = state.selectedMessageIds.toMutableSet()
            if (newSelection.contains(messageId)) {
                newSelection.remove(messageId)
            } else {
                newSelection.add(messageId)
            }
            state.copy(selectedMessageIds = newSelection)
        }
    }

    fun selectAllMessages() {
        _uiState.update { state ->
            state.copy(selectedMessageIds = state.messages.map { it.id }.toSet())
        }
    }

    fun clearMessageSelection() {
        _uiState.update { it.copy(selectedMessageIds = emptySet()) }
    }

    fun deleteSelectedMessages(deleteFromDevice: Boolean, deleteForEveryone: Boolean = false) {
        val selectedIds = _uiState.value.selectedMessageIds
        if (selectedIds.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val messagesToDelete = _uiState.value.messages.filter { it.id in selectedIds }
            messagesToDelete.forEach { message ->
                if (deleteForEveryone && message.fromUserId == currentUserId) {
                    signalRepository.sendRemoteDelete(contactId, message.remoteId)
                    message.fileUrl?.let { url ->
                        signalRepository.deleteFileFromServer(url)
                    }
                }

                message.localFilePath?.let { path ->
                    // ПРОВЕРКА: Сколько сообщений используют этот файл
                    val usageCount = messageDao.getMessageCountByFilePath(path)
                    val isInternal = path.contains(context.filesDir.absolutePath)
                    val isPublic = path.startsWith("content://") || path.contains("AtsuPager")
                    
                    if (usageCount <= 1) { // Удаляем файл физически только если это последняя ссылка
                        if (isInternal || (deleteFromDevice && isPublic)) {
                            deleteLocalFile(path)
                        }
                    }
                }
                messageDao.deleteMessageById(message.id)
            }
            withContext(Dispatchers.Main) {
                clearMessageSelection()
            }
        }
    }

    fun deleteMessage(message: ChatMessage, deleteFromDevice: Boolean, deleteForEveryone: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (deleteForEveryone && message.fromUserId == currentUserId) {
                signalRepository.sendRemoteDelete(contactId, message.remoteId)
                message.fileUrl?.let { url ->
                    signalRepository.deleteFileFromServer(url)
                }
            }
            message.localFilePath?.let { path ->
                // ПРОВЕРКА: Сколько сообщений используют этот файл
                val usageCount = messageDao.getMessageCountByFilePath(path)
                val isInternal = path.contains(context.filesDir.absolutePath)
                val isPublic = path.startsWith("content://") || path.contains("AtsuPager")
                
                if (usageCount <= 1) { // Удаляем файл физически только если это последняя ссылка
                    if (isInternal || (deleteFromDevice && isPublic)) {
                        deleteLocalFile(path)
                    }
                }
            }
            messageDao.deleteMessageById(message.id)
        }
    }

    fun clearChat(deleteFiles: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val allMessages = messageDao.getMessagesForChatSync(currentUserId, contactId)
            
            if (deleteFiles) {
                allMessages.forEach { msg ->
                    msg.localFilePath?.let { path ->
                        val usageCount = messageDao.getMessageCountByFilePath(path)
                        if (usageCount <= 1) {
                            if (path.startsWith("content://") || path.contains("AtsuPager")) {
                                deleteLocalFile(path)
                            }
                        }
                    }
                }
            }
            sessionManager.getMediaDir(contactId, "").deleteRecursively()
            messageDao.deleteAllMessagesForChat(currentUserId, contactId)
        }
    }

    private fun deleteLocalFile(path: String) {
        try {
            if (path.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(path), null, null)
            } else {
                val file = File(path)
                if (file.exists()) file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun exportAndKeepMessage(message: ChatMessage) {
        viewModelScope.launch {
            fileDownloader.exportEncryptedFile(message)?.let { newUri ->
                messageDao.updateLocalFilePath(message.id, newUri.toString())
                _uiState.update { it.copy(error = "Файл успешно экспортирован") }
            }
        }
    }

    fun forwardSelectedMessages(targetUserIds: List<String>) {
        val selectedIds = _uiState.value.selectedMessageIds
        if (selectedIds.isEmpty()) return

        val messagesToForward = _uiState.value.messages.filter { it.id in selectedIds }
        
        viewModelScope.launch(Dispatchers.IO) {
            targetUserIds.forEach { targetUserId ->
                messagesToForward.forEach { msg ->
                    when (msg.type) {
                        MessageType.TEXT -> {
                            val forwardMsg = msg.copy(
                                id = 0, 
                                remoteId = UUID.randomUUID().toString(), // Явно генерируем новый ID
                                fromUserId = currentUserId, 
                                toUserId = targetUserId,
                                timestamp = System.currentTimeMillis(), 
                                isRead = true
                            )
                            val newId = messageDao.insertMessage(forwardMsg)
                            signalRepository.sendFileMessage(targetUserId, forwardMsg.copy(id = newId))
                        }
                        MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.FILE -> {
                            forwardMediaSecurely(msg, targetUserId)
                        }
                        else -> {}
                    }
                }
            }
            withContext(Dispatchers.Main) {
                clearMessageSelection()
            }
        }
    }

    private suspend fun forwardMediaSecurely(message: ChatMessage, targetUserId: String) {
        val sourcePath = message.localFilePath ?: return
        
        // 1. Обработка экспортированных файлов (content://)
        if (sourcePath.startsWith("content://")) {
            forwardExportedFile(message, sourcePath, targetUserId)
            return
        }

        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return

        try {
            val forwardMsg = message.copy(
                id = 0, 
                remoteId = UUID.randomUUID().toString(), // Явно генерируем новый ID
                fromUserId = currentUserId, 
                toUserId = targetUserId,
                timestamp = System.currentTimeMillis(), 
                isRead = true,
                localFilePath = sourcePath, 
                fileUrl = null, 
                fileEncryptionKey = null
            )
            val newMessageId = messageDao.insertMessage(forwardMsg)

            signalRepository.uploadFile("Atsusend", sourceFile, targetUserId, localKey).fold(
                onSuccess = { result ->
                    messageDao.updateMessageUrl(newMessageId, result.url)
                    result.encryptedKey?.let { messageDao.updateFileEncryptionKey(newMessageId, it) }
                    signalRepository.sendFileMessage(targetUserId, forwardMsg.copy(id = newMessageId, fileUrl = result.url, fileEncryptionKey = result.encryptedKey))
                },
                onFailure = { messageDao.deleteMessageById(newMessageId) }
            )
        } catch (_: Exception) { }
    }

    private suspend fun forwardExportedFile(message: ChatMessage, uriString: String, targetUserId: String) {
        val uri = Uri.parse(uriString)
        val targetDir = sessionManager.getMediaDir(targetUserId, "outgoing")
        val secureFile = File(targetDir, "fwd_${UUID.randomUUID()}.enc")
        
        val encryptionSuccess = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    secureFile.outputStream().use { fos ->
                        encryptionManager.getEncryptingStreamForStorage(fos, localKey)?.use { cipherOut ->
                            input.copyTo(cipherOut)
                        }
                    }
                }
                true
            } catch (_: Exception) {
                secureFile.delete()
                false
            }
        }

        if (encryptionSuccess) {
            val forwardMsg = message.copy(
                id = 0, 
                remoteId = UUID.randomUUID().toString(), // Явно генерируем новый ID
                fromUserId = currentUserId, 
                toUserId = targetUserId,
                timestamp = System.currentTimeMillis(), 
                isRead = true,
                localFilePath = secureFile.absolutePath, 
                fileUrl = null, 
                fileEncryptionKey = null
            )
            val newMessageId = messageDao.insertMessage(forwardMsg)
            signalRepository.uploadFile("Atsusend", secureFile, targetUserId, localKey).fold(
                onSuccess = { result ->
                    messageDao.updateMessageUrl(newMessageId, result.url)
                    result.encryptedKey?.let { messageDao.updateFileEncryptionKey(newMessageId, it) }
                    signalRepository.sendFileMessage(targetUserId, forwardMsg.copy(id = newMessageId, fileUrl = result.url, fileEncryptionKey = result.encryptedKey))
                },
                onFailure = { 
                    messageDao.deleteMessageById(newMessageId)
                    secureFile.delete()
                }
            )
        }
    }

    private fun determineMessageType(mime: String?) = when {
        mime?.startsWith("image/") == true -> MessageType.IMAGE
        mime?.startsWith("video/") == true -> MessageType.VIDEO
        else -> MessageType.FILE
    }

    private fun getFileMetadata(uri: Uri): Triple<String, Long, String?>? = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) Triple(cursor.getString(nameIdx), cursor.getLong(sizeIdx), context.contentResolver.getType(uri)) else null
    }

    private fun getImageDimensions(uri: Uri): Pair<Int, Int>? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, opts)
            if (opts.outWidth > 0) opts.outWidth to opts.outHeight else null
        }
    } catch (_: Exception) { null }

    fun initiateCall(isVideo: Boolean) {
        checkAccess {
            viewModelScope.launch {
                viewModelScope.launch(Dispatchers.IO) {
                    messageDao.insertMessage(
                        ChatMessage(
                            fromUserId = currentUserId, toUserId = contactId, text = "CALL_OUTGOING",
                            timestamp = System.currentTimeMillis(), isRead = true, type = MessageType.OUTGOING_CALL
                        )
                    )
                }
                callStatusManager.startCall(contactId, true, isVideo, null)
            }
        }
    }

    fun setSelectingText(messageId: Long?) = _uiState.update { it.copy(selectingTextMsgId = messageId) }
    fun onErrorDismissed() = _uiState.update { it.copy(error = null) }
    fun onCallNavigated() = _uiState.update { it.copy(navigateToCall = null) }

    override fun onCleared() {
        super.onCleared()
        try { localKey.fill(0) } catch (_: Exception) {}
    }
}
