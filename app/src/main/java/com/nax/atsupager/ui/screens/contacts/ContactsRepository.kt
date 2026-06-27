package com.nax.atsupager.ui.screens.contacts

import com.nax.atsupager.data.db.Contact
import com.nax.atsupager.data.db.ContactDao
import com.nax.atsupager.data.db.UserDao
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.network.AuthRepository
import com.nax.atsupager.data.model.User
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsRepository @Inject constructor(
    private val contactDao: ContactDao,
    private val userDao: UserDao,
    private val messageDao: MessageDao,
    private val authRepository: AuthRepository
) {
    fun getContactsFlow(): Flow<List<User>> = userDao.getAllContactsFlow()

    suspend fun getContacts(): List<User> {
        val contactIds = contactDao.getAllContacts().map { it.userId }
        return if (contactIds.isNotEmpty()) {
            userDao.getUsersByIds(contactIds)
        } else {
            emptyList()
        }
    }

    suspend fun getContact(userId: String): User? {
        return if (contactDao.isContact(userId)) {
            userDao.getUserById(userId)
        } else {
            null
        }
    }

    suspend fun addContact(user: User) {
        userDao.insertAll(listOf(user))
        contactDao.insertContact(Contact(userId = user.id))
    }

    suspend fun renameContact(userId: String, newName: String) {
        val user = userDao.getUserById(userId)
        if (user != null) {
            userDao.insertAll(listOf(user.copy(username = newName)))
        }
    }

    suspend fun isContact(userId: String): Boolean {
        return contactDao.isContact(userId)
    }

    suspend fun deleteContact(userId: String) {
        val currentUserId = authRepository.getCurrentUserId() ?: return
        
        // 1. Находим все сообщения чата, чтобы удалить файлы
        val allMsgs = messageDao.getMessagesForChatSync(currentUserId, userId)
        allMsgs.forEach { msg ->
            msg.localFilePath?.let { path ->
                if (messageDao.getMessageCountByFilePath(path) <= 1) {
                    try { File(path).delete() } catch (_: Exception) {}
                }
            }
        }
        
        // 2. Принудительно удаляем все записи из БД (игнорируя isSaved)
        messageDao.forceDeleteAllMessagesForChat(currentUserId, userId)
        
        // 3. Удаляем самого контакта
        contactDao.deleteContact(userId)
        userDao.deleteUserById(userId)
    }
}
