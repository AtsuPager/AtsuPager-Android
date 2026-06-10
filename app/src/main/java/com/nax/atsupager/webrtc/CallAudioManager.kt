package com.nax.atsupager.webrtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.util.Log
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.nax.atsupager.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CallAudioManager"

sealed class AudioDevice {
    object Earpiece : AudioDevice()
    object Speaker : AudioDevice()
    data class Bluetooth(val name: String = "Bluetooth") : AudioDevice()
}

@Singleton
class CallAudioManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val powerManager: PowerManager
) : SensorEventListener {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get vibrator", e)
            null
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var gameToneGenerator: ToneGenerator? = null

    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphoneState: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var isCameraActive: Boolean = false

    private val _audioDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    val audioDevices: StateFlow<List<AudioDevice>> = _audioDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioDevice?>(null)
    val selectedDevice: StateFlow<AudioDevice?> = _selectedDevice.asStateFlow()

    private val _isNear = MutableStateFlow(false)
    val isNear: StateFlow<Boolean> = _isNear.asStateFlow()

    private val bluetoothScoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                Log.d(TAG, "Bluetooth SCO state changed: $state")
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        audioManager.isBluetoothScoOn = true
                        updateDeviceList()
                        _selectedDevice.update { AudioDevice.Bluetooth() }
                        updateProximitySensorState()
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        // Важно: не переключаемся на Earpiece сразу. 
                        // Возможно, это временная потеря или процесс переподключения.
                        // Откатываемся только если устройство физически исчезло.
                        audioManager.isBluetoothScoOn = false
                        updateDeviceList()
                        if (_selectedDevice.value is AudioDevice.Bluetooth && !isBluetoothAvailable()) {
                            selectAudioDevice(AudioDevice.Earpiece)
                        }
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        audioManager.isBluetoothScoOn = false
                        updateDeviceList()
                        // В случае ошибки пробуем вернуться на динамик
                        if (_selectedDevice.value is AudioDevice.Bluetooth) {
                            selectAudioDevice(AudioDevice.Earpiece)
                        }
                    }
                }
            }
        }
    }

    private fun getGameToneGenerator(): ToneGenerator? {
        if (gameToneGenerator == null) {
            try {
                gameToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create gameToneGenerator", e)
            }
        }
        return gameToneGenerator
    }

    fun start(isVideoCall: Boolean = false) {
        isCameraActive = isVideoCall
        savedAudioMode = audioManager.mode
        savedSpeakerphoneState = audioManager.isSpeakerphoneOn

        // Устанавливаем режим связи ПЕРЕД включением Bluetooth
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false

        try {
            context.registerReceiver(
                bluetoothScoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                null,
                null
            )
        } catch (e: Exception) { }

        updateDeviceList()
        
        // ЖЕСТКИЙ ПРИОРИТЕТ: если Bluetooth доступен, всегда выбираем его
        if (isBluetoothAvailable()) {
            selectAudioDevice(AudioDevice.Bluetooth())
        } else {
            val initialDevice = if (isVideoCall) AudioDevice.Speaker else AudioDevice.Earpiece
            selectAudioDevice(initialDevice)
            if (isVideoCall) ensureCallVolume()
        }
    }

    fun setCameraState(active: Boolean) {
        if (isCameraActive == active) return
        isCameraActive = active
        updateProximitySensorState()
    }

    fun stop() {
        stopTones()
        audioManager.mode = savedAudioMode
        audioManager.isSpeakerphoneOn = savedSpeakerphoneState
        setBluetoothScoOn(false)
        try { context.unregisterReceiver(bluetoothScoReceiver) } catch (e: Exception) { }
        releaseWakeLock()
        disableProximitySensor()
    }

    fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AtsuPager:CallWakeLock").apply { acquire(10 * 60 * 1000L) }
    }

    fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun enableProximitySensor() {
        if (proximityWakeLock != null) return
        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "AtsuPager:ProximityWakeLock")
            proximityWakeLock?.acquire()
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun disableProximitySensor() {
        sensorManager.unregisterListener(this)
        _isNear.value = false
        if (proximityWakeLock?.isHeld == true) proximityWakeLock?.release()
        proximityWakeLock = null
    }

    private fun updateProximitySensorState() {
        if (_selectedDevice.value is AudioDevice.Earpiece) {
            enableProximitySensor()
        } else {
            disableProximitySensor()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val isNear = distance < (proximitySensor?.maximumRange ?: 0.5f) && distance < 5.0f
            _isNear.value = isNear
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun ensureCallVolume() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (current < max * 0.7) audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * 0.8).toInt(), 0)
        } catch (e: Exception) { }
    }

    fun selectAudioDevice(device: AudioDevice) {
        Log.d(TAG, "Selecting device: $device")
        when (device) {
            is AudioDevice.Earpiece -> {
                audioManager.isSpeakerphoneOn = false
                setBluetoothScoOn(false)
                _selectedDevice.update { AudioDevice.Earpiece }
            }
            is AudioDevice.Speaker -> {
                audioManager.isSpeakerphoneOn = true
                setBluetoothScoOn(false)
                _selectedDevice.update { AudioDevice.Speaker }
                ensureCallVolume()
            }
            is AudioDevice.Bluetooth -> {
                audioManager.isSpeakerphoneOn = false
                // Важно: на некоторых девайсах Bluetooth SCO не заведется, 
                // если уже включен Speakerphone. Мы его выключили выше.
                if (!audioManager.isBluetoothScoOn) {
                    try {
                        // Устанавливаем флаг ПЕРЕД стартом, чтобы система знала о намерении
                        audioManager.isBluetoothScoOn = true 
                        audioManager.startBluetoothSco()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start SCO", e)
                        audioManager.isBluetoothScoOn = false
                    }
                }
                _selectedDevice.update { AudioDevice.Bluetooth() }
            }
        }
        updateProximitySensorState()
    }

    private fun setBluetoothScoOn(isOn: Boolean) {
        try {
            if (isOn) {
                if(isBluetoothAvailable() && !audioManager.isBluetoothScoOn) {
                    audioManager.isBluetoothScoOn = true
                    audioManager.startBluetoothSco()
                }
            } else {
                if (audioManager.isBluetoothScoOn) {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
            }
        } catch(e: Exception) { }
    }

    private fun updateDeviceList() {
        val devices = mutableListOf<AudioDevice>()
        devices.add(AudioDevice.Earpiece)
        devices.add(AudioDevice.Speaker)
        if (isBluetoothAvailable()) devices.add(AudioDevice.Bluetooth())
        _audioDevices.update { devices }
    }

    fun isBluetoothAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                // Проверяем и SCO (для звонков) и A2DP (как признак наличия гарнитуры вообще)
                devices.any { 
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoAvailableOffCall || audioManager.isBluetoothA2dpOn
            }
        } catch (e: Exception) { false }
    }

    fun playOutgoingCallTone() {
        stopTones()
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, -1)
        } catch(e: Exception) { }
    }

    fun playIncomingRingtone() { playResource(R.raw.ringtone, true) }

    fun playBusyTone() {
        stopTones()
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 50)
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_BUSY, 2000)
        } catch(e: Exception) { }
    }

    fun vibrate(duration: Long) {
        val v = vibrator ?: return
        try {
            if (v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(duration)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    fun playCheckTone() {
        getGameToneGenerator()?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        vibrate(100)
    }

    fun playMoveTone() {
        getGameToneGenerator()?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        vibrate(40)
    }

    fun playDrawTone() {
        getGameToneGenerator()?.startTone(ToneGenerator.TONE_PROP_PROMPT, 200)
        vibrate(60)
    }

    fun playVictoryTone() {
        vibrate(500)
        val tg = getGameToneGenerator()
        tg?.startTone(ToneGenerator.TONE_DTMF_0, 200)
        Handler(Looper.getMainLooper()).postDelayed({ tg?.startTone(ToneGenerator.TONE_DTMF_4, 200) }, 200)
        Handler(Looper.getMainLooper()).postDelayed({ tg?.startTone(ToneGenerator.TONE_DTMF_7, 400) }, 400)
    }

    fun playDefeatTone() {
        vibrate(500)
        val tg = getGameToneGenerator()
        tg?.startTone(ToneGenerator.TONE_DTMF_7, 200)
        Handler(Looper.getMainLooper()).postDelayed({ tg?.startTone(ToneGenerator.TONE_DTMF_4, 200) }, 200)
    }

    private fun playResource(resId: Int, loop: Boolean) {
        stopTones()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.parse("android.resource://${context.packageName}/$resId"))
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = loop
                prepare()
                start()
            }
        } catch (e: Exception) { }
    }

    fun stopTones() {
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
            
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) { }
    }
}
