package com.nax.atsupager.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.*

/**
 * Clipboard clearing delay options.
 */
enum class ClipboardClearDelay(val millis: Long) {
    THIRTY_SECONDS(30_000L),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(300_000L),
    NEVER(-1L)
}

/**
 * Module for secure clipboard operations.
 * Provides data labeling as sensitive and multi-layered clearing.
 */
object ClipboardSecurityManager {

    private const val TAG = "ClipboardSecurity"
    private const val PREF_NAME = "AtsuPagerPrefs"
    private const val KEY_CLIPBOARD_EXPIRY = "clipboard_expiry_time"
    
    private var clearJob: Job? = null
    // Use a dedicated Dispatchers.Default so the coroutine doesn't stall when Main is suspended
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Copies text to the clipboard with a privacy label.
     */
    fun copyToClipboard(
        context: Context,
        label: String,
        text: String,
        clearDelay: Long = 60_000L
    ) {
        val appContext = context.applicationContext
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        val clipData = ClipData.newPlainText(label, text).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                description.extras = PersistableBundle().apply {
                    // IS_SENSITIVE tells keyboards (Gboard, etc.) not to save text to history
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
        }
        
        try {
            clipboard.setPrimaryClip(clipData)
            Log.d(TAG, "Text copied. Clear scheduled in $clearDelay ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }

        // Cancel all current clearing tasks
        clearJob?.cancel()
        ClipboardClearWorker.cancel(appContext)

        if (clearDelay > 0) {
            val expiryTime = System.currentTimeMillis() + clearDelay
            saveExpiryTime(appContext, expiryTime)

            // Layer 1: Coroutine for fast clearing (works while the process is alive)
            clearJob = scope.launch {
                delay(clearDelay)
                performClear(appContext)
            }

            // Layer 2: WorkManager (backup for systems with allowed background)
            ClipboardClearWorker.enqueue(appContext, clearDelay)
        } else {
            // If "Never", reset the expiry time
            saveExpiryTime(appContext, 0)
        }
    }

    /**
     * Checks and clears the clipboard if the time has expired.
     * Called when the app returns to focus (AppLifecycleObserver and MainActivity).
     */
    fun checkAndClearIfExpired(context: Context) {
        val appContext = context.applicationContext
        val expiryTime = getExpiryTime(appContext)
        if (expiryTime > 0 && System.currentTimeMillis() >= expiryTime) {
            Log.d(TAG, "Expiry time reached while app was inactive. Clearing now.")
            performClear(appContext)
        }
    }

    /**
     * Forced clipboard clearing.
     */
    fun clearClipboard(context: Context) {
        clearJob?.cancel()
        val appContext = context.applicationContext
        ClipboardClearWorker.cancel(appContext)
        performClear(appContext)
    }

    /**
     * Performs physical clipboard clearing.
     */
    fun performClear(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            // Fixed: use if-else to avoid calling "setPrimaryClip" (and system toast) on newer Android
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // On Android 9+, this is the standard and quiet way to clear
                clipboard.clearPrimaryClip()
                Log.d(TAG, "Clipboard cleared via clearPrimaryClip().")
            } else {
                // Only for older versions, use wiping with an empty clip
                val emptyClip = ClipData.newPlainText("", "").apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        description.extras = PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                }
                clipboard.setPrimaryClip(emptyClip)
                Log.d(TAG, "Clipboard cleared via empty clip (Legacy).")
            }
            
            // Reset the flag in settings ONLY after a successful operation
            saveExpiryTime(context, 0)
        } catch (e: Exception) {
            // SecurityException will occur in the background on Android 10+.
            // In this case, the expiryTime flag will remain and clearing will work upon returning to the app.
            Log.w(TAG, "Background clear restricted by Android. Will retry on app focus.")
        }
    }

    private fun saveExpiryTime(context: Context, time: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CLIPBOARD_EXPIRY, time)
            .apply()
    }

    private fun getExpiryTime(context: Context): Long {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CLIPBOARD_EXPIRY, 0)
    }
}
