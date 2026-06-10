package com.nax.atsupager.ui.screens.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nax.atsupager.R
import com.nax.atsupager.data.manager.SessionManager
import com.nax.atsupager.data.network.AuthRepository
import com.nax.atsupager.data.network.SignalRepository
import com.nax.atsupager.data.network.ConnectionStatus
import com.nax.atsupager.security.*
import com.nax.atsupager.ui.theme.AppFont
import com.nax.atsupager.webrtc.NtfyService
import com.nax.atsupager.webrtc.NtfyStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class PhotoQuality { LOW, MEDIUM, HIGH }
enum class VideoQuality { P480, P720, P1080 }
enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class AccessStatus { ACTIVE, EXPIRED, NO_ACCESS }

enum class MessageTTL(val seconds: Long) {
    OFF(0),
    ONE_HOUR(3600),
    ONE_DAY(86400),
    ONE_WEEK(604800),
    ONE_MONTH(2592000);

    val millis: Long get() = seconds * 1000
}

data class SettingsUiState(
    val isNtfyEnabled: Boolean = true,
    val ntfyStatus: NtfyStatus = NtfyStatus.IDLE,
    val vpsStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val ntfyServerUrl: String = "",
    val onlyContacts: Boolean = false,
    val isAppLockEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val isScreenshotsDisabled: Boolean = true,
    val isAutoDownloadEnabled: Boolean = false,
    val isReadReceiptsEnabled: Boolean = true,
    val isPinSet: Boolean = false,
    val bitcoinAddress: String? = null,
    val mnemonic: List<String>? = null,
    val loginName: String = "",
    val photoQuality: PhotoQuality = PhotoQuality.MEDIUM,
    val videoQuality: VideoQuality = VideoQuality.P720,
    val audioBitrate: Int = 64000,
    val currentLanguage: String = "en",
    val themeMode: ThemeMode = ThemeMode.DARK,
    val appFont: AppFont = AppFont.SYSTEM,
    val messageTTL: MessageTTL = MessageTTL.OFF,
    val clipboardClearDelay: ClipboardClearDelay = ClipboardClearDelay.ONE_MINUTE,
    val isProxyEnabled: Boolean = false,
    val proxyHost: String = ProxyManager.DEFAULT_TOR_HOST,
    val proxyPort: Int = ProxyManager.DEFAULT_TOR_PORT,
    val profiles: Map<String, String> = emptyMap(),
    val activeProfileId: String = "",
    val profilesWithPin: Set<String> = emptySet(),
    val accessStatus: AccessStatus = AccessStatus.NO_ACCESS,
    val accessExpiry: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
    private val keyStorageManager: KeyStorageManager,
    private val authRepository: AuthRepository,
    private val signalRepository: SignalRepository,
    private val messageLifecycleManager: MessageLifecycleManager,
    private val sessionManager: SessionManager
) : ViewModel() {

    companion object {
        const val PREF_ONLY_CONTACTS = "pref_only_contacts"
        const val PREF_APP_LOCK_ENABLED = "pref_app_lock_enabled"
        const val PREF_BIOMETRIC_ENABLED = "pref_biometric_enabled"
        const val PREF_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        const val PREF_AUTO_DOWNLOAD = "pref_auto_download"
        const val PREF_READ_RECEIPTS = "pref_read_receipts"
        const val PREF_USER_ID = "user_id"
        const val PREF_LOGIN_NAME = "pref_login_name"
        const val PREF_PHOTO_QUALITY = "pref_photo_quality"
        const val PREF_VIDEO_QUALITY = "pref_video_quality"
        const val PREF_AUDIO_BITRATE = "pref_audio_bitrate"
        const val PREF_MESSAGE_TTL = "pref_message_ttl"
        const val PREF_CLIPBOARD_CLEAR_DELAY = "pref_clipboard_clear_delay"
        const val PREF_THEME_MODE = "pref_theme_mode"
        const val PREF_APP_FONT = "pref_app_font"
        const val PREF_ACCESS_EXPIRY = "pref_access_expiry_long"
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadSettings()
        
        NtfyService.status.onEach { status ->
            _uiState.update { it.copy(ntfyStatus = status) }
        }.launchIn(viewModelScope)

        signalRepository.status.onEach { status ->
            _uiState.update { it.copy(vpsStatus = status) }
        }.launchIn(viewModelScope)

        sessionManager.profilesFlow.onEach {
            loadSettings()
        }.launchIn(viewModelScope)
    }

    private fun loadSettings() {
        val storageUserId = authRepository.getCurrentUserId() ?: sessionManager.getActiveProfileId()
        val isEnabled = prefs.getBoolean(NtfyService.PREF_NTFY_ENABLED, true)
        val serverUrl = prefs.getString(NtfyService.PREF_NTFY_SERVER, NtfyService.DEFAULT_NTFY_SERVER) 
            ?: NtfyService.DEFAULT_NTFY_SERVER
        val onlyContacts = prefs.getBoolean(PREF_ONLY_CONTACTS, false)
        val appLockEnabled = prefs.getBoolean(PREF_APP_LOCK_ENABLED, false)
        val biometricEnabled = prefs.getBoolean(PREF_BIOMETRIC_ENABLED, false)
        val screenshotsDisabled = prefs.getBoolean(PREF_DISABLE_SCREENSHOTS, true)
        val autoDownloadEnabled = prefs.getBoolean(PREF_AUTO_DOWNLOAD, false)
        val readReceiptsEnabled = prefs.getBoolean(PREF_READ_RECEIPTS, true)
        val isPinSet = keyStorageManager.hasPin(storageUserId)
        val loginName = prefs.getString(PREF_LOGIN_NAME, "") ?: ""
        
        val mnemonicChars = keyStorageManager.getMnemonicAsCharArray(storageUserId)
        val btcAddress = mnemonicChars?.let { keyStorageManager.getBitcoinAddressFromMnemonic(it) }
        val mnemonic = mnemonicChars?.let { chars ->
            try {
                String(chars).split(" ")
            } finally {
                SecureDataHandler.wipe(chars)
            }
        }

        // Загрузка статуса доступа
        val expiryTimestamp = prefs.getLong("${PREF_ACCESS_EXPIRY}_$storageUserId", 0L)
        val accessStatus = when {
            expiryTimestamp == -1L -> AccessStatus.ACTIVE // Бессрочно
            expiryTimestamp > System.currentTimeMillis() -> AccessStatus.ACTIVE
            expiryTimestamp == 0L -> AccessStatus.NO_ACCESS
            else -> AccessStatus.EXPIRED
        }
        val expiryString = if (expiryTimestamp == -1L) {
            context.getString(R.string.access_expiry_never)
        } else if (expiryTimestamp > 0) {
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(expiryTimestamp))
        } else ""

        val photoQuality = PhotoQuality.valueOf(prefs.getString(PREF_PHOTO_QUALITY, PhotoQuality.MEDIUM.name) ?: PhotoQuality.MEDIUM.name)
        val videoQuality = VideoQuality.valueOf(prefs.getString(PREF_VIDEO_QUALITY, VideoQuality.P720.name) ?: VideoQuality.P720.name)
        val audioBitrate = prefs.getInt(PREF_AUDIO_BITRATE, 64000)
        
        val themeMode = ThemeMode.valueOf(prefs.getString(PREF_THEME_MODE, ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
        val appFont = try {
            AppFont.valueOf(prefs.getString(PREF_APP_FONT, AppFont.SYSTEM.name) ?: AppFont.SYSTEM.name)
        } catch (_: Exception) {
            AppFont.SYSTEM
        }

        val messageTTL = MessageTTL.valueOf(prefs.getString(PREF_MESSAGE_TTL, MessageTTL.OFF.name) ?: MessageTTL.OFF.name)
        val clipboardDelay = try {
            ClipboardClearDelay.valueOf(prefs.getString(PREF_CLIPBOARD_CLEAR_DELAY, ClipboardClearDelay.ONE_MINUTE.name) ?: ClipboardClearDelay.ONE_MINUTE.name)
        } catch (_: Exception) {
            ClipboardClearDelay.ONE_MINUTE
        }

        val proxyEnabled = prefs.getBoolean(ProxyManager.PREF_PROXY_ENABLED, false)
        val proxyHost = prefs.getString(ProxyManager.PREF_PROXY_HOST, ProxyManager.DEFAULT_TOR_HOST) ?: ProxyManager.DEFAULT_TOR_HOST
        val proxyPort = prefs.getInt(ProxyManager.PREF_PROXY_PORT, ProxyManager.DEFAULT_TOR_PORT)

        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val langCode = if (currentLocales.isEmpty) "system" else currentLocales.toLanguageTags()

        val profilesMap = sessionManager.getProfilesIds().associateWith { id ->
            sessionManager.getProfileName(id)
        }
        val profilesWithPin = sessionManager.getProfilesIds().filter { 
            keyStorageManager.hasPin(it)
        }.toSet()

        _uiState.update { it.copy(
            isNtfyEnabled = isEnabled, 
            ntfyServerUrl = serverUrl,
            onlyContacts = onlyContacts,
            isAppLockEnabled = appLockEnabled,
            isBiometricEnabled = biometricEnabled,
            isScreenshotsDisabled = screenshotsDisabled,
            isAutoDownloadEnabled = autoDownloadEnabled,
            isReadReceiptsEnabled = readReceiptsEnabled,
            isPinSet = isPinSet,
            bitcoinAddress = btcAddress,
            mnemonic = mnemonic,
            loginName = loginName,
            photoQuality = photoQuality,
            videoQuality = videoQuality,
            audioBitrate = audioBitrate,
            currentLanguage = langCode,
            themeMode = themeMode,
            appFont = appFont,
            messageTTL = messageTTL,
            clipboardClearDelay = clipboardDelay,
            isProxyEnabled = proxyEnabled,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            profiles = profilesMap,
            activeProfileId = sessionManager.getActiveProfileId(),
            profilesWithPin = profilesWithPin,
            ntfyStatus = NtfyService.status.value,
            accessStatus = accessStatus,
            accessExpiry = expiryString
        ) }
    }

    /**
     * Обрабатывает код активации: очищает от мусора, подписывает и отправляет на сервер.
     */
    fun applyAccessCode(code: String, onResult: (Boolean, String?) -> Unit) {
        // Очистка: только буквы и цифры в верхнем регистре
        val cleanCode = code.replace(Regex("[^A-Z0-9]"), "").uppercase()

        if (cleanCode.length != 16) {
            onResult(false, context.getString(R.string.invalid_code))
            return
        }

        viewModelScope.launch {
            signalRepository.verifyAccessCode(cleanCode) { success, error, expiry ->
                viewModelScope.launch {
                    if (success && expiry != null) {
                        val storageUserId = authRepository.getCurrentUserId() ?: sessionManager.getActiveProfileId()
                        prefs.edit().putLong("${PREF_ACCESS_EXPIRY}_$storageUserId", expiry).apply()
                        loadSettings()
                        onResult(true, null)
                    } else {
                        onResult(false, error ?: context.getString(R.string.invalid_code))
                    }
                }
            }
        }
    }

    /**
     * Немедленно очищает мнемонику из состояния UI для защиты RAM.
     */
    fun clearMnemonicFromState() {
        _uiState.update { it.copy(mnemonic = null) }
    }

    fun switchProfile(userId: String) {
        _uiState.update { it.copy(activeProfileId = userId) }
        
        viewModelScope.launch {
            delay(500)
            sessionManager.switchProfile(userId)
        }
    }

    fun createNewProfile(username: String) {
        viewModelScope.launch {
            authRepository.createNewIdentity(username).onSuccess { newId ->
                switchProfile(newId)
            }
        }
    }

    fun importNewProfile(mnemonicWords: List<String>, username: String) {
        viewModelScope.launch {
            val mnemonicChars = mnemonicWords.joinToString(" ").toCharArray()
            authRepository.importIdentity(mnemonicChars, username).onSuccess { newId ->
                switchProfile(newId)
            }
        }
    }

    /**
     * Удаляет профиль. Принимает CharArray PIN-кода для безопасности.
     */
    fun deleteProfile(userId: String, pinChars: CharArray, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val hasPin = keyStorageManager.hasPin(userId)
            val isPinValid = !hasPin || keyStorageManager.verifyPin(userId, pinChars)
            
            // Очищаем PIN сразу после проверки
            SecureDataHandler.wipe(pinChars)

            if (isPinValid) {
                val isActive = userId == sessionManager.getActiveProfileId()
                if (isActive) {
                    delay(300)
                }
                sessionManager.deleteProfile(userId)
                onSuccess()
            } else {
                onError(context.getString(R.string.error_wrong_pin))
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(PREF_THEME_MODE, mode.name).apply()
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setAppFont(font: AppFont) {
        prefs.edit().putString(PREF_APP_FONT, font.name).apply()
        _uiState.update { it.copy(appFont = font) }
    }

    fun toggleProxy(enabled: Boolean) {
        prefs.edit().putBoolean(ProxyManager.PREF_PROXY_ENABLED, enabled).apply()
        _uiState.update { it.copy(isProxyEnabled = enabled) }
        if (_uiState.value.isNtfyEnabled) {
            signalRepository.stopPolling()
            signalRepository.startPolling()
        }
    }

    fun setProxySettings(host: String, port: Int) {
        prefs.edit().putString(ProxyManager.PREF_PROXY_HOST, host).putInt(ProxyManager.PREF_PROXY_PORT, port).apply()
        _uiState.update { it.copy(proxyHost = host, proxyPort = port) }
        if (_uiState.value.isProxyEnabled && _uiState.value.isNtfyEnabled) {
            signalRepository.stopPolling()
            signalRepository.startPolling()
        }
    }

    fun setMessageTTL(ttl: MessageTTL) {
        prefs.edit().putString(PREF_MESSAGE_TTL, ttl.name).apply()
        _uiState.update { it.copy(messageTTL = ttl) }
        messageLifecycleManager.schedulePeriodicCleanup()
    }

    fun setClipboardClearDelay(delay: ClipboardClearDelay) {
        prefs.edit().putString(PREF_CLIPBOARD_CLEAR_DELAY, delay.name).apply()
        _uiState.update { it.copy(clipboardClearDelay = delay) }
    }

    fun setLoginName(name: String) {
        prefs.edit().putString(PREF_LOGIN_NAME, name).apply()
        _uiState.update { it.copy(loginName = name) }
        
        val currentUserId = authRepository.getCurrentUserId() ?: sessionManager.getDisplayActiveId()
        sessionManager.addProfileToList(currentUserId, name)
    }

    fun changeLanguage(langCode: String) {
        val appLocale: LocaleListCompat = if (langCode == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(langCode)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)
        _uiState.update { it.copy(currentLanguage = langCode) }
    }

    fun toggleNtfy(enabled: Boolean) {
        prefs.edit().putBoolean(NtfyService.PREF_NTFY_ENABLED, enabled).apply()
        _uiState.update { it.copy(isNtfyEnabled = enabled) }
        
        if (enabled) {
            NtfyService.start(context)
            signalRepository.startPolling()
        } else {
            NtfyService.stop(context)
            signalRepository.stopPolling()
        }
    }

    fun setNtfyServerUrl(url: String) {
        prefs.edit().putString(NtfyService.PREF_NTFY_SERVER, url).apply()
        _uiState.update { it.copy(ntfyServerUrl = url) }
        if (_uiState.value.isNtfyEnabled) {
            NtfyService.stop(context)
            NtfyService.start(context)
        }
    }

    fun toggleOnlyContacts(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_ONLY_CONTACTS, enabled).apply()
        _uiState.update { it.copy(onlyContacts = enabled) }
    }

    fun toggleAppLock(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_APP_LOCK_ENABLED, enabled).apply()
        _uiState.update { it.copy(isAppLockEnabled = enabled) }
    }

    fun toggleBiometric(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_BIOMETRIC_ENABLED, enabled).apply()
        _uiState.update { it.copy(isBiometricEnabled = enabled) }
    }

    fun toggleScreenshotsDisabled(disabled: Boolean) {
        prefs.edit().putBoolean(PREF_DISABLE_SCREENSHOTS, disabled).apply()
        _uiState.update { it.copy(isScreenshotsDisabled = disabled) }
    }

    fun toggleReadReceipts(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_READ_RECEIPTS, enabled).apply()
        _uiState.update { it.copy(isReadReceiptsEnabled = enabled) }
    }

    /**
     * Проверяет старый PIN. Принимает CharArray.
     */
    fun verifyOldPin(pinChars: CharArray): Boolean {
        val userId = authRepository.getCurrentUserId() ?: sessionManager.getActiveProfileId()
        return try {
            keyStorageManager.verifyPin(userId, pinChars)
        } finally {
            SecureDataHandler.wipe(pinChars)
        }
    }

    /**
     * Устанавливает новый PIN. Принимает CharArray.
     */
    fun setPin(pinChars: CharArray?) {
        val userId = authRepository.getCurrentUserId() ?: sessionManager.getActiveProfileId()
        if (pinChars == null) {
            keyStorageManager.removePin(userId)
        } else {
            keyStorageManager.savePin(userId, pinChars)
            SecureDataHandler.wipe(pinChars)
        }
        _uiState.update { it.copy(isPinSet = pinChars != null) }
        val profilesWithPin = sessionManager.getProfilesIds().filter { keyStorageManager.hasPin(it) }.toSet()
        _uiState.update { it.copy(profilesWithPin = profilesWithPin) }
    }
}
