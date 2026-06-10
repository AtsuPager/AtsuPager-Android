package com.nax.atsupager.security

import android.app.Activity
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Module for managing the application's visual privacy.
 * Encapsulates logic for screenshot protection and task switcher masking.
 */
object VisualPrivacyManager {

    private val _isSecureModeEnabled = MutableStateFlow(true)
    val isSecureModeEnabled = _isSecureModeEnabled.asStateFlow()

    /**
     * Dynamically manages the window's secure flag.
     * When enabled (isSecure = true), the system blocks screenshots,
     * screen recording, and hides content in the recent tasks list.
     */
    fun updateSecureFlag(activity: Activity, isSecure: Boolean) {
        _isSecureModeEnabled.value = isSecure
        activity.runOnUiThread {
            if (isSecure) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    /**
     * Checks if the privacy overlay should be displayed.
     * Returns true if the app is in the background and secure mode is enabled.
     */
    fun shouldShowPrivacyMask(isInForeground: Boolean): Boolean {
        return !isInForeground && _isSecureModeEnabled.value
    }
}
