package com.nax.atsupager.security

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker for guaranteed clipboard clearing.
 * Uses centralized logic from ClipboardSecurityManager.
 */
@HiltWorker
class ClipboardClearWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Use the centralized clearing method that accounts for the Android version
            // and doesn't trigger unnecessary system notifications.
            ClipboardSecurityManager.performClear(applicationContext)
            
            Log.d("ClipboardWorker", "Clipboard cleanup task executed.")
            Result.success()
        } catch (e: Exception) {
            Log.e("ClipboardWorker", "Failed to clear clipboard in background", e)
            Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "clipboard_clear_work"

        fun enqueue(context: Context, delayMillis: Long) {
            val workRequest = OneTimeWorkRequestBuilder<ClipboardClearWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME)
        }
    }
}
