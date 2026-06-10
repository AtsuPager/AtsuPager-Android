package com.nax.atsupager.data.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.nax.atsupager.MainActivity
import com.nax.atsupager.security.KeyStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.system.exitProcess
import java.io.File
import java.security.MessageDigest

private const val TAG = "SessionManager"

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("global_prefs") private val globalPrefs: SharedPreferences,
    private val keyStorageManager: KeyStorageManager
) {
    companion object {
        const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        const val KEY_PROFILES_LIST = "profiles_list"
        private const val PREF_NAME_PREFIX = "profile_name_"
        const val NO_PROFILE = "no_profile"
    }

    private val _profilesFlow = MutableStateFlow<Set<String>>(emptySet())
    val profilesFlow = _profilesFlow.asStateFlow()

    init {
        _profilesFlow.value = getProfilesIds()
    }

    fun getActiveProfileId(): String {
        return globalPrefs.getString(KEY_ACTIVE_PROFILE_ID, NO_PROFILE) ?: NO_PROFILE
    }

    private fun getProfileHash(userId: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(userId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
    }

    /**
     * Возвращает путь к папке медиа для конкретного чата.
     */
    fun getMediaDir(chatPartnerId: String, subFolder: String): File {
        val activeId = getActiveProfileId()
        val hash = getProfileHash(activeId)
        val dir = File(context.filesDir, "profiles/$hash/media/$chatPartnerId/$subFolder")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getDisplayActiveId(): String {
        val active = getActiveProfileId()
        return if (active == NO_PROFILE) "No Account" else active
    }

    fun addProfileToList(userId: String, username: String? = null) {
        val profiles = getProfilesIds().toMutableSet()
        profiles.add(userId)
        globalPrefs.edit().putStringSet(KEY_PROFILES_LIST, profiles).commit()

        username?.let {
            globalPrefs.edit().putString(PREF_NAME_PREFIX + userId, it).commit()
        }
        _profilesFlow.value = profiles.toSet()
    }

    fun getProfilesIds(): Set<String> {
        return globalPrefs.getStringSet(KEY_PROFILES_LIST, emptySet())?.toSet() ?: emptySet()
    }

    fun getProfileName(userId: String): String {
        val name = globalPrefs.getString(PREF_NAME_PREFIX + userId, null)
        return name ?: (userId.take(8) + "...")
    }

    fun switchProfile(userId: String) {
        globalPrefs.edit().putString(KEY_ACTIVE_PROFILE_ID, userId).commit()
        restartAppCleanly()
    }

    fun deleteProfile(userId: String) {
        val profiles = getProfilesIds().toMutableSet()
        profiles.remove(userId)

        globalPrefs.edit()
            .putStringSet(KEY_PROFILES_LIST, profiles)
            .remove(PREF_NAME_PREFIX + userId)
            .commit()

        keyStorageManager.purgeProfileData(userId)

        val dbName = getDbNameForProfile(userId)
        context.deleteDatabase(dbName)
        context.deleteDatabase("$dbName-shm")
        context.deleteDatabase("$dbName-wal")

        val prefsName = "AtsuProfilePrefs_$userId"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences(prefsName)
        } else {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        }

        try {
            val hash = getProfileHash(userId)
            val profileDir = File(context.filesDir, "profiles/$hash")
            if (profileDir.exists()) profileDir.deleteRecursively()
        } catch (e: Exception) { }

        _profilesFlow.value = profiles.toSet()

        if (getActiveProfileId() == userId) {
            val next = profiles.firstOrNull() ?: NO_PROFILE
            globalPrefs.edit().putString(KEY_ACTIVE_PROFILE_ID, next).commit()
            restartAppCleanly()
        }
    }

    private fun restartAppCleanly() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        exitProcess(0)
    }

    fun getDbNameForProfile(userId: String): String {
        if (userId == NO_PROFILE) return "atsu_empty.db"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "atsu_user_$hash.db"
    }
}
