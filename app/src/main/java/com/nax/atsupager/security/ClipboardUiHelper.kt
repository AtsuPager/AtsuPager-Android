package com.nax.atsupager.security

import android.content.Context
import android.os.Build
import androidx.compose.material3.SnackbarHostState
import com.nax.atsupager.R
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Helper for synchronizing system copying and UI notifications.
 * Reads clearing delay settings from SharedPreferences.
 */
object ClipboardUiHelper {
    private var lastJob: Job? = null

    /**
     * Copies text and triggers a coordinated notification chain based on user settings.
     */
    fun copyWithNotification(
        context: Context,
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        label: String,
        text: String
    ) {
        val prefs = context.getSharedPreferences("AtsuPagerPrefs", Context.MODE_PRIVATE)
        val delayName = prefs.getString(SettingsViewModel.PREF_CLIPBOARD_CLEAR_DELAY, ClipboardClearDelay.ONE_MINUTE.name)
        val clearDelay = try {
            ClipboardClearDelay.valueOf(delayName ?: ClipboardClearDelay.ONE_MINUTE.name)
        } catch (e: Exception) {
            ClipboardClearDelay.ONE_MINUTE
        }

        // 1. Perform secure copying with the required delay
        ClipboardSecurityManager.copyToClipboard(context, label, text, clearDelay.millis)

        // 2. Manage UI notifications (Snackbar)
        lastJob?.cancel()
        
        lastJob = scope.launch {
            // Show "Copied"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                snackbarHostState.showSnackbar(context.getString(R.string.text_copied))
            }

            // If clearing is enabled, wait and show the clearing notification
            if (clearDelay != ClipboardClearDelay.NEVER) {
                delay(clearDelay.millis)
                snackbarHostState.showSnackbar(context.getString(R.string.clipboard_cleared))
            }
        }
    }
}
