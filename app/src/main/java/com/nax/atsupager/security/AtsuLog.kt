package com.nax.atsupager.security

import android.util.Log
import com.nax.atsupager.BuildConfig

/**
 * Secure logging wrapper.
 * In release builds, it completely ignores calls,
 * preventing data leakage into Logcat.
 */
object AtsuLog {
    private const val GLOBAL_TAG = "AtsuPager"
    
    // Use BuildConfig.DEBUG to determine the build mode.
    private val isLogEnabled = BuildConfig.DEBUG && !isProduction()

    private fun isProduction(): Boolean {
        // Logs will be disabled in release builds
        return !BuildConfig.DEBUG
    }

    fun d(tag: String, msg: String) {
        if (isLogEnabled) Log.d(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (isLogEnabled) {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        }
    }

    fun i(tag: String, msg: String) {
        if (isLogEnabled) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (isLogEnabled) {
            if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        }
    }
}
