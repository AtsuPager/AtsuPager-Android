/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.contacts

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.nax.atsupager.R
import com.nax.atsupager.data.db.*
import com.nax.atsupager.data.model.User
import com.nax.atsupager.data.network.*
import com.nax.atsupager.security.SecureDataHandler
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import com.nax.atsupager.ui.screens.settings.AccessStatus
import com.nax.atsupager.webrtc.ActiveCallInfo
import com.nax.atsupager.webrtc.CallStatusManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ChatSummary(
    val contact: User? = null,
    val group: GroupEntity? = null,
    val lastMessage: ChatMessage? = null,
    val unreadCount: Int,
    val isLastMessageFromMe: Boolean,
    val isContact: Boolean = true,
    val isAdmin: Boolean = false
) {
    val id: String get() = contact?.id ?: group?.groupId ?: ""
    val name: String get() = contact?.username ?: group?.name ?: ""
    val isGroup: Boolean get() = group != null
}

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
    val chatUnreadCount: Int = 0,
    val accessStatus: AccessStatus = AccessStatus.ACTIVE,
    val showAccessDialog: Boolean = false
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val contactsRepository: ContactsRepository,
    private val messageDao: MessageDao,
    private val groupDao: GroupDao,
    private val groupRepository: GroupRepository,
    private val signalRepository: SignalRepository,
    private val sharedPreferences: SharedPreferences,
    private val callStatusManager: CallStatusManager,
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val gson = Gson()
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
        checkAccessOnStart()
        
        signalRepository.accessRequiredEvent
            .onEach { _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.EXPIRED) } }
            .launchIn(viewModelScope)
    }

    private fun checkAccessOnStart() {
        val expiry = sharedPreferences.getLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$currentUserId", 0L)
        val isActiveStatus = when {
            expiry == -1L -> true
            expiry > System.currentTimeMillis() -> true
            else -> false
        }
        if (!isActiveStatus) {
            _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.NO_ACCESS) }
        } else {
            _uiState.update { it.copy(accessStatus = AccessStatus.ACTIVE) }
        }
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
        val groupsFlow = groupDao.getAllGroupsFlow()
        val membersFlow = groupDao.getAllMembersFlow()

        combine(messagesFlow, contactsFlow, groupsFlow, membersFlow) { messages, localContacts, allGroups, allMembers ->
            val contactIds = localContacts.map { it.id }.toSet()
            val groupsMap = allGroups.associateBy { it.groupId }
            val rolesMap = allMembers.filter { it.userId == currentUserId }.associate { it.groupId to it.role }

            val activeSummaries = messages.mapNotNull { msg ->
                if (msg.groupId != null) {
                    val group = groupsMap[msg.groupId] ?: return@mapNotNull null
                    val unreadCount = messageDao.getUnreadCountForGroup(msg.groupId, currentUserId).first()
                    val role = rolesMap[msg.groupId]
                    ChatSummary(
                        group = group,
                        lastMessage = msg,
                        unreadCount = unreadCount,
                        isLastMessageFromMe = msg.fromUserId == currentUserId,
                        isContact = true,
                        isAdmin = role == "ADMIN" || group.ownerId == currentUserId
                    )
                } else {
                    val contactId = if (msg.fromUserId == currentUserId) msg.toUserId else msg.fromUserId
                    val contact = userRepository.getUser(contactId) ?: return@mapNotNull null
                    val unreadCount = messageDao.getUnreadCountFromUserSync(currentUserId, contactId)
                    val isContact = contactIds.contains(contactId)
                    ChatSummary(
                        contact = contact,
                        lastMessage = msg,
                        unreadCount = unreadCount,
                        isLastMessageFromMe = msg.fromUserId == currentUserId,
                        isContact = isContact,
                        isAdmin = false
                    )
                }
            }
            
            val activeIds = activeSummaries.map { it.id }.toSet()
            
            val inactiveSummaries = localContacts.filter { !activeIds.contains(it.id) }.map { user ->
                ChatSummary(
                    contact = user,
                    lastMessage = null,
                    unreadCount = 0,
                    isLastMessageFromMe = false,
                    isContact = true,
                    isAdmin = false
                )
            }.sortedBy { it.contact?.username?.lowercase() }

            val inactiveGroups = allGroups.filter { !activeIds.contains(it.groupId) }.map { group ->
                val role = rolesMap[group.groupId]
                ChatSummary(
                    group = group,
                    lastMessage = null,
                    unreadCount = 0,
                    isLastMessageFromMe = false,
                    isContact = true,
                    isAdmin = role == "ADMIN" || group.ownerId == currentUserId
                )
            }

            activeSummaries + inactiveSummaries + inactiveGroups
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

    fun createGroup(name: String, memberIds: List<String>) {
        viewModelScope.launch {
            val groupId = groupRepository.createGroup(name, memberIds)
            if (groupId.isNotEmpty()) {
                _toastEvent.emit(context.getString(R.string.add))
                clearSelection()
            }
        }
    }

    fun leaveGroup(groupId: String, deleteFiles: Boolean, deleteForEveryone: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            groupRepository.leaveGroup(groupId, deleteFiles, deleteForEveryone)
            withContext(Dispatchers.Main) { clearSelection() }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            groupRepository.deleteGroup(groupId)
            withContext(Dispatchers.Main) { clearSelection() }
        }
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
        return chats.filter { it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
    }

    fun handleHomeNavigation() {
        onSearchQueryChange("")
    }

    fun toggleExpanded(id: String) {
        _uiState.update { state ->
            state.copy(expandedId = if (state.expandedId == id) null else id)
        }
    }

    fun collapseExpanded() {
        _uiState.update { it.copy(expandedId = null) }
    }

    fun applyAccessCode(code: String, onResult: (Boolean, String?) -> Unit) {
        val cleanCode = code.replace(Regex("[^A-Z0-9]"), "").uppercase()
        if (cleanCode.length != 16) {
            onResult(false, context.getString(R.string.invalid_code))
            return
        }

        viewModelScope.launch {
            signalRepository.verifyAccessCode(cleanCode) { success, error, expiry ->
                viewModelScope.launch {
                    if (success && expiry != null) {
                        sharedPreferences.edit().putLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$currentUserId", expiry).apply()
                        _uiState.update { it.copy(showAccessDialog = false, accessStatus = AccessStatus.ACTIVE) }
                        onResult(true, null)
                    } else {
                        onResult(false, error ?: context.getString(R.string.invalid_code))
                    }
                }
            }
        }
    }

    fun closeAccessDialog() {
        _uiState.update { it.copy(showAccessDialog = false) }
    }

    fun initiateCall(targetId: String, isVideo: Boolean) {
        viewModelScope.launch {
            viewModelScope.launch(Dispatchers.IO) {
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

    fun toggleSelection(id: String) {
        _uiState.update { state ->
            val newSelected = if (state.selectedIds.contains(id)) {
                state.selectedIds - id
            } else {
                state.selectedIds + id
            }
            state.copy(selectedIds = newSelected, expandedId = null)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            val ids = state.filteredChats.map { it.id }.toSet()
            state.copy(selectedIds = ids, expandedId = null)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    fun deleteSelected(deleteFiles: Boolean, deleteContact: Boolean, deleteForEveryone: Boolean = false) {
        val state = _uiState.value
        val idsToDelete = state.selectedIds
        if (idsToDelete.isEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            idsToDelete.forEach { id ->
                val summary = state.chats.find { it.id == id } ?: return@forEach
                val allMessages = if (summary.isGroup) {
                    messageDao.getMessagesForGroupSync(id)
                } else {
                    messageDao.getMessagesForChatSync(currentUserId, id)
                }

                if (deleteForEveryone) {
                    val isGroupOwner = if (summary.isGroup) summary.group?.ownerId == currentUserId else false
                    val messagesToRemove = if (isGroupOwner) allMessages else allMessages.filter { it.fromUserId == currentUserId }
                    
                    if (messagesToRemove.isNotEmpty()) {
                        val lastMsg = messagesToRemove.maxByOrNull { it.timestamp }
                        val lastTimestamp = lastMsg?.timestamp ?: System.currentTimeMillis()
                        val lastRemoteId = lastMsg?.remoteId
                        
                        signalRepository.sendRemoteBulkDelete(id, summary.isGroup, lastTimestamp, lastRemoteId, isGroupOwner)
                        
                        messagesToRemove.forEach { msg ->
                            msg.fileUrl?.let { url -> signalRepository.deleteFileFromServer(url) }
                        }
                    }
                }

                if (summary.isGroup) {
                    if (deleteFiles) {
                        allMessages.forEach { msg ->
                            msg.localFilePath?.let { deleteLocalFile(it) }
                        }
                    }
                    
                    if (deleteContact) {
                        groupRepository.leaveGroup(id, deleteFiles, deleteForEveryone)
                    } else {
                        messageDao.deleteAllMessagesForGroup(id)
                    }
                } else {
                    if (deleteFiles) {
                        allMessages.forEach { msg ->
                            msg.localFilePath?.let { path ->
                                val usageCount = messageDao.getMessageCountByFilePath(path)
                                if (usageCount <= 1) {
                                    if (path.startsWith("content://") || path.contains("AtsuPager")) {
                                        deleteLocalFile(path)
                                    }
                                }
                            }
                        }
                    }
                    messageDao.deleteAllMessagesForChat(currentUserId, id)
                    if (deleteContact) {
                        contactsRepository.deleteContact(id)
                    }
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

    fun renameGroup(groupId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            groupDao.renameGroup(groupId, newName)
            val members = groupDao.getGroupMemberIds(groupId)
            val renameSignal = SignalData(
                callId = "group_ctrl",
                type = SignalType.GROUP_RENAME,
                payload = gson.toJson(GroupRenamePacket(groupId, newName))
            )
            members.forEach { if (it != currentUserId) signalRepository.sendSignal(it, renameSignal) }
            
            messageDao.insertMessage(ChatMessage(
                fromUserId = currentUserId,
                toUserId = "",
                groupId = groupId,
                text = "SYSTEM_GROUP_RENAMED:$newName",
                timestamp = System.currentTimeMillis(),
                type = MessageType.SYSTEM
            ))
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
        return uiState.value.chats.any { it.contact?.id == userId && it.isContact }
    }

    fun exportContacts(uri: Uri, passwordChars: CharArray?, includeHistory: Boolean) {
        viewModelScope.launch {
            val outputStream = try {
                context.contentResolver.openOutputStream(uri)
            } catch (e: Exception) { null }

            if (outputStream == null) {
                _toastEvent.emit(context.getString(R.string.contacts_error_file_open))
                if (passwordChars != null) SecureDataHandler.wipe(passwordChars)
                return@launch
            }
            
            val success = userRepository.exportContacts(outputStream, passwordChars, includeHistory)
            if (success) {
                _toastEvent.emit(context.getString(R.string.contacts_export_success))
            } else {
                _toastEvent.emit(context.getString(R.string.contacts_export_failed))
            }
        }
    }

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
                4 -> _toastEvent.emit(context.getString(R.string.contacts_import_success_contacts_only))
            }
        }
    }
}
