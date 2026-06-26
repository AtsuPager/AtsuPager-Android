/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.contacts

import android.content.*
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.security.ClipboardUiHelper
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.ui.components.*
import com.nax.atsupager.ui.screens.main.*
import com.nax.atsupager.ui.navigation.Screen
import com.nax.atsupager.webrtc.ActiveCallInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.Shape
import java.text.SimpleDateFormat
import java.util.*

sealed class ContactsDialog {
    object AddChoice : ContactsDialog()
    object AddContact : ContactsDialog()
    data class Rename(val summary: ChatSummary) : ContactsDialog()
    object BulkDelete : ContactsDialog()
    data class Export(val uri: Uri) : ContactsDialog()
    data class ImportPassword(val uri: Uri) : ContactsDialog()
    object ContactPicker : ContactsDialog()
    data class GroupName(val memberIds: List<String>) : ContactsDialog()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    initialTab: Int = -1,
    onNavigateToMain: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onReturnToCall: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var activeDialog by remember { mutableStateOf<ContactsDialog?>(null) }

    val isSelectionMode = uiState.selectedIds.isNotEmpty()

    val activeCallUser = remember(uiState.activeCallInfo, uiState.chats) {
        uiState.activeCallInfo?.let { call ->
            uiState.chats.find { it.id == call.userId }?.contact
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.collapseExpanded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val createExportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) activeDialog = ContactsDialog.Export(uri)
    }

