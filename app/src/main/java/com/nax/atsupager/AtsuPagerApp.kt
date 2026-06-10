package com.nax.atsupager

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.webkit.WebView
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.nax.atsupager.security.MessageLifecycleManager
import dagger.hilt.android.HiltAndroidApp
import org.webrtc.PeerConnectionFactory
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class AtsuPagerApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var appLifecycleObserver: AppLifecycleObserver

    @Inject
    lateinit var messageLifecycleManager: MessageLifecycleManager

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Используем Provider, чтобы избежать круговых зависимостей при инициализации
    @Inject
    lateinit var imageLoaderProvider: Provider<ImageLoader>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Register the lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        // Initialize WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        // WebView workaround
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = Application.getProcessName()
            if (packageName != processName) {
                WebView.setDataDirectorySuffix(processName)
            }
        }

        createNotificationChannel()

        // Запуск планировщика очистки сообщений
        messageLifecycleManager.schedulePeriodicCleanup()
    }

    override fun newImageLoader(): ImageLoader = imageLoaderProvider.get()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "WebRtcServiceChannel",
                "Active Call Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
