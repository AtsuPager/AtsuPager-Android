/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.main

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nax.atsupager.data.db.*
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

data class CallNavigationState(val userId: String, val isVideo: Boolean)

data class PendingAttachment(
    val uri: Uri? = null,
    val imageData: ByteArray? = null,
    val type: MessageType,
    val fileName: String,
    val fileSize: Long,
    val caption: String = "",
    val isPrivate: Boolean = false
)

data class MainUiState(
    val user: User? = null,
    val group: GroupEntity? = null,
    val isGroup: Boolean = false,
    val memberNames: Map<String, String> = emptyMap(),
    val groupMembers: List<User> = emptyList(),
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
    val showAccessDialog: Boolean = false,
    val isGroupLeft: Boolean = false,
    val replyingTo: ChatMessage? = null,
    val pendingAttachment: PendingAttachment? = null,
    val isMuted: Boolean = false,
    val isCameraOpen: Boolean = false,
    val isAdmin: Boolean = false,
    val isPrivateMode: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val contactsRepository: ContactsRepository,
    private val contactDao: ContactDao,
    private val userDao: UserDao,
    private val groupDao: GroupDao,
    private val groupRepository: GroupRepository,
    private val signalRepository: SignalRepository,
    private val chatMediaSender: ChatMediaSender,
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

    private val contactId: String? = savedStateHandle.get<String>("userId")
    private val groupId: String? = savedStateHandle.get<String>("groupId")
    val isGroup = groupId != null
    val currentUserId: String = sharedPreferences.getString(AuthRepository.KEY_USER_ID, "")!!

    private val _uiState = MutableStateFlow(MainUiState(currentUserId = currentUserId, isGroup = isGroup))
    val uiState = _uiState.asStateFlow()

    val contacts: Flow<List<User>> = contactsRepository.getContactsFlow()
    val groups: Flow<List<GroupEntity>> = groupDao.getAllGroupsFlow()

    private var recordingStartTime: Long = 0
    private var recordingJob: Job? = null
    private var recordedFile: File? = null
    private val _messageWaitingForDownload = MutableStateFlow<Long?>(null)

    val localKey: ByteArray by lazy { keyStorageManager.getMediaEncryptionKey(currentUserId) }

    init {
        loadInitialData()
        listenForMessages()
        observeExternalStates()
        observeContactStatus()
        observeGroupMembers()
        startVisualCleanupTimer()
        
        val targetId = contactId ?: groupId
        if (targetId != null) signalRepository.clearUserNotifications(targetId)
        
        checkAccessOnStart()
        signalRepository.accessRequiredEvent
            .onEach { _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.EXPIRED) } }
            .launchIn(viewModelScope)
    }

    private fun observeGroupMembers() {
        if (isGroup && groupId != null) {
            groupDao.getGroupMemberIdsFlow(groupId)
                .onEach {
                    refreshMemberNames(groupId)
                    checkAdminStatus(groupId)
                }
                .launchIn(viewModelScope)
        }
    }

    private suspend fun checkAdminStatus(groupId: String) {
        val group = groupDao.getGroupById(groupId)
        val role = groupDao.getUserRole(groupId, currentUserId)
        _uiState.update { it.copy(isAdmin = role == "ADMIN" || group?.ownerId == currentUserId) }
    }

    private fun checkAccessOnStart() {
        val expiry = sharedPreferences.getLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$currentUserId", 0L)
        val isActiveStatus = expiry == -1L || expiry > System.currentTimeMillis()
        
        if (!isActiveStatus) {
            _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.NO_ACCESS) }
        } else {
            _uiState.update { it.copy(accessStatus = AccessStatus.ACTIVE) }
        }
    }

    fun checkAccess(onAuthorized: () -> Unit) {
        val expiry = sharedPreferences.getLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$currentUserId", 0L)
        if (expiry == -1L || expiry > System.currentTimeMillis()) {
            _uiState.update { it.copy(accessStatus = AccessStatus.ACTIVE) }
            onAuthorized()
        } else {
            _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.EXPIRED) }
        }
    }

    fun applyAccessCode(code: String, onResult: (Boolean, String?) -> Unit) {
        val cleanCode = code.replace(Regex("[^A-Z0-9]"), "").uppercase()
        if (cleanCode.length != 16) {
            onResult(false, context.getString(com.nax.atsupager.R.string.invalid_code))
            return
        }
        viewModelScope.launch {
            signalRepository.verifyAccessCode(cleanCode) { success, error, expiry ->
                viewModelScope.launch {
                    if (success && expiry != null) {
                        onAccessActivated(expiry)
                        onResult(true, null)
                    } else onResult(false, error ?: context.getString(com.nax.atsupager.R.string.invalid_code))
                }
            }
        }
    }

    fun closeAccessDialog() = _uiState.update { it.copy(showAccessDialog = false) }

    private fun onAccessActivated(newExpiry: Long) {
        sharedPreferences.edit().putLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$currentUserId", newExpiry).apply()
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
        if (!isGroup && contactId != null) {
            viewModelScope.launch { _uiState.update { it.copy(isContact = userRepository.isContact(contactId)) } }
        }
    }

    private fun observeExternalStates() {
        audioPlayer.playbackState.onEach { state -> _uiState.update { it.copy(playbackState = state) } }.launchIn(viewModelScope)
        callStatusManager.activeCall.onEach { call ->
            _uiState.update { it.copy(activeCallInfo = call) }
            if (call != null && (isGroup || call.userId != contactId)) {
                _uiState.update { it.copy(activeCallUser = userRepository.getUser(call.userId)) }
            } else _uiState.update { it.copy(activeCallUser = null) }
        }.launchIn(viewModelScope)

        callStatusManager.callDuration.onEach { d -> _uiState.update { it.copy(callDuration = d) } }.launchIn(viewModelScope)
        fileDownloader.downloadingMessageIds.onEach { ids -> _uiState.update { it.copy(downloadingMessageIds = ids) } }.launchIn(viewModelScope)
        fileDownloader.downloadProgress.onEach { p -> _uiState.update { it.copy(downloadProgress = p) } }.launchIn(viewModelScope)
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            if (isGroup && groupId != null) {
                groupDao.getGroupById(groupId)?.let { g ->
                    val role = groupDao.getUserRole(groupId, currentUserId)
                    _uiState.update { it.copy(group = g, isMuted = g.isMuted, isLoading = false, isAdmin = role == "ADMIN" || g.ownerId == currentUserId) }
                    refreshMemberNames(groupId)
                } ?: _uiState.update { it.copy(isLoading = false) }
            } else if (contactId != null) {
                _uiState.update { it.copy(isContact = userRepository.isContact(contactId), isLoading = true) }
                userRepository.getUser(contactId)?.let { u -> _uiState.update { it.copy(user = u, isMuted = u.isMuted, isLoading = false) } }
                try {
                    userRepository.forceRefreshContact(contactId)
                    userRepository.getUser(contactId)?.let { u -> _uiState.update { it.copy(user = u, isMuted = u.isMuted) } }
                } catch (_: Exception) { } finally { _uiState.update { it.copy(isLoading = false) } }
            }
        }
    }

    private suspend fun refreshMemberNames(groupId: String, additionalIds: Set<String> = emptySet()) {
        val currentMemberIds = groupRepository.getGroupMemberIds(groupId)
        val allIds = (currentMemberIds + additionalIds + currentUserId).distinct()
        val namesMap = mutableMapOf<String, String>()
        val membersList = mutableListOf<User>()

        for (id in allIds) {
            val user = if (id == currentUserId) {
                User(id, sharedPreferences.getString(SettingsViewModel.PREF_LOGIN_NAME, "") ?: "User_${id.takeLast(4)}", null)
            } else {
                contactsRepository.getContact(id)
                    ?: userRepository.getUser(id)
                    ?: User(id, "User_${id.takeLast(4)}", null)
            }
            namesMap[id] = user.username
            if (currentMemberIds.contains(id)) membersList.add(user)
        }
        _uiState.update { it.copy(memberNames = namesMap, groupMembers = membersList) }
    }

    private fun listenForMessages() {
        val flow = if (isGroup && groupId != null) messageDao.getMessagesForGroup(groupId)
        else if (contactId != null) messageDao.getMessagesForChat(currentUserId, contactId)
        else emptyFlow()

        flow.onEach { messages ->
            _uiState.update { state ->
                val mediaOnly = messages.filter { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
                state.copy(messages = messages, mediaMessages = mediaOnly, isMediaViewerOpen = state.isMediaViewerOpen && mediaOnly.isNotEmpty())
            }
            checkPendingDownloads(messages)
            if (isGroup && groupId != null) {
                messageDao.markGroupMessagesAsRead(groupId)
                signalRepository.clearUserNotifications(groupId)
                val unknown = messages.map { it.fromUserId }.filter { !uiState.value.memberNames.containsKey(it) }
                if (unknown.isNotEmpty()) refreshMemberNames(groupId, unknown.toSet())
            } else if (contactId != null) {
                val unread = messages.filter { !it.isRead && it.fromUserId == contactId }
                if (unread.isNotEmpty()) {
                    messageDao.markMessagesAsRead(currentUserId, contactId)
                    signalRepository.clearUserNotifications(contactId)
                    if (sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) {
                        unread.filter { it.type == MessageType.TEXT }.forEach { signalRepository.sendReceipt(contactId, it.remoteId, SignalType.MSG_READ) }
                    }
                }
            }
        }.launchIn(viewModelScope)
    }

    private fun checkPendingDownloads(messages: List<ChatMessage>) {
        val waitingId = _messageWaitingForDownload.value ?: return
        messages.find { it.id == waitingId && it.localFilePath != null }?.let { m ->
            when (m.type) {
                MessageType.VIDEO -> openMediaViewer(m)
                MessageType.AUDIO -> playAudio(m)
                MessageType.FILE -> if (m.mimeType?.startsWith("audio/") == true) playAudio(m)
                else -> Unit
            }
            _messageWaitingForDownload.value = null
        }
    }

    fun startReply(message: ChatMessage) = _uiState.update { it.copy(replyingTo = message) }
    fun cancelReply() = _uiState.update { it.copy(replyingTo = null) }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        checkAccess {
            viewModelScope.launch {
                val replyTo = _uiState.value.replyingTo
                val replyToName = replyTo?.let { if (isGroup) _uiState.value.memberNames[it.fromUserId] else _uiState.value.user?.username } ?: replyTo?.fromUserId?.takeLast(4)
                val message = ChatMessage(
                    fromUserId = currentUserId,
                    toUserId = contactId ?: "",
                    groupId = groupId,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    isRead = true,
                    type = MessageType.TEXT,
                    replyToId = replyTo?.remoteId,
                    replyToName = replyToName,
                    replyToText = replyTo?.text,
                    replyToType = replyTo?.type,
                    isPrivate = _uiState.value.isPrivateMode
                )
                val id = messageDao.insertMessage(message)
                if (isGroup && groupId != null) signalRepository.sendGroupMessage(groupId, message.copy(id = id))
                else if (contactId != null) signalRepository.sendFileMessage(contactId, message.copy(id = id))
                cancelReply()
                _uiState.update { it.copy(isPrivateMode = false) }
            }
        }
    }

    fun prepareFileAttachment(uri: Uri) {
        viewModelScope.launch {
            val metadata = MainUiUtils.getFileMetadata(context, uri) ?: return@launch
            val type = MainUiUtils.determineMessageType(metadata.third)
            _uiState.update { it.copy(pendingAttachment = PendingAttachment(uri, null, type, metadata.first, metadata.second)) }
        }
    }

    fun openCamera() = _uiState.update { it.copy(isCameraOpen = true) }
    fun closeCamera() = _uiState.update { it.copy(isCameraOpen = false) }

    fun onImageCaptured(data: ByteArray) {
        _uiState.update { it.copy(isCameraOpen = false, pendingAttachment = PendingAttachment(null, data, MessageType.IMAGE, "Camera_${System.currentTimeMillis()}.jpg", data.size.toLong())) }
    }

    fun updatePendingAttachmentCaption(caption: String) = _uiState.update { s -> s.copy(pendingAttachment = s.pendingAttachment?.copy(caption = caption)) }
    fun togglePendingAttachmentPrivate() = _uiState.update { s ->
        val canBePrivate = s.pendingAttachment?.type == MessageType.IMAGE ||
                s.pendingAttachment?.type == MessageType.VIDEO ||
                s.pendingAttachment?.type == MessageType.AUDIO ||
                s.pendingAttachment?.type == MessageType.TEXT
        
        if (canBePrivate) {
            s.copy(pendingAttachment = s.pendingAttachment?.copy(isPrivate = !s.pendingAttachment.isPrivate))
        } else {
            s.copy(error = context.getString(com.nax.atsupager.R.string.private_mode_not_supported))
        }
    }
    fun cancelPendingAttachment() = _uiState.update { it.copy(pendingAttachment = null) }

    fun sendPendingAttachment() {
        val pending = _uiState.value.pendingAttachment ?: return
        val targetId = contactId ?: groupId ?: return
        viewModelScope.launch {
            val msgId = when {
                pending.imageData != null -> chatMediaSender.sendCapturedImage(pending.imageData, pending.caption, targetId, isGroup, _uiState.value.replyingTo, _uiState.value.memberNames, _uiState.value.user, pending.isPrivate)
                pending.uri != null -> chatMediaSender.sendMediaFile(pending.uri, pending.caption, targetId, isGroup, _uiState.value.replyingTo, _uiState.value.memberNames, _uiState.value.user, pending.isPrivate)
                else -> -1L
            }
            if (msgId != -1L) cancelReply()
        }
        _uiState.update { it.copy(pendingAttachment = null) }
    }

    fun startRecording() {
        checkAccess {
            val targetId = contactId ?: groupId ?: return@checkAccess
            val outputFile = File(sessionManager.getMediaDir(targetId, "outgoing"), "voice_${UUID.randomUUID()}.enc")
            if (audioRecorder.start(outputFile, localKey, sharedPreferences.getInt(SettingsViewModel.PREF_AUDIO_BITRATE, 64000))) {
                recordedFile = outputFile; recordingStartTime = System.currentTimeMillis()
                _uiState.update { it.copy(isRecording = true, isAudioReadyToSend = false, amplitudes = emptyList()) }
                startRecordingTimer()
            }
        }
    }

    private fun startRecordingTimer() {
        recordingJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                val amp = (audioRecorder.getMaxAmplitude().toFloat() / 32767f).pow(0.3f)
                _uiState.update { it.copy(recordingTime = String.format("%02d:%02d", elapsed / 60, elapsed % 60), amplitudes = (it.amplitudes + amp).takeLast(100)) }
                delay(100)
            }
        }
    }

    fun stopRecording() { recordingJob?.cancel(); audioRecorder.stop(); _uiState.update { it.copy(isRecording = false, isAudioReadyToSend = true) } }
    fun cancelRecording() { recordingJob?.cancel(); audioRecorder.stop(); recordedFile?.delete(); recordedFile = null; _uiState.update { it.copy(isRecording = false, isAudioReadyToSend = false) } }

    fun sendRecordedAudio() {
        val file = recordedFile ?: return
        val duration = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
        if (duration > 0) {
            viewModelScope.launch {
                val targetId = contactId ?: groupId ?: return@launch
                chatMediaSender.sendVoiceMessage(file, duration, targetId, isGroup, _uiState.value.replyingTo, _uiState.value.memberNames, _uiState.value.user, _uiState.value.isPrivateMode)
                cancelReply()
                _uiState.update { it.copy(isPrivateMode = false) }
            }
        } else file.delete()
        recordedFile = null; _uiState.update { it.copy(isAudioReadyToSend = false) }
    }

    fun downloadFile(message: ChatMessage, toPublicFolder: Boolean) {
        if (message.isPrivate && toPublicFolder) {
            _uiState.update { it.copy(error = context.getString(com.nax.atsupager.R.string.private_export_blocked)) }
            return
        }
        if (toPublicFolder) fileDownloader.downloadToPublicFolder(message) else fileDownloader.downloadFile(message)
        if (!isGroup && contactId != null && sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) signalRepository.sendReceipt(contactId, message.remoteId, SignalType.FILE_DOWNLOADED)
    }

    fun playAudio(message: ChatMessage) {
        if (message.localFilePath != null) {
            audioPlayer.play(message)
            if (!isGroup && contactId != null && message.fromUserId == contactId && sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) signalRepository.sendReceipt(contactId, message.remoteId, SignalType.FILE_DOWNLOADED)
        } else { _messageWaitingForDownload.value = message.id; fileDownloader.downloadFile(message) }
    }

    fun seekAudio(progress: Float) = audioPlayer.seekTo(progress)
    fun onPlayVideo(message: ChatMessage) { if (message.localFilePath != null) openMediaViewer(message) else { _messageWaitingForDownload.value = message.id; fileDownloader.downloadFile(message) } }
    fun onPlayAudio(message: ChatMessage) = playAudio(message)
    fun onViewImage(message: ChatMessage) { if (message.localFilePath != null) openMediaViewer(message) else fileDownloader.downloadFile(message) }

    fun openMediaViewer(message: ChatMessage) {
        val mediaOnly = _uiState.value.messages.filter { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
        _uiState.update { it.copy(mediaMessages = mediaOnly, initialMediaIndex = mediaOnly.indexOfFirst { m -> m.id == message.id }.coerceAtLeast(0), isMediaViewerOpen = true) }
        if (!isGroup && contactId != null && message.fromUserId == contactId && sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) signalRepository.sendReceipt(contactId, message.remoteId, SignalType.FILE_DOWNLOADED)
    }

    fun closeMediaViewer() = _uiState.update { it.copy(isMediaViewerOpen = false) }
    fun addToContacts() { contactId?.let { viewModelScope.launch { userRepository.addContact(it); _uiState.update { s -> s.copy(isContact = true) } } } }

    fun toggleMessageSelection(messageId: Long) = _uiState.update { s -> val new = s.selectedMessageIds.toMutableSet(); if (new.contains(messageId)) new.remove(messageId) else new.add(messageId); s.copy(selectedMessageIds = new) }
    fun selectAllMessages() = _uiState.update { it.copy(selectedMessageIds = it.messages.map { m -> m.id }.toSet()) }
    fun clearMessageSelection() = _uiState.update { it.copy(selectedMessageIds = emptySet()) }

    fun deleteSelectedMessages(deleteFromDevice: Boolean, deleteForEveryone: Boolean = false) {
        val msgs = _uiState.value.messages.filter { it.id in _uiState.value.selectedMessageIds }
        viewModelScope.launch { signalRepository.deleteSelectedMessages(msgs, deleteFromDevice, deleteForEveryone); withContext(Dispatchers.Main) { clearMessageSelection() } }
    }

    fun deleteMessage(message: ChatMessage, deleteFromDevice: Boolean, deleteForEveryone: Boolean = false) = viewModelScope.launch { signalRepository.deleteMessage(message, deleteFromDevice, deleteForEveryone) }

    fun clearChat(deleteFiles: Boolean, deleteForEveryone: Boolean = false) {
        val targetId = contactId ?: groupId ?: return
        viewModelScope.launch {
            signalRepository.clearChat(targetId, isGroup, deleteFiles, deleteForEveryone)
            if (!isGroup) sessionManager.getMediaDir(targetId, "").deleteRecursively()
        }
    }

    fun exportAndKeepMessage(message: ChatMessage) {
        if (message.isPrivate) {
            _uiState.update { it.copy(error = context.getString(com.nax.atsupager.R.string.private_export_blocked)) }
            return
        }
        viewModelScope.launch { fileDownloader.exportEncryptedFile(message)?.let { messageDao.updateLocalFilePath(message.id, it.toString()) } }
    }

    fun toggleSaveMessages(messages: List<ChatMessage>) {
        val targetStatus = !messages.all { it.isSaved }
        viewModelScope.launch {
            messages.forEach { msg ->
                if (msg.isSaved != targetStatus) {
                    messageDao.updateSavedStatus(msg.id, targetStatus)
                }
            }
        }
    }

    fun forwardSelectedMessages(targetIds: List<String>) {
        val msgs = _uiState.value.messages.filter { it.id in _uiState.value.selectedMessageIds }
        val firstMsg = msgs.firstOrNull() ?: return
        if (firstMsg.isPrivate) {
            _uiState.update { it.copy(error = context.getString(com.nax.atsupager.R.string.private_forward_blocked)) }
            clearMessageSelection()
            return
        }
        clearMessageSelection()
        viewModelScope.launch(Dispatchers.IO) { chatMediaSender.forwardMessage(firstMsg, targetIds) }
    }

    fun addGroupMembers(memberIds: List<String>) = groupId?.let { id -> viewModelScope.launch(Dispatchers.IO) { groupRepository.addMembersToGroup(id, memberIds); refreshMemberNames(id) } }
    fun kickMember(userId: String) = groupId?.let { id -> viewModelScope.launch(Dispatchers.IO) { groupRepository.kickMember(id, userId); refreshMemberNames(id) } }
    fun leaveGroup(deleteFiles: Boolean, deleteForEveryone: Boolean) = groupId?.let { id -> viewModelScope.launch(Dispatchers.IO) { groupRepository.leaveGroup(id, deleteFiles, deleteForEveryone); withContext(Dispatchers.Main) { _uiState.update { it.copy(isGroupLeft = true) } } } }
    fun deleteGroup() = groupId?.let { id -> viewModelScope.launch(Dispatchers.IO) { groupRepository.deleteGroup(id); withContext(Dispatchers.Main) { _uiState.update { it.copy(isGroupLeft = true) } } } }

    fun toggleMute() {
        val muted = !_uiState.value.isMuted
        viewModelScope.launch(Dispatchers.IO) {
            if (isGroup && groupId != null) groupDao.setMuted(groupId, muted) else contactId?.let { userDao.setMuted(it, muted) }
            withContext(Dispatchers.Main) { _uiState.update { it.copy(isMuted = muted) } }
        }
    }

    fun togglePrivateMode() {
        _uiState.update { it.copy(isPrivateMode = !it.isPrivateMode) }
    }

    fun initiateCall(isVideo: Boolean) = contactId?.let { id ->
        checkAccess {
            viewModelScope.launch {
                messageDao.insertMessage(
                    ChatMessage(
                        fromUserId = currentUserId,
                        toUserId = id,
                        text = "CALL_OUTGOING",
                        timestamp = System.currentTimeMillis(),
                        isRead = true,
                        type = MessageType.OUTGOING_CALL
                    )
                )
                callStatusManager.startCall(id, true, isVideo, null)
            }
        }
    }

    fun setSelectingText(messageId: Long?) = _uiState.update { it.copy(selectingTextMsgId = messageId) }
    fun onErrorDismissed() = _uiState.update { it.copy(error = null) }
    fun onCallNavigated() = _uiState.update { it.copy(navigateToCall = null) }

    override fun onCleared() { super.onCleared(); try { localKey.fill(0) } catch (_: Exception) {} }
}
