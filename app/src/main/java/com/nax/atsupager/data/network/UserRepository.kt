package com.nax.atsupager.data.network

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.nax.atsupager.data.db.*
import com.nax.atsupager.data.model.User
import com.nax.atsupager.data.model.ProfileBackup
import com.nax.atsupager.security.Bip39Manager
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.security.SecureDataHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val encryptionManager: EncryptionManager,
    private val keyStorageManager: KeyStorageManager,
    private val bip39Manager: Bip39Manager
) {
    private val gson = Gson()

    fun getCurrentUserId(): String? = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null)
    fun getCurrentUserIdSync(): String? = sharedPreferences.getString(AuthRepository.KEY_USER_ID, null)

    suspend fun addContactByIdentity(identity: String, customName: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val parts = identity.split("@")
            val address = if (parts.size == 2) parts[1] else identity
            val username = customName ?: (if (parts.size == 2) parts[0] else "User_${identity.takeLast(4)}")
            userDao.insertAll(listOf(User(id = address, username = username, publicKey = null)))
            contactDao.insertContact(Contact(address))
            true
        } catch (e: Exception) { false }
    }

    suspend fun addContact(userId: String, name: String? = null) {
        withContext(Dispatchers.IO) {
            val user = userDao.getUserById(userId)
            val username = name ?: user?.username ?: "User_${userId.takeLast(4)}"
            userDao.insertAll(listOf(User(id = userId, username = username, publicKey = user?.publicKey)))
            contactDao.insertContact(Contact(userId))
        }
    }

    suspend fun isContact(userId: String): Boolean = withContext(Dispatchers.IO) { contactDao.isContact(userId) }

    suspend fun updateUserInfo(
        address: String,
        username: String? = null,
        publicKey: String? = null,
        fromNetwork: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val user = userDao.getUserById(address)
        val isContact = contactDao.isContact(address)
        
        val currentName = user?.username ?: "User_${address.takeLast(4)}"
        var finalName = currentName
        
        if (!username.isNullOrBlank()) {
            val isCurrentPlaceholder = currentName.startsWith("User_") || currentName == address
            val isNewNamePlaceholder = username.startsWith("User_") || username == address
            
            if (!isNewNamePlaceholder) {
                if (isCurrentPlaceholder || !isContact) {
                    if (currentName != username) {
                        finalName = username
                    }
                }
            }
        }
        
        val finalPublicKey = publicKey ?: user?.publicKey
        
        if (user == null || user.username != finalName || user.publicKey != finalPublicKey) {
            userDao.insertAll(listOf(User(
                id = address,
                username = finalName,
                publicKey = finalPublicKey,
                isMuted = user?.isMuted ?: false
            )))
        }
    }

    suspend fun updatePublicKey(address: String, publicKey: String) {
        updateUserInfo(address, publicKey = publicKey)
    }

    suspend fun updateUsername(address: String, username: String, fromNetwork: Boolean = false) {
        updateUserInfo(address, username = username, fromNetwork = fromNetwork)
    }

    suspend fun getUser(userId: String): User? = withContext(Dispatchers.IO) {
        userDao.getUserById(userId)
    }

    suspend fun getContacts(): List<User> = withContext(Dispatchers.IO) {
        val contactIds = contactDao.getAllContacts().map { it.userId }
        if (contactIds.isNotEmpty()) userDao.getUsersByIds(contactIds) else emptyList()
    }

    suspend fun exportContacts(outputStream: OutputStream, password: CharArray?, includeHistory: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            val backup = ProfileBackup(
                users = userDao.getAllUsers(),
                contactIds = contactDao.getAllContacts().map { it.userId },
                groups = groupDao.getAllGroupsSync(),
                groupMembers = groupDao.getAllMembersSync(),
                messages = if (includeHistory) messageDao.getAllMessagesSync() else null,
                settings = sharedPreferences.all
            )
            val jsonData = gson.toJson(backup).toByteArray()
            if (password != null && password.isNotEmpty()) {
                val salt = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
                val key = encryptionManager.deriveKeyFromPassword(password, salt).encoded
                val encrypted = encryptionManager.encryptWithRawKey(jsonData, key)
                outputStream.write(MARKER_PASSWORD.toInt()); outputStream.write(salt); outputStream.write(encrypted)
            } else {
                val userId = getCurrentUserId() ?: return@withContext false
                val mnemonic = keyStorageManager.getMnemonicAsCharArray(userId) ?: return@withContext false
                try {
                    val seed = bip39Manager.toSeed(mnemonic)
                    val key = MessageDigest.getInstance("SHA-256").digest(seed + "PROFILE_BACKUP_V2".toByteArray())
                    val encrypted = encryptionManager.encryptWithRawKey(jsonData, key)
                    outputStream.write(MARKER_MNEMONIC.toInt()); outputStream.write(encrypted)
                } finally { SecureDataHandler.wipe(mnemonic) }
            }
            outputStream.flush(); true
        } catch (e: Exception) { false } finally { outputStream.close() }
    }

    suspend fun importContacts(inputStream: InputStream, password: CharArray?): Int = withContext(Dispatchers.IO) {
        try {
            val marker = inputStream.read()
            val decryptedData: ByteArray? = when (marker.toByte()) {
                MARKER_MNEMONIC -> {
                    val userId = getCurrentUserId() ?: return@withContext 2
                    val mnemonic = keyStorageManager.getMnemonicAsCharArray(userId) ?: return@withContext 2
                    try {
                        val seed = bip39Manager.toSeed(mnemonic)
                        val key = MessageDigest.getInstance("SHA-256").digest(seed + "PROFILE_BACKUP_V2".toByteArray())
                        encryptionManager.decryptWithRawKey(inputStream.readBytes(), key)
                    } finally { SecureDataHandler.wipe(mnemonic) }
                }
                MARKER_PASSWORD -> {
                    if (password == null || password.isEmpty()) return@withContext 1
                    val salt = ByteArray(16); if (inputStream.read(salt) != 16) return@withContext 2
                    val key = encryptionManager.deriveKeyFromPassword(password, salt).encoded
                    encryptionManager.decryptWithRawKey(inputStream.readBytes(), key)
                }
                else -> null
            }

            if (decryptedData == null) return@withContext 2

            val json = String(decryptedData, StandardCharsets.UTF_8)
            val backup = gson.fromJson(json, ProfileBackup::class.java)
            val currentUserId = getCurrentUserId()

            // 1. Импорт пользователей
            if (backup.users.isNotEmpty()) {
                userDao.insertAll(backup.users)
            }

            // 2. Импорт контактов (исключая самого себя)
            if (backup.contactIds.isNotEmpty()) {
                val contacts = backup.contactIds
                    .filter { it != currentUserId }
                    .map { Contact(it) }
                contactDao.insertContacts(contacts)
            }

            // 3. Импорт групп и участников
            if (backup.groups.isNotEmpty()) {
                groupDao.insertGroups(backup.groups)
            }
            if (backup.groupMembers.isNotEmpty()) {
                groupDao.insertMembers(backup.groupMembers)
            }

            // 4. Импорт сообщений
            backup.messages?.let { messages ->
                if (messages.isNotEmpty()) {
                    messageDao.insertMessages(messages)
                }
            }

            0
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            2
        } finally {
            inputStream.close()
        }
    }

    suspend fun forceRefreshContact(userId: String) {}
}
