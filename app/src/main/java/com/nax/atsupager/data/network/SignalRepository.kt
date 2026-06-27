/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.data.network

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.nax.atsupager.BuildConfig
import com.nax.atsupager.R
import com.nax.atsupager.data.db.*
import com.nax.atsupager.data.model.User
import com.nax.atsupager.data.manager.SessionManager
import com.nax.atsupager.security.*
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import com.nax.atsupager.webrtc.CallStatusManager
import com.nax.atsupager.webrtc.NtfyService
import com.nax.atsupager.webrtc.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.CharArrayReader
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SignalRepository"

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, ERROR
}

class DecryptionFailedException(cause: Throwable) : Exception(cause)

private data class ProcessedSignal(
    val wrapper: SignalDataWrapper,
    val rtcData: SignalData? = null,
    val textMessage: String? = null,
    val chatMessage: ChatMessage? = null,
    val decryptionFailed: Boolean = false
)

private data class OutgoingSignalRequest(
    val userId: String,
    val rawData: CharArray,
    val isChatMessage: Boolean = false
) {
    fun wipe() {
        SecureDataHandler.wipe(rawData)
    }
}

@Singleton
class SignalRepository @Inject constructor(
    private val encryptionManager: EncryptionManager,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val messageDao: MessageDao,
    private val groupDao: GroupDao,
    private val protocolProcessor: SignalProtocolProcessor,
    private val fileDownloader: FileDownloader,
    private val sharedPreferences: SharedPreferences,
    private val keyStorageManager: KeyStorageManager,
    private val callStatusManager: CallStatusManager,
    private val notificationHelper: NotificationHelper,
    private val proxyManager: ProxyManager,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context
) {

    private val gson = Gson()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    
    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status = _status.asStateFlow()

    private val _accessRequiredEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val accessRequiredEvent = _accessRequiredEvent.asSharedFlow()

    private val _incomingCallSignals = MutableSharedFlow<IncomingSignal>(
        replay = 10, 
        extraBufferCapacity = 20, 
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ) 
    val incomingCallSignals = _incomingCallSignals.asSharedFlow()

    private val outgoingSignalChannel = Channel<OutgoingSignalRequest>(Channel.UNLIMITED)
    private var authWatchdogJob: Job? = null

    private var lastUnreadChatId: String? = null
    private var lastUnreadIsGroup: Boolean = false

    init {
        repositoryScope.launch {
            for (request in outgoingSignalChannel) {
                launch {
                    try {
                        performSendSignal(request.userId, request.rawData, request.isChatMessage)
                    } finally {
                        request.wipe()
                    }
                }
            }
        }
    }

    fun deleteFileFromServer(url: String) {
        fileDownloader.confirmDownload(url)
    }

    fun clearUserNotifications(userId: String) {
        if (lastUnreadChatId == userId) lastUnreadChatId = null
        notificationHelper.cancelNotification(userId.hashCode())
    }

    fun forcePoll() {
        if (socket?.connected() == false) startPolling()
    }

    fun resurfaceLastMessageIfNeeded() {
        val chatId = lastUnreadChatId ?: return
        val isGroup = lastUnreadIsGroup
        repositoryScope.launch {
            val currentUserId = authRepository.getCurrentUserId() ?: return@launch
            val unreadCount = if (isGroup) {
                messageDao.getUnreadCountForGroup(chatId, currentUserId).first()
            } else {
                messageDao.getUnreadCountFromUserSync(currentUserId, chatId)
            }
            
            if (unreadCount > 0) {
                notificationHelper.showUINotification(chatId, context.getString(R.string.notification_new_msg), isGroup = isGroup)
            } else {
                lastUnreadChatId = null
            }
        }
    }

    fun startPolling() {
        if (!sharedPreferences.getBoolean(NtfyService.PREF_NTFY_ENABLED, true)) {
            _status.value = ConnectionStatus.DISCONNECTED
            return
        }

        val currentUserId = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null) ?: return
        if (socket != null) {
            if (socket?.connected() == true && _status.value == ConnectionStatus.CONNECTED) return
            stopPolling()
        }

        try {
            _status.value = ConnectionStatus.CONNECTING
            val proxiedHttpClient = OkHttpClient.Builder().proxy(proxyManager.getProxy()).connectTimeout(20, TimeUnit.SECONDS).readTimeout(1, TimeUnit.MINUTES).build()
            val options = IO.Options.builder().setReconnection(true).setReconnectionAttempts(Int.MAX_VALUE).setReconnectionDelay(1000).setReconnectionDelayMax(5000).setForceNew(true).build()
            options.callFactory = proxiedHttpClient
            options.webSocketFactory = proxiedHttpClient
            socket = IO.socket(BuildConfig.VPS_URL, options)
            socket?.on(Socket.EVENT_CONNECT) { _status.value = ConnectionStatus.AUTHENTICATING; startAuthWatchdog() }
            socket?.on("auth_challenge") { args ->
                val challenge = args[0] as String
                repositoryScope.launch {
                    val authSignature = keyStorageManager.signWithBitcoinKey(currentUserId, challenge)
                    val bitcoinPubKey = keyStorageManager.getBitcoinPublicKeyBase64(currentUserId) ?: ""
                    val keySignature = keyStorageManager.signWithBitcoinKey(currentUserId, bitcoinPubKey)
                    val username = sharedPreferences.getString(SettingsViewModel.PREF_LOGIN_NAME, "") ?: ""
                    if (authSignature != null && keySignature != null) {
                        val authData = JSONObject().apply { put("address", currentUserId); put("signature", authSignature); put("challenge", challenge); put("publicKey", bitcoinPubKey); put("keySignature", keySignature); put("username", username) }
                        socket?.emit("auth_verify", authData)
                    }
                }
            }
            socket?.on("auth_success") { args -> 
                _status.value = ConnectionStatus.CONNECTED; authWatchdogJob?.cancel() 
                if (args.isNotEmpty()) {
                    val data = args[0] as? JSONObject
                    val expiry = data?.optLong("expiry", 0) ?: 0
                    if (expiry > 0 || expiry == -1L) sharedPreferences.edit().putLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$currentUserId", expiry).apply()
                }
            }
            socket?.on("access_required") { sharedPreferences.edit().putLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$currentUserId", 0L).apply(); _accessRequiredEvent.tryEmit(Unit) }
            socket?.on("new_signal") { args -> if (_status.value == ConnectionStatus.CONNECTED) handleIncomingSocketData(args[0] as JSONObject) }
            socket?.on("user_key_response") { args ->
                val data = args[0] as JSONObject
                val address = data.optString("address")
                val publicKey = data.optString("publicKey")
                val username = data.optString("username")
                repositoryScope.launch {
                    if (encryptionManager.isKeyValidForAddress(publicKey, address)) {
                        userRepository.updateUserInfo(address, username, publicKey, fromNetwork = true)
                    }
                }
            }
            socket?.on(Socket.EVENT_DISCONNECT) { if (sharedPreferences.getBoolean(NtfyService.PREF_NTFY_ENABLED, true)) _status.value = ConnectionStatus.DISCONNECTED; authWatchdogJob?.cancel() }
            socket?.connect()
        } catch (e: Exception) { _status.value = ConnectionStatus.ERROR }
    }

    fun verifyAccessCode(code: String, onResult: (Boolean, String?, Long?) -> Unit) {
        val currentUserId = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null) ?: return
        repositoryScope.launch {
            if (socket == null || socket?.connected() == false) { onResult(false, context.getString(R.string.status_idle), null); return@launch }
            val signature = keyStorageManager.signWithBitcoinKey(currentUserId, code) ?: ""
            val request = JSONObject().apply { put("code", code); put("address", currentUserId); put("signature", signature) }
            val isHandled = AtomicBoolean(false)
            socket?.once("access_result") { args ->
                if (isHandled.compareAndSet(false, true)) {
                    val data = args[0] as JSONObject
                    val success = data.optBoolean("success"); val expiry = data.optLong("expiry", 0); val error = data.optString("error", null)
                    onResult(success, error, if (success) expiry else null)
                }
            }
            socket?.emit("access_verify", request)
            delay(15000)
            if (isHandled.compareAndSet(false, true)) { onResult(false, "Server response timeout", null); socket?.off("access_result") }
        }
    }

    private fun startAuthWatchdog() {
        authWatchdogJob?.cancel()
        authWatchdogJob = repositoryScope.launch { delay(12000); if (_status.value == ConnectionStatus.AUTHENTICATING) { stopPolling(); delay(2000); startPolling() } }
    }

    fun stopPolling() { authWatchdogJob?.cancel(); _status.value = ConnectionStatus.DISCONNECTED; socket?.disconnect(); socket?.off(); socket = null }

    private fun handleIncomingSocketData(json: JSONObject) {
        repositoryScope.launch {
            try {
                if (!sharedPreferences.getBoolean(NtfyService.PREF_NTFY_ENABLED, true)) return@launch
                val from = json.optString("from", "")
                val networkUsername = json.optString("username", null)
                val pk = json.optString("pk", null)
                val data = json.optString("data", null)
                val message = json.optString("message", null)

                if (!pk.isNullOrEmpty() && !encryptionManager.isKeyValidForAddress(pk, from)) return@launch

                userRepository.updateUserInfo(address = from, username = networkUsername, publicKey = pk, fromNetwork = true)

                if (!pk.isNullOrEmpty() && userRepository.getUser(from) == null) {
                    socket?.emit("request_user_key", from)
                }

                val wrapper = SignalDataWrapper(
                    from = from, data = data, message = message, pk = pk,
                    createdAt = json.optLong("created_at", System.currentTimeMillis()),
                    networkUsername = networkUsername
                )
                val currentUserIdStr = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null) ?: return@launch
                val signal = processAndDecrypt(wrapper, currentUserIdStr)
                handleProcessedSignal(signal, currentUserIdStr)
            } catch (e: Exception) { }
        }
    }

    private suspend fun handleProcessedSignal(signal: ProcessedSignal, currentUserIdStr: String) {
        if (signal.decryptionFailed) return
        val from = signal.wrapper.from
        val rtcData = signal.rtcData
        
        val bestName = rtcData?.senderName ?: signal.wrapper.networkUsername
        if (!bestName.isNullOrBlank()) {
            userRepository.updateUserInfo(address = from, username = bestName, fromNetwork = true)
        }

        val signalToProcess = rtcData?.copy(senderName = bestName)

        if (signalToProcess != null) {
            if (signalToProcess.type == SignalType.OFFER) {
                if (callStatusManager.isRecentlyEnded(signalToProcess.callId)) return
                val currentCall = callStatusManager.activeCall.value
                if (currentCall != null && currentCall.callId != signalToProcess.callId) {
                    sendSignal(from, SignalData(signalToProcess.callId, SignalType.BYE))
                    return
                }
            }
            protocolProcessor.handleSignal(from, signalToProcess, signal.wrapper.createdAt, currentUserIdStr)
            _incomingCallSignals.emit(IncomingSignal(from, gson.toJson(signalToProcess), signal.wrapper.createdAt))
        } else if (signal.chatMessage != null) {
            lastUnreadChatId = signal.chatMessage.groupId ?: from
            lastUnreadIsGroup = signal.chatMessage.groupId != null
            protocolProcessor.handleChatMessage(from, signal.chatMessage, signal.wrapper.createdAt, currentUserIdStr)
            if (sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) sendReceipt(from, signal.chatMessage.remoteId, SignalType.MSG_DELIVERED)
        } else if (signal.textMessage != null) {
            lastUnreadChatId = from
            lastUnreadIsGroup = false
            protocolProcessor.handleTextMessage(from, signal.textMessage, signal.wrapper.createdAt, currentUserIdStr)
        }
    }

    private suspend fun processAndDecrypt(wrapper: SignalDataWrapper, currentUserId: String): ProcessedSignal {
        try {
            val senderPubKey = wrapper.pk ?: userRepository.getUser(wrapper.from)?.publicKey ?: throw Exception("No key")
            if (!wrapper.data.isNullOrEmpty()) {
                val payload = gson.fromJson(wrapper.data, EncryptedPayload::class.java)
                val decryptedChars = encryptionManager.decryptToCharArray(payload, currentUserId, senderPubKey) ?: throw Exception("ECDH failed")
                return try { ProcessedSignal(wrapper, rtcData = gson.fromJson(CharArrayReader(decryptedChars), SignalData::class.java)) } finally { SecureDataHandler.wipe(decryptedChars) }
            } else if (!wrapper.message.isNullOrEmpty()) {
                val innerMsgStr = try { JSONObject(wrapper.message).optString("message", wrapper.message) } catch(e:Exception) { wrapper.message }
                val payload = gson.fromJson(innerMsgStr, EncryptedPayload::class.java)
                val decryptedChars = encryptionManager.decryptToCharArray(payload, currentUserId, senderPubKey) ?: throw Exception("ECDH failed")
                return try {
                    val reader = CharArrayReader(decryptedChars)
                    val chatMsg = try { gson.fromJson(reader, ChatMessage::class.java) } catch (e: Exception) { null }
                    if (chatMsg != null) ProcessedSignal(wrapper, chatMessage = chatMsg) else ProcessedSignal(wrapper, textMessage = String(decryptedChars))
                } finally { SecureDataHandler.wipe(decryptedChars) }
            }
            return ProcessedSignal(wrapper)
        } catch (e: Exception) { return ProcessedSignal(wrapper, decryptionFailed = true) }
    }

    fun sendSignal(userId: String, signal: SignalData) {
        val myName = sharedPreferences.getString(SettingsViewModel.PREF_LOGIN_NAME, null)
        val enrichedSignal = if (signal.senderName == null && !myName.isNullOrBlank()) {
            signal.copy(senderName = myName)
        } else signal
        val signalJson = gson.toJson(enrichedSignal)
        repositoryScope.launch { outgoingSignalChannel.send(OutgoingSignalRequest(userId, signalJson.toCharArray())) }
    }

    private suspend fun performSendSignal(userId: String, rawChars: CharArray, isChatMessage: Boolean) {
        val currentUserId = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null) ?: return
        val pubKey = userRepository.getUser(userId)?.publicKey ?: return
        val encrypted = encryptionManager.encrypt(rawChars, pubKey, currentUserId) ?: return
        val myPubKey = keyStorageManager.getBitcoinPublicKeyBase64(currentUserId)
        val username = sharedPreferences.getString(SettingsViewModel.PREF_LOGIN_NAME, "") ?: ""
        
        val signalObject = JSONObject().apply {
            if (isChatMessage) put("message", JSONObject().put("message", gson.toJson(encrypted)))
            else put("data", gson.toJson(encrypted))
            put("pk", myPubKey)
            put("username", username)
        }

        val socketData = JSONObject().apply { put("to", userId); put("signal", signalObject) }
        socket?.emit("send_signal", socketData)
    }

    suspend fun prepareTransportEncryption(targetUserId: String): StreamingEncryptionResult? {
        val currentUserId = authRepository.getCurrentUserId() ?: return null
        val pubKey = userRepository.getUser(targetUserId)?.publicKey ?: return null
        return encryptionManager.prepareStreamingEncryption(pubKey, currentUserId)
    }

    fun prepareGroupStreamingEncryption(): StreamingEncryptionResult? = encryptionManager.prepareGroupStreamingEncryption()
    fun prepareGroupTransportEncryption(): StreamingEncryptionResult? = encryptionManager.prepareGroupStreamingEncryption()

    suspend fun deleteMessage(message: ChatMessage, deleteFromDevice: Boolean, deleteForEveryone: Boolean = false) {
        val currentUserId = authRepository.getCurrentUserId() ?: return
        withContext(Dispatchers.IO) {
            val isOwner = message.groupId?.let { gid -> groupDao.getGroupById(gid)?.ownerId == currentUserId } ?: false
            if (deleteForEveryone && (message.fromUserId == currentUserId || isOwner)) {
                if (message.groupId == null && message.toUserId.isNotEmpty()) sendRemoteDelete(message.toUserId, message.remoteId)
                else if (message.groupId != null) groupDao.getGroupMemberIds(message.groupId).forEach { memberId -> if (memberId != currentUserId) sendRemoteDelete(memberId, message.remoteId) }
            }
            message.localFilePath?.let { path -> if (messageDao.getMessageCountByFilePath(path) <= 1) { val isInternal = path.contains(sessionManager.getMediaDir("", "").absolutePath); if (isInternal || deleteFromDevice) try { File(path).let { if (it.exists()) it.delete() } } catch (e: Exception) { } } }
            messageDao.deleteMessageById(message.id)
        }
    }

    suspend fun deleteSelectedMessages(messages: List<ChatMessage>, deleteFromDevice: Boolean, deleteForEveryone: Boolean) {
        val currentUserId = authRepository.getCurrentUserId() ?: return
        if (messages.isEmpty()) return
        val targetId = messages.first().groupId ?: messages.first().toUserId
        withContext(Dispatchers.IO) {
            val remoteIdsForEveryone = mutableListOf<String>()
            messages.forEach { message ->
                val isOwner = message.groupId?.let { gid -> groupDao.getGroupById(gid)?.ownerId == currentUserId } ?: false
                if (deleteForEveryone && (message.fromUserId == currentUserId || isOwner)) remoteIdsForEveryone.add(message.remoteId)
                message.localFilePath?.let { path -> if (messageDao.getMessageCountByFilePath(path) <= 1) { val isInternal = path.contains(sessionManager.getMediaDir("", "").absolutePath); if (isInternal || deleteFromDevice) try { File(path).let { if (it.exists()) it.delete() } } catch (e: Exception) { } } }
                messageDao.deleteMessageById(message.id)
            }
            if (remoteIdsForEveryone.isNotEmpty()) { val isGroup = groupDao.getGroupById(targetId) != null; sendRemoteMultiDelete(targetId, isGroup, remoteIdsForEveryone) }
        }
    }

    suspend fun clearChat(targetId: String, isGroup: Boolean, deleteFiles: Boolean, deleteForEveryone: Boolean) {
        val currentUserId = authRepository.getCurrentUserId() ?: return
        withContext(Dispatchers.IO) {
            val allMessages = if (isGroup) messageDao.getMessagesForGroupSync(targetId) else messageDao.getMessagesForChatSync(currentUserId, targetId)
            val isGroupOwner = if (isGroup) groupDao.getGroupById(targetId)?.ownerId == currentUserId else false
            if (deleteForEveryone) {
                val messagesToRemove = if (isGroupOwner) allMessages else allMessages.filter { it.fromUserId == currentUserId }
                if (messagesToRemove.isNotEmpty() || (isGroup && isGroupOwner)) { val lastMsg = messagesToRemove.maxByOrNull { it.timestamp }; sendRemoteBulkDelete(targetId, isGroup, lastMsg?.timestamp ?: System.currentTimeMillis(), lastMsg?.remoteId, isGroupOwner) }
            }
            if (deleteFiles) allMessages.forEach { msg -> if (!msg.isSaved) msg.localFilePath?.let { path -> if (messageDao.getMessageCountByFilePath(path) <= 1) try { File(path).let { if (it.exists()) it.delete() } } catch (e: Exception) { } } }
            if (isGroup) messageDao.deleteAllMessagesForGroup(targetId) else messageDao.deleteAllMessagesForChat(currentUserId, targetId)
        }
    }

    fun sendRemoteDelete(contactId: String, remoteId: String) = sendSignal(contactId, SignalData("msg_ctrl", SignalType.MSG_DELETE, gson.toJson(DeleteMessagePacket(remoteId))))
    fun sendRemoteMultiDelete(targetId: String, isGroup: Boolean, remoteIds: List<String>) { val signal = SignalData("msg_ctrl", SignalType.MSG_MULTI_DELETE, gson.toJson(MultiDeletePacket(remoteIds))); if (isGroup) repositoryScope.launch { groupDao.getGroupMemberIds(targetId).forEach { if (it != authRepository.getCurrentUserId()) sendSignal(it, signal) } } else sendSignal(targetId, signal) }
    fun sendRemoteBulkDelete(targetId: String, isGroup: Boolean, beforeTimestamp: Long, lastRemoteId: String?, isFullClear: Boolean = false) { val currentUserId = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null) ?: return; val signal = SignalData("msg_ctrl", SignalType.MSG_BULK_DELETE, gson.toJson(BulkDeletePacket(currentUserId, targetId, lastRemoteId, beforeTimestamp, isFullClear))); if (isGroup) repositoryScope.launch { groupDao.getGroupMemberIds(targetId).forEach { if (it != currentUserId) sendSignal(it, signal) } } else sendSignal(targetId, signal) }
    fun sendReceipt(contactId: String, remoteId: String, type: SignalType) { if (!sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) return; sendSignal(contactId, SignalData("msg_receipt", type, gson.toJson(ReceiptPacket(remoteId)))) }
    suspend fun sendMessage(contactId: String, text: String) { outgoingSignalChannel.send(OutgoingSignalRequest(contactId, text.toCharArray(), true)) }
    suspend fun sendMessage(contactId: String, text: String, isPrivate: Boolean = false) { 
        val currentUserId = authRepository.getCurrentUserId() ?: return
        val message = ChatMessage(
            fromUserId = currentUserId,
            toUserId = contactId,
            text = text,
            timestamp = System.currentTimeMillis(),
            isPrivate = isPrivate,
            type = MessageType.TEXT
        )
        outgoingSignalChannel.send(OutgoingSignalRequest(contactId, gson.toJson(message).toCharArray(), true)) 
    }
    suspend fun sendFileMessage(contactId: String, message: ChatMessage) { outgoingSignalChannel.send(OutgoingSignalRequest(contactId, gson.toJson(message).toCharArray(), true)) }
    suspend fun sendGroupMessage(groupId: String, message: ChatMessage) { val members = groupDao.getGroupMemberIds(groupId); val me = authRepository.getCurrentUserId() ?: return; val json = gson.toJson(message); members.forEach { if (it != me) outgoingSignalChannel.send(OutgoingSignalRequest(it, json.toCharArray(), true)) } }
    fun sendGroupInvite(groupId: String, name: String, members: List<GroupMemberPacket>, ownerId: String) {
        val signal = SignalData("group_invite", SignalType.GROUP_INVITE, gson.toJson(GroupInvitePacket(groupId, name, members, ownerId)))
        val currentUserId = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null)
        members.forEach { if (it.userId != currentUserId) sendSignal(it.userId, signal) }
    }
    suspend fun sendGroupRename(groupId: String, newName: String) { val members = groupDao.getGroupMemberIds(groupId); val me = authRepository.getCurrentUserId() ?: return; val signal = SignalData("group_rename", SignalType.GROUP_RENAME, gson.toJson(GroupRenamePacket(groupId, newName))); members.forEach { if (it != me) sendSignal(it, signal) } }
    fun setFastPolling(enabled: Boolean) { }
    fun cancelNotification(notificationId: Int) { notificationHelper.cancelNotification(notificationId) }
}
