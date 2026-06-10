package com.nax.atsupager

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nax.atsupager.data.network.SignalRepository
import com.nax.atsupager.security.ClipboardSecurityManager
import com.nax.atsupager.webrtc.SignalingClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLifecycleObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signalingClient: SignalingClient,
    private val signalRepository: SignalRepository
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d("AppLifecycleObserver", "App in foreground")
        
        // Очищаем буфер обмена при входе, если время хранения истекло, пока приложение было в фоне
        ClipboardSecurityManager.checkAndClearIfExpired(context)

        // Разрешаем опрос сигналов
        signalingClient.start()
        
        // Принудительно опрашиваем сервер сразу при входе
        signalRepository.forcePoll()
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d("AppLifecycleObserver", "App in background")
    }
}
