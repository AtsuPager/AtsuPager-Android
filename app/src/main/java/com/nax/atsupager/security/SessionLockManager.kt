package com.nax.atsupager.security

import android.content.SharedPreferences
import com.nax.atsupager.data.network.AuthRepository
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionLockManager @Inject constructor(
    private val prefs: SharedPreferences,
    private val keyStorageManager: KeyStorageManager,
    private val authRepository: AuthRepository
) {
    private val _isLocked = MutableStateFlow(false)
    val isLocked = _isLocked.asStateFlow()

    private var lastBackgroundTime: Long = 0
    private val lockTimeout = 30000L // 30 seconds

    fun onAppForeground() {
        val isLockEnabled = prefs.getBoolean(SettingsViewModel.PREF_APP_LOCK_ENABLED, false)
        val userId = authRepository.getCurrentUserId()
        
        // If lock is disabled OR PIN code is not set, do not lock
        if (!isLockEnabled || userId == null || !keyStorageManager.hasPin(userId)) {
            _isLocked.value = false
            return
        }

        if (lastBackgroundTime != 0L && (System.currentTimeMillis() - lastBackgroundTime) > lockTimeout) {
            _isLocked.value = true
        }
    }

    fun onAppBackground() {
        lastBackgroundTime = System.currentTimeMillis()
    }

    fun unlock() {
        _isLocked.value = false
        lastBackgroundTime = 0
    }
    
    fun setLocked(locked: Boolean) {
        _isLocked.value = locked
    }
}
