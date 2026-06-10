package com.nax.atsupager.ui.screens.call

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.data.network.IceCandidateModel
import com.nax.atsupager.data.network.IncomingSignal
import com.nax.atsupager.data.network.IceServerRepository
import com.nax.atsupager.data.network.SignalData
import com.nax.atsupager.data.network.SignalRepository
import com.nax.atsupager.data.network.SignalType
import com.nax.atsupager.data.network.UserRepository
import com.nax.atsupager.webrtc.AudioDevice
import com.nax.atsupager.webrtc.CallAction
import com.nax.atsupager.webrtc.CallAudioManager
import com.nax.atsupager.webrtc.CallDataCache
import com.nax.atsupager.webrtc.CallStatusManager
import com.nax.atsupager.webrtc.SignalingClient
import com.nax.atsupager.webrtc.WebRtcManager
import com.nax.atsupager.webrtc.WebRtcService
import com.nax.atsupager.ui.screens.contacts.ContactsRepository
import com.nax.atsupager.R
import com.nax.atsupager.webrtc.NtfyService
import com.nax.atsupager.webrtc.ActiveCallInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "CallViewModel"

enum class CallState {
    IDLE, CREATING_OFFER, INCOMING_CALL, CONNECTING, CONNECTED, RECONNECTING, REJECTED, DISCONNECTED, ERROR
}

data class CallUiState(
    val callState: CallState = CallState.IDLE,
    val isVideoCall: Boolean = false,
    val isMicOn: Boolean = true,
    val isCameraOn: Boolean = true,
    val isRemoteCameraOn: Boolean = true,
    val isRemoteMicOn: Boolean = true,
    val isLocalCameraActive: Boolean = false,
    val isCameraPaused: Boolean = false,
    val isLocalVideoOnTop: Boolean = true,
    val isFullScreen: Boolean = false,
    val isReady: Boolean = false,
    val isFrontCamera: Boolean = true,
    val username: String? = null,
    val isContact: Boolean = true,
    val error: String? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val callDuration: Long = 0,
    val availableAudioDevices: List<AudioDevice> = emptyList(),
    val selectedAudioDevice: AudioDevice? = null,
    val iceConnectionState: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW,
    val isAnswering: Boolean = false,
    val activeGameType: String? = null,
    val isMinimized: Boolean = false,
    val totalUnreadCount: Int = 0,
    val chatUnreadCount: Int = 0
) {
    val isGameActive: Boolean get() = activeGameType != null
    val isChessActive: Boolean get() = activeGameType == "chess"
}

