package com.nax.atsupager.ui.screens.contacts

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.data.model.User
import com.nax.atsupager.data.network.AuthRepository
import com.nax.atsupager.data.network.UserRepository
import com.nax.atsupager.security.SecureDataHandler
import com.nax.atsupager.webrtc.ActiveCallInfo
import com.nax.atsupager.webrtc.CallStatusManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ChatSummary(
    val contact: User,
    val lastMessage: ChatMessage? = null,
    val unreadCount: Int,
    val isLastMessageFromMe: Boolean,
    val isContact: Boolean = true
)

data class ResolvedContactInfo(
    val address: String,
    val serverName: String?,
    val isResolving: Boolean = false
)

data class ContactsUiState(
    val chats: List<ChatSummary> = emptyList(),
    val filteredChats: List<ChatSummary> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val activeCallInfo: ActiveCallInfo? = null,
    val isCallMinimized: Boolean = false,
    val callDuration: Long = 0L,
    val currentUserId: String = "",
    val isSettingsVisible: Boolean = false,
    val resolvedContact: ResolvedContactInfo? = null,
    val selectedIds: Set<String> = emptySet(),
    val expandedId: String? = null,
    val totalUnreadCount: Int = 0,
    val chatUnreadCount: Int = 0
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val contactsRepository: ContactsRepository,
    private val messageDao: MessageDao,
    private val sharedPreferences: SharedPreferences,
    private val callStatusManager: CallStatusManager,
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val currentUserId: String = sharedPreferences.getString(AuthRepository.KEY_USER_ID, "") ?: ""
    
    private val _uiState = MutableStateFlow(
        ContactsUiState(
            currentUserId = currentUserId
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent = _toastEvent.asSharedFlow()

    init {
        observeData()
        observeActiveCall()
        observeUnreadCounts()
    }

    private fun observeUnreadCounts() {
        messageDao.getTotalUnreadCountFlow(currentUserId)
            .onEach { count ->
                _uiState.update { it.copy(totalUnreadCount = count) }
            }
            .launchIn(viewModelScope)

        callStatusManager.activeUserUnreadCount
            .onEach { count ->
                _uiState.update { it.copy(chatUnreadCount = count) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeActiveCall() {
        callStatusManager.activeCall
            .onEach { activeCall ->
                _uiState.update { it.copy(activeCallInfo = activeCall) }
            }
            .launchIn(viewModelScope)

        callStatusManager.isMinimized
            .onEach { isMinimized ->
                _uiState.update { it.copy(isCallMinimized = isMinimized) }
            }
            .launchIn(viewModelScope)

        callStatusManager.callDuration
            .onEach { duration ->
                _uiState.update { it.copy(callDuration = duration) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeData() {
        val messagesFlow = messageDao.getLastMessagesForAllChats(currentUserId)
        val contactsFlow = contactsRepository.getContactsFlow()

        combine(messagesFlow, contactsFlow) { messages, localContacts ->
            val contactIds = localContacts.map { it.id }.toSet()

            // 1. Active chats (with history)
            val activeSummaries = messages.mapNotNull { msg ->
                val contactId = if (msg.fromUserId == currentUserId) msg.toUserId else msg.fromUserId
                val contact = userRepository.getUser(contactId) ?: return@mapNotNull null
                val unreadCount = messageDao.getUnreadCountFromUserSync(currentUserId, contactId)
                val isContact = contactIds.contains(contactId)
                ChatSummary(contact, msg, unreadCount, msg.fromUserId == currentUserId, isContact)
            }
            
            val activeIds = activeSummaries.map { it.contact.id }.toSet()
            
            // 2. Inactive contacts (no history)
            val inactiveSummaries = localContacts.filter { !activeIds.contains(it.id) }.map { user ->
                ChatSummary(
                    contact = user,
                    lastMessage = null,
                    unreadCount = 0,
                    isLastMessageFromMe = false,
                    isContact = true
                )
            }.sortedBy { it.contact.username.lowercase() }

            activeSummaries + inactiveSummaries
        }
        .flowOn(Dispatchers.IO)
        .onEach { summaries ->
            _uiState.update { currentState ->
                currentState.copy(
                    chats = summaries, 
                    filteredChats = filterChats(summaries, currentState.searchQuery),
                    isLoading = false
                )
            }
        }
        .launchIn(viewModelScope)
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { 
            it.copy(
                searchQuery = query,
                filteredChats = filterChats(it.chats, query),
                selectedIds = emptySet(),
                expandedId = null
            ) 
        }
    }

    private fun filterChats(chats: List<ChatSummary>, query: String): List<ChatSummary> {
        if (query.isBlank()) return chats
        return chats.filter { it.contact.username.contains(query, ignoreCase = true) || it.contact.id.contains(query, ignoreCase = true) }
    }

    fun handleHomeNavigation() {
        onSearchQueryChange("")
    }

    fun toggleExpanded(userId: String) {
        _uiState.update { state ->
            state.copy(expandedId = if (state.expandedId == userId) null else userId)
        }
    }

    fun collapseExpanded() {
        _uiState.update { it.copy(expandedId = null) }
    }

    fun initiateCall(targetId: String, isVideo: Boolean) {
        viewModelScope.launch {
            launch(Dispatchers.IO) {
                messageDao.insertMessage(
                    ChatMessage(
                        fromUserId = currentUserId, 
                        toUserId = targetId, 
                        text = "CALL_OUTGOING",
                        timestamp = System.currentTimeMillis(), 
                        isRead = true, 
                        type = MessageType.OUTGOING_CALL
                    )
                )
            }
            callStatusManager.startCall(targetId, true, isVideo, null)
        }
    }

    fun toggleSelection(userId: String) {
        _uiState.update { state ->
            val newSelected = if (state.selectedIds.contains(userId)) {
                state.selectedIds - userId
            } else {
                state.selectedIds + userId
            }
            state.copy(selectedIds = newSelected, expandedId = null)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            val ids = state.filteredChats.map { it.contact.id }.toSet()
            state.copy(selectedIds = ids, expandedId = null)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    fun deleteSelected(deleteFiles: Boolean, deleteContact: Boolean) {
        val state = _uiState.value
        val idsToDelete = state.selectedIds
        if (idsToDelete.isEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            idsToDelete.forEach { userId ->
                if (deleteFiles) {
                    messageDao.getMessagesForChatSync(currentUserId, userId).forEach { msg ->
                        msg.localFilePath?.let { deleteLocalFile(it) }
                    }
                }
                
                messageDao.deleteAllMessagesForChat(currentUserId, userId)
                
                if (deleteContact) {
                    contactsRepository.deleteContact(userId)
                }
            }
            withContext(Dispatchers.Main) { clearSelection() }
        }
    }

    fun resolveContactIdentity(input: String) {
        viewModelScope.launch {
            if (input.isBlank()) return@launch
            _uiState.update { it.copy(resolvedContact = ResolvedContactInfo("", null, isResolving = true)) }
            
            val address = if (input.contains("@")) input.split("@")[1] else input
            if (address == currentUserId) {
                _toastEvent.emit(context.getString(R.string.error_add_self))
                _uiState.update { it.copy(resolvedContact = null) }
                return@launch
            }
            
            val localUser = withContext(Dispatchers.IO) { userRepository.getUser(address) }
            val serverName = if (localUser != null && !localUser.username.startsWith("User_")) {
                localUser.username
            } else {
                if (input.contains("@")) input.split("@")[0] else null
            }
            
            _uiState.update { it.copy(resolvedContact = ResolvedContactInfo(address, serverName, isResolving = false)) }
        }
    }

    fun clearResolvedContact() {
        _uiState.update { it.copy(resolvedContact = null) }
    }

    fun addContact(input: String, customName: String? = null) {
        viewModelScope.launch {
            if (input.isBlank()) return@launch
            val success = userRepository.addContactByIdentity(input, customName)
            if (success) {
                _toastEvent.emit(context.getString(R.string.add))
                _uiState.update { it.copy(resolvedContact = null) }
            } else {
                _toastEvent.emit(context.getString(R.string.error))
            }
        }
    }
    
    fun addContact(user: User) {
        if (user.id == currentUserId) {
            viewModelScope.launch { _toastEvent.emit(context.getString(R.string.error_add_self)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) { contactsRepository.addContact(user) }
    }

    fun renameContact(userId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) { contactsRepository.renameContact(userId, newName) }
    }

    fun deleteContact(userId: String, deleteFiles: Boolean, deleteContact: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (deleteFiles) {
                messageDao.getMessagesForChatSync(currentUserId, userId).forEach { msg ->
                    msg.localFilePath?.let { deleteLocalFile(it) }
                }
            }
            messageDao.deleteAllMessagesForChat(currentUserId, userId)
            if (deleteContact) {
                contactsRepository.deleteContact(userId)
            }
        }
    }

    private fun deleteLocalFile(path: String) {
        try {
            if (path.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(path), null, null)
            } else {
                val file = File(path)
                if (file.exists()) file.delete()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    fun isContact(userId: String): Boolean {
        return uiState.value.chats.any { it.contact.id == userId && it.isContact }
    }

    /**
     * Экспорт контактов. Принимает CharArray пароля.
     */
    fun exportContacts(uri: Uri, passwordChars: CharArray?) {
        viewModelScope.launch {
            val outputStream = try {
                context.contentResolver.openOutputStream(uri)
            } catch (e: Exception) { null }

            if (outputStream == null) {
                _toastEvent.emit(context.getString(R.string.contacts_error_file_open))
                if (passwordChars != null) SecureDataHandler.wipe(passwordChars)
                return@launch
            }
            
            val success = userRepository.exportContacts(outputStream, passwordChars)
            if (success) {
                _toastEvent.emit(context.getString(R.string.contacts_export_success))
            } else {
                _toastEvent.emit(context.getString(R.string.contacts_export_failed))
            }
            // Пароль затирается внутри userRepository.exportContacts
        }
    }

    /**
     * Импорт контактов. Принимает CharArray пароля.
     */
    fun importContacts(uri: Uri, passwordChars: CharArray?, onPasswordRequired: () -> Unit) {
        viewModelScope.launch {
            val inputStream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) { null }

            if (inputStream == null) {
                _toastEvent.emit(context.getString(R.string.contacts_error_file_open))
                if (passwordChars != null) SecureDataHandler.wipe(passwordChars)
                return@launch
            }

            val result = userRepository.importContacts(inputStream, passwordChars)
            when (result) {
                0 -> _toastEvent.emit(context.getString(R.string.contacts_import_success))
                1 -> onPasswordRequired()
                2 -> _toastEvent.emit(context.getString(R.string.contacts_import_error_data))
                3 -> _toastEvent.emit(context.getString(R.string.contacts_import_error_mnemonic))
            }
            // Пароль затирается внутри userRepository.importContacts
        }
    }
}