    val pickImportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importContacts(uri, null) {
                activeDialog = ContactsDialog.ImportPassword(uri)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastEvent.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    BackHandler(enabled = isSelectionMode) {
        viewModel.clearSelection()
    }

    if (uiState.showAccessDialog) {
        AccessCodeDialog(
            onDismiss = { viewModel.closeAccessDialog() },
            onVerify = { code, onResult -> viewModel.applyAccessCode(code, onResult) }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Box(modifier = Modifier.fillMaxWidth()) {
                AtsuTopAppBar(
                    mode = TopAppBarMode.HOME,
                    user = activeCallUser,
                    activeCallInfo = uiState.activeCallInfo,
                    callDuration = uiState.callDuration,
                    unreadCount = uiState.totalUnreadCount,
                    chatUnreadCount = uiState.chatUnreadCount,
                    onActionClick = { action ->
                        when (action) {
                            TopAppBarAction.HOME -> viewModel.handleHomeNavigation()
                            TopAppBarAction.RETURN_TO_CALL -> onReturnToCall()
                            TopAppBarAction.GAMES -> activeCallUser?.let { onNavigateToMain(Screen.Games.createRoute(it.id)) }
                            TopAppBarAction.VOICE_CALL -> activeCallUser?.let { viewModel.initiateCall(it.id, false) }
                            TopAppBarAction.VIDEO_CALL -> activeCallUser?.let { viewModel.initiateCall(it.id, true) }
                            TopAppBarAction.CHAT -> activeCallUser?.let { onNavigateToMain(Screen.Main.createRoute(it.id)) }
                            TopAppBarAction.EXPORT_PROFILE -> { createExportFileLauncher.launch("atsupager_profile.enc") }
                            TopAppBarAction.IMPORT_PROFILE -> { pickImportFileLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
                            else -> {}
                        }
                    },
                    onOpenSettings = onOpenSettings
                )

                SelectionActionsBar(
                    visible = isSelectionMode,
                    selectedCount = uiState.selectedIds.size,
                    totalCount = uiState.filteredChats.size,
                    onClear = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onEdit = {
                        val id = uiState.selectedIds.first()
                        val summary = uiState.chats.find { it.id == id }
                        if (summary != null) {
                            val canRenameGroup = summary.isGroup && summary.group?.ownerId == uiState.currentUserId
                            if (!summary.isGroup || canRenameGroup) {
                                activeDialog = ContactsDialog.Rename(summary)
                            }
                        }
                    },
                    onCopy = {
                        val id = uiState.selectedIds.first()
                        val user = uiState.chats.find { it.id == id }?.contact
                        val fullId = user?.let { if (it.id.contains("@")) it.id else "${it.username}@${it.id}" } ?: id
                        ClipboardUiHelper.copyWithNotification(context, scope, snackbarHostState, "AtsuContact", fullId)
                        viewModel.clearSelection()
                    },
                    onShare = {
                        val id = uiState.selectedIds.first()
                        val user = uiState.chats.find { it.id == id }?.contact
                        val fullId = user?.let { if (it.id.contains("@")) it.id else "${it.username}@${it.id}" } ?: id
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_id_text, fullId))
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_id_text, fullId)))
                        viewModel.clearSelection()
                    },
                    onDelete = { activeDialog = ContactsDialog.BulkDelete },
                    onGroup = {
                        val selectedContacts = uiState.chats.filter { uiState.selectedIds.contains(it.id) && !it.isGroup }.map { it.id }
                        if (selectedContacts.size >= 1) {
                            activeDialog = ContactsDialog.GroupName(selectedContacts)
                        }
                    },
                    modifier = Modifier.matchParentSize(),
                    canEdit = remember(uiState.selectedIds, uiState.chats) {
                        if (uiState.selectedIds.size != 1) return@remember false
                        val id = uiState.selectedIds.first()
                        val summary = uiState.chats.find { it.id == id } ?: return@remember false
                        !summary.isGroup || summary.group?.ownerId == uiState.currentUserId
                    },
                    canGroup = remember(uiState.selectedIds, uiState.chats) {
                        val selected = uiState.chats.filter { it.id in uiState.selectedIds }
                        selected.isNotEmpty() && selected.all { !it.isGroup }
                    },
                    canShare = remember(uiState.selectedIds, uiState.chats) {
                        if (uiState.selectedIds.size != 1) return@remember false
                        val id = uiState.selectedIds.first()
                        val summary = uiState.chats.find { it.id == id }
                        summary?.isGroup == false
                    },
                    canCopy = remember(uiState.selectedIds, uiState.chats) {
                        if (uiState.selectedIds.size != 1) return@remember false
                        val id = uiState.selectedIds.first()
                        val summary = uiState.chats.find { it.id == id }
                        summary?.isGroup == false
                    }
                )
            }
        },
        snackbarHost = { AtsuSnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                SearchAndActionHeader(
                    searchQuery = uiState.searchQuery,
                    onSearchQueryChange = viewModel::onSearchQueryChange,
                    placeholderText = stringResource(R.string.search_contacts),
                    showAddButton = !isSelectionMode,
                    onAddClick = {
                        activeDialog = ContactsDialog.AddChoice
                    }
                )

                val activeCallForItems = if (uiState.isCallMinimized) uiState.activeCallInfo else null
                val listState = rememberLazyListState()

                LaunchedEffect(uiState.expandedId) {
                    if (uiState.expandedId != null) {
                        delay(100)
                        val visibleItem = listState.layoutInfo.visibleItemsInfo.find { it.key == uiState.expandedId }
                        if (visibleItem != null) {
                            val viewportEnd = listState.layoutInfo.viewportEndOffset
                            val projectedBottom = visibleItem.offset + visibleItem.size + 220
                            if (projectedBottom > viewportEnd) {
                                listState.animateScrollBy((projectedBottom - viewportEnd + 50).toFloat())
                            }
                        }
                    }
                }

                if (uiState.filteredChats.isEmpty()) {
                    EmptyState(stringResource(R.string.no_contacts), Icons.Default.PeopleOutline)
                } else {
                    val activeChats = remember(uiState.filteredChats) { uiState.filteredChats.filter { it.lastMessage != null } }
                    val inactiveChats = remember(uiState.filteredChats) { uiState.filteredChats.filter { it.lastMessage == null } }
                    
                    val inactiveContacts = remember(inactiveChats) { inactiveChats.filter { !it.isGroup } }
                    val inactiveGroups = remember(inactiveChats) { inactiveChats.filter { it.isGroup } }
                    
                    val groupedActive = remember(activeChats) { activeChats.groupBy { formatHeaderDate(it.lastMessage!!.timestamp, context) } }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        groupedActive.forEach { (date, items) ->
                            item { DateHeader(date) }
                            chatListItems(
                                items = items,
                                uiState = uiState,
                                viewModel = viewModel,
                                activeCallInfo = activeCallForItems,
                                onChatClick = { summary ->
                                    val route = if (summary.isGroup) Screen.GroupChat.createRoute(summary.id) else Screen.Main.createRoute(summary.id)
                                    onNavigateToMain(route)
                                },
                                onGamesClick = { summary -> onNavigateToMain(Screen.Games.createRoute(summary.id)) },
                                onReturnToCall = onReturnToCall
                            )
                        }

                        if (inactiveContacts.isNotEmpty()) {
                            item { DateHeader(stringResource(R.string.tab_contacts)) }
                            chatListItems(
                                items = inactiveContacts,
                                uiState = uiState,
                                viewModel = viewModel,
                                activeCallInfo = activeCallForItems,
                                onChatClick = { onNavigateToMain(Screen.Main.createRoute(it.id)) },
                                onGamesClick = { onNavigateToMain(Screen.Games.createRoute(it.id)) },
                                onReturnToCall = onReturnToCall
                            )
                        }

                        if (inactiveGroups.isNotEmpty()) {
                            item { DateHeader(stringResource(R.string.tab_groups)) }
                            chatListItems(
                                items = inactiveGroups,
                                uiState = uiState,
                                viewModel = viewModel,
                                activeCallInfo = activeCallForItems,
                                onChatClick = { onNavigateToMain(Screen.GroupChat.createRoute(it.id)) },
                                onGamesClick = { },
                                onReturnToCall = onReturnToCall
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog Handling
    when (val dialog = activeDialog) {
        is ContactsDialog.AddChoice -> {
            AddChoiceDialog(
                onAddContact = {
                    viewModel.clearResolvedContact()
                    activeDialog = ContactsDialog.AddContact
                },
                onCreateGroup = {
                    activeDialog = ContactsDialog.ContactPicker
                },
                onDismiss = { activeDialog = null }
            )
        }
        is ContactsDialog.AddContact -> {
            AddContactDialog(
                resolvedInfo = uiState.resolvedContact,
                onResolve = viewModel::resolveContactIdentity,
                onDismiss = { activeDialog = null; viewModel.clearResolvedContact() },
                onAdd = { input, name -> viewModel.addContact(input, name); activeDialog = null }
            )
        }
        is ContactsDialog.Rename -> {
            RenameDialog(
                initialName = dialog.summary.name,
                title = if (dialog.summary.isGroup) stringResource(R.string.rename_group) else stringResource(R.string.rename_contact),
                onDismiss = { activeDialog = null; viewModel.clearSelection() },
                onRename = { newName -> 
                    if (dialog.summary.isGroup) viewModel.renameGroup(dialog.summary.id, newName)
                    else viewModel.renameContact(dialog.summary.id, newName)
                    activeDialog = null
                    viewModel.clearSelection()
                }
            )
        }
        is ContactsDialog.BulkDelete -> {
            val selectedSummaries = remember(uiState.selectedIds, uiState.chats) {
                uiState.chats.filter { it.id in uiState.selectedIds }
            }
            val containsGroups = selectedSummaries.any { it.isGroup }
            val singleGroupAdmin = selectedSummaries.size == 1 && selectedSummaries.first().let { it.isGroup && it.isAdmin }
            
            MergedDeleteDialog(
                count = uiState.selectedIds.size,
                containsGroups = containsGroups,
                isAdmin = singleGroupAdmin,
                onDismiss = { activeDialog = null },
                onConfirm = { deleteFiles, deleteContact, deleteForEveryone -> 
                    viewModel.deleteSelected(deleteFiles, deleteContact, deleteForEveryone)
                    activeDialog = null 
                },
                onDeleteGroup = {
                    viewModel.deleteGroup(uiState.selectedIds.first())
                    activeDialog = null
                }
            )
        }
        is ContactsDialog.Export -> {
            ExportContactsDialog(
                onDismiss = { activeDialog = null },
                onExport = { password, history -> 
                    viewModel.exportContacts(dialog.uri, password?.toCharArray(), history) 
                    activeDialog = null
                }
            )
        }
        is ContactsDialog.ImportPassword -> {
            ImportPasswordDialog(
                onDismiss = { activeDialog = null },
                onConfirm = { password -> 
                    viewModel.importContacts(dialog.uri, password.toCharArray()) {}
                    activeDialog = null 
                }
            )
        }
        is ContactsDialog.ContactPicker -> {
            val allContacts = remember(uiState.chats) { uiState.chats.mapNotNull { it.contact }.distinctBy { it.id } }
            ContactPickerSheet(
                title = stringResource(R.string.create_group),
                contacts = allContacts,
                onContactsSelected = { selectedIds ->
                    activeDialog = ContactsDialog.GroupName(selectedIds)
                },
                onDismiss = { activeDialog = null }
            )
        }
        is ContactsDialog.GroupName -> {
            GroupNameDialog(
                onDismiss = { activeDialog = null },
                onConfirm = { name ->
                    viewModel.createGroup(name, dialog.memberIds)
                    activeDialog = null
                }
            )
        }
        null -> {}
    }
}

private fun LazyListScope.chatListItems(
    items: List<ChatSummary>,
    uiState: ContactsUiState,
    viewModel: ContactsViewModel,
    activeCallInfo: ActiveCallInfo?,
    onChatClick: (ChatSummary) -> Unit,
    onGamesClick: (ChatSummary) -> Unit,
    onReturnToCall: () -> Unit
) {
    itemsIndexed(items, key = { _, it -> it.id }) { index, summary ->
        ChatItem(
            summary = summary,
            isSelected = uiState.selectedIds.contains(summary.id),
            isSelectionMode = uiState.selectedIds.isNotEmpty(),
            isExpanded = uiState.expandedId == summary.id,
            onToggleExpanded = { viewModel.toggleExpanded(summary.id) },
            onChatClick = { onChatClick(summary) },
            onVoiceCall = { viewModel.initiateCall(summary.id, false) },
            onVideoCall = { viewModel.initiateCall(summary.id, true) },
            onGames = { onGamesClick(summary) },
            onLongClick = { viewModel.toggleSelection(summary.id) },
            onAddContact = { summary.contact?.let { viewModel.addContact(it) } },
            isContact = summary.isContact,
            currentUserId = uiState.currentUserId,
            activeCallInfo = activeCallInfo,
            onReturnToCall = onReturnToCall,
            isLoading = uiState.isLoading,
            shape = getGroupShape(index, items.size),
            showDivider = index < items.size - 1
        )
    }
}

private fun getGroupShape(index: Int, total: Int): Shape {
    val cornerSize = 16.dp
    return when {
        total == 1 -> RoundedCornerShape(cornerSize)
        index == 0 -> RoundedCornerShape(topStart = cornerSize, topEnd = cornerSize)
        index == total - 1 -> RoundedCornerShape(bottomStart = cornerSize, bottomEnd = cornerSize)
        else -> RectangleShape
    }
}

@Composable
private fun SearchAndActionHeader(searchQuery: String, onSearchQueryChange: (String) -> Unit, placeholderText: String, showAddButton: Boolean, onAddClick: () -> Unit) {
    val focusManager = LocalFocusManager.current
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        StyledTextField(value = searchQuery, onValueChange = onSearchQueryChange, placeholderText = placeholderText, modifier = Modifier.weight(1f).height(48.dp), leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }, trailingIcon = { if (searchQuery.isNotEmpty()) { IconButton(onClick = { onSearchQueryChange(""); focusManager.clearFocus() }) { Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp)) } } }, keyboardOptions = KeyboardSecurity.secureChatOptions)
        if (showAddButton) {
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onAddClick, modifier = Modifier.size(48.dp).background(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp))) { Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.add), tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun DateHeader(date: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 24.dp, bottom = 12.dp)) {
        Text(
            text = date.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatItem(
    summary: ChatSummary,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onChatClick: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onGames: () -> Unit,
    onLongClick: () -> Unit,
    onAddContact: () -> Unit,
    isContact: Boolean,
    currentUserId: String,
    activeCallInfo: ActiveCallInfo? = null,
    onReturnToCall: () -> Unit,
    isLoading: Boolean = false,
    shape: Shape = RectangleShape,
    showDivider: Boolean = true
) {
    val context = LocalContext.current
    val hasUnread = summary.unreadCount > 0
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.surfaceVariant
        hasUnread -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surface
    }

    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val borderAlpha by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 0.1f, animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "alpha")

    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(backgroundColor)
                .combinedClickable(
                    onClick = { if (isSelectionMode) onLongClick() else onToggleExpanded() },
                    onLongClick = onLongClick
                )
        ) {
            Row(
                modifier = Modifier.padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) RoundCheckbox(selected = isSelected, modifier = Modifier.padding(end = 12.dp))
                ContactAvatar(
                    username = summary.name,
                    isActiveCall = !summary.isGroup && summary.id == activeCallInfo?.userId,
                    isGroup = summary.isGroup
                )
                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = summary.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (summary.isGroup) {
                                Spacer(Modifier.width(8.dp))
                                val isOwner = summary.group?.ownerId == currentUserId
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = (if (isOwner) stringResource(R.string.owner_label) else stringResource(R.string.group)).uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        if (!isSelectionMode) {
                            if (!summary.isGroup && !isContact && !isLoading) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .border(width = 2.dp, color = Color.Red.copy(alpha = borderAlpha), shape = RoundedCornerShape(12.dp))
                                        .clickable { onAddContact() }
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Spacer(Modifier.width(6.dp))
                                        Text(text = stringResource(R.string.add), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier.clip(CircleShape).clickable(onClick = onChatClick).padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (hasUnread) BadgePulseEffect(color = MaterialTheme.colorScheme.primary)
                                    BadgedBox(
                                        badge = {
                                            if (hasUnread) {
                                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                    Text(summary.unreadCount.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (summary.isGroup) Icons.Default.Groups else Icons.AutoMirrored.Filled.Chat,
                                            contentDescription = "Chat",
                                            modifier = Modifier.size(20.dp),
                                            tint = if (hasUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            if (summary.lastMessage != null) {
                                MessageDirectionIcon(summary.lastMessage!!, currentUserId)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = getMessagePreview(summary.lastMessage!!, context, currentUserId),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.chat_is_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (summary.lastMessage != null) {
                            Text(
                                text = formatTimeOnly(summary.lastMessage!!.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded && !isSelectionMode, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                ExpandedActions(
                    onChat = onChatClick,
                    onVoiceCall = onVoiceCall,
                    onVideoCall = onVideoCall,
                    onGames = onGames,
                    onReturnToCall = onReturnToCall,
                    activeCallInfo = activeCallInfo,
                    targetUserId = summary.id,
                    backgroundColor = Color.Transparent,
                    isGroup = summary.isGroup
                )
            }

            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun ExpandedActions(
    onChat: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onGames: () -> Unit,
    onReturnToCall: () -> Unit,
    modifier: Modifier = Modifier,
    activeCallInfo: ActiveCallInfo? = null,
    targetUserId: String = "",
    backgroundColor: Color = Color.Transparent,
    isGroup: Boolean = false
) {
    val isAudioActiveWithThisUser = activeCallInfo != null && activeCallInfo.userId == targetUserId && !activeCallInfo.isVideo
    val isVideoActiveWithThisUser = activeCallInfo != null && activeCallInfo.userId == targetUserId && activeCallInfo.isVideo
    val voiceEnabled = !isGroup && (activeCallInfo == null || isAudioActiveWithThisUser)
    val videoEnabled = !isGroup && (activeCallInfo == null || isVideoActiveWithThisUser)
    Surface(modifier = modifier.fillMaxWidth(), color = backgroundColor, shape = RectangleShape) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (!isGroup) {
                SquareActionButton(icon = Icons.Default.Extension, contentDescription = stringResource(R.string.games), containerColor = Color(0xFFFFA600), onClick = onGames)
                SquareActionButton(icon = Icons.Default.Call, contentDescription = stringResource(R.string.call), containerColor = Color(0xFF4CAF50), onClick = { if (isAudioActiveWithThisUser) onReturnToCall() else onVoiceCall() }, enabled = voiceEnabled, showPulse = isAudioActiveWithThisUser, isActive = isAudioActiveWithThisUser)
                SquareActionButton(icon = Icons.Default.Videocam, contentDescription = stringResource(R.string.video_call), containerColor = Color(0xFF2196F3), onClick = { if (isVideoActiveWithThisUser) onReturnToCall() else onVideoCall() }, enabled = videoEnabled, showPulse = isVideoActiveWithThisUser, isActive = isVideoActiveWithThisUser)
            }
            SquareActionButton(icon = Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.chat), containerColor = Color(0xFF00BCD4), onClick = onChat)
        }
    }
}

@Composable
private fun SquareActionButton(icon: ImageVector, contentDescription: String, containerColor: Color, onClick: () -> Unit, enabled: Boolean = true, showPulse: Boolean = false, isActive: Boolean = false) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        if (showPulse) ActionPulseEffect()
        val currentColor = if (isActive) Color.Green else containerColor
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = BorderStroke(1.5.dp, if (enabled) currentColor else currentColor.copy(alpha = 0.38f)),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                val iconAlpha = if (enabled) 1f else 0.38f
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(22.dp),
                    tint = currentColor.copy(alpha = iconAlpha)
                )
            }
        }
    }
}

@Composable
private fun ActionPulseEffect(color: Color = Color.Green) {
    val infiniteTransition = rememberInfiniteTransition(label = "action_pulse")
    val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.6f, animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "scale")
    val alpha by infiniteTransition.animateFloat(initialValue = 0.5f, targetValue = 0f, animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "alpha")
    Box(Modifier.size(32.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }.background(color, CircleShape))
}

@Composable
private fun BadgePulseEffect(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "badge_pulse")
    val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 2.4f, animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearOutSlowInEasing), repeatMode = RepeatMode.Restart), label = "scale")
    val alpha by infiniteTransition.animateFloat(initialValue = 0.5f, targetValue = 0f, animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearOutSlowInEasing), repeatMode = RepeatMode.Restart), label = "alpha")
    Box(Modifier.size(14.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }.background(color, CircleShape))
}

