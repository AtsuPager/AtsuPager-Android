package com.nax.atsupager.webrtc

import android.util.Log
import com.google.gson.Gson
import com.nax.atsupager.data.network.GameInvitePacket
import com.nax.atsupager.data.network.SignalData
import com.nax.atsupager.data.network.SignalType
import com.nax.atsupager.data.network.UserRepository
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.network.AuthRepository
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveCallInfo(
    val userId: String,
    val isCaller: Boolean,
    val isVideo: Boolean,
    val callId: String?,
    val initialOffer: String? = null,
    val isEstablished: Boolean = false,
    val isRemoteMicOn: Boolean = true,
    val callerName: String? = null // Храним имя из сети
)

enum class CallAction {
    ANSWER, REJECT
}

@Singleton
class CallStatusManager @Inject constructor(
    private val userRepository: UserRepository,
    private val messageDao: MessageDao,
    private val sharedPreferences: SharedPreferences,
    private val gson: Gson
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var durationJob: Job? = null
    private var unreadJob: Job? = null
    private var activeUserUnreadJob: Job? = null

    private val processedGameIds = mutableSetOf<String>()
    private var lastEndedCallId: String? = null
    private var lastEndedCallTime: Long = 0L

    private val _activeCall = MutableStateFlow<ActiveCallInfo?>(null)
    val activeCall = _activeCall.asStateFlow()

    private val _callDuration = MutableStateFlow(0L)
    val callDuration = _callDuration.asStateFlow()

    private val _isMinimized = MutableStateFlow(false)
    val isMinimized = _isMinimized.asStateFlow()

    private val _activeGameType = MutableStateFlow<String?>(null)
    val activeGameType = _activeGameType.asStateFlow()

    private val _incomingInvite = MutableStateFlow<GameInvitePacket?>(null)
    val incomingInvite = _incomingInvite.asStateFlow()

    private val _inviteSenderName = MutableStateFlow<String?>(null)
    val inviteSenderName = _inviteSenderName.asStateFlow()

    private val _acceptedInvite = MutableStateFlow<GameInvitePacket?>(null)
    val acceptedInvite = _acceptedInvite.asStateFlow()

    private val _totalUnreadCount = MutableStateFlow(0)
    val totalUnreadCount = _totalUnreadCount.asStateFlow()

    private val _activeUserUnreadCount = MutableStateFlow(0)
    val activeUserUnreadCount = _activeUserUnreadCount.asStateFlow()

    private val _hasChats = MutableStateFlow(false)
    val hasChats = _hasChats.asStateFlow()

    private val _gameActions = MutableSharedFlow<GameAction>(extraBufferCapacity = 1)
    val gameActions = _gameActions.asSharedFlow()

    private val _callActions = MutableSharedFlow<CallAction>(extraBufferCapacity = 1)
    val callActions = _callActions.asSharedFlow()

    init {
        startUnreadObservation()
        observeActiveUserUnread()
    }

    private fun startUnreadObservation() {
        unreadJob?.cancel()
        unreadJob = managerScope.launch {
            val currentUserId = sharedPreferences.getString(AuthRepository.KEY_USER_ID, "") ?: ""
            if (currentUserId.isEmpty()) return@launch
            combine(messageDao.getTotalUnreadCountFlow(currentUserId), messageDao.getLastMessagesForAllChats(currentUserId)) { unread, chats ->
                _totalUnreadCount.value = unread
                _hasChats.value = chats.isNotEmpty()
            }.collect()
        }
    }

    private fun observeActiveUserUnread() {
        managerScope.launch {
            activeCall.collect { call ->
                activeUserUnreadJob?.cancel()
                if (call != null) {
                    val currentUserId = sharedPreferences.getString(AuthRepository.KEY_USER_ID, "") ?: ""
                    if (currentUserId.isNotEmpty()) {
                        activeUserUnreadJob = managerScope.launch {
                            messageDao.getUnreadCountFromUser(currentUserId, call.userId).collect { count -> _activeUserUnreadCount.value = count }
                        }
                    }
                } else { _activeUserUnreadCount.value = 0 }
            }
        }
    }

    sealed class GameAction {
        data class ACCEPT(val invite: GameInvitePacket) : GameAction()
        data class REJECT(val gameType: String) : GameAction()
    }

    fun observeSignals(signalingClient: SignalingClient) {
        if (observeJob?.isActive == true) return
        if (unreadJob == null || !unreadJob!!.isActive) startUnreadObservation()
        observeJob = managerScope.launch {
            signalingClient.incomingSignals.collect { signal ->
                try {
                    val signalData = gson.fromJson(signal.data, SignalData::class.java) ?: return@collect
                    when (signalData.type) {
                        SignalType.GAME_INVITE, SignalType.CHESS_INVITE, SignalType.BACKGAMMON_INVITE, SignalType.CHECKERS_INVITE -> {
                            val payload = signalData.payload ?: return@collect
                            val invite = if (signalData.type == SignalType.GAME_INVITE) gson.fromJson(payload, GameInvitePacket::class.java) else convertOldInvite(payload, signalData.type, signal.from)
                            if (invite == null || processedGameIds.contains(invite.gameId)) return@collect
                            val sender = userRepository.getUser(signal.from)
                            showInvite(invite, sender?.username ?: signal.from)
                        }
                        SignalType.CHESS_SYNC -> _activeGameType.value = "chess"
                        SignalType.BACKGAMMON_SYNC -> _activeGameType.value = "backgammon"
                        SignalType.CHECKERS_SYNC -> _activeGameType.value = "checkers"
                        SignalType.GAME_CLOSE, SignalType.CHESS_CLOSE, SignalType.BACKGAMMON_CLOSE, SignalType.CHECKERS_CLOSE -> _activeGameType.value = null
                        else -> {}
                    }
                } catch (e: Exception) { }
            }
        }
    }

    private fun convertOldInvite(payload: String, type: SignalType, senderId: String): GameInvitePacket? {
        return try {
            val gameType = when(type) { SignalType.CHESS_INVITE -> "chess"; SignalType.BACKGAMMON_INVITE -> "backgammon"; SignalType.CHECKERS_INVITE -> "checkers"; else -> "chess" }
            val map = gson.fromJson(payload, Map::class.java)
            val gameId = (map["gameId"] as? String) ?: "old_${senderId}_${gameType}"
            GameInvitePacket(gameType, senderId, (map["senderColor"] as? String) ?: "white", gameId, map["state"] as? String ?: map["fen"] as? String)
        } catch (e: Exception) { null }
    }

    fun setGameActive(type: String?) { _activeGameType.value = type }

    fun startCall(userId: String, isCaller: Boolean, isVideo: Boolean, callId: String?, initialOffer: String? = null, callerName: String? = null) {
        _activeCall.value = ActiveCallInfo(userId, isCaller, isVideo, callId, initialOffer, isEstablished = false, isRemoteMicOn = true, callerName = callerName)
        _isMinimized.value = false
        _callDuration.value = 0L
    }

    fun updateCallId(callId: String) { _activeCall.update { it?.copy(callId = callId) } }
    fun setCallConnected(connected: Boolean) { _activeCall.update { it?.copy(isEstablished = connected) }; if (connected) startDurationTimer() else durationJob?.cancel() }
    fun updateRemoteMicState(isOn: Boolean) { _activeCall.update { it?.copy(isRemoteMicOn = isOn) } }

    private fun startDurationTimer() {
        if (durationJob?.isActive == true) return
        durationJob = managerScope.launch { while (isActive) { delay(1000); _callDuration.value += 1 } }
    }

    fun endCall() {
        _activeCall.value?.callId?.let { id -> lastEndedCallId = id; lastEndedCallTime = System.currentTimeMillis() }
        _activeCall.value = null; _isMinimized.value = false; _activeGameType.value = null; _callDuration.value = 0L; durationJob?.cancel(); clearInvite(); _acceptedInvite.value = null
    }

    fun isRecentlyEnded(callId: String?): Boolean {
        if (callId == null || lastEndedCallId == null) return false
        return callId == lastEndedCallId && (System.currentTimeMillis() - lastEndedCallTime < 5000)
    }

    fun answerCall() { _callActions.tryEmit(CallAction.ANSWER) }
    fun rejectCall() { _callActions.tryEmit(CallAction.REJECT); endCall() }
    fun minimize() { _isMinimized.value = true }
    fun restore() { _isMinimized.value = false }
    fun showInvite(invite: GameInvitePacket, senderName: String?) { _inviteSenderName.value = senderName; _incomingInvite.value = invite }
    fun clearInvite() { _incomingInvite.value = null; _inviteSenderName.value = null }
    fun acceptInvite() { _incomingInvite.value?.let { invite -> processedGameIds.add(invite.gameId); _acceptedInvite.value = invite; _gameActions.tryEmit(GameAction.ACCEPT(invite)); _activeGameType.value = invite.gameType }; clearInvite() }
    fun consumeAcceptedInvite() { _acceptedInvite.value = null }
    fun rejectInvite() { _incomingInvite.value?.let { invite -> processedGameIds.add(invite.gameId); _gameActions.tryEmit(GameAction.REJECT(invite.gameType)) }; clearInvite() }
}
