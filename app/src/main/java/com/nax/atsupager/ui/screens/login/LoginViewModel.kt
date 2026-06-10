package com.nax.atsupager.ui.screens.login

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nax.atsupager.R
import com.nax.atsupager.data.manager.SessionManager
import com.nax.atsupager.data.network.AuthRepository
import com.nax.atsupager.security.SecureDataHandler
import com.nax.atsupager.webrtc.NtfyService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState
    data class Success(
        val userId: String, 
        val username: String,
        val mnemonic: List<String>? = null
    ) : LoginUiState
    data class Error(val message: String) : LoginUiState
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _currentLanguage = MutableStateFlow(getCurrentLangCode())
    val currentLanguage = _currentLanguage.asStateFlow()

    private fun getCurrentLangCode(): String {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        return if (currentLocales.isEmpty) "system" else currentLocales.toLanguageTags()
    }

    fun changeLanguage(langCode: String) {
        val appLocale: LocaleListCompat = if (langCode == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(langCode)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)
        _currentLanguage.value = langCode
    }

    fun createIdentity(username: String) {
        if (username.isBlank()) {
            _uiState.update { LoginUiState.Error(context.getString(R.string.error_empty_username)) }
            return
        }
        
        _uiState.update { LoginUiState.Loading }
        viewModelScope.launch {
            authRepository.createNewIdentity(username).fold(
                onSuccess = { userId ->
                    val mnemonic = authRepository.getMnemonic(userId)
                    _uiState.update { LoginUiState.Success(userId, username, mnemonic) }
                },
                onFailure = { error ->
                    _uiState.update { LoginUiState.Error(error.message ?: context.getString(R.string.error_unknown)) }
                }
            )
        }
    }

    /**
     * Импортирует личность. Принимает CharArray для минимизации String Ghosts в памяти.
     */
    fun importIdentity(mnemonicChars: CharArray, username: String) {
        if (username.isBlank()) {
            _uiState.update { LoginUiState.Error(context.getString(R.string.error_empty_username)) }
            SecureDataHandler.wipe(mnemonicChars)
            return
        }

        // Базовая проверка формата без создания промежуточных строк
        var spaceCount = 0
        for (c in mnemonicChars) { if (c == ' ') spaceCount++ }
        
        if (spaceCount != 11 && mnemonicChars.isNotEmpty()) {
            _uiState.update { LoginUiState.Error(context.getString(R.string.error_mnemonic_size)) }
            SecureDataHandler.wipe(mnemonicChars)
            return
        }

        _uiState.update { LoginUiState.Loading }
        viewModelScope.launch {
            // Передаем CharArray в репозиторий, который сам его затрет
            authRepository.importIdentity(mnemonicChars, username).fold(
                onSuccess = { userId ->
                    _uiState.update { LoginUiState.Success(userId, username) }
                },
                onFailure = { error ->
                    _uiState.update { LoginUiState.Error(error.message ?: context.getString(R.string.error_unknown)) }
                }
            )
        }
    }

    fun completeLogin(userId: String) {
        NtfyService.start(context)
        sessionManager.switchProfile(userId)
    }

    fun resetToIdle() {
        _uiState.update { LoginUiState.Idle }
    }
}
