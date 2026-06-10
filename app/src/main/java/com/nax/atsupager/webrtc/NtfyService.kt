package com.nax.atsupager.webrtc

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nax.atsupager.BuildConfig
import com.nax.atsupager.MainActivity
import com.nax.atsupager.R
import com.nax.atsupager.data.network.AuthRepository
import com.nax.atsupager.data.network.SignalRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class NtfyStatus {
    IDLE, CONNECTING, CONNECTED, ERROR
}

@AndroidEntryPoint
class NtfyService : Service() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var signalRepository: SignalRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val gson = Gson()
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    // Ресивер для отслеживания включения экрана (для сообщений)
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                Log.d(TAG, "Screen ON: checking for unread messages to resurface")
                signalRepository.resurfaceLastMessageIfNeeded()
            }
        }
    }

    companion object {
        private const val TAG = "NtfyService"
        private const val NOTIFICATION_ID = 2002
        
        // Сделали публичным для использования в SignalRepository
        const val CALL_NOTIFICATION_ID = 2003
        
        const val PREF_NTFY_ENABLED = "ntfy_enabled"
        const val PREF_NTFY_SERVER = "ntfy_server_url"
        val DEFAULT_NTFY_SERVER = BuildConfig.VPS_URL
        const val ACTION_STOP = "com.nax.atsupager.webrtc.ntfy.STOP"
        
        // Возвращаем константы счетчиков для MainActivity
        const val PREF_MISSED_CALLS = "missed_calls_count"
        const val PREF_NEW_MESSAGES = "new_messages_count"

        var isRunning = false
            private set

        private val _status = MutableStateFlow(NtfyStatus.IDLE)
        val status = _status.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, NtfyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NtfyService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
            _status.value = NtfyStatus.IDLE // Мгновенный сброс статуса при команде стоп
        }

        fun cancelCallNotification(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(CALL_NOTIFICATION_ID)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        registerReceiver(screenStateReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            _status.value = NtfyStatus.IDLE
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, createServiceNotification())
        startListening()
        return START_STICKY
    }

    private fun startListening() {
        job?.cancel()
        val userId = authRepository.getCurrentUserId() ?: return
        
        val savedUrl = prefs.getString(PREF_NTFY_SERVER, DEFAULT_NTFY_SERVER) ?: DEFAULT_NTFY_SERVER
        val cleanUrl = savedUrl.trim().removeSuffix("/").removeSuffix("/ntfy").removeSuffix("/")
        
        if (cleanUrl != savedUrl.removeSuffix("/")) {
            Log.d(TAG, "Cleaning ntfy URL from $savedUrl to $cleanUrl")
            prefs.edit().putString(PREF_NTFY_SERVER, cleanUrl).apply()
        }
        
        val topic = "atsupager_$userId"
        val fullUrl = "$cleanUrl/$topic/json"

        Log.d(TAG, "Listening to: $fullUrl")

        job = serviceScope.launch {
            while (isActive) {
                try {
                    _status.value = NtfyStatus.CONNECTING
                    val request = Request.Builder().url(fullUrl).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        
                        _status.value = NtfyStatus.CONNECTED
                        val source = response.body?.source() ?: return@use
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            processNotification(line)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Ntfy error: ${e.message}")
                        _status.value = NtfyStatus.ERROR
                        delay(5000)
                    }
                }
            }
        }
    }

    private fun processNotification(jsonLine: String) {
        if (jsonLine.isBlank() || !jsonLine.contains("{")) return
        try {
            val json = gson.fromJson(jsonLine, JsonObject::class.java)
            val msg = json.get("message")?.asString?.uppercase() ?: ""
            if (msg.isNotEmpty()) {
                Log.d(TAG, "Wake up signal: $msg")
                signalRepository.forcePoll()
            }
        } catch (e: Exception) {}
    }

    private fun createServiceNotification(): Notification {
        val chId = "NtfyServiceChannel_V2" // Инкрементируем версию канала для применения настроек
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(chId, "AtsuPager Background Service", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keep app active to receive messages"
                setShowBadge(false) // Отключаем точку на иконке для этого уведомления
            }
            m.createNotificationChannel(channel)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, chId)
            .setContentTitle("AtsuPager Online")
            .setContentText("Служба приема сообщений активна")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}

        isRunning = false
        _status.value = NtfyStatus.IDLE
        job?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
