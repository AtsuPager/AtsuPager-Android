package com.nax.atsupager.data.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nax.atsupager.data.manager.SessionManager
import com.nax.atsupager.security.Bip39Manager
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.security.SecureDataHandler
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthRepository"

@Singleton
class AuthRepository @Inject constructor(
    private val keyStorageManager: KeyStorageManager,
    private val bip39Manager: Bip39Manager,
    private val sharedPreferences: SharedPreferences,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context
) {

    companion object {
        const val KEY_USER_ID = "user_id"
    }

    fun getCurrentUserId(): String? {
        return sharedPreferences.getString(KEY_USER_ID, null)
    }

    /**
     * Creates a new identity and performs a forced data write.
     */
    suspend fun createNewIdentity(username: String): Result<String> = withContext(Dispatchers.IO) {
        val mnemonic = bip39Manager.generateMnemonic()
        try {
            val btcAddress = keyStorageManager.getBitcoinAddressFromMnemonic(mnemonic)
            keyStorageManager.saveMnemonic(btcAddress, mnemonic)
            
            val profilePrefs = context.getSharedPreferences("AtsuProfilePrefs_$btcAddress", Context.MODE_PRIVATE)
            profilePrefs.edit()
                .putString(KEY_USER_ID, btcAddress)
                .putString(SettingsViewModel.PREF_LOGIN_NAME, username)
                .commit()

            sessionManager.addProfileToList(btcAddress, username)
            
            Log.d(TAG, "Created new identity: $btcAddress")
            Result.success(btcAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create identity", e)
            Result.failure(e)
        } finally {
            SecureDataHandler.wipe(mnemonic)
        }
    }

    /**
     * Imports an identity from a CharArray.
     */
    suspend fun importIdentity(mnemonicChars: CharArray, username: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // For BIP39 validation, the bitcoinj library still needs a list of strings,
            // but we do this as quickly and locally as possible.
            val mnemonicStr = String(mnemonicChars)
            val words = mnemonicStr.split(" ")
            
            if (!bip39Manager.validateMnemonic(words)) {
                return@withContext Result.failure(Exception("Invalid Mnemonic"))
            }
            
            val btcAddress = keyStorageManager.getBitcoinAddressFromMnemonic(mnemonicChars)
            keyStorageManager.saveMnemonic(btcAddress, mnemonicChars)
            
            val profilePrefs = context.getSharedPreferences("AtsuProfilePrefs_$btcAddress", Context.MODE_PRIVATE)
            profilePrefs.edit()
                .putString(KEY_USER_ID, btcAddress)
                .putString(SettingsViewModel.PREF_LOGIN_NAME, username)
                .commit()

            sessionManager.addProfileToList(btcAddress, username)
            
            Result.success(btcAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import identity", e)
            Result.failure(e)
        } finally {
            SecureDataHandler.wipe(mnemonicChars)
        }
    }

    fun getMnemonic(userId: String): List<String>? {
        val chars = keyStorageManager.getMnemonicAsCharArray(userId) ?: return null
        return try {
            String(chars).split(" ")
        } finally {
            SecureDataHandler.wipe(chars)
        }
    }
}
