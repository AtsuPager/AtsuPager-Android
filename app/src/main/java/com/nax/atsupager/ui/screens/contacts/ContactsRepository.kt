package com.nax.atsupager.ui.screens.contacts

import com.nax.atsupager.data.db.Contact
import com.nax.atsupager.data.db.ContactDao
import com.nax.atsupager.data.db.UserDao
import com.nax.atsupager.data.model.User
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsRepository @Inject constructor(
    private val contactDao: ContactDao,
    private val userDao: UserDao
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
        contactDao.deleteContact(userId)
        // Удаляем пользователя полностью, чтобы при повторном звонке/добавлении
        // имя не "всплывало" из кэша таблицы users.
        userDao.deleteUserById(userId)
    }
}
