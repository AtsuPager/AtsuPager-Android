package com.nax.atsupager.ui.screens.lock

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.security.SecureDataHandler
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class LockUiState(
    val pinLength: Int = 0,
    val isBiometricEnabled: Boolean = false,
    val isUnlocked: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class LockViewModel @Inject constructor(
    private val keyStorageManager: KeyStorageManager,
    private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState = _uiState.asStateFlow()

    private val pinBuffer = CharArray(8)
    private var currentPinLength = 0

    private val userId: String
        get() = prefs.getString(SettingsViewModel.PREF_USER_ID, "default_user") ?: "default_user"

    init {
        val biometricEnabled = prefs.getBoolean(SettingsViewModel.PREF_BIOMETRIC_ENABLED, false)
        _uiState.update { it.copy(isBiometricEnabled = biometricEnabled) }
    }

    fun onPinInput(char: Char) {
        if (currentPinLength < 8) {
            pinBuffer[currentPinLength] = char
            currentPinLength++
            _uiState.update { it.copy(pinLength = currentPinLength, error = null) }
            if (currentPinLength >= 4) {
                checkPin()
            }
        }
    }

    fun onBackspace() {
        if (currentPinLength > 0) {
            currentPinLength--
            pinBuffer[currentPinLength] = '\u0000'
            _uiState.update { it.copy(pinLength = currentPinLength) }
        }
    }

    private fun checkPin() {
        val pinToVerify = CharArray(currentPinLength)
        System.arraycopy(pinBuffer, 0, pinToVerify, 0, currentPinLength)

        if (keyStorageManager.verifyPin(userId, pinToVerify)) {
            _uiState.update { it.copy(isUnlocked = true) }
            clearPinBuffer()
        } else if (currentPinLength >= 8) {
            _uiState.update { it.copy(pinLength = 0, error = "Wrong PIN") }
            clearPinBuffer()
        }
        SecureDataHandler.wipe(pinToVerify)
    }

    private fun clearPinBuffer() {
        SecureDataHandler.wipe(pinBuffer)
        currentPinLength = 0
        _uiState.update { it.copy(pinLength = 0) }
    }
    
    fun onBiometricSuccess() {
        _uiState.update { it.copy(isUnlocked = true) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        clearPinBuffer()
    }
}
