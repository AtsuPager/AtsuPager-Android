package com.nax.atsupager.webrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.nax.atsupager.MainActivity
import com.nax.atsupager.R
import com.nax.atsupager.data.network.SignalData
import com.nax.atsupager.data.network.SignalRepository
import com.nax.atsupager.data.network.SignalType
import com.nax.atsupager.data.network.UserRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WebRtcService : LifecycleService() {

    @Inject
    lateinit var webRtcManagerFactory: WebRtcManager.Factory

    @Inject
    lateinit var overlayManager: CallOverlayManager
    
    @Inject
    lateinit var callStatusManager: CallStatusManager

    @Inject
    lateinit var signalRepository: SignalRepository

    @Inject
    lateinit var callAudioManager: CallAudioManager

    @Inject
    lateinit var userRepository: UserRepository

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isAppInBackground = false
    private var isServiceRunning = false
    private var audioManager: AudioManager? = null
    private var isAnsweredByMe = false
    private var currentCallerName: String? = null
    
    private var audioFocusRequest: AudioFocusRequest? = null

    // Ресивер для отслеживания включения экрана
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                val activeCall = callStatusManager.activeCall.value
                // Если экран включился и есть входящий звонок, на который не ответили
                if (activeCall != null && !activeCall.isCaller && !activeCall.isEstablished && !isAnsweredByMe) {
                    Log.d(TAG, "Screen turned ON during incoming call - showing UI")
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, createNotification())
                    
                    // Дополнительно пробуем поднять активити напрямую
                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                        setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("incoming_call_push", true)
                    }
                    startActivity(mainIntent)
                }
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus GAINED")
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                currentManager?.toggleMicrophone(true)
            }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus LOST")
            }
        }
    }

    companion object {
        private const val TAG = "WebRtcService"
        const val ACTION_START = "com.nax.atsupager.webrtc.START"
        const val ACTION_STOP = "com.nax.atsupager.webrtc.STOP"
        const val ACTION_ANSWER = "com.nax.atsupager.webrtc.ANSWER"
        const val ACTION_HANGUP_BROADCAST = "com.nax.atsupager.webrtc.HANGUP"
        const val NOTIFICATION_ID = 1001
        
        const val EXTRA_USER_NAME = "extra_user_name"
        
        const val CHANNEL_ID_ONGOING = "WebRtcOngoingChannel_v6" // Инкремент версии для обновления настроек канала
        const val CHANNEL_ID_INCOMING = "WebRtcIncomingChannel_v6"
        
        @Volatile
        var currentManager: WebRtcManager? = null
            private set

        fun start(context: Context, userName: String? = null) {
            val intent = Intent(context, WebRtcService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USER_NAME, userName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebRtcService", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WebRtcService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun initManager(factory: WebRtcManager.Factory, listener: WebRtcManager.WebRtcListener, iceServers: List<org.webrtc.PeerConnection.IceServer>, isVideo: Boolean): WebRtcManager {
            val manager = factory.create(listener, iceServers, isVideo)
            currentManager = manager
            return manager
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannels()
        acquireLocks()
        requestAudioFocus()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        observeCallStatus()
        observeCallActions()
        
        // Регистрируем ресивер экрана
        registerReceiver(screenStateReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private fun observeCallStatus() {
        lifecycleScope.launch {
            callStatusManager.activeCall.collectLatest { info ->
                if (info == null) {
                    isAnsweredByMe = false
                    currentCallerName = null
                } else {
                    // Получаем имя звонящего, если оно еще не установлено из Intent
                    if (currentCallerName == null) {
                        val user = userRepository.getUser(info.userId)
                        currentCallerName = user?.username ?: info.userId
                    }
                }
                
                if (isServiceRunning) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, createNotification())
                }
            }
        }
    }

    private fun observeCallActions() {
        lifecycleScope.launch {
            callStatusManager.callActions.collectLatest { action ->
                if (action == CallAction.ANSWER) {
                    isAnsweredByMe = true
                    if (isServiceRunning) {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, createNotification())
                    }
                }
            }
        }
    }

    private fun requestAudioFocus() {
        audioManager?.let { am ->
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isMicrophoneMute = false
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
            }
        }
    }

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            isAppInBackground = true
            currentManager?.setAppInForeground(false)
        }
        override fun onStart(owner: LifecycleOwner) {
            isAppInBackground = false
            currentManager?.setAppInForeground(true)
            overlayManager.hideOverlay()
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "AtsuPager:CallWakeLock").apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wifiManager.createWifiLock(lockType, "AtsuPager:WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(audioFocusChangeListener)
            }
            
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                isServiceRunning = true
                val nameFromIntent = intent.getStringExtra(EXTRA_USER_NAME)
                if (nameFromIntent != null) {
                    currentCallerName = nameFromIntent
                }

                startForegroundService()
                requestAudioFocus()
                
                val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                currentManager?.setAppInForeground(isForeground)
            }
            ACTION_STOP -> {
                Log.d(TAG, "Hangup action received")
                isServiceRunning = false
                isAnsweredByMe = false
                
                val activeCall = callStatusManager.activeCall.value
                val targetId = activeCall?.userId
                val callId = activeCall?.callId
                
                callStatusManager.rejectCall()
                callAudioManager.stop()

                if (targetId != null && callId != null) {
                    lifecycleScope.launch {
                        signalRepository.sendSignal(targetId, SignalData(callId = callId, type = SignalType.BYE))
                    }
                }

                sendBroadcast(Intent(ACTION_HANGUP_BROADCAST))
                stopForegroundService()
            }
            ACTION_ANSWER -> {
                Log.d(TAG, "Answer action received from service")
                isAnsweredByMe = true
                callStatusManager.answerCall()
                
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, createNotification())

                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("incoming_call_push", true)
                    putExtra("answer_call", true)
                }
                startActivity(mainIntent)
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start FGS: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        isServiceRunning = false
        overlayManager.hideOverlay()
        currentManager?.close()
        currentManager = null
        callAudioManager.stop()
        releaseLocks()
        
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val activeCall = callStatusManager.activeCall.value
        val isCaller = activeCall?.isCaller ?: false
        val isEstablished = activeCall?.isEstablished ?: false
        
        val channelId = if (isCaller || isEstablished || isAnsweredByMe) CHANNEL_ID_ONGOING else CHANNEL_ID_INCOMING

        val baseIntent = Intent(this, MainActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("incoming_call_push", true)
        }
        
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, baseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val answerIntent = Intent(this, MainActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("incoming_call_push", true)
            putExtra("answer_call", true)
        }
        val answerPendingIntent = PendingIntent.getActivity(
            this, 2, answerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, WebRtcService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = when {
            isEstablished || isAnsweredByMe -> getString(R.string.call_ongoing)
            isCaller -> getString(R.string.calling)
            else -> getString(R.string.incoming_call)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(currentCallerName ?: getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (!isCaller && !isEstablished && !isAnsweredByMe) {
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
            builder.setFullScreenIntent(contentPendingIntent, true)
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            builder.addAction(android.R.drawable.ic_menu_call, getString(R.string.accept), answerPendingIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.reject), stopPendingIntent)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.hangup), stopPendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val ongoingChannel = NotificationChannel(
                CHANNEL_ID_ONGOING,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            
            val incomingChannel = NotificationChannel(
                CHANNEL_ID_INCOMING,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(true)
                // Более явный и "рваный" паттерн вибрации: 0мс пауза, 500мс вибрация, 200мс пауза, 500мс вибрация и т.д.
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(true)
                }
            }
            
            manager.createNotificationChannel(ongoingChannel)
            manager.createNotificationChannel(incomingChannel)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}

        isServiceRunning = false
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)

        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        overlayManager.hideOverlay()
        callAudioManager.stop()
        releaseLocks()
        super.onDestroy()
    }
}
