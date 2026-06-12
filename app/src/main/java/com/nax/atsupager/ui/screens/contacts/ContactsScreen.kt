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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import com.nax.atsupager.data.model.User
import com.nax.atsupager.security.ClipboardUiHelper
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.security.SecureDataHandler
import com.nax.atsupager.ui.components.StyledTextField
import com.nax.atsupager.ui.components.ContactAvatar
import com.nax.atsupager.ui.screens.main.*
import com.nax.atsupager.ui.navigation.Screen
import com.nax.atsupager.webrtc.ActiveCallInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import com.nax.atsupager.ui.components.AtsuSnackbarHost

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

    var showAddDialog by remember { mutableStateOf(false) }
    var contactToRename by remember { mutableStateOf<User?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf<Uri?>(null) }
    var pendingExportUri by remember { mutableStateOf<Uri?>(null) }

    val isSelectionMode = uiState.selectedIds.isNotEmpty()

    val activeCallUser = remember(uiState.activeCallInfo, uiState.chats) {
        uiState.activeCallInfo?.let { call ->
            uiState.chats.find { it.contact.id == call.userId }?.contact
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
        if (uri != null) {
            pendingExportUri = uri
            showExportDialog = true
        }
    }

    val pickImportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importContacts(uri, null) {
                showImportPasswordDialog = uri
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

    // Access Activation Dialog
    if (uiState.showAccessDialog) {
        var codeInput by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }
        var errorText by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!isVerifying) viewModel.closeAccessDialog() },
            title = { Text(stringResource(R.string.access_settings)) },
            text = {
                Column {
                    Text(stringResource(R.string.buy_code_info), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    StyledTextField(
                        value = codeInput,
                        onValueChange = { 
                            val filtered = it.uppercase().filter { char -> char.isLetterOrDigit() || char == '-' }
                            if (filtered.length <= 25) codeInput = filtered 
                        },
                        placeholderText = stringResource(R.string.enter_code_hint),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardSecurity.secureChatOptions
                    )
                    if (errorText != null) {
                        Text(text = errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            },
            confirmButton = {
                val cleanLength = codeInput.filter { it.isLetterOrDigit() }.length
                Button(
                    onClick = {
                        isVerifying = true
                        viewModel.applyAccessCode(codeInput) { success, error ->
                            isVerifying = false
                            if (!success) errorText = error
                        }
                    },
                    enabled = cleanLength == 16 && !isVerifying
                ) {
                    if (isVerifying) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Text(stringResource(R.string.apply_code))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeAccessDialog() }, enabled = !isVerifying) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
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
                        TopAppBarAction.EXPORT_CONTACTS -> { createExportFileLauncher.launch("atsupager_contacts.enc") }
                        TopAppBarAction.IMPORT_CONTACTS -> { pickImportFileLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
                        else -> {}
                    }
                },
                onOpenSettings = onOpenSettings
            )
        },
        bottomBar = {
            SelectionActionsBar(
                visible = isSelectionMode,
                selectedCount = uiState.selectedIds.size,
                onClear = viewModel::clearSelection,
                onSelectAll = viewModel::selectAll,
                onEdit = {
                    val id = uiState.selectedIds.first()
                    val user = uiState.chats.find { it.contact.id == id }?.contact
                    contactToRename = user
                },
                onCopy = {
                    val id = uiState.selectedIds.first()
                    val user = uiState.chats.find { it.contact.id == id }?.contact
                    val fullId = user?.let { if (it.id.contains("@")) it.id else "${it.username}@${it.id}" } ?: id
                    ClipboardUiHelper.copyWithNotification(context, scope, snackbarHostState, "AtsuContact", fullId)
                    viewModel.clearSelection()
                },
                onShare = {
                    val id = uiState.selectedIds.first()
                    val user = uiState.chats.find { it.contact.id == id }?.contact
                    val fullId = user?.let { if (it.id.contains("@")) it.id else "${it.username}@${it.id}" } ?: id
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_id_text, fullId))
                    }
                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_id_text, fullId)))
                    viewModel.clearSelection()
                },
                onDelete = { showBulkDeleteDialog = true }
            )
        },
        snackbarHost = { AtsuSnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                        viewModel.clearResolvedContact()
                        showAddDialog = true
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
                    val inactiveContacts = remember(uiState.filteredChats) { uiState.filteredChats.filter { it.lastMessage == null } }
                    val groupedActive = remember(activeChats) { activeChats.groupBy { formatHeaderDate(it.lastMessage!!.timestamp, context) } }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        groupedActive.forEach { (date, items) ->
                            item { DateHeader(date) }
                            itemsIndexed(items, key = { _, it -> it.contact.id }) { index, summary ->
                                ChatItem(
                                    summary = summary,
                                    isSelected = uiState.selectedIds.contains(summary.contact.id),
                                    isSelectionMode = isSelectionMode,
                                    isExpanded = uiState.expandedId == summary.contact.id,
                                    onToggleExpanded = { viewModel.toggleExpanded(summary.contact.id) },
                                    onChatClick = { onNavigateToMain(Screen.Main.createRoute(summary.contact.id)) },
                                    onVoiceCall = { viewModel.initiateCall(summary.contact.id, false) },
                                    onVideoCall = { viewModel.initiateCall(summary.contact.id, true) },
                                    onGames = { onNavigateToMain(Screen.Games.createRoute(summary.contact.id)) },
                                    onLongClick = { viewModel.toggleSelection(summary.contact.id) },
                                    onAddContact = { viewModel.addContact(summary.contact) },
                                    isContact = summary.isContact,
                                    currentUserId = uiState.currentUserId,
                                    activeCallInfo = activeCallForItems,
                                    onReturnToCall = onReturnToCall,
                                    isLoading = uiState.isLoading,
                                    shape = getGroupShape(index, items.size),
                                    showDivider = index < items.size - 1
                                )
                            }
                        }

                        if (inactiveContacts.isNotEmpty()) {
                            item { DateHeader(stringResource(R.string.tab_contacts)) }
                            itemsIndexed(inactiveContacts, key = { _, it -> it.contact.id }) { index, summary ->
                                ChatItem(
                                    summary = summary,
                                    isSelected = uiState.selectedIds.contains(summary.contact.id),
                                    isSelectionMode = isSelectionMode,
                                    isExpanded = uiState.expandedId == summary.contact.id,
                                    onToggleExpanded = { viewModel.toggleExpanded(summary.contact.id) },
                                    onChatClick = { onNavigateToMain(Screen.Main.createRoute(summary.contact.id)) },
                                    onVoiceCall = { viewModel.initiateCall(summary.contact.id, false) },
                                    onVideoCall = { viewModel.initiateCall(summary.contact.id, true) },
                                    onGames = { onNavigateToMain(Screen.Games.createRoute(summary.contact.id)) },
                                    onLongClick = { viewModel.toggleSelection(summary.contact.id) },
                                    onAddContact = { viewModel.addContact(summary.contact) },
                                    isContact = summary.isContact,
                                    currentUserId = uiState.currentUserId,
                                    activeCallInfo = activeCallForItems,
                                    onReturnToCall = onReturnToCall,
                                    isLoading = uiState.isLoading,
                                    shape = getGroupShape(index, inactiveContacts.size),
                                    showDivider = index < inactiveContacts.size - 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            resolvedInfo = uiState.resolvedContact,
            onResolve = viewModel::resolveContactIdentity,
            onDismiss = { showAddDialog = false; viewModel.clearResolvedContact() },
            onAdd = { input, name -> viewModel.addContact(input, name); showAddDialog = false }
        )
    }

    contactToRename?.let { user ->
        RenameContactDialog(
            initialName = user.username,
            onDismiss = { contactToRename = null; viewModel.clearSelection() },
            onRename = { newName -> viewModel.renameContact(user.id, newName); contactToRename = null; viewModel.clearSelection() }
        )
    }

    if (showBulkDeleteDialog) {
        MergedDeleteDialog(
            count = uiState.selectedIds.size,
            onDismiss = { showBulkDeleteDialog = false },
            onConfirm = { deleteFiles, deleteContact -> viewModel.deleteSelected(deleteFiles, deleteContact); showBulkDeleteDialog = false }
        )
    }

    if (showExportDialog && pendingExportUri != null) {
        ExportContactsDialog(
            onDismiss = { showExportDialog = false; pendingExportUri = null },
            onExport = { password -> 
                viewModel.exportContacts(pendingExportUri!!, password?.toCharArray()); 
                showExportDialog = false; 
                pendingExportUri = null 
            }
        )
    }

    showImportPasswordDialog?.let { uri ->
        ImportPasswordDialog(
            onDismiss = { showImportPasswordDialog = null },
            onConfirm = { password -> 
                viewModel.importContacts(uri, password.toCharArray()) {}; 
                showImportPasswordDialog = null 
            }
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
fun AddContactDialog(resolvedInfo: ResolvedContactInfo?, onResolve: (String) -> Unit, onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    var identity by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(resolvedInfo) { if (resolvedInfo?.serverName != null && name.isBlank()) name = resolvedInfo.serverName }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (resolvedInfo == null) {
                    Text(stringResource(R.string.add_contact_msg, ""), style = MaterialTheme.typography.bodySmall)
                    StyledTextField(value = identity, onValueChange = { identity = it }, placeholderText = "name@1A1zP1...", modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton(onClick = { onResolve(identity) }, enabled = identity.isNotBlank()) { Icon(Icons.Default.Search, null) } }, keyboardOptions = KeyboardSecurity.secureChatOptions)
                } else if (resolvedInfo.isResolving) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Text(text = "ID: ${resolvedInfo.address.take(8)}...${resolvedInfo.address.takeLast(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StyledTextField(value = name, onValueChange = { name = it }, placeholderText = stringResource(R.string.contact_name), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardSecurity.secureChatOptions)
                    if (resolvedInfo.serverName == null && name.isBlank()) Text(text = stringResource(R.string.error_name_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            if (resolvedInfo != null && !resolvedInfo.isResolving) Button(onClick = { onAdd(resolvedInfo.address, name.takeIf { it.isNotBlank() }) }, enabled = resolvedInfo.serverName != null || name.isNotBlank()) { Text(stringResource(R.string.add)) }
            else if (resolvedInfo == null) Button(onClick = { onResolve(identity) }, enabled = identity.isNotBlank()) { Text(stringResource(R.string.next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun RenameContactDialog(initialName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_contact)) },
        text = { StyledTextField(value = name, onValueChange = { name = it }, placeholderText = stringResource(R.string.contact_name), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardSecurity.secureChatOptions) },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onRename(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun MergedDeleteDialog(count: Int, onDismiss: () -> Unit, onConfirm: (Boolean, Boolean) -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    var deleteMode by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (count == 1) stringResource(R.string.bulk_delete_chat_single) else stringResource(R.string.bulk_delete_chat_multiple, count))
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { deleteMode = 0 }.padding(vertical = 4.dp)) {
                        RadioButton(selected = deleteMode == 0, onClick = { deleteMode = 0 })
                        Text(stringResource(R.string.clear_history_only), modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { deleteMode = 1 }.padding(vertical = 4.dp)) {
                        RadioButton(selected = deleteMode == 1, onClick = { deleteMode = 1 })
                        Text(stringResource(R.string.delete_all_confirm), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).clickable { deleteFiles = !deleteFiles }) {
                    Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Text(stringResource(R.string.delete_files_on_device), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteFiles, deleteMode == 1) }) { Text(text = if (deleteMode == 1) stringResource(R.string.delete_contact_action) else stringResource(R.string.clear_button), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun ExportContactsDialog(onDismiss: () -> Unit, onExport: (String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_contacts)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.contacts_export_method))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { usePassword = false }) {
                    RadioButton(selected = !usePassword, onClick = { usePassword = false })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.contacts_backup_mnemonic), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.contacts_backup_mnemonic_desc), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { usePassword = true }) {
                    RadioButton(selected = usePassword, onClick = { usePassword = true })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.contacts_backup_password), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.contacts_backup_password_desc), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (usePassword) StyledTextField(value = password, onValueChange = { password = it }, placeholderText = stringResource(R.string.contacts_backup_password_placeholder), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardSecurity.securePasswordOptions)
            }
        },
        confirmButton = { Button(onClick = { 
            val result = if (usePassword) password else null
            onExport(result)
            password = "" // Сбрасываем строку в UI немедленно
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { 
            password = ""
            onDismiss()
        }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun ImportPasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_import_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.contacts_import_password_msg))
                Spacer(Modifier.height(12.dp))
                StyledTextField(value = password, onValueChange = { password = it }, placeholderText = stringResource(R.string.contacts_import_password_placeholder), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardSecurity.securePasswordOptions)
            }
        },
        confirmButton = { Button(onClick = { 
            onConfirm(password)
            password = "" // Сбрасываем строку
        }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = {
            password = ""
            onDismiss()
        }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun SearchAndActionHeader(searchQuery: String, onSearchQueryChange: (String) -> Unit, placeholderText: String, showAddButton: Boolean, onAddClick: () -> Unit) {
    val focusManager = LocalFocusManager.current
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        StyledTextField(value = searchQuery, onValueChange = onSearchQueryChange, placeholderText = placeholderText, modifier = Modifier.weight(1f).height(48.dp), leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }, trailingIcon = { if (searchQuery.isNotEmpty()) { IconButton(onClick = { onSearchQueryChange(""); focusManager.clearFocus() }) { Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp)) } } }, keyboardOptions = KeyboardSecurity.secureChatOptions)
        if (showAddButton) {
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onAddClick, modifier = Modifier.size(48.dp).background(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp))) { Icon(imageVector = Icons.Default.PersonAdd, contentDescription = stringResource(R.string.add_contact), tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
        }
    }
}

