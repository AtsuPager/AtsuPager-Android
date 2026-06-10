package com.nax.atsupager.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.*
import com.nax.atsupager.data.db.AppDatabase
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.ui.screens.settings.MessageTTL
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageLifecycleManager @Inject constructor(
    private val messageDao: MessageDao,
    private val appDatabase: AppDatabase,
    private val prefs: SharedPreferences,
    @ApplicationContext private val context: Context
) {
    private val TAG = "MessageLifecycle"

    /**
     * Performed by the "Janitor" (MessageCleanupWorker) every 15 minutes.
     * Deletes data and files only, without blocking the database.
     */
    suspend fun performPrivacyCleanup() = withContext(Dispatchers.IO) {
        deleteExpiredOnly()
        Log.d(TAG, "Background privacy cleanup finished (files deleted).")
    }

    /**
     * Performed by the "Vacuum Truck" (MessageVacuumWorker) according to the settings schedule.
     * Performs heavy physical cleanup (VACUUM).
     */
    suspend fun performMaintenance() = withContext(Dispatchers.IO) {
        try {
            // Clean up records once more just in case before vacuuming
            deleteExpiredOnly()
            appDatabase.openHelper.writableDatabase.execSQL("VACUUM")
            Log.d(TAG, "Background maintenance (VACUUM) completed.")
        } catch (e: Exception) {
            Log.e(TAG, "Maintenance error", e)
        }
    }

    /**
     * Method for visual deletion in chat.
     */
    suspend fun runCleanup() = performPrivacyCleanup()

    /**
     * Deletes only files and database records.
     */
    suspend fun deleteExpiredOnly(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ttlName = prefs.getString(SettingsViewModel.PREF_MESSAGE_TTL, MessageTTL.OFF.name)
            val ttl = try {
                MessageTTL.valueOf(ttlName ?: MessageTTL.OFF.name)
            } catch (e: Exception) {
                MessageTTL.OFF
            }

            if (ttl == MessageTTL.OFF) return@withContext false

            val threshold = System.currentTimeMillis() - ttl.millis
            val expiredMessages = messageDao.getExpiredMessages(threshold)

            if (expiredMessages.isNotEmpty()) {
                expiredMessages.forEach { msg ->
                    msg.localFilePath?.let { path ->
                        try {
                            val file = File(path)
                            if (file.exists()) file.delete()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting file: $path", e)
                        }
                    }
                }
                messageDao.deleteExpiredMessages(threshold)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete error", e)
        }
        return@withContext false
    }

    /**
     * Schedules both tasks: the frequent "Janitor" and the scheduled "Vacuum Truck".
     */
    fun schedulePeriodicCleanup() {
        val ttlName = prefs.getString(SettingsViewModel.PREF_MESSAGE_TTL, MessageTTL.OFF.name)
        val ttl = try {
            MessageTTL.valueOf(ttlName ?: MessageTTL.OFF.name)
        } catch (e: Exception) {
            MessageTTL.OFF
        }

        if (ttl == MessageTTL.OFF) {
            WorkManager.getInstance(context).cancelUniqueWork("PrivacyCleanupWork")
            WorkManager.getInstance(context).cancelUniqueWork("DeepMaintenanceWork")
            return
        }

        // 1. SCHEDULE THE JANITOR (File deletion every 15 minutes)
        val privacyRequest = PeriodicWorkRequestBuilder<MessageCleanupWorker>(
            15, TimeUnit.MINUTES
        ).build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "PrivacyCleanupWork",
            ExistingPeriodicWorkPolicy.KEEP,
            privacyRequest
        )

        // 2. SCHEDULE THE VACUUM TRUCK (VACUUM according to settings schedule)
        val maintenanceRequest = PeriodicWorkRequestBuilder<MessageVacuumWorker>(
            ttl.millis, TimeUnit.MILLISECONDS
        )
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "DeepMaintenanceWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            maintenanceRequest
        )
        
        Log.d(TAG, "Work scheduled: Privacy (15m), Maintenance (${ttl.name})")
    }
}
