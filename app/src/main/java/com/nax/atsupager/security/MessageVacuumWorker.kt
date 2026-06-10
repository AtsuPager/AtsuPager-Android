package com.nax.atsupager.security

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MessageVacuumWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageLifecycleManager: MessageLifecycleManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            messageLifecycleManager.performMaintenance()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
