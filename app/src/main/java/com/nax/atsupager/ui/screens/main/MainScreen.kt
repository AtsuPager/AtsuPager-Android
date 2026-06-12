package com.nax.atsupager.ui.screens.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.webrtc.CallStatusManager
import com.nax.atsupager.security.ClipboardUiHelper
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.ui.components.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCall: (String, Boolean) -> Unit,
    onNavigateToGames: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToHome: (Int) -> Unit,
    callStatusManager: CallStatusManager,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val totalUnread by callStatusManager.totalUnreadCount.collectAsState()
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDownloadDialog by remember { mutableStateOf(false) }
    var selectedMessageForDownload by remember { mutableStateOf<ChatMessage?>(null) }
    
    var showClearConfirm by remember { mutableStateOf(false) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var showExportConfirmBySelection by remember { mutableStateOf(false) }
    var deleteFilesFromDevice by remember { mutableStateOf(false) }
    var deleteForEveryone by remember { mutableStateOf(false) }
    var showForwardPicker by remember { mutableStateOf(false) }

    val isSelectionMode = uiState.selectedMessageIds.isNotEmpty()
    val selectedMessages = remember(uiState.selectedMessageIds, uiState.messages) {
        uiState.messages.filter { it.id in uiState.selectedMessageIds }
    }
    val singleSelectedMessage = selectedMessages.singleOrNull()
    val hasMyMessages = remember(selectedMessages) { selectedMessages.any { it.fromUserId == uiState.currentUserId } }

    val permissionState = rememberMultiplePermissionsState(
        permissions = listOfNotNull(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
        )
    ) {
        if (!it.all { (_, granted) -> granted }) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.sendFile(it) }
    }

    var tempUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) tempUri?.let { viewModel.sendFile(it) }
    }
    val captureVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) tempUri?.let { viewModel.sendFile(it) }
    }

    LaunchedEffect(Unit) { permissionState.launchMultiplePermissionRequest() }
    
    BackHandler(enabled = isSelectionMode) {
        viewModel.clearMessageSelection()
    }

    LaunchedEffect(uiState.navigateToCall) {
        uiState.navigateToCall?.let {
            onNavigateToCall(it.userId, it.isVideo)
            viewModel.onCallNavigated()
        }
    }

    uiState.error?.let {
        LaunchedEffect(it) {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorDismissed()
        }
    }

    // Access Activation Dialog (Intercepting actions)
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

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_chat_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.clear_chat_confirm_msg))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { deleteFilesFromDevice = !deleteFilesFromDevice }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = deleteFilesFromDevice, onCheckedChange = { deleteFilesFromDevice = it })
                        Text(stringResource(R.string.delete_files_on_device), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearChat(deleteFilesFromDevice)
                    showClearConfirm = false
                }) {
                    Text(stringResource(R.string.clear_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_selected_messages_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_selected_messages_msg, uiState.selectedMessageIds.size))
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (hasMyMessages) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { deleteForEveryone = !deleteForEveryone }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(checked = deleteForEveryone, onCheckedChange = { deleteForEveryone = it })
                            Text(stringResource(R.string.delete_for_everyone), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { deleteFilesFromDevice = !deleteFilesFromDevice }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = deleteFilesFromDevice, onCheckedChange = { deleteFilesFromDevice = it })
                        Text(stringResource(R.string.delete_files_on_device), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelectedMessages(deleteFilesFromDevice, deleteForEveryone)
                    showBulkDeleteConfirm = false
                    deleteForEveryone = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showExportConfirmBySelection && singleSelectedMessage != null) {
        ExportConfirmationDialog(
            onDismiss = { showExportConfirmBySelection = false },
            onConfirm = {
                viewModel.exportAndKeepMessage(singleSelectedMessage)
                viewModel.clearMessageSelection()
                showExportConfirmBySelection = false
            }
        )
    }

    if (showForwardPicker) {
        val contacts by viewModel.contacts.collectAsState(initial = emptyList())
        ContactPickerSheet(
            contacts = contacts,
            onContactsSelected = { selectedIds ->
                viewModel.forwardSelectedMessages(selectedIds)
                showForwardPicker = false
            },
            onDismiss = { showForwardPicker = false }
        )
    }

    if (showDownloadDialog && selectedMessageForDownload != null) {
        DownloadOptionsDialog(
            onDismiss = { showDownloadDialog = false; selectedMessageForDownload = null },
            onDownload = { toPublic ->
                viewModel.downloadFile(selectedMessageForDownload!!, toPublic)
                showDownloadDialog = false; selectedMessageForDownload = null
            }
        )
    }

    if (uiState.isMediaViewerOpen) {
        MediaPagerViewer(
            mediaMessages = uiState.mediaMessages,
            initialIndex = uiState.initialMediaIndex,
            viewModel = viewModel,
            onDismiss = viewModel::closeMediaViewer,
            onDownload = { selectedMessageForDownload = it; showDownloadDialog = true },
            downloadingIds = uiState.downloadingMessageIds,
            downloadProgressMap = uiState.downloadProgress,
            onDelete = { msg ->
                viewModel.clearMessageSelection()
                viewModel.toggleMessageSelection(msg.id)
                showBulkDeleteConfirm = true
            }
        )
    }

    Scaffold(
        topBar = {
            Box(modifier = Modifier.fillMaxWidth()) {
                AtsuTopAppBar(
                    mode = TopAppBarMode.CHAT,
                    user = uiState.user,
                    activeCallInfo = uiState.activeCallInfo,
                    activeCallUser = uiState.activeCallUser,
                    callDuration = uiState.callDuration,
                    unreadCount = totalUnread,
                    onActionClick = { action ->
                        if (!isSelectionMode) {
                            when (action) {
                                TopAppBarAction.HOME, TopAppBarAction.HISTORY, TopAppBarAction.CONTACTS -> onNavigateToHome(-1)
                                TopAppBarAction.GAMES -> onNavigateToGames()
                                TopAppBarAction.VOICE_CALL -> viewModel.initiateCall(false)
                                TopAppBarAction.VIDEO_CALL -> viewModel.initiateCall(true)
                                TopAppBarAction.RETURN_TO_CALL -> {
                                    uiState.activeCallInfo?.let { onNavigateToCall(it.userId, it.isVideo) }
                                }
                                TopAppBarAction.CLEAR_CHAT -> showClearConfirm = true
                                else -> {}
                            }
                        }
                    },
                    onOpenSettings = { if (!isSelectionMode) onOpenSettings() }
                )

                SelectionActionsBar(
                    visible = isSelectionMode,
                    selectedCount = uiState.selectedMessageIds.size,
                    onClear = viewModel::clearMessageSelection,
                    onSelectAll = viewModel::selectAllMessages,
                    onForward = { showForwardPicker = true },
                    onDelete = { showBulkDeleteConfirm = true },
                    canCopy = singleSelectedMessage?.type == MessageType.TEXT,
                    onCopy = {
                        singleSelectedMessage?.let { msg ->
                            ClipboardUiHelper.copyWithNotification(
                                context = context,
                                scope = scope,
                                snackbarHostState = snackbarHostState,
                                label = "ChatMessage",
                                text = msg.text
                            )
                            viewModel.clearMessageSelection()
                        }
                    },
                    canSelectText = singleSelectedMessage?.type == MessageType.TEXT,
                    onSelectText = {
                        singleSelectedMessage?.let { msg ->
                            viewModel.setSelectingText(msg.id)
                            viewModel.clearMessageSelection()
                        }
                    },
                    canExport = singleSelectedMessage?.localFilePath?.endsWith(".enc") == true,
                    onExport = { showExportConfirmBySelection = true },
                    modifier = Modifier.matchParentSize()
                )
            }
        },
        snackbarHost = { AtsuSnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        ScreenFrame(
            modifier = Modifier
                .padding(innerPadding)
                .imePadding()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (permissionState.allPermissionsGranted) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        ChatLayout(
                            uiState = uiState,
                            onSendMessage = { viewModel.sendMessage(it) },
                            onAttachFile = { viewModel.checkAccess { filePickerLauncher.launch("*/*") } },
                            onFileClick = { message ->
                                val isMediaMessage = message.type == MessageType.IMAGE ||
                                        message.type == MessageType.VIDEO ||
                                        message.type == MessageType.AUDIO ||
                                        message.type == MessageType.FILE

                                if (isMediaMessage && message.localFilePath == null) {
                                    selectedMessageForDownload = message
                                    showDownloadDialog = true
                                } else if (message.localFilePath != null) {
                                    when (message.type) {
                                        MessageType.IMAGE -> viewModel.onViewImage(message)
                                        MessageType.VIDEO -> viewModel.onPlayVideo(message)
                                        MessageType.AUDIO -> viewModel.playAudio(message)
                                        MessageType.FILE -> {
                                            if (message.mimeType?.startsWith("audio/") == true) {
                                                viewModel.playAudio(message)
                                            } else {
                                                MainUiUtils.openFile(context, message)
                                            }
                                        }
                                        else -> MainUiUtils.openFile(context, message)
                                    }
                                }
                            },
                            onDeleteMessage = viewModel::deleteMessage,
                            onExportMessage = viewModel::exportAndKeepMessage,
                            onStartRecording = viewModel::startRecording,
                            onStopRecording = viewModel::stopRecording,
                            onCancelRecording = viewModel::cancelRecording,
                            onSendRecordedAudio = viewModel::sendRecordedAudio,
                            onPlayAudio = viewModel::playAudio,
                            onStopAudio = { viewModel.audioPlayer.stopAndReset() },
                            onSeekAudio = viewModel::seekAudio,
                            onTakePhoto = { 
                                viewModel.checkAccess {
                                    tempUri = MainUiUtils.createTempUri(context, "jpg")
                                    takePictureLauncher.launch(tempUri!!)
                                }
                            },
                            onCaptureVideo = { 
                                viewModel.checkAccess {
                                    tempUri = MainUiUtils.createTempUri(context, "mp4")
                                    captureVideoLauncher.launch(tempUri!!)
                                }
                            },
                            onToggleSelection = viewModel::toggleMessageSelection,
                            onClearSelection = viewModel::clearMessageSelection,
                            onSelectAll = viewModel::selectAllMessages,
                            onBulkDelete = { showBulkDeleteConfirm = true },
                            onSelectingTextChange = viewModel::setSelectingText,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }
        }
    }
}
