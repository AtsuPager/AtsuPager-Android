package com.nax.atsupager.ui.screens.splash

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nax.atsupager.data.manager.SessionManager
import com.nax.atsupager.data.network.AuthRepository
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SplashDestination {
    object Loading : SplashDestination()
    object Login : SplashDestination()
    object Main : SplashDestination()
    object Lock : SplashDestination()
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val keyStorageManager: KeyStorageManager,
    private val prefs: SharedPreferences // Это будут профильные префы
) : ViewModel() {

    private val _destination = MutableStateFlow<SplashDestination>(SplashDestination.Loading)
    val destination = _destination.asStateFlow()

    init {
        checkDestination()
    }

    private fun checkDestination() {
        viewModelScope.launch {
            delay(800) 
            
            val activeId = sessionManager.getActiveProfileId()
            
            // Если профиль не выбран или в текущем профиле нет ID пользователя — идем на логин
            val currentUserId = prefs.getString(AuthRepository.KEY_USER_ID, null)
            
            if (activeId == SessionManager.NO_PROFILE || currentUserId == null) {
                _destination.value = SplashDestination.Login
            } else {
                // Если профиль выбран и инициализирован
                navigateToMainOrLock(activeId)
            }
        }
    }

    private fun navigateToMainOrLock(userId: String) {
        val isLockEnabled = prefs.getBoolean(SettingsViewModel.PREF_APP_LOCK_ENABLED, false)
        // Проверяем наличие PIN для конкретного активного пользователя
        if (isLockEnabled && keyStorageManager.hasPin(userId)) {
            _destination.value = SplashDestination.Lock
        } else {
            _destination.value = SplashDestination.Main
        }
    }
}
