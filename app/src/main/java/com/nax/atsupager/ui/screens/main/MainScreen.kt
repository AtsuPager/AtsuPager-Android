/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.webrtc.CallStatusManager
import com.nax.atsupager.security.ClipboardUiHelper
import com.nax.atsupager.ui.components.*

sealed class MainDialog {
    object ClearChat : MainDialog()
    object BulkDelete : MainDialog()
    object ExportConfirm : MainDialog()
    object ForwardPicker : MainDialog()
    object AddMemberPicker : MainDialog()
    object MembersSheet : MainDialog()
    object LeaveGroupOptions : MainDialog()
    data class DownloadOptions(val message: ChatMessage) : MainDialog()
}

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

    var activeDialog by remember { mutableStateOf<MainDialog?>(null) }

    val isSelectionMode = uiState.selectedMessageIds.isNotEmpty()
    val selectedMessages = remember(uiState.selectedMessageIds, uiState.messages) {
        uiState.messages.filter { it.id in uiState.selectedMessageIds }
    }
    val singleSelectedMessage = selectedMessages.singleOrNull()
    val hasMyMessages = remember(selectedMessages) { selectedMessages.any { it.fromUserId == uiState.currentUserId } }
    val isOwner = uiState.group?.ownerId == uiState.currentUserId
    val canDeleteForEveryone = hasMyMessages || (uiState.isGroup && isOwner)

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
        uri?.let { viewModel.prepareFileAttachment(it) }
    }

    var tempUri by remember { mutableStateOf<Uri?>(null) }
    val captureVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) tempUri?.let { viewModel.prepareFileAttachment(it) }
    }

    LaunchedEffect(Unit) { permissionState.launchMultiplePermissionRequest() }
    
    BackHandler(enabled = isSelectionMode || uiState.isCameraOpen) {
        if (uiState.isCameraOpen) viewModel.closeCamera()
        else viewModel.clearMessageSelection()
    }

    LaunchedEffect(uiState.navigateToCall) {
        uiState.navigateToCall?.let {
            onNavigateToCall(it.userId, it.isVideo)
            viewModel.onCallNavigated()
        }
    }
    
    LaunchedEffect(uiState.isGroupLeft) {
        if (uiState.isGroupLeft) {
            onNavigateBack()
        }
    }

    uiState.error?.let {
        LaunchedEffect(it) {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorDismissed()
        }
    }

    // Camera Overlay
    if (uiState.isCameraOpen) {
        AtsuCamera(
            onImageCaptured = { viewModel.onImageCaptured(it) },
            onCancel = { viewModel.closeCamera() }
        )
        return
    }

    // Access Activation Dialog
    if (uiState.showAccessDialog) {
        AccessCodeDialog(
            onDismiss = { viewModel.closeAccessDialog() },
            onVerify = { code, onResult -> viewModel.applyAccessCode(code, onResult) }
        )
    }

    // Dialog Handling
    when (val dialog = activeDialog) {
        is MainDialog.ClearChat -> {
            ClearChatConfirmDialog(
                onDismiss = { activeDialog = null },
                onConfirm = { deleteFiles, forEveryone ->
                    viewModel.clearChat(deleteFiles, forEveryone)
                    activeDialog = null
                }
            )
        }
        is MainDialog.BulkDelete -> {
            BulkDeleteMessagesDialog(
                count = uiState.selectedMessageIds.size,
                canDeleteForEveryone = canDeleteForEveryone,
                onDismiss = { activeDialog = null },
                onConfirm = { deleteFiles, forEveryone ->
                    viewModel.deleteSelectedMessages(deleteFiles, forEveryone)
                    activeDialog = null
                }
            )
        }
        is MainDialog.ExportConfirm -> {
            if (singleSelectedMessage != null) {
                ExportConfirmationDialog(
                    onDismiss = { activeDialog = null },
                    onConfirm = {
                        viewModel.exportAndKeepMessage(singleSelectedMessage)
                        viewModel.clearMessageSelection()
                        activeDialog = null
                    }
                )
            }
        }
        is MainDialog.ForwardPicker -> {
            val contacts by viewModel.contacts.collectAsState(initial = emptyList())
            val groups by viewModel.groups.collectAsState(initial = emptyList())
            val allTargets = remember(contacts, groups) {
                contacts.map { PickerTarget(it.id, it.username, false) } +
                groups.map { PickerTarget(it.groupId, it.name, true) }
            }
            ContactPickerSheet(
                title = stringResource(R.string.forward_to),
                targets = allTargets,
                onContactsSelected = { selectedIds ->
                    viewModel.forwardSelectedMessages(selectedIds)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        is MainDialog.AddMemberPicker -> {
            val contacts by viewModel.contacts.collectAsState(initial = emptyList())
            val currentMemberIds = uiState.groupMembers.map { it.id }.toSet()
            val availableContacts = contacts.filter { it.id !in currentMemberIds }
            ContactPickerSheet(
                title = stringResource(R.string.add_member),
                contacts = availableContacts,
                onContactsSelected = { selectedIds ->
                    viewModel.addGroupMembers(selectedIds)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        is MainDialog.MembersSheet -> {
            GroupMembersSheet(
                members = uiState.groupMembers,
                ownerId = uiState.group?.ownerId ?: "",
                currentUserId = uiState.currentUserId,
                onKickMember = { userId -> viewModel.kickMember(userId) },
                onDismiss = { activeDialog = null }
            )
        }
        is MainDialog.LeaveGroupOptions -> {
            LeaveGroupOptionsDialog(
                isAdmin = uiState.isAdmin || isOwner,
                onDismiss = { activeDialog = null },
                onLeave = { deleteFiles, forEveryone ->
                    viewModel.leaveGroup(deleteFiles, forEveryone)
                    activeDialog = null
                },
                onDelete = {
                    viewModel.deleteGroup()
                    activeDialog = null
                }
            )
        }
        is MainDialog.DownloadOptions -> {
            DownloadOptionsDialog(
                onDismiss = { activeDialog = null },
                onDownload = { toPublic ->
                    viewModel.downloadFile(dialog.message, toPublic)
                    activeDialog = null
                }
            )
        }
        null -> {}
    }

    if (uiState.isMediaViewerOpen) {
        MediaPagerViewer(
            mediaMessages = uiState.mediaMessages,
            initialIndex = uiState.initialMediaIndex,
            viewModel = viewModel,
            onDismiss = viewModel::closeMediaViewer,
            onDownload = { activeDialog = MainDialog.DownloadOptions(it) },
            downloadingIds = uiState.downloadingMessageIds,
            downloadProgressMap = uiState.downloadProgress,
            onDelete = { msg ->
                viewModel.clearMessageSelection()
                viewModel.toggleMessageSelection(msg.id)
                activeDialog = MainDialog.BulkDelete
            }
        )
    }

    Scaffold(
        topBar = {
            Box(modifier = Modifier.fillMaxWidth()) {
                AtsuTopAppBar(
                    mode = TopAppBarMode.CHAT,
                    user = uiState.user,
                    group = uiState.group,
                    currentUserId = uiState.currentUserId,
                    activeCallInfo = uiState.activeCallInfo,
                    activeCallUser = uiState.activeCallUser,
                    callDuration = uiState.callDuration,
                    unreadCount = totalUnread,
                    isMuted = uiState.isMuted,
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
                                TopAppBarAction.CLEAR_CHAT -> activeDialog = MainDialog.ClearChat
                                TopAppBarAction.ADD_MEMBER -> activeDialog = MainDialog.AddMemberPicker
                                TopAppBarAction.GROUP_MEMBERS -> activeDialog = MainDialog.MembersSheet
                                TopAppBarAction.LEAVE_GROUP -> activeDialog = MainDialog.LeaveGroupOptions
                                TopAppBarAction.TOGGLE_MUTE -> viewModel.toggleMute()
                                else -> {}
                            }
                        }
                    },
                    onOpenSettings = { if (!isSelectionMode) onOpenSettings() }
                )

                SelectionActionsBar(
                    visible = isSelectionMode,
                    selectedCount = uiState.selectedMessageIds.size,
                    totalCount = uiState.messages.size,
                    onClear = viewModel::clearMessageSelection,
                    onSelectAll = viewModel::selectAllMessages,
                    onReply = {
                        singleSelectedMessage?.let { 
                            viewModel.startReply(it)
                            viewModel.clearMessageSelection()
                        }
                    },
                    canReply = true,
                    onForward = { 
                        if (singleSelectedMessage?.isPrivate == true) {
                            viewModel.onErrorDismissed()
                            viewModel.clearMessageSelection()
                        } else {
                            activeDialog = MainDialog.ForwardPicker 
                        }
                    },
                    canForward = singleSelectedMessage?.isPrivate != true,
                    onDelete = { activeDialog = MainDialog.BulkDelete },
                    canCopy = singleSelectedMessage?.type == MessageType.TEXT && singleSelectedMessage?.isPrivate != true,
                    onCopy = {
                        singleSelectedMessage?.let { msg ->
                            if (msg.isPrivate) {
                                // Blocked via 'canCopy' check, but safety first
                                return@let
                            }
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
                    canSelectText = singleSelectedMessage?.type == MessageType.TEXT && singleSelectedMessage?.isPrivate != true,
                    onSelectText = {
                        singleSelectedMessage?.let { msg ->
                            viewModel.setSelectingText(msg.id)
                            viewModel.clearMessageSelection()
                        }
                    },
                    canExport = singleSelectedMessage?.localFilePath?.endsWith(".enc") == true && singleSelectedMessage?.isPrivate != true,
                    onExport = { activeDialog = MainDialog.ExportConfirm },
                    canSave = selectedMessages.isNotEmpty(),
                    isSaved = selectedMessages.isNotEmpty() && selectedMessages.all { it.isSaved },
                    onToggleSave = { viewModel.toggleSaveMessages(selectedMessages) },
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
                                
                                val isAudioType = message.type == MessageType.AUDIO || 
                                                 (message.type == MessageType.FILE && message.mimeType?.startsWith("audio/") == true)

                                if (isMediaMessage && message.localFilePath == null) {
                                    // Для аудио не показываем диалог выбора папки при клике на пузырь,
                                    // чтобы всё взаимодействие шло через кнопку Play.
                                    if (!isAudioType) {
                                        activeDialog = MainDialog.DownloadOptions(message)
                                    }
                                } else if (message.localFilePath != null) {
                                    when (message.type) {
                                        MessageType.IMAGE -> viewModel.onViewImage(message)
                                        MessageType.VIDEO -> viewModel.onPlayVideo(message)
                                        MessageType.AUDIO -> { /* Игнорируем клик на пузырь, Play нажмут сами */ }
                                        MessageType.FILE -> {
                                            if (message.mimeType?.startsWith("audio/") == true) {
                                                /* Игнорируем клик на пузырь */
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
                                viewModel.checkAccess { viewModel.openCamera() }
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
                            onBulkDelete = { activeDialog = MainDialog.BulkDelete },
                            onSelectingTextChange = viewModel::setSelectingText,
                            onReply = viewModel::startReply,
                            onCancelReply = viewModel::cancelReply,
                            onScrollToMessage = { remoteId -> },
                            onCaptionChange = viewModel::updatePendingAttachmentCaption,
                            onCancelAttachment = viewModel::cancelPendingAttachment,
                            onSendAttachment = viewModel::sendPendingAttachment,
                            onTogglePrivateMode = viewModel::togglePrivateMode,
                            onTogglePendingPrivate = viewModel::togglePendingAttachmentPrivate,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }
        }
    }
}