@Composable
fun RoundCheckbox(selected: Boolean, modifier: Modifier = Modifier) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Gray
    Box(modifier = modifier.size(24.dp).clip(CircleShape).background(backgroundColor).border(2.dp, borderColor, CircleShape), contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = Color.White) }
}

@Composable
fun SelectionActionsBar(visible: Boolean, selectedCount: Int, onClear: () -> Unit, onSelectAll: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = RectangleShape, color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            Row(modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.onBackground) }
                IconButton(onClick = onSelectAll) { Icon(Icons.Default.DoneAll, contentDescription = "Select All", tint = MaterialTheme.colorScheme.onBackground) }
                Text(text = selectedCount.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 4.dp))
                Spacer(modifier = Modifier.weight(1f))
                if (selectedCount == 1) {
                    Row {
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Copy", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    IconButton(onClick = onDelete) { Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp)) }
                }
            }
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
                ContactAvatar(username = summary.contact.username, isActiveCall = summary.contact.id == activeCallInfo?.userId)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = summary.contact.username, style = MaterialTheme.typography.titleMedium, fontWeight = if (hasUnread) FontWeight.ExtraBold else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (summary.lastMessage != null) {
                            MessageDirectionIcon(summary.lastMessage!!, currentUserId)
                            Spacer(Modifier.width(6.dp))
                            Text(text = getMessagePreview(summary.lastMessage!!, context, currentUserId), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(text = stringResource(R.string.chat_is_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.height(IntrinsicSize.Min)
                ) {
                    if (!isContact && !isSelectionMode && !isLoading) {
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
                    } else if (!isSelectionMode) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(onClick = onChatClick)
                                .padding(4.dp),
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
                                    imageVector = Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = "Chat",
                                    modifier = Modifier.size(20.dp),
                                    tint = if (hasUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    
                    if (summary.lastMessage != null) {
                        Text(
                            text = formatTimeOnly(summary.lastMessage!!.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                        )
                    } else {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            
            AnimatedVisibility(visible = isExpanded && !isSelectionMode, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { 
                ExpandedActions(onChat = onChatClick, onVoiceCall = onVoiceCall, onVideoCall = onVideoCall, onGames = onGames, onReturnToCall = onReturnToCall, activeCallInfo = activeCallInfo, targetUserId = summary.contact.id, backgroundColor = Color.Transparent) 
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
private fun ExpandedActions(onChat: () -> Unit, onVoiceCall: () -> Unit, onVideoCall: () -> Unit, onGames: () -> Unit, onReturnToCall: () -> Unit, modifier: Modifier = Modifier, activeCallInfo: ActiveCallInfo? = null, targetUserId: String = "", backgroundColor: Color = Color.Transparent) {
    val isAudioActiveWithThisUser = activeCallInfo != null && activeCallInfo.userId == targetUserId && !activeCallInfo.isVideo
    val isVideoActiveWithThisUser = activeCallInfo != null && activeCallInfo.userId == targetUserId && activeCallInfo.isVideo
    val voiceEnabled = activeCallInfo == null || isAudioActiveWithThisUser
    val videoEnabled = activeCallInfo == null || isVideoActiveWithThisUser
    Surface(modifier = modifier.fillMaxWidth(), color = backgroundColor, shape = RectangleShape) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            SquareActionButton(icon = Icons.Default.Extension, contentDescription = "Games", containerColor = Color(0xFFFFA600), onClick = onGames)
            SquareActionButton(icon = Icons.Default.Call, contentDescription = "Voice Call", containerColor = Color(0xFF4CAF50), onClick = { if (isAudioActiveWithThisUser) onReturnToCall() else onVoiceCall() }, enabled = voiceEnabled, showPulse = isAudioActiveWithThisUser, isActive = isAudioActiveWithThisUser)
            SquareActionButton(icon = Icons.Default.Videocam, contentDescription = "Video Call", containerColor = Color(0xFF2196F3), onClick = { if (isVideoActiveWithThisUser) onReturnToCall() else onVideoCall() }, enabled = videoEnabled, showPulse = isVideoActiveWithThisUser, isActive = isVideoActiveWithThisUser)
            SquareActionButton(icon = Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat", containerColor = Color(0xFF00BCD4), onClick = onChat)
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
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp)); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun getMessagePreview(message: ChatMessage, context: Context, currentUserId: String): String {
    val isFromMe = message.fromUserId == currentUserId
    return when (message.type) {
        MessageType.TEXT -> if (isFromMe) context.getString(R.string.preview_text_outgoing) else context.getString(R.string.preview_text_incoming)
        MessageType.IMAGE -> if (isFromMe) context.getString(R.string.preview_image_outgoing) else context.getString(R.string.preview_text_incoming)
        MessageType.VIDEO -> if (isFromMe) context.getString(R.string.preview_video_outgoing) else context.getString(R.string.preview_video_incoming)
        MessageType.AUDIO -> if (isFromMe) context.getString(R.string.preview_audio_outgoing) else context.getString(R.string.preview_audio_incoming)
        MessageType.FILE -> if (isFromMe) context.getString(R.string.preview_file_outgoing) else context.getString(R.string.preview_file_incoming)
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