@HiltViewModel
class CallViewModel @Inject constructor(
    private val webRtcManagerFactory: WebRtcManager.Factory,
    private val signalRepository: SignalRepository,
    private val userRepository: UserRepository,
    private val signalingClient: SignalingClient,
    private val callAudioManager: CallAudioManager,
    private val iceServerRepository: IceServerRepository,
    private val callDataCache: CallDataCache,
    private val messageDao: MessageDao,
    private val contactsRepository: ContactsRepository,
    val eglBase: EglBase,
    private val savedStateHandle: SavedStateHandle,
    private val callStatusManager: CallStatusManager,
    @ApplicationContext private val context: Context
) : ViewModel(), WebRtcManager.WebRtcListener {

    private var targetUserId: String = ""
    private var isCaller: Boolean = false
    private var isVideoCall: Boolean = false
    private var callId: String? = null
    private var initialOffer: SessionDescription? = null
    
    private val isHangupSent = AtomicBoolean(false)
    private var callTimeoutJob: Job? = null
    private var connectingTimeoutJob: Job? = null
    private var reconnectionJob: Job? = null
    private var isAnsweringPending = false
    
    private var wasCameraPausedBySystem = false

    private var lastSentVideoState: Boolean? = null
    private var lastSentAudioState: Boolean? = null
    private var lastSinksState: String = ""

    private var iceRestartCount = 0
    private val MAX_ICE_RESTARTS = 3
    private var isPolite: Boolean = false
    private var isForceTcpMode = false
    
    private val pendingSignals = mutableListOf<IncomingSignal>()
    private val iceCandidatesQueue = mutableListOf<IceCandidate>()
    private val signalQueueLock = Any()
    
    private val localIceCandidateBuffer = mutableListOf<IceCandidate>()
    private var iceGatheringJob: Job? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    
    val webRtcManager: WebRtcManager? get() = WebRtcService.currentManager

    val bgRenderer = SurfaceViewRenderer(context).apply {
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        setEnableHardwareScaler(true)
    }
    
    val pipRenderer = SurfaceViewRenderer(context).apply {
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        setEnableHardwareScaler(true)
        setZOrderMediaOverlay(true)
    }

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState = _uiState.asStateFlow()
    private val gson = Gson()

    init {
        Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
        observeActiveCall()
        observeCallDuration()
        observeProximitySensor()
        observeGameState()
        observeMinimizedState()
        observeExternalCallActions()
        observeUnreadCounts()
        
        viewModelScope.launch {
            signalingClient.incomingSignals.collect { signal -> 
                if (signal.from == targetUserId || signal.data.contains("CHESS_INVITE") || signal.data.contains("BACKGAMMON_INVITE")) {
                    synchronized(signalQueueLock) {
                        if (webRtcManager == null) {
                            pendingSignals.add(signal)
                        } else {
                            handleSignal(signal)
                        }
                    }
                }
            }
        }
    }

    private fun observeActiveCall() {
        callStatusManager.activeCall
            .filterNotNull()
            .onEach { info ->
                val isCurrentlySameUser = targetUserId == info.userId
                val isCallIdUpdating = callId == null && info.callId != null
                val isSameCall = isCurrentlySameUser && (callId == info.callId || isCallIdUpdating)

                if (!isSameCall) {
                    resetViewModel()
                    targetUserId = info.userId
                    isCaller = info.isCaller
                    isVideoCall = info.isVideo
                    callId = info.callId
                    
                    _uiState.update { it.copy(
                        isVideoCall = info.isVideo, 
                        isCameraOn = info.isVideo,
                        isRemoteCameraOn = info.isVideo,
                        isRemoteMicOn = info.isRemoteMicOn
                    ) }
                    
                    updateInitialOffer(info.initialOffer)
                    startInitialization()
                } else {
                    if (isCallIdUpdating) callId = info.callId
                    if (initialOffer == null && info.initialOffer != null) {
                        updateInitialOffer(info.initialOffer)
                    }
                    if (_uiState.value.isRemoteMicOn != info.isRemoteMicOn) {
                        _uiState.update { it.copy(isRemoteMicOn = info.isRemoteMicOn) }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeCallDuration() {
        callStatusManager.callDuration
            .onEach { duration ->
                _uiState.update { it.copy(callDuration = duration) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeUnreadCounts() {
        callStatusManager.totalUnreadCount
            .onEach { count ->
                _uiState.update { it.copy(totalUnreadCount = count) }
            }
            .launchIn(viewModelScope)

        callStatusManager.activeUserUnreadCount
            .onEach { count ->
                _uiState.update { it.copy(chatUnreadCount = count) }
            }
            .launchIn(viewModelScope)
    }
    
    private fun updateInitialOffer(offerString: String?) {
        if (offerString == null) return
        try {
            initialOffer = parseSdp(URLDecoder.decode(offerString, StandardCharsets.UTF_8.toString()))
        } catch (e: Exception) {
            initialOffer = parseSdp(offerString)
        }
        if (initialOffer != null && !isCaller && uiState.value.callState == CallState.IDLE) {
            _uiState.update { it.copy(callState = CallState.INCOMING_CALL) }
            callAudioManager.playIncomingRingtone()
        }
    }

    private fun observeGameState() {
        callStatusManager.activeGameType
            .onEach { gameType ->
                _uiState.update { it.copy(activeGameType = gameType) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeMinimizedState() {
        callStatusManager.isMinimized
            .onEach { minimized ->
                _uiState.update { it.copy(isMinimized = minimized) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeExternalCallActions() {
        callStatusManager.callActions
            .onEach { action ->
                when (action) {
                    CallAction.ANSWER -> onAnswer()
                    CallAction.REJECT -> onHangup(true)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeProximitySensor() {
        callAudioManager.isNear
            .onEach { isNear ->
                if (isNear) {
                    if (uiState.value.isCameraOn) {
                        wasCameraPausedBySystem = true
                        onPauseVideoForNavigation()
                    }
                } else {
                    if (wasCameraPausedBySystem) {
                        onResumeVideoFromNavigation()
                        wasCameraPausedBySystem = false
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun resetViewModel() {
        isHangupSent.set(false)
        callTimeoutJob?.cancel()
        connectingTimeoutJob?.cancel()
        reconnectionJob?.cancel()
        iceGatheringJob?.cancel()
        _uiState.value = CallUiState()
        iceRestartCount = 0
        lastSentVideoState = null
        lastSentAudioState = null
        lastSinksState = ""
        wasCameraPausedBySystem = false
        isForceTcpMode = false
        synchronized(signalQueueLock) {
            pendingSignals.clear()
            iceCandidatesQueue.clear()
        }
    }

    private fun startInitialization() {
        try {
            bgRenderer.init(eglBase.eglBaseContext, null)
            pipRenderer.init(eglBase.eglBaseContext, null)
        } catch (e: Exception) { }

        NtfyService.cancelCallNotification(context)
        signalRepository.forcePoll()
        callAudioManager.start(isVideoCall)
        
        viewModelScope.launch {
            combine(callAudioManager.audioDevices, callAudioManager.selectedDevice) { devices, selected ->
                _uiState.update { it.copy(availableAudioDevices = devices, selectedAudioDevice = selected) }
            }.collect {}
        }
        
        loadUserInfo(targetUserId)
        initializeCallFlow()
    }

    private fun loadUserInfo(userId: String) {
        viewModelScope.launch {
            try {
                val user = userRepository.getUser(userId)
                val isContact = contactsRepository.isContact(userId)
                _uiState.update { it.copy(username = user?.username, isContact = isContact) }
            } catch (e: Exception) { Log.e(TAG, "Failed to load user info", e) }
        }
    }

    private fun initializeCallFlow() {
        if (isCaller && callId == null) {
            this.callId = UUID.randomUUID().toString()
            callStatusManager.updateCallId(this.callId!!)
        }

        val myId = userRepository.getCurrentUserIdSync()
        if (myId != null) {
            isPolite = myId > targetUserId
        }

        if (initialOffer == null) {
            initialOffer = callId?.let { callDataCache.get(it) }
        }

        if (!isCaller) {
            if (initialOffer != null) {
                _uiState.update { it.copy(callState = CallState.INCOMING_CALL) }
                callAudioManager.playIncomingRingtone()
            } else {
                viewModelScope.launch {
                    _uiState.update { it.copy(callState = CallState.CONNECTING) }
                    withTimeoutOrNull(5000) {
                        while (initialOffer == null && isActive) {
                            initialOffer = callId?.let { callDataCache.get(it) }
                            if (initialOffer == null) {
                                val currentInfo = callStatusManager.activeCall.value
                                if (currentInfo?.initialOffer != null) {
                                    updateInitialOffer(currentInfo.initialOffer)
                                }
                            }
                            if (initialOffer == null) {
                                val offerSignal = synchronized(signalQueueLock) {
                                    pendingSignals.find { 
                                        try {
                                            val data = gson.fromJson(it.data, SignalData::class.java)
                                            data.type == SignalType.OFFER
                                        } catch(e: Exception) { false }
                                    }
                                }
                                if (offerSignal != null) {
                                    handleSignal(offerSignal)
                                    synchronized(signalQueueLock) { pendingSignals.remove(offerSignal) }
                                }
                            }
                            if (initialOffer == null) delay(500)
                        }
                    }
                    if (initialOffer == null && uiState.value.callState == CallState.CONNECTING) {
                        onHangup(false)
                    }
                }
            }
        }

        loadIceServers {
            val nameToShow = uiState.value.username ?: targetUserId
            WebRtcService.start(context, nameToShow)
            WebRtcService.initManager(webRtcManagerFactory, this@CallViewModel, iceServers, isVideo = isVideoCall)
            
            synchronized(signalQueueLock) {
                val signals = ArrayList(pendingSignals)
                pendingSignals.clear()
                signals.forEach { handleSignal(it) }
            }
            
            _uiState.update { it.copy(isReady = true) }
            if (isCaller) startCall() else if (isAnsweringPending) onAnswer()
        }
    }

    private fun loadIceServers(onLoaded: () -> Unit) {
        viewModelScope.launch {
            try {
                withTimeout(3000) {
                    iceServers = iceServerRepository.getIceServers()
                }
                Log.d(TAG, "Loaded ${iceServers.size} ICE servers")
            } catch (e: Exception) {
                Log.e(TAG, "ICE fetch failed/timeout, using fallback", e)
                iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            } finally {
                onLoaded()
            }
        }
    }

    private fun startCall() {
        if (uiState.value.isReady && isCaller) {
            _uiState.update { it.copy(callState = CallState.CREATING_OFFER) }
            callAudioManager.playOutgoingCallTone()
            webRtcManager?.toggleCamera(uiState.value.isCameraOn)
            webRtcManager?.toggleMicrophone(uiState.value.isMicOn)
            webRtcManager?.createOffer()
            
            startConnectingTimer()
            
            callTimeoutJob = viewModelScope.launch {
                delay(45000)
                if (uiState.value.callState != CallState.CONNECTED) onHangup(isTimeout = true)
            }
        }
    }

    private fun startConnectingTimer() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = viewModelScope.launch {
            delay(25000) // 25 секунд на обычную установку связи перед переходом в Stealth Mode
            if (uiState.value.callState != CallState.CONNECTED && !isForceTcpMode) {
                Log.w(TAG, "Connection slow, initiating Site-Masking Mode (TCP 8443)")
                initiateIceRestart(forceStealth = true)
            }
        }
    }

    private fun handleSignal(signal: IncomingSignal) {
        try {
            val signalData = gson.fromJson(signal.data, SignalData::class.java) ?: return
            
            when (signalData.type) {
                SignalType.CHESS_INVITE, SignalType.BACKGAMMON_INVITE -> return
                SignalType.VIDEO_STATE -> {
                    val isOn = signalData.payload == "on"
                    if (uiState.value.isRemoteCameraOn == isOn) return
                    _uiState.update { it.copy(isRemoteCameraOn = isOn) }
                    updateSinks(uiState.value.isLocalVideoOnTop, uiState.value.remoteVideoTrack)
                    return
                }
                SignalType.AUDIO_STATE -> {
                    val isOn = signalData.payload == "on"
                    if (uiState.value.isRemoteMicOn == isOn) return
                    _uiState.update { it.copy(isRemoteMicOn = isOn) }
                    callStatusManager.updateRemoteMicState(isOn)
                    return
                }
                else -> {}
            }

            if (signalData.callId != this.callId && signalData.type != SignalType.OFFER) return

            when (signalData.type) {
                SignalType.ANSWER -> {
                    callTimeoutJob?.cancel()
                    callAudioManager.stopTones()
                    _uiState.update { it.copy(isRemoteCameraOn = signalData.isVideo) }
                    updateSinks(uiState.value.isLocalVideoOnTop, uiState.value.remoteVideoTrack)
                    parseSdp(signalData.payload)?.let { webRtcManager?.handleRemoteDescription(it) }
                }
                SignalType.ICE_CANDIDATE -> {
                    val payload = signalData.payload ?: return
                    val candidates = mutableListOf<IceCandidate>()
                    try {
                        val element = gson.fromJson(payload, com.google.gson.JsonElement::class.java)
                        if (element.isJsonArray) {
                            element.asJsonArray.forEach { 
                                val model = gson.fromJson(it, IceCandidateModel::class.java)
                                candidates.add(IceCandidate(model.sdpMid, model.sdpMLineIndex, model.sdp))
                            }
                        } else {
                            val model = gson.fromJson(payload, IceCandidateModel::class.java)
                            candidates.add(IceCandidate(model.sdpMid, model.sdpMLineIndex, model.sdp))
                        }
                    } catch (e: Exception) { }
                    
                    if (candidates.isNotEmpty()) {
                        synchronized(signalQueueLock) {
                            if (webRtcManager?.hasRemoteDescription == true) {
                                webRtcManager?.addRemoteIceCandidates(candidates)
                            } else {
                                iceCandidatesQueue.addAll(candidates)
                            }
                        }
                    }
                }
                SignalType.BYE -> onHangupReceived()
                SignalType.OFFER -> {
                    if (signalData.isIceRestart && uiState.value.callState == CallState.DISCONNECTED) return
                    
                    val isActiveCall = uiState.value.callState == CallState.CONNECTED || 
                                       uiState.value.callState == CallState.RECONNECTING || 
                                       uiState.value.callState == CallState.CREATING_OFFER ||
                                       uiState.value.callState == CallState.CONNECTING

                    if (isActiveCall) {
                        parseSdp(signalData.payload)?.let { remoteSdp ->
                            if (isPolite) {
                                webRtcManager?.rollback {
                                    webRtcManager?.handleRemoteDescription(remoteSdp)
                                    webRtcManager?.createAnswer()
                                    if (uiState.value.callState != CallState.CONNECTED) {
                                        _uiState.update { it.copy(callState = CallState.CONNECTING) }
                                    }
                                }
                            } else {
                                webRtcManager?.handleRemoteDescription(remoteSdp)
                            }
                        }
                        _uiState.update { it.copy(isRemoteCameraOn = signalData.isVideo) }
                        updateSinks(uiState.value.isLocalVideoOnTop, uiState.value.remoteVideoTrack)
                    } else if (uiState.value.callState == CallState.IDLE || uiState.value.callState == CallState.INCOMING_CALL) {
                        if (this.callId == null) {
                            this.callId = signalData.callId
                            callStatusManager.updateCallId(this.callId!!)
                        }
                        _uiState.update { it.copy(isRemoteCameraOn = signalData.isVideo) }
                        updateSinks(uiState.value.isLocalVideoOnTop, uiState.value.remoteVideoTrack)
                        
                        val newOffer = parseSdp(signalData.payload)
                        if (newOffer != null) {
                            initialOffer = newOffer
                            if (uiState.value.callState == CallState.IDLE) {
                                _uiState.update { it.copy(callState = CallState.INCOMING_CALL) }
                                callAudioManager.playIncomingRingtone()
                            }
                        }
                    }
                }
                else -> {}
            }
        } catch (e: Exception) { Log.e(TAG, "Signal handle error", e) }
    }

    fun updateSinks(isVideoOnTop: Boolean, remoteTrack: VideoTrack?, isDualPiP: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main) {
            val manager = webRtcManager ?: return@launch
            val isLocalActive = uiState.value.isLocalCameraActive && !uiState.value.isCameraPaused
            val isRemoteActive = uiState.value.isRemoteCameraOn
            val trackId = remoteTrack?.id() ?: "null"
            val stateId = "top_${isVideoOnTop}_local_${isLocalActive}_remote_${isRemoteActive}_track_$trackId" + (if (isDualPiP) "_dual" else "")
            
            if (stateId == lastSinksState) return@launch
            lastSinksState = stateId

            manager.removeLocalVideoSink(bgRenderer)
            manager.removeLocalVideoSink(pipRenderer)
            remoteTrack?.removeSink(bgRenderer)
            remoteTrack?.removeSink(pipRenderer)
            
            bgRenderer.clearImage()
            pipRenderer.clearImage()
            
            if (isDualPiP) {
                if (isLocalActive) manager.setLocalVideoSink(pipRenderer)
                if (remoteTrack != null && isRemoteActive) remoteTrack.addSink(bgRenderer)
            } else {
                if (isVideoOnTop) {
                    if (isLocalActive) manager.setLocalVideoSink(pipRenderer)
                    if (remoteTrack != null && isRemoteActive) remoteTrack.addSink(bgRenderer)
                } else {
                    if (isLocalActive) manager.setLocalVideoSink(bgRenderer)
                    if (remoteTrack != null && isRemoteActive) remoteTrack.addSink(pipRenderer)
                }
            }
        }
    }

    fun onAnswer() {
        if (!uiState.value.isReady || webRtcManager == null) { 
            isAnsweringPending = true
            _uiState.update { it.copy(isAnswering = true, callState = CallState.CONNECTING) }
            return 
        }
        isAnsweringPending = false; callAudioManager.stopTones()
        _uiState.update { it.copy(isAnswering = true, callState = CallState.CONNECTING) }
        webRtcManager?.toggleCamera(uiState.value.isCameraOn)
        webRtcManager?.toggleMicrophone(uiState.value.isMicOn)
        sendVideoState(uiState.value.isCameraOn)
        sendAudioState(uiState.value.isMicOn)

        if (initialOffer != null) {
            webRtcManager?.handleRemoteDescription(initialOffer!!)
            webRtcManager?.createAnswer()
            startConnectingTimer()
        } else {
            _uiState.update { it.copy(callState = CallState.ERROR, error = context.getString(R.string.error_call_signal_missing)) }
        }
    }

    fun onReject() { onHangup(isUserInitiated = true) }

    fun onHangup(isUserInitiated: Boolean = true, isTimeout: Boolean = false) {
        if (isHangupSent.getAndSet(true)) return
        
        Log.d(TAG, "onHangup: isUserInitiated=$isUserInitiated, isTimeout=$isTimeout")

        _uiState.update { it.copy(
            isMicOn = false, 
            isCameraOn = false, 
            remoteVideoTrack = null, 
            callState = if (isTimeout) CallState.ERROR else CallState.DISCONNECTED, 
            isCameraPaused = false
        ) }

        WebRtcService.stop(context)
        callAudioManager.stop()
        callStatusManager.endCall()
        if (isUserInitiated && uiState.value.callState == CallState.CREATING_OFFER) {
            callAudioManager.playBusyTone()
        } else {
            callAudioManager.stopTones()
        }

        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            callId?.let { callDataCache.remove(it) }
            if (callId != null) {
                signalRepository.sendSignal(targetUserId, SignalData(callId = callId!!, type = SignalType.BYE))
            }
        }

        callTimeoutJob?.cancel()
        connectingTimeoutJob?.cancel()
        reconnectionJob?.cancel()
        iceGatheringJob?.cancel()
    }

    fun onToggleMic() { 
        val next = !uiState.value.isMicOn
        webRtcManager?.toggleMicrophone(next)
        _uiState.update { it.copy(isMicOn = next) } 
        sendAudioState(next)
    }
    
    fun onToggleCamera() { 
        val next = !uiState.value.isCameraOn
        webRtcManager?.toggleCamera(next)
        _uiState.update { it.copy(isCameraOn = next, isCameraPaused = false) } 
        sendVideoState(next)
    }

    fun onSwitchCamera() { webRtcManager?.switchCamera() }
    fun onAudioDeviceSelected(device: AudioDevice) { callAudioManager.selectAudioDevice(device) }
    fun onToggleVideoLayout() { _uiState.update { it.copy(isLocalVideoOnTop = !it.isLocalVideoOnTop) } }
    fun onToggleFullScreen() { _uiState.update { it.copy(isFullScreen = !it.isFullScreen) } }
    
    fun onPauseVideoForNavigation() {
        _uiState.update { it.copy(isCameraPaused = true) }
        webRtcManager?.toggleCamera(false)
        sendVideoState(false)
        updateSinks(uiState.value.isLocalVideoOnTop, uiState.value.remoteVideoTrack)
    }

    fun onResumeVideoFromNavigation() {
        _uiState.update { it.copy(isCameraPaused = false) }
        if (uiState.value.isCameraOn && uiState.value.callState != CallState.IDLE) {
            webRtcManager?.toggleCamera(true)
            sendVideoState(true)
        }
        updateSinks(uiState.value.isLocalVideoOnTop, uiState.value.remoteVideoTrack)
    }

    fun onAddContact() {
        viewModelScope.launch {
            try {
                val user = userRepository.getUser(targetUserId)
                if (user != null) {
                    contactsRepository.addContact(user)
                    _uiState.update { it.copy(isContact = true) }
                }
            } catch (e: Exception) { Log.e(TAG, "Failed to load user info", e) }
        }
    }

    private fun sendVideoState(isOn: Boolean) {
        if (lastSentVideoState == isOn) return
        lastSentVideoState = isOn
        viewModelScope.launch {
            if (callId != null) {
                signalRepository.sendSignal(targetUserId, SignalData(callId = callId!!, type = SignalType.VIDEO_STATE, payload = if (isOn) "on" else "off"))
            }
        }
    }

    private fun sendAudioState(isOn: Boolean) {
        if (lastSentAudioState == isOn) return
        lastSentAudioState = isOn
        viewModelScope.launch {
            if (callId != null) {
                signalRepository.sendSignal(targetUserId, SignalData(callId = callId!!, type = SignalType.AUDIO_STATE, payload = if (isOn) "on" else "off"))
            }
        }
    }

    override fun onReady() {}
    
    override fun onRemoteDescriptionSet() {
        synchronized(signalQueueLock) {
            if (iceCandidatesQueue.isNotEmpty()) {
                webRtcManager?.addRemoteIceCandidates(ArrayList(iceCandidatesQueue))
                iceCandidatesQueue.clear()
            }
        }
    }
    
    override fun onCameraSwitched(isFront: Boolean) { _uiState.update { it.copy(isFrontCamera = isFront) } }
    
    override fun onCameraStateChanged(isActive: Boolean) {
        _uiState.update { it.copy(isLocalCameraActive = isActive) }
        callAudioManager.setCameraState(isActive)
        updateSinks(uiState.value.isLocalVideoOnTop, uiState.value.remoteVideoTrack)
        val actualCameraState = isActive && uiState.value.isCameraOn && !uiState.value.isCameraPaused
        sendVideoState(actualCameraState)
    }

    override fun onConnectionStateChange(state: PeerConnection.IceConnectionState) {
        viewModelScope.launch {
            _uiState.update { it.copy(iceConnectionState = state) }
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    connectingTimeoutJob?.cancel()
                    reconnectionJob?.cancel(); iceRestartCount = 0; callTimeoutJob?.cancel(); callAudioManager.stopTones(); callAudioManager.acquireWakeLock()
                    _uiState.update { it.copy(callState = CallState.CONNECTED) }
                    
                    callStatusManager.setCallConnected(true)

                    launch(Dispatchers.IO) {
                        val lastIncoming = messageDao.getLastMessageByType(targetUserId, MessageType.INCOMING_CALL)
                        if (lastIncoming != null && (lastIncoming.text == "CALL_INCOMING" || lastIncoming.text == context.getString(R.string.incoming_call))) {
                            messageDao.updateMessageTypeAndText(lastIncoming.id, MessageType.INCOMING_CALL, "CALL_ACCEPTED")
                            messageDao.markMessageAsRead(lastIncoming.id)
                        }
                    }
                    
                    sendVideoState(uiState.value.isCameraOn && uiState.value.isLocalCameraActive && !uiState.value.isCameraPaused)
                    sendAudioState(uiState.value.isMicOn)
                }
                PeerConnection.IceConnectionState.FAILED -> initiateIceRestart()
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    reconnectionJob?.cancel()
                    reconnectionJob = launch { _uiState.update { it.copy(callState = CallState.RECONNECTING) }; delay(5000); if (isActive) initiateIceRestart() }
                }
                else -> {}
            }
        }
    }

    private fun initiateIceRestart(forceStealth: Boolean = false) {
        if (uiState.value.callState == CallState.DISCONNECTED) return
        
        if (iceRestartCount < MAX_ICE_RESTARTS || forceStealth) { 
            if (!forceStealth) iceRestartCount++
            
            Log.d(TAG, "Initiating ICE Restart ($iceRestartCount/$MAX_ICE_RESTARTS). StealthMode=$forceStealth")
            
            if (forceStealth && !isForceTcpMode) {
                Log.w(TAG, "Switching to Site-Masking Mode (TCP 8443)")
                isForceTcpMode = true
                webRtcManager?.updateConfiguration(forceTcp = true)
            }

            _uiState.update { it.copy(callState = CallState.RECONNECTING) }
            webRtcManager?.restartIce()
            webRtcManager?.createOffer() 
        } else {
            onHangup(false)
        }
    }

    override fun onTrack(track: MediaStreamTrack) {}
    override fun onRemoteVideoTrack(videoTrack: VideoTrack) { 
        _uiState.update { it.copy(remoteVideoTrack = videoTrack) }
        updateSinks(uiState.value.isLocalVideoOnTop, videoTrack)
    }
    override fun onError(error: String) { 
        _uiState.update { it.copy(callState = CallState.ERROR, error = error) } 
        viewModelScope.launch {
            delay(2000)
            if (uiState.value.callState == CallState.ERROR) {
                onHangup(false)
            }
        }
    }
    fun getTargetUserId() = targetUserId

    private fun onHangupReceived() { 
        if (isHangupSent.getAndSet(true)) return
        Log.d(TAG, "onHangupReceived")
        
        // Если звонок прерван на стадии входящего — это пропущенный
        if (uiState.value.callState == CallState.INCOMING_CALL) {
            viewModelScope.launch(Dispatchers.IO) {
                val lastIncoming = messageDao.getLastMessageByType(targetUserId, MessageType.INCOMING_CALL)
                if (lastIncoming != null && (lastIncoming.text == "CALL_INCOMING" || lastIncoming.text == context.getString(R.string.incoming_call))) {
                    messageDao.updateMessageTypeAndText(
                        lastIncoming.id, 
                        MessageType.MISSED_CALL, 
                        "CALL_MISSED"
                    )
                }
            }
        }

        _uiState.update { it.copy(remoteVideoTrack = null, callState = CallState.DISCONNECTED, isCameraPaused = false) }
        WebRtcService.stop(context)
        callAudioManager.stop()
        callStatusManager.endCall()
        callAudioManager.stopTones()
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            callId?.let { callDataCache.remove(it) }
        }
        callTimeoutJob?.cancel()
        connectingTimeoutJob?.cancel()
        reconnectionJob?.cancel()
        iceGatheringJob?.cancel()
    }

    override fun onCleared() { 
        super.onCleared()
        Log.d(TAG, "onCleared")
        try {
            bgRenderer.release()
            pipRenderer.release()
        } catch (e: Exception) { }
        if (!isHangupSent.get()) {
            onHangup(isUserInitiated = false)
        } else {
            callAudioManager.stop()
        }
    }

    private fun parseSdp(payload: String?): SessionDescription? {
        if (payload == null) return null
        return try {
            val json = gson.fromJson(payload, JsonObject::class.java)
            SessionDescription(SessionDescription.Type.valueOf(json.get("type").asString.uppercase()), json.get("description").asString)
        } catch (e: Exception) { null }
    }
    
    override fun onSdp(sdp: SessionDescription) {
        viewModelScope.launch {
            val sdpJson = JsonObject().apply { addProperty("type", sdp.type.canonicalForm()); addProperty("description", sdp.description) }
            val signalType = if (sdp.type == SessionDescription.Type.OFFER) SignalType.OFFER else SignalType.ANSWER
            val signal = SignalData(callId = callId!!, type = signalType, payload = gson.toJson(sdpJson), isVideo = uiState.value.isCameraOn, isIceRestart = uiState.value.callState == CallState.RECONNECTING)
            signalRepository.sendSignal(targetUserId, signal)
            signalRepository.forcePoll()
        }
    }
    
    override fun onIceCandidates(candidates: List<IceCandidate>) {
        synchronized(localIceCandidateBuffer) { localIceCandidateBuffer.addAll(candidates) }
        if (iceGatheringJob?.isActive != true) {
            iceGatheringJob = viewModelScope.launch {
                while (isActive) {
                    val candidatesToSend = synchronized(localIceCandidateBuffer) { 
                        if (localIceCandidateBuffer.isEmpty()) null else ArrayList(localIceCandidateBuffer).also { localIceCandidateBuffer.clear() } 
                    }
                    if (candidatesToSend != null) {
                        try {
                            val payload = gson.toJson(candidatesToSend.map { IceCandidateModel(it.sdpMid, it.sdpMLineIndex, it.sdp) })
                            signalRepository.sendSignal(targetUserId, SignalData(callId = callId!!, type = SignalType.ICE_CANDIDATE, payload = payload))
                        } catch (e: Exception) { 
                            Log.e(TAG, "Error sending candidates", e)
                        }
                    }
                    delay(500)
                }
            }
        }
    }
}