@Composable
private fun MessageDirectionIcon(message: ChatMessage, currentUserId: String) {
    val isFromMe = message.fromUserId == currentUserId
    val isAccepted = message.type == MessageType.INCOMING_CALL && (message.text == "CALL_ACCEPTED" || message.text.contains("(accepted)", ignoreCase = true) || message.text.contains("(принят)", ignoreCase = true))
    val icon = when (message.type) {
        MessageType.MISSED_CALL -> Icons.AutoMirrored.Filled.CallMissed
        MessageType.INCOMING_CALL -> Icons.Default.SouthWest
        MessageType.OUTGOING_CALL -> Icons.AutoMirrored.Filled.CallMade
        else -> if (isFromMe) Icons.AutoMirrored.Filled.CallMade else Icons.Default.SouthWest
    }
    val tint = when {
        message.type == MessageType.MISSED_CALL -> MaterialTheme.colorScheme.error
        isAccepted -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.primary
    }
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint.copy(alpha = 0.8f))
}

@Composable
private fun EmptyState(message: String, icon: ImageVector) {
    val brandColor = if (MaterialTheme.colorScheme.background == Color.Black) Color(0xFF05E5FF) else Color(0xFF0D47A1)
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(64.dp), tint = brandColor.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp)); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun getMessagePreview(message: ChatMessage, context: Context, currentUserId: String): String {
    val isFromMe = message.fromUserId == currentUserId
    return when (message.type) {
        MessageType.TEXT -> if (isFromMe) context.getString(R.string.preview_text_outgoing) else context.getString(R.string.preview_text_incoming)
        MessageType.IMAGE -> if (isFromMe) context.getString(R.string.preview_image_outgoing) else context.getString(R.string.preview_text_incoming)
        MessageType.VIDEO -> if (isFromMe) context.getString(R.string.preview_video_outgoing) else context.getString(R.string.preview_text_incoming)
        MessageType.AUDIO -> if (isFromMe) context.getString(R.string.preview_audio_outgoing) else context.getString(R.string.preview_text_incoming)
        MessageType.FILE -> if (isFromMe) context.getString(R.string.preview_file_outgoing) else context.getString(R.string.preview_text_incoming)
        MessageType.SYSTEM -> {
            val text = message.text
            when {
                text.startsWith("SYSTEM_JOINED_GROUP") -> context.getString(R.string.system_joined_group, "")
                text.startsWith("SYSTEM_MEMBER_JOINED:") -> context.getString(R.string.system_member_joined, text.substringAfter(":").take(8))
                text.startsWith("SYSTEM_MEMBER_LEFT:") -> context.getString(R.string.system_member_left, text.substringAfter(":").take(8))
                text.startsWith("SYSTEM_MEMBER_KICKED:") -> context.getString(R.string.system_member_kicked, text.substringAfter(":").take(8))
                text.startsWith("SYSTEM_NEW_OWNER:") -> context.getString(R.string.system_new_owner, text.substringAfter(":").take(8))
                text.startsWith("SYSTEM_YOU_KICKED") -> context.getString(R.string.system_you_kicked)
                text.startsWith("SYSTEM_GROUP_RENAMED:") -> {
                    val newName = text.substringAfter("SYSTEM_GROUP_RENAMED:")
                    context.getString(R.string.system_group_renamed, newName)
                }
                else -> context.getString(R.string.system_group_created)
            }
        }
        MessageType.GAME_INVITE -> context.getString(R.string.games)
        MessageType.INCOMING_CALL, MessageType.OUTGOING_CALL, MessageType.MISSED_CALL -> {
            when {
                message.text == "CALL_ACCEPTED" || message.text.contains("(accepted)") || message.text.contains("(принят)") -> context.getString(R.string.preview_incoming_call)
                message.text == "CALL_MISSED" || message.type == MessageType.MISSED_CALL -> context.getString(R.string.preview_missed_call)
                message.text == "CALL_OUTGOING" || message.type == MessageType.OUTGOING_CALL -> context.getString(R.string.preview_outgoing_call)
                else -> context.getString(R.string.preview_incoming_call)
            }
        }
    }
}

private fun formatHeaderDate(timestamp: Long, context: Context): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    return when {
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> context.getString(R.string.today)
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun formatTimeOnly(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
