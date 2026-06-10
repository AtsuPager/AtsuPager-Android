package com.nax.atsupager.webrtc

import android.util.Log
import com.nax.atsupager.data.network.IncomingSignal
import com.nax.atsupager.data.network.SignalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SignalingClient"

@Singleton
class SignalingClient @Inject constructor(
    private val signalRepository: SignalRepository
) {
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Увеличили replay до 10, чтобы не терять сигналы подтверждения игры, если они приходят пачкой
    val incomingSignals: Flow<IncomingSignal> = signalRepository.incomingCallSignals
        .shareIn(clientScope, SharingStarted.WhileSubscribed(), replay = 10)

    fun start() {
        Log.d(TAG, "Requesting to start signal polling.")
        signalRepository.startPolling()
    }

    fun stop() {
        Log.d(TAG, "Requesting to stop signal polling.")
        signalRepository.stopPolling()
    }
}
