package com.nax.atsupager.data.network

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nax.atsupager.data.db.ContactDao
import com.nax.atsupager.data.db.UserDao
import com.nax.atsupager.data.model.User
import com.nax.atsupager.data.db.Contact
import com.nax.atsupager.security.Bip39Manager
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.security.SecureDataHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UserRepository"
private const val MARKER_MNEMONIC: Byte = 0x01
private const val MARKER_PASSWORD: Byte = 0x02

@Singleton
class UserRepository @Inject constructor(
    private val sharedPreferences: SharedPreferences,
    private val userDao: UserDao,
    private val contactDao: ContactDao,
    private val encryptionManager: EncryptionManager,
    private val keyStorageManager: KeyStorageManager,
    private val bip39Manager: Bip39Manager
) {
    private val gson = Gson()

    fun getCurrentUserId(): String? = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null)

    fun getCurrentUserIdSync(): String? = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null)

    suspend fun addContactByIdentity(identity: String, customName: String? = null): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val parts = identity.split("@")
                val address = if (parts.size == 2) parts[1] else identity
                val username = customName ?: (if (parts.size == 2) parts[0] else "User_${identity.takeLast(4)}")
                
                val newUser = User(id = address, username = username, publicKey = null)
                userDao.insertAll(listOf(newUser))
                contactDao.insertContact(Contact(address))
                true
            } catch (e: Exception) { false }
        }
    }

    suspend fun addContact(userId: String, name: String? = null) {
        withContext(Dispatchers.IO) {
            if (name != null) {
                val user = userDao.getUserById(userId)
                userDao.insertAll(listOf(User(id = userId, username = name, publicKey = user?.publicKey)))
            }
            contactDao.insertContact(Contact(userId))
        }
    }

    suspend fun isContact(userId: String): Boolean = withContext(Dispatchers.IO) {
        contactDao.isContact(userId)
    }

    suspend fun updatePublicKey(address: String, publicKey: String) {
        withContext(Dispatchers.IO) {
            val user = userDao.getUserById(address)
            if (user != null) {
                userDao.insertAll(listOf(user.copy(publicKey = publicKey)))
            } else {
                userDao.insertAll(listOf(User(id = address, username = "User_${address.takeLast(4)}", publicKey = publicKey)))
            }
        }
    }

    suspend fun updateUsername(address: String, username: String, fromNetwork: Boolean = false) {
        withContext(Dispatchers.IO) {
            val user = userDao.getUserById(address)
            val isContact = contactDao.isContact(address)
            val isDefaultName = user?.username?.startsWith("User_") ?: true
            
            if (fromNetwork && isContact && !isDefaultName) return@withContext

            if (user != null) {
                if (user.username != username) {
                    userDao.insertAll(listOf(user.copy(username = username)))
                }
            } else {
                userDao.insertAll(listOf(User(id = address, username = username, publicKey = null)))
            }
        }
    }

    suspend fun renameContact(userId: String, newName: String) {
        updateUsername(userId, newName, fromNetwork = false)
    }

    suspend fun getUser(userId: String): User? = withContext(Dispatchers.IO) {
        userDao.getUserById(userId) ?: if (userId.length > 20) {
            val u = User(id = userId, username = "User_${userId.takeLast(4)}", publicKey = null)
            userDao.insertAll(listOf(u))
            u
        } else null
    }

    suspend fun getContacts(): List<User> = withContext(Dispatchers.IO) {
        val contactIds = contactDao.getAllContacts().map { it.userId }
        if (contactIds.isNotEmpty()) userDao.getUsersByIds(contactIds) else emptyList()
    }

    /**
     * Экспорт контактов с использованием CharArray пароля.
     */
    suspend fun exportContacts(outputStream: OutputStream, password: CharArray?): Boolean = withContext(Dispatchers.IO) {
        try {
            val contacts = getContacts()
            val jsonData = gson.toJson(contacts).toByteArray()

            if (password != null && password.isNotEmpty()) {
                val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                val key = encryptionManager.deriveKeyFromPassword(password, salt).encoded
                val encrypted = encryptionManager.encryptWithRawKey(jsonData, key)
                outputStream.write(MARKER_PASSWORD.toInt())
                outputStream.write(salt)
                outputStream.write(encrypted)
                SecureDataHandler.wipe(password) // Очищаем пароль сразу
            } else {
                val userId = getCurrentUserId() ?: return@withContext false
                val mnemonic = keyStorageManager.getMnemonicAsCharArray(userId) ?: return@withContext false
                try {
                    val seed = bip39Manager.toSeed(mnemonic)
                    val key = MessageDigest.getInstance("SHA-256").digest(seed + "CONTACTS_BACKUP".toByteArray())
                    val encrypted = encryptionManager.encryptWithRawKey(jsonData, key)
                    outputStream.write(MARKER_MNEMONIC.toInt())
                    outputStream.write(encrypted)
                } finally {
                    SecureDataHandler.wipe(mnemonic)
                }
            }
            outputStream.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            false
        } finally {
            outputStream.close()
        }
    }

    /**
     * Импорт контактов с использованием CharArray пароля.
     */
    suspend fun importContacts(inputStream: InputStream, password: CharArray?): Int = withContext(Dispatchers.IO) {
        try {
            val marker = inputStream.read()
            val decryptedData: ByteArray? = when (marker.toByte()) {
                MARKER_MNEMONIC -> {
                    val userId = getCurrentUserId() ?: return@withContext 2
                    val mnemonic = keyStorageManager.getMnemonicAsCharArray(userId) ?: return@withContext 2
                    try {
                        val seed = bip39Manager.toSeed(mnemonic)
                        val key = MessageDigest.getInstance("SHA-256").digest(seed + "CONTACTS_BACKUP".toByteArray())
                        encryptionManager.decryptWithRawKey(inputStream.readBytes(), key)
                    } finally {
                        SecureDataHandler.wipe(mnemonic)
                    }
                }
                MARKER_PASSWORD -> {
                    if (password == null || password.isEmpty()) return@withContext 1
                    val salt = ByteArray(16)
                    if (inputStream.read(salt) != 16) return@withContext 2
                    val key = encryptionManager.deriveKeyFromPassword(password, salt).encoded
                    encryptionManager.decryptWithRawKey(inputStream.readBytes(), key)
                }
                else -> null
            }

            if (decryptedData == null) return@withContext 2

            val listType = object : TypeToken<List<User>>() {}.type
            val importedContacts: List<User> = InputStreamReader(ByteArrayInputStream(decryptedData), StandardCharsets.UTF_8).use {
                gson.fromJson(it, listType)
            }
            SecureDataHandler.wipe(decryptedData)

            importedContacts.forEach { user ->
                userDao.insertAll(listOf(user))
                contactDao.insertContact(Contact(user.id))
            }
            0
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            2
        } finally {
            if (password != null) SecureDataHandler.wipe(password) // Очищаем пароль
            inputStream.close()
        }
    }
    
    suspend fun refreshUsers() {}
    suspend fun forceRefreshContact(userId: String) {}
}
