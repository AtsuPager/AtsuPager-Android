package com.nax.atsupager.ui.screens.main

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.security.ClipboardSecurityManager
import com.nax.atsupager.security.ClipboardUiHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

@Composable
fun ChatLayout(
    uiState: MainUiState,
    onSendMessage: (String) -> Unit,
    onAttachFile: () -> Unit,
    onFileClick: (ChatMessage) -> Unit,
    onDeleteMessage: (ChatMessage, Boolean, Boolean) -> Unit,
    onExportMessage: (ChatMessage) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onSendRecordedAudio: () -> Unit,
    onPlayAudio: (ChatMessage) -> Unit,
    onStopAudio: () -> Unit,
    onSeekAudio: (Float) -> Unit,
    onTakePhoto: () -> Unit,
    onCaptureVideo: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onBulkDelete: () -> Unit,
    onSelectingTextChange: (Long?) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.messages.size - 1)
        }
    }

    val currentToolbar = LocalTextToolbar.current
    val customToolbar = remember(currentToolbar) {
        object : TextToolbar {
            override val status: TextToolbarStatus get() = currentToolbar.status
            override fun hide() = currentToolbar.hide()
            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?
            ) {
                val securedCopy = onCopyRequested?.let { originalCopy ->
                    {
                        originalCopy()
                        scope.launch {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                snackbarHostState.showSnackbar(context.getString(R.string.text_copied))
                            }
                            ClipboardSecurityManager.clearClipboard(context)
                            com.nax.atsupager.security.ClipboardClearWorker.enqueue(context, 60000)
                            delay(60000)
                            snackbarHostState.showSnackbar(context.getString(R.string.clipboard_cleared))
                        }
                        Unit
                    }
                }
                currentToolbar.showMenu(rect, securedCopy, onPasteRequested, onCutRequested, onSelectAllRequested)
            }
        }
    }

    val isSelectionMode = uiState.selectedMessageIds.isNotEmpty()

    CompositionLocalProvider(LocalTextToolbar provides customToolbar) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { 
                            if (isSelectionMode) {
                                onClearSelection()
                            } else {
                                onSelectingTextChange(null) 
                            }
                        })
                    },
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(uiState.messages, key = { _, message -> message.id }) { index, message ->
                    if (shouldShowDate(uiState.messages, index)) {
                        DateHeader(timestamp = message.timestamp)
                    }
                    MessageBubble(
                        currentUserId = uiState.currentUserId,
                        message = message,
                        isDownloading = uiState.downloadingMessageIds.contains(message.id),
                        downloadProgress = uiState.downloadProgress[message.id],
                        isUploading = uiState.uploadingMessageIds.contains(message.id),
                        playbackState = if (uiState.playbackState.messageId?.toString() == message.id.toString()) uiState.playbackState else null,
                        isSelectingText = uiState.selectingTextMsgId == message.id,
                        onSelectionChange = onSelectingTextChange,
                        isSelected = uiState.selectedMessageIds.contains(message.id),
                        isInSelectionMode = isSelectionMode,
                        onToggleSelection = { onToggleSelection(message.id) },
                        onFileClick = onFileClick,
                        onDeleteMessage = onDeleteMessage,
                        onExportMessage = onExportMessage,
                        onPlayAudio = onPlayAudio,
                        onStopAudio = onStopAudio,
                        onSeekAudio = onSeekAudio,
                        snackbarHostState = snackbarHostState
                    )
                }
            }
            
            // Контейнер панели ввода. Всегда занимает место.
            Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                Box(modifier = Modifier.graphicsLayer { alpha = if (isSelectionMode) 0.6f else 1f }) {
                    MessageInput(
                        text = text,
                        uiState = uiState,
                        onTextChange = { if (!isSelectionMode) text = it },
                        onSend = {
                            if (!isSelectionMode) {
                                onSendMessage(text)
                                text = ""
                            }
                        },
                        onAttachFile = onAttachFile,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                        onCancelRecording = onCancelRecording,
                        onSendRecordedAudio = onSendRecordedAudio,
                        onTakePhoto = onTakePhoto,
                        onCaptureVideo = onCaptureVideo
                    )
                }
                
                // Блокировщик нажатий в режиме выделения
                if (isSelectionMode) {
                    Box(modifier = Modifier.matchParentSize().clickable(enabled = true, onClick = {}))
                }
            }
        }
    }
}

@Composable
fun DateHeader(timestamp: Long) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text = MainUiUtils.formatDate(timestamp, context),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun shouldShowDate(messages: List<ChatMessage>, index: Int): Boolean {
    if (index == 0) return true
    val prevDate = Calendar.getInstance().apply { timeInMillis = messages[index - 1].timestamp }
    val currDate = Calendar.getInstance().apply { timeInMillis = messages[index].timestamp }
    return prevDate.get(Calendar.DAY_OF_YEAR) != currDate.get(Calendar.DAY_OF_YEAR) ||
            prevDate.get(Calendar.YEAR) != currDate.get(Calendar.YEAR)
}
