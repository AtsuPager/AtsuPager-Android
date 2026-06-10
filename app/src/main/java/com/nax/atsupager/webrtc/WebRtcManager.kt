package com.nax.atsupager.webrtc

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.*
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WebRtcManager"
private const val AUDIO_TRACK_ID = "ARDAMSa0"
private const val VIDEO_TRACK_ID = "ARDAMSv0"
private const val STREAM_ID = "ARDAMS"

class WebRtcManager @AssistedInject constructor(
    @Assisted private val listener: WebRtcListener,
    private val peerConnectionFactory: PeerConnectionFactory,
    @ApplicationContext private val context: Context,
    @Assisted private var iceServers: List<PeerConnection.IceServer>,
    @Assisted val isVideoCall: Boolean,
    private val eglBase: EglBase
) {

    @AssistedFactory
    interface Factory {
        fun create(
            listener: WebRtcListener,
            iceServers: List<PeerConnection.IceServer>,
            isVideoCall: Boolean
        ): WebRtcManager
    }

    private val handlerThread = HandlerThread("WebRtcThread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val isClosed = AtomicBoolean(false)

    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var isFrontCamera = true

    private var isCameraStarted = false
    private var isCameraUserRequested = false
    private var isAppInForeground = true

    var hasRemoteDescription = false
        private set

    interface WebRtcListener {
        fun onReady()
        fun onSdp(sdp: SessionDescription)
        fun onIceCandidates(candidates: List<IceCandidate>)
        fun onConnectionStateChange(state: PeerConnection.IceConnectionState)
        fun onTrack(track: MediaStreamTrack)
        fun onRemoteVideoTrack(videoTrack: VideoTrack)
        fun onError(error: String)
        fun onRemoteDescriptionSet()
        fun onCameraSwitched(isFront: Boolean)
        fun onCameraStateChanged(isActive: Boolean)
    }

    init {
        runOnWebRtcThread {
            try {
                val rtcConfig = createRtcConfig(iceServers)
                peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
                    ?: run { listener.onError("Failed to create PeerConnection"); return@runOnWebRtcThread }

                val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
                localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
                localAudioTrack?.setEnabled(true)
                peerConnection?.addTrack(localAudioTrack, listOf(STREAM_ID))

                videoCapturer = createVideoCapturer()
                if (videoCapturer != null) {
                    surfaceTextureHelper = SurfaceTextureHelper.create("VideoCapturerThread", eglBase.eglBaseContext)
                    val videoSource = peerConnectionFactory.createVideoSource(videoCapturer?.isScreencast ?: false)
                    videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
                    localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
                    localVideoTrack?.setEnabled(false)
                    peerConnection?.addTrack(localVideoTrack, listOf(STREAM_ID))
                }
                
                listener.onReady()
            } catch (e: Exception) { listener.onError("Initialization failed: ${e.message}") }
        }
    }

    private fun createRtcConfig(servers: List<PeerConnection.IceServer>, forceTcpRelay: Boolean = false): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(servers).apply {
            iceCandidatePoolSize = 10
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            
            // Настройки для повышения стабильности в сложных сетях
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            
            if (forceTcpRelay) {
                // Принудительно используем только TURN (Relay), если прямой UDP заблокирован
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
        }
    }

    /**
     * Позволяет обновить конфигурацию (например, включить Force TCP) без пересоздания PeerConnection
     */
    fun updateConfiguration(forceTcp: Boolean) = runOnWebRtcThread {
        try {
            val serversToUse = if (forceTcp) {
                // Оставляем только TURN/TURNS серверы, удаляем STUN
                iceServers.filter { it.urls.any { url -> url.startsWith("turn") } }
            } else {
                iceServers
            }
            val newConfig = createRtcConfig(serversToUse, forceTcpRelay = forceTcp)
            peerConnection?.setConfiguration(newConfig)
            Log.d(TAG, "WebRTC Configuration updated: forceTcp=$forceTcp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update configuration", e)
        }
    }

    private fun createVideoCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) { isFrontCamera = true; return enumerator.createCapturer(deviceName, null) }
        }
        return null
    }

    private fun runOnWebRtcThread(block: () -> Unit) { if (!isClosed.get()) handler.post(block) }

    fun createOffer() = runOnWebRtcThread {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(sdpObserver, constraints)
    }

    fun createAnswer() = runOnWebRtcThread {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createAnswer(sdpObserver, constraints)
    }

    fun handleRemoteDescription(sdp: SessionDescription) = runOnWebRtcThread {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { hasRemoteDescription = true; listener.onRemoteDescriptionSet() }
            override fun onSetFailure(error: String?) { Log.e(TAG, "Remote SDP error: $error") }
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, sdp)
    }

    fun rollback(onComplete: () -> Unit) = runOnWebRtcThread {
        if (peerConnection?.signalingState() != PeerConnection.SignalingState.STABLE) {
            Log.d(TAG, "Signaling state is ${peerConnection?.signalingState()}, performing rollback...")
            val rollbackSdp = SessionDescription(SessionDescription.Type.ROLLBACK, "")
            peerConnection?.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() { 
                    Log.d(TAG, "Rollback successful")
                    handler.post { onComplete() }
                }
                override fun onSetFailure(error: String?) { 
                    Log.e(TAG, "Rollback failed: $error")
                    handler.post { onComplete() }
                }
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, rollbackSdp)
        } else {
            onComplete()
        }
    }

    fun addRemoteIceCandidates(candidates: List<IceCandidate>) = runOnWebRtcThread {
        candidates.forEach { peerConnection?.addIceCandidate(it) }
    }

    fun toggleMicrophone(isEnabled: Boolean) = runOnWebRtcThread { localAudioTrack?.setEnabled(isEnabled) }
    
    fun toggleCamera(isEnabled: Boolean) = runOnWebRtcThread {
        isCameraUserRequested = isEnabled
        applyCameraState()
    }

    fun setAppInForeground(isForeground: Boolean) = runOnWebRtcThread {
        isAppInForeground = isForeground
        applyCameraState()
    }

    private fun applyCameraState() {
        val shouldBeActive = isCameraUserRequested && isAppInForeground
        localVideoTrack?.setEnabled(shouldBeActive)
        if (shouldBeActive && !isCameraStarted) {
            try {
                videoCapturer?.startCapture(1280, 720, 30)
                isCameraStarted = true
                listener.onCameraSwitched(isFrontCamera)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start capture", e)
            }
        } else if (!shouldBeActive && isCameraStarted) {
            try {
                videoCapturer?.stopCapture()
                isCameraStarted = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop capture", e)
            }
        }
        handler.post { listener.onCameraStateChanged(shouldBeActive) }
    }

    fun switchCamera() = runOnWebRtcThread {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { isFrontCamera = isFront; listener.onCameraSwitched(isFront) }
            override fun onCameraSwitchError(error: String?) { Log.e(TAG, "Camera switch error: $error") }
        })
    }

    fun setLocalVideoSink(sink: VideoSink) = runOnWebRtcThread { localVideoTrack?.addSink(sink) }
    fun removeLocalVideoSink(sink: VideoSink) = runOnWebRtcThread { localVideoTrack?.removeSink(sink) }
    fun restartIce() = runOnWebRtcThread { peerConnection?.restartIce() }

    fun close() {
        if (isClosed.getAndSet(true)) return
        handler.post {
            try {
                localAudioTrack?.setEnabled(false)
                localVideoTrack?.setEnabled(false)

                if (isCameraStarted) {
                    videoCapturer?.stopCapture()
                }
                surfaceTextureHelper?.dispose()
                peerConnection?.close()
                localAudioTrack?.dispose()
                localVideoTrack?.dispose()
                peerConnection?.dispose()
                Log.d(TAG, "WebRtcManager closed and tracks disabled")
            } catch (e: Exception) { 
                Log.e(TAG, "Error during close: ${e.message}")
            } finally { 
                handlerThread.quitSafely() 
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { runOnWebRtcThread { listener.onConnectionStateChange(state) } }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate) { runOnWebRtcThread { listener.onIceCandidates(listOf(candidate)) } }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(transceiver: RtpTransceiver?) {
            transceiver?.receiver?.track()?.let { track ->
                if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    remoteVideoTrack = track as VideoTrack
                    runOnWebRtcThread { listener.onRemoteVideoTrack(remoteVideoTrack!!) }
                } else {
                    track.setEnabled(true)
                    runOnWebRtcThread { listener.onTrack(track) }
                }
            }
        }
    }

    private val sdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            sdp?.let {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() { runOnWebRtcThread { listener.onSdp(it) } }
                    override fun onSetFailure(error: String?) { Log.e(TAG, "SDP set error: $error") }
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, it)
            }
        }
        override fun onCreateFailure(error: String?) { Log.e(TAG, "SDP create error: $error") }
        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {}
    }
}
