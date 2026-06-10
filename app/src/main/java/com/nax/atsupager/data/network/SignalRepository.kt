/*
 * AtsuPager - Secure Bitcoin-based Messenger
 * Copyright (c) 2026 AtsuLab. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * For commercial licensing inquiries, contact AtsuLab.
 */

package com.nax.atsupager.data.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.nax.atsupager.BuildConfig
import com.nax.atsupager.MainActivity
import com.nax.atsupager.R
import com.nax.atsupager.data.db.*
import com.nax.atsupager.data.model.*
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.EncryptedPayload
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.security.ProxyManager
import com.nax.atsupager.security.SecureDataHandler
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import com.nax.atsupager.webrtc.CallStatusManager
import com.nax.atsupager.webrtc.NtfyService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.CharArrayReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SignalRepository"

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, ERROR
}

data class FileUploadResult(val url: String, val encryptedKey: String?)
class DecryptionFailedException(cause: Throwable) : Exception(cause)

private data class ProcessedSignal(
    val wrapper: SignalDataWrapper,
    val rtcData: com.nax.atsupager.data.network.SignalData? = null,
    val textMessage: String? = null,
    val chatMessage: ChatMessage? = null,
    val decryptionFailed: Boolean = false
)

private data class OutgoingSignalRequest(
    val userId: String,
    val rawData: CharArray, // Use CharArray for security
    val isChatMessage: Boolean = false
) {
    fun wipe() {
        SecureDataHandler.wipe(rawData)
    }
}

