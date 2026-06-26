package com.nax.atsupager.ui.screens.main

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.shadow
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.data.network.DownloadProgress
import com.nax.atsupager.data.network.PlaybackState
import com.nax.atsupager.ui.components.ContactAvatar
import com.nax.atsupager.ui.screens.main.bubbles.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures

@Composable
fun MessageBubble(
    currentUserId: String,
    message: ChatMessage,
    isDownloading: Boolean,
    downloadProgress: DownloadProgress?,
    isUploading: Boolean,
    playbackState: PlaybackState?,
    isSelectingText: Boolean,
    onSelectionChange: (Long?) -> Unit,
    isSelected: Boolean,
    isInSelectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onFileClick: (ChatMessage) -> Unit,
    onDeleteMessage: (ChatMessage, Boolean, Boolean) -> Unit,
    onExportMessage: (ChatMessage) -> Unit,
    onPlayAudio: (ChatMessage) -> Unit,
    onStopAudio: () -> Unit,
    onSeekAudio: (Float) -> Unit,
    snackbarHostState: SnackbarHostState,
    isGroup: Boolean = false,
    senderName: String? = null,
    memberNames: Map<String, String> = emptyMap(),
    onReply: ((ChatMessage) -> Unit)? = null,
    onScrollToMessage: ((String) -> Unit)? = null
) {
    val isFromMe = message.fromUserId == currentUserId
    val isCallEvent = message.type == MessageType.INCOMING_CALL ||
                      message.type == MessageType.OUTGOING_CALL ||
                      message.type == MessageType.MISSED_CALL
    
    val isSystem = message.type == MessageType.SYSTEM
    val isGameInvite = message.type == MessageType.GAME_INVITE

    val currentOnFileClick by rememberUpdatedState(onFileClick)
    val currentMessage by rememberUpdatedState(message)

    val arrangement = when {
        isSystem || isGameInvite -> Arrangement.Center
        isFromMe -> Arrangement.End
        else -> Arrangement.Start
    }
    
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    } else if (isSystem || isGameInvite) {
        Color.Transparent
    } else if (isFromMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    
    val bubbleShape = if (isFromMe) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    val selectionShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(
                if (isSelected) Modifier
                    .padding(horizontal = 6.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), selectionShape)
                    .border(width = 0.5.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), selectionShape)
                else Modifier
            )
            .pointerInput(message.id, isSelectingText, isInSelectionMode) {
                detectTapGestures(
                    onLongPress = { 
                        if (!isSelectingText && !isSystem) {
                            onToggleSelection()
                        }
                    },
                    onTap = { 
                        if (isInSelectionMode) {
                            onToggleSelection()
                        } else if (isSelectingText) {
                            onSelectionChange(null)
                        } else {
                            currentOnFileClick(currentMessage)
                        }
                    }
                )
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.Bottom
    ) {
        Column(horizontalAlignment = if (isFromMe && !isSystem && !isGameInvite) Alignment.End else Alignment.Start) {
            if (isGroup && !isFromMe && senderName != null && !isCallEvent && !isSystem && !isGameInvite) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp, end = 12.dp)
                ) {
                    ContactAvatar(
                        username = senderName,
                        size = 24.dp,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Box {
                val isSecure = message.localFilePath?.endsWith(".enc") == true
                val isDecrypted = !isSecure && message.localFilePath != null && 
                                 (message.localFilePath.contains("AtsuPager") || message.localFilePath.startsWith("content://"))

                val messageContentModifier = Modifier
                    .widthIn(max = 330.dp)
                    .then(
                        if (!isSystem && !isGameInvite) {
                            Modifier.shadow(elevation = if (isSelected) 0.dp else if (isCallEvent) 0.dp else 1.dp, shape = bubbleShape)
                                    .background(backgroundColor, bubbleShape)
                        } else Modifier
                    )
                    .padding(
                        horizontal = if (isCallEvent || isSystem || isGameInvite) 0.dp else 12.dp,
                        vertical = if (isCallEvent || isSystem || isGameInvite) 0.dp else 8.dp
                    )

                val isSideTimeType = message.type == MessageType.AUDIO || 
                                     message.type == MessageType.FILE ||
                                     message.type == MessageType.IMAGE ||
                                     message.type == MessageType.VIDEO

                Column(modifier = messageContentModifier) {
                    // Отображение цитируемого сообщения
                    if (message.replyToId != null && !isCallEvent && !isSystem) {
                        QuotedMessage(
                            name = message.replyToName,
                            text = message.replyToText,
                            type = message.replyToType,
                            onClick = { message.replyToId?.let { onScrollToMessage?.invoke(it) } },
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    if (isSideTimeType) {
                        Column {
                            val hasCaption = !message.text.isNullOrBlank()
                            Row(
                                modifier = Modifier.wrapContentWidth(),
                                verticalAlignment = if (message.type == MessageType.AUDIO || message.type == MessageType.FILE) 
                                                    Alignment.CenterVertically else Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(modifier = if (message.type == MessageType.AUDIO || message.type == MessageType.FILE) 
                                               Modifier.weight(1f, fill = false) else Modifier) {
                                    when (message.type) {
                                        MessageType.IMAGE -> ImageMessage(message, isDownloading, downloadProgress, isUploading, isFromMe, isSecure, isDecrypted)
                                        MessageType.VIDEO -> VideoMessage(message, isDownloading, downloadProgress, isUploading, isFromMe, isSecure, isDecrypted)
                                        MessageType.AUDIO -> AudioMessage(currentUserId, message, isDownloading, downloadProgress, isUploading, isSecure, isDecrypted, playbackState, onPlayAudio, onStopAudio, onSeekAudio)
                                        MessageType.FILE -> FileMessage(message, isDownloading, downloadProgress, isUploading, isFromMe, isSecure, isDecrypted, playbackState, { onPlayAudio(message) }, onStopAudio, onSeekAudio)
                                        else -> {}
                                    }
                                }
                                
                                if (!isCallEvent && !hasCaption) {
                                    TimestampWithSeparator(message, isFromMe, isCompact = true)
                                }
                            }
                            
                            if (hasCaption) {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                )
                                if (!isCallEvent) {
                                    TimestampWithSeparator(
                                        message = message,
                                        isFromMe = isFromMe,
                                        isCompact = false,
                                        modifier = Modifier.align(Alignment.End)
                                    )
                                }
                            }
                        }
                    } else {
                        when (message.type) {
                            MessageType.TEXT, MessageType.SYSTEM, MessageType.GAME_INVITE -> 
                                TextMessage(message, isFromMe, isSecure, isDecrypted, isSelectingText, memberNames)
                            MessageType.INCOMING_CALL, MessageType.OUTGOING_CALL, MessageType.MISSED_CALL -> 
                                CallEventMessage(message.type, message.timestamp, message.text)
                            else -> {}
                        }
                        
                        if (!isCallEvent && !isSystem) {
                            TimestampWithSeparator(
                                message = message,
                                isFromMe = isFromMe,
                                isCompact = false,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimestampWithSeparator(
    message: ChatMessage,
    isFromMe: Boolean,
    isCompact: Boolean, 
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.then(if (!isCompact) Modifier.padding(top = 2.dp) else Modifier)
    ) {
        if (isFromMe) {
            val icon = if (message.remoteRead) Icons.Filled.DoneAll else if (message.isDelivered) Icons.Filled.DoneAll else Icons.Filled.Done
            val tint = if (message.remoteRead) Color(0xFF4CAF50) 
                       else if (message.isDelivered) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
        }

        Text(
            text = "·",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        )
        Text(
            text = MainUiUtils.formatTime(message.timestamp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}