@Singleton
class SignalRepository @Inject constructor(
    private val fileApiService: FileApiService,
    private val encryptionManager: EncryptionManager,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val messageDao: MessageDao,
    private val chessDao: ChessDao,
    private val fileDownloader: FileDownloader,
    private val sharedPreferences: SharedPreferences,
    private val keyStorageManager: KeyStorageManager,
    private val callStatusManager: CallStatusManager,
    private val proxyManager: ProxyManager,
    @ApplicationContext private val context: Context
) {

    private val gson = Gson()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    
    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status = _status.asStateFlow()

    private val _incomingCallSignals = MutableSharedFlow<IncomingSignal>(
        replay = 10, 
        extraBufferCapacity = 20, 
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ) 
    val incomingCallSignals = _incomingCallSignals.asSharedFlow()

    private val outgoingSignalChannel = Channel<OutgoingSignalRequest>(Channel.UNLIMITED)
    private var authWatchdogJob: Job? = null

    private var lastUnreadSenderId: String? = null

    init {
        repositoryScope.launch {
            for (request in outgoingSignalChannel) {
                try {
                    performSendSignal(request.userId, request.rawData, request.isChatMessage)
                } finally {
                    request.wipe() // Wipe data immediately after sending
                }
            }
        }
    }

    fun clearUserNotifications(userId: String) {
        if (lastUnreadSenderId == userId) lastUnreadSenderId = null
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(userId.hashCode())
    }

    fun cancelNotification(notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }

    fun resurfaceLastMessageIfNeeded() {
        val senderId = lastUnreadSenderId ?: return
        repositoryScope.launch {
            val currentUserId = authRepository.getCurrentUserId() ?: return@launch
            val unreadCount = messageDao.getUnreadCountFromUserSync(currentUserId, senderId)
            if (unreadCount > 0) {
                showUINotification(senderId, context.getString(R.string.notification_new_msg), isResurface = true)
            } else {
                lastUnreadSenderId = null
            }
        }
    }

    fun forcePoll() {
        if (socket?.connected() == false) startPolling()
    }

    fun setFastPolling(enabled: Boolean) { }

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

            val proxiedHttpClient = OkHttpClient.Builder()
                .proxy(proxyManager.getProxy())
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.MINUTES)
                .writeTimeout(1, TimeUnit.MINUTES)
                .build()

            val options = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .setReconnectionDelay(1000)
                .setReconnectionDelayMax(5000)
                .setForceNew(true)
                .build()
            
            options.callFactory = proxiedHttpClient
            options.webSocketFactory = proxiedHttpClient

            socket = IO.socket(BuildConfig.VPS_URL, options)

            socket?.on(Socket.EVENT_CONNECT) {
                _status.value = ConnectionStatus.AUTHENTICATING
                startAuthWatchdog()
            }

            socket?.on("auth_challenge") { args ->
                val challenge = args[0] as String
                repositoryScope.launch {
                    val authSignature = keyStorageManager.signWithBitcoinKey(currentUserId, challenge)
                    val bitcoinPubKey = keyStorageManager.getBitcoinPublicKeyBase64(currentUserId) ?: ""
                    val keySignature = keyStorageManager.signWithBitcoinKey(currentUserId, bitcoinPubKey)
                    val username = sharedPreferences.getString(SettingsViewModel.PREF_LOGIN_NAME, "") ?: ""
                    
                    if (authSignature != null && keySignature != null) {
                        val authData = JSONObject().apply {
                            put("address", currentUserId)
                            put("signature", authSignature)
                            put("challenge", challenge)
                            put("publicKey", bitcoinPubKey)
                            put("keySignature", keySignature)
                            put("username", username)
                        }
                        socket?.emit("auth_verify", authData)
                    }
                }
            }

            socket?.on("user_key_response") { args ->
                val data = args[0] as JSONObject
                val address = data.optString("address")
                val pubKey = data.optString("publicKey")
                val keySig = data.optString("keySignature")
                val username = data.optString("username")

                repositoryScope.launch {
                    if (address.isNotEmpty() && pubKey.isNotEmpty() && keySig.isNotEmpty()) {
                        val isValid = keyStorageManager.verifyBitcoinSignature(address, pubKey, keySig)
                        if (isValid) {
                            userRepository.updatePublicKey(address, pubKey)
                            if (username.isNotEmpty()) userRepository.updateUsername(address, username, fromNetwork = true)
                        }
                    }
                }
            }

            socket?.on("auth_success") { _status.value = ConnectionStatus.CONNECTED; authWatchdogJob?.cancel() }
            socket?.on("auth_failed") { _status.value = ConnectionStatus.ERROR; authWatchdogJob?.cancel() }
            socket?.on("new_signal") { args -> if (_status.value == ConnectionStatus.CONNECTED) handleIncomingSocketData(args[0] as JSONObject) }
            socket?.on(Socket.EVENT_DISCONNECT) { 
                if (sharedPreferences.getBoolean(NtfyService.PREF_NTFY_ENABLED, true)) {
                    _status.value = ConnectionStatus.DISCONNECTED 
                }
                authWatchdogJob?.cancel() 
            }
            socket?.connect()
        } catch (e: Exception) { _status.value = ConnectionStatus.ERROR }
    }

    private fun startAuthWatchdog() {
        authWatchdogJob?.cancel()
        authWatchdogJob = repositoryScope.launch {
            delay(12000)
            if (_status.value == ConnectionStatus.AUTHENTICATING) { stopPolling(); delay(2000); startPolling() }
        }
    }

    fun stopPolling() {
        authWatchdogJob?.cancel()
        _status.value = ConnectionStatus.DISCONNECTED
        socket?.disconnect()
        socket?.off()
        socket = null
    }

    private fun handleIncomingSocketData(json: JSONObject) {
        repositoryScope.launch {
            try {
                if (!sharedPreferences.getBoolean(NtfyService.PREF_NTFY_ENABLED, true)) return@launch

                val from = json.optString("from", "")
                val signalJson = json.optJSONObject("signal") ?: json
                
                val pk = signalJson.optString("pk", null)
                val data = signalJson.optString("data", null)
                val message = signalJson.optString("message", null)

                if (!pk.isNullOrEmpty()) {
                    if (encryptionManager.isKeyValidForAddress(pk, from)) {
                        val existingUser = userRepository.getUser(from)
                        userRepository.updatePublicKey(from, pk)
                        if (existingUser == null || existingUser.username.startsWith("User_")) {
                            socket?.emit("request_user_key", from)
                        }
                    } else {
                        Log.e(TAG, "CRITICAL: Public key mismatch for address $from! Dropping signal.")
                        return@launch
                    }
                }

                val wrapper = SignalDataWrapper(
                    from = from,
                    data = data,
                    message = message,
                    pk = pk,
                    createdAt = json.optLong("created_at", System.currentTimeMillis())
                )
                val currentUserIdStr = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null) ?: return@launch
                val signal = processAndDecrypt(wrapper, currentUserIdStr)
                handleProcessedSignal(signal, currentUserIdStr)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming socket data", e)
            }
        }
    }

    private suspend fun handleProcessedSignal(signal: ProcessedSignal, currentUserIdStr: String) {
        if (signal.decryptionFailed) return

        val from = signal.wrapper.from
        val onlyContacts = sharedPreferences.getBoolean(SettingsViewModel.PREF_ONLY_CONTACTS, false)
        
        // Check if the sender is a contact (if filter is enabled)
        val isFromContact = if (onlyContacts && from.isNotEmpty()) {
            userRepository.isContact(from)
        } else {
            true
        }

        val rtcData = signal.rtcData
        if (rtcData != null) {
            when (rtcData.type) {
                com.nax.atsupager.data.network.SignalType.MSG_DELIVERED,
                com.nax.atsupager.data.network.SignalType.MSG_READ, 
                com.nax.atsupager.data.network.SignalType.FILE_DOWNLOADED,
                com.nax.atsupager.data.network.SignalType.MSG_DELETE -> {
                    // Allow technical signals even from non-contacts for synchronization
                }
                com.nax.atsupager.data.network.SignalType.OFFER -> {
                    if (!isFromContact) {
                        Log.d(TAG, "Call offer from non-contact $from dropped due to 'only contacts' filter")
                        return
                    }
                }
                else -> {
                    if (!isFromContact) return
                }
            }

            // Handle allowed RTC signals
            when (rtcData.type) {
                com.nax.atsupager.data.network.SignalType.MSG_DELIVERED -> {
                    rtcData.payload?.let { payload ->
                        val packet = gson.fromJson(payload, ReceiptPacket::class.java)
                        messageDao.markAsDelivered(packet.remoteId)
                    }
                    return
                }
                com.nax.atsupager.data.network.SignalType.MSG_READ, 
                com.nax.atsupager.data.network.SignalType.FILE_DOWNLOADED -> {
                    rtcData.payload?.let { payload ->
                        val packet = gson.fromJson(payload, ReceiptPacket::class.java)
                        messageDao.markAsRemoteRead(packet.remoteId)
                    }
                    return
                }
                com.nax.atsupager.data.network.SignalType.MSG_DELETE -> {
                    rtcData.payload?.let { payload ->
                        try {
                            val deletePacket = gson.fromJson(payload, DeleteMessagePacket::class.java)
                            val message = messageDao.getMessageByRemoteId(deletePacket.remoteId)
                            if (message != null) {
                                message.localFilePath?.let { path ->
                                    try {
                                        val file = File(path)
                                        if (file.exists()) file.delete()
                                    } catch (e: Exception) { }
                                }
                                messageDao.deleteByRemoteId(deletePacket.remoteId)
                            }
                        } catch (e: Exception) { }
                    }
                    return
                }
                com.nax.atsupager.data.network.SignalType.OFFER -> {
                    if (callStatusManager.isRecentlyEnded(rtcData.callId)) return
                    val currentCall = callStatusManager.activeCall.value
                    val isDuplicateOrRestart = rtcData.isIceRestart || (currentCall?.callId == rtcData.callId)

                    if (currentCall != null && !isDuplicateOrRestart) {
                        sendSignal(signal.wrapper.from, com.nax.atsupager.data.network.SignalData(
                            callId = rtcData.callId ?: "busy",
                            type = com.nax.atsupager.data.network.SignalType.BYE
                        ))
                        repositoryScope.launch {
                            messageDao.insertMessage(ChatMessage(fromUserId = signal.wrapper.from, toUserId = currentUserIdStr, text = "CALL_MISSED_BUSY", timestamp = signal.wrapper.createdAt, isRead = false, type = MessageType.MISSED_CALL))
                            showUINotification(signal.wrapper.from, context.getString(R.string.notification_new_msg))
                        }
                        return
                    }

                    if (!isDuplicateOrRestart) {
                        showCallNotification(signal.wrapper.from, rtcData.callId ?: "unknown", rtcData.isVideo)
                        repositoryScope.launch {
                            messageDao.insertMessage(ChatMessage(fromUserId = signal.wrapper.from, toUserId = currentUserIdStr, text = "CALL_INCOMING", timestamp = signal.wrapper.createdAt, isRead = false, type = MessageType.INCOMING_CALL))
                        }
                    }
                    callStatusManager.startCall(signal.wrapper.from, false, rtcData.isVideo, rtcData.callId, rtcData.payload)
                }
                else -> {}
            }
            _incomingCallSignals.emit(IncomingSignal(signal.wrapper.from, gson.toJson(rtcData), signal.wrapper.createdAt))
        } else if (signal.chatMessage != null || signal.textMessage != null) {
            // Block messages if not from contact
            if (!isFromContact) {
                Log.d(TAG, "Message from non-contact $from dropped due to 'only contacts' filter")
                return
            }

            if (signal.chatMessage != null) {
                val incomingMsg = signal.chatMessage.copy(fromUserId = signal.wrapper.from, toUserId = currentUserIdStr, timestamp = signal.wrapper.createdAt, isRead = false, localFilePath = null)
                messageDao.insertMessage(incomingMsg)
                if (sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) {
                    sendReceipt(signal.wrapper.from, incomingMsg.remoteId, com.nax.atsupager.data.network.SignalType.MSG_DELIVERED)
                }
                lastUnreadSenderId = signal.wrapper.from
                showUINotification(signal.wrapper.from, context.getString(R.string.notification_new_msg))
            } else if (signal.textMessage != null) {
                val newMsg = ChatMessage(fromUserId = signal.wrapper.from, toUserId = currentUserIdStr, text = signal.textMessage, timestamp = signal.wrapper.createdAt, isRead = false, type = MessageType.TEXT)
                messageDao.insertMessage(newMsg)
                if (sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) {
                    sendReceipt(signal.wrapper.from, newMsg.remoteId, com.nax.atsupager.data.network.SignalType.MSG_DELIVERED)
                }
                lastUnreadSenderId = signal.wrapper.from
                showUINotification(signal.wrapper.from, context.getString(R.string.notification_new_msg))
            }
        }
    }

    private fun showCallNotification(fromUserId: String, callId: String, isVideo: Boolean) {
        repositoryScope.launch {
            val user = userRepository.getUser(fromUserId)
            val name = user?.username ?: fromUserId
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("incoming_call_from", fromUserId)
                putExtra("call_id", callId)
                putExtra("is_video", isVideo)
            }
            val pendingIntent = PendingIntent.getActivity(context, callId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val channelId = "AtsuCallChannel_v1"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(context.getString(R.string.incoming_call)).setContentText(name)
                .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true).setOngoing(true).setFullScreenIntent(pendingIntent, true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            manager.notify(NtfyService.CALL_NOTIFICATION_ID, builder.build())
        }
    }

    private fun showUINotification(fromUserId: String, body: String, isResurface: Boolean = false) {
        repositoryScope.launch {
            val currentUserId = authRepository.getCurrentUserId() ?: return@launch
            val unreadCount = messageDao.getUnreadCountFromUserSync(currentUserId, fromUserId)
            val user = userRepository.getUser(fromUserId)
            val name = user?.username ?: fromUserId
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("chat_with_user_id", fromUserId)
                if (isResurface) putExtra("from_screen_on", true)
            }
            val pendingIntent = PendingIntent.getActivity(context, fromUserId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val channelId = "AtsuMessageChannel_v3"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "AtsuPager Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 100, 300)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                }
                manager.createNotificationChannel(channel)
            }
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(name)
                .setContentText(if (unreadCount > 1) "($unreadCount) ${context.getString(R.string.notification_new_msg)}" else body)
                .setAutoCancel(true).setContentIntent(pendingIntent).setDefaults(NotificationCompat.DEFAULT_ALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setNumber(unreadCount)
            if (isResurface) builder.setOnlyAlertOnce(false)
            manager.notify(fromUserId.hashCode(), builder.build())
        }
    }

    private suspend fun processAndDecrypt(wrapper: SignalDataWrapper, currentUserId: String): ProcessedSignal {
        try {
            var senderUser = userRepository.getUser(wrapper.from)
            val senderPubKey = wrapper.pk ?: senderUser?.publicKey
            
            if (senderPubKey == null) {
                socket?.emit("request_user_key", wrapper.from)
                var waitAttempts = 0
                while (waitAttempts < 6) {
                    delay(500); senderUser = userRepository.getUser(wrapper.from)
                    if (senderUser?.publicKey != null) break
                    waitAttempts++
                }
            }
            
            val finalPubKey = senderPubKey ?: senderUser?.publicKey ?: throw Exception("No key")

            if (!wrapper.data.isNullOrEmpty()) {
                val payload = gson.fromJson(wrapper.data, EncryptedPayload::class.java)
                val decryptedChars = encryptionManager.decryptToCharArray(payload, currentUserId, finalPubKey) ?: throw Exception("Data decryption failed")
                return try {
                    val reader = CharArrayReader(decryptedChars)
                    ProcessedSignal(wrapper, rtcData = gson.fromJson(reader, com.nax.atsupager.data.network.SignalData::class.java))
                } finally {
                    SecureDataHandler.wipe(decryptedChars)
                }
            } else if (!wrapper.message.isNullOrEmpty()) {
                val innerMsgStr = try { JSONObject(wrapper.message).optString("message", wrapper.message) } catch(e:Exception) { wrapper.message }
                val payload = gson.fromJson(innerMsgStr, EncryptedPayload::class.java)
                val decryptedChars = encryptionManager.decryptToCharArray(payload, currentUserId, finalPubKey) ?: throw Exception("Message decryption failed")
                
                return try {
                    val reader = CharArrayReader(decryptedChars)
                    val chatMsg = try { gson.fromJson(reader, ChatMessage::class.java) } catch (e: Exception) { null }
                    if (chatMsg != null) {
                        ProcessedSignal(wrapper, chatMessage = chatMsg)
                    } else {
                        ProcessedSignal(wrapper, textMessage = String(decryptedChars))
                    }
                } finally {
                    SecureDataHandler.wipe(decryptedChars)
                }
            }
            return ProcessedSignal(wrapper)
        } catch (e: Exception) {
            Log.e(TAG, "processAndDecrypt failed", e)
            return ProcessedSignal(wrapper, decryptionFailed = true)
        }
    }

    fun sendSignal(userId: String, signal: com.nax.atsupager.data.network.SignalData) {
        val signalJson = gson.toJson(signal)
        val chars = signalJson.toCharArray()
        repositoryScope.launch { 
            outgoingSignalChannel.send(OutgoingSignalRequest(userId, chars)) 
        }
    }

    private suspend fun performSendSignal(userId: String, rawChars: CharArray, isChatMessage: Boolean) {
        if (_status.value != ConnectionStatus.CONNECTED) return
        val currentUserId = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null) ?: return
        
        var user = userRepository.getUser(userId)
        
        if (user?.publicKey != null && user.publicKey!!.length > 100) {
            userRepository.updatePublicKey(userId, "")
            user = userRepository.getUser(userId)
        }

        if (user?.publicKey == null || user.publicKey!!.isEmpty()) {
            socket?.emit("request_user_key", userId); var attempts = 0
            while ((user?.publicKey == null || user.publicKey!!.isEmpty()) && attempts < 10) { 
                delay(500); user = userRepository.getUser(userId); attempts++ 
            }
        }

        val pubKey = user?.publicKey ?: return
        val encrypted = encryptionManager.encrypt(rawChars, pubKey, currentUserId) ?: return
        val myPubKey = keyStorageManager.getBitcoinPublicKeyBase64(currentUserId)
        
        val socketData = JSONObject().apply {
            put("to", userId)
            val signalObj = JSONObject().apply {
                if (isChatMessage) put("message", JSONObject().put("message", gson.toJson(encrypted)))
                else put("data", gson.toJson(encrypted))
                put("pk", myPubKey)
            }
            put("signal", signalObj)
        }
        socket?.emit("send_signal", socketData)
    }

    suspend fun uploadFile(folder: String, file: File, targetUserId: String, localKey: ByteArray? = null): Result<FileUploadResult> {
        return withContext(Dispatchers.IO) {
            try {
                val currentUserId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(Exception("Unauthorized"))
                var user = userRepository.getUser(targetUserId)
                if (user?.publicKey == null) { socket?.emit("request_user_key", targetUserId); delay(1500); user = userRepository.getUser(targetUserId) }
                val pubKey = user?.publicKey ?: return@withContext Result.failure(Exception("No public key"))
                val transportEnc = encryptionManager.prepareStreamingEncryption(pubKey, currentUserId) ?: return@withContext Result.failure(Exception("Transport enc failed"))
                val encryptedRequestBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun writeTo(sink: BufferedSink) {
                        sink.write(transportEnc.iv); val rawInputStream = FileInputStream(file)
                        val inputStream = if (localKey != null && file.name.endsWith(".enc")) encryptionManager.getDecryptingStreamFromStorage(rawInputStream, localKey) else rawInputStream
                        inputStream?.use { input ->
                            val buffer = ByteArray(65536); var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                val out = transportEnc.cipher.update(buffer, 0, read)
                                if (out != null) sink.write(out)
                            }
                            val final = transportEnc.cipher.doFinal()
                            if (final != null) sink.write(final)
                        }
                    }
                }
                val body = MultipartBody.Part.createFormData("file", file.name, encryptedRequestBody)
                val folderBody = folder.toRequestBody("text/plain".toMediaTypeOrNull())
                val response = fileApiService.uploadFile(folderBody, body)
                if (response.isSuccessful && response.body() != null) Result.success(FileUploadResult(response.body()!!.url, transportEnc.encryptedKeyBase64))
                else Result.failure(IOException("Upload failed"))
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    fun sendRemoteDelete(contactId: String, remoteId: String) {
        val deletePacket = DeleteMessagePacket(remoteId)
        val signal = com.nax.atsupager.data.network.SignalData(
            callId = "msg_ctrl",
            type = com.nax.atsupager.data.network.SignalType.MSG_DELETE,
            payload = gson.toJson(deletePacket)
        )
        sendSignal(contactId, signal)
    }

    fun sendReceipt(contactId: String, remoteId: String, type: com.nax.atsupager.data.network.SignalType) {
        if (!sharedPreferences.getBoolean(SettingsViewModel.PREF_READ_RECEIPTS, true)) return
        val packet = ReceiptPacket(remoteId)
        val signal = com.nax.atsupager.data.network.SignalData(
            callId = "msg_receipt",
            type = type,
            payload = gson.toJson(packet)
        )
        sendSignal(contactId, signal)
    }

    fun deleteFileFromServer(fileUrl: String) {
        fileDownloader.confirmDownload(fileUrl)
    }

    suspend fun sendMessage(contactId: String, text: String) { 
        outgoingSignalChannel.send(OutgoingSignalRequest(contactId, text.toCharArray(), true)) 
    }
    
    suspend fun sendFileMessage(contactId: String, message: ChatMessage) { 
        val json = gson.toJson(message)
        outgoingSignalChannel.send(OutgoingSignalRequest(contactId, json.toCharArray(), true)) 
    }
}
