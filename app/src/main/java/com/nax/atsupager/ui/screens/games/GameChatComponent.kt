package com.nax.atsupager.ui.screens.games

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.ui.components.ContactAvatar
import com.nax.atsupager.ui.screens.main.WaveformCanvas
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

@Composable
fun GameChatComponent(
    contactId: String,
    modifier: Modifier = Modifier,
    onMove: ((Float, Float) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onToggleExpanded: ((Boolean) -> Unit)? = null,
    viewModel: GameChatViewModel = hiltViewModel(key = contactId)
) {
    var isExpanded by remember { mutableStateOf(false) }
    val messages by viewModel.messages.collectAsState()
    val contact by viewModel.contact.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()
    val haptic = LocalHapticFeedback.current

    var lastMsgId by remember { mutableLongStateOf(0L) }
    var showHeadsUp by remember { mutableStateOf(false) }

    LaunchedEffect(contactId) {
        viewModel.initChat(contactId)
    }

    LaunchedEffect(messages) {
        val last = messages.lastOrNull()
        if (last != null && last.id > lastMsgId) {
            if (last.fromUserId == contactId && !isExpanded) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showHeadsUp = true
                delay(5000)
                showHeadsUp = false
            }
            lastMsgId = last.id
        }
    }

    LaunchedEffect(isExpanded) {
        onToggleExpanded?.invoke(isExpanded)
        if (isExpanded) {
            viewModel.markAsRead()
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Column(horizontalAlignment = Alignment.End) {
            if (isExpanded) {
                ChatExpandedView(
                    contactId = contactId,
                    messages = messages,
                    viewModel = viewModel,
                    onCollapse = { isExpanded = false },
                    onClose = { onClose?.invoke() }
                )
                Spacer(modifier = Modifier.height(4.dp)) // Уменьшен зазор до 4dp
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.wrapContentSize()
            ) {
                AnimatedVisibility(
                    visible = showHeadsUp && !isExpanded,
                    enter = fadeIn() + slideInHorizontally { it },
                    exit = fadeOut() + slideOutHorizontally { it }
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 0.dp),
                        modifier = Modifier.padding(end = 8.dp).widthIn(max = 180.dp),
                        shadowElevation = 6.dp
                    ) {
                        Text(
                            text = stringResource(R.string.notification_new_msg),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(contentAlignment = Alignment.Center) {
                    if (unreadCount > 0 && !isExpanded) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 1f, targetValue = 1.25f,
                            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                            label = "scale"
                        )
                        Box(Modifier.size(64.dp).scale(pulseScale).background(MaterialTheme.colorScheme.primary.copy(0.4f), CircleShape))
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .pointerInput(Unit) {
                                if (onMove != null) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        onMove(dragAmount.x, dragAmount.y)
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { isExpanded = !isExpanded })
                            }
                    ) {
                        ContactAvatar(username = contact?.username ?: "...", size = 64.dp)
                        
                        if (unreadCount > 0 && !isExpanded) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                                shape = CircleShape, color = Color.Red, shadowElevation = 4.dp
                            ) {
                                Text(unreadCount.toString(), color = Color.White, fontSize = 11.sp, modifier = Modifier.wrapContentSize(), fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatExpandedView(
    contactId: String,
    messages: List<ChatMessage>,
    viewModel: GameChatViewModel,
    onCollapse: () -> Unit,
    onClose: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var amplitudes by remember { mutableStateOf(listOf<Float>()) }
    var recordingTime by remember { mutableStateOf("00:00") }
    var recordingStartTime by remember { mutableLongStateOf(0L) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .width(260.dp)
            .heightIn(min = 200.dp, max = 340.dp)
            .fillMaxHeight(0.5f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.tab_chats), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, null)
                }
            }

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
                items(messages.reversed()) { msg -> 
                    MessageItem(msg, viewModel) 
                }
            }

            if (isRecording) {
                RecordingRow(recordingTime, amplitudes, 
                    onCancel = { 
                        recordingJob?.cancel()
                        viewModel.cancelRecording()
                        isRecording = false 
                    },
                    onSend = {
                        recordingJob?.cancel()
                        val duration = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
                        viewModel.stopRecording(contactId, duration)
                        isRecording = false
                    }
                )
            } else {
                InputRow(text, { text = it }, 
                    onSend = {
                        if (text.isNotBlank()) {
                            viewModel.sendMessage(text)
                            text = ""
                        }
                    },
                    onStartRecord = {
                        if (viewModel.startRecording(contactId)) {
                            isRecording = true
                            recordingStartTime = System.currentTimeMillis()
                            amplitudes = emptyList()
                            recordingJob = scope.launch {
                                while (true) {
                                    val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                                    recordingTime = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                                    val amp = (viewModel.getMaxAmplitude().toFloat() / 32767f).pow(0.3f)
                                    amplitudes = (amplitudes + amp).takeLast(40)
                                    delay(100)
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageItem(msg: ChatMessage, viewModel: GameChatViewModel) {
    val isMe = msg.fromUserId == viewModel.currentUserId
    val downloadingIds by viewModel.downloadingIds.collectAsState()
    val isDownloading = downloadingIds.contains(msg.id)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (msg.type == MessageType.AUDIO) {
                Row(modifier = Modifier.padding(8.dp).clickable(enabled = !isDownloading) { viewModel.playAudio(msg) }, verticalAlignment = Alignment.CenterVertically) {
                    if (isDownloading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.audio_file), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                Text(text = msg.text, modifier = Modifier.padding(10.dp), fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun InputRow(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit, onStartRecord: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = text, 
            onValueChange = onTextChange, 
            modifier = Modifier.weight(1f), 
            placeholder = { Text("...", fontSize = 13.sp) }, 
            colors = TextFieldDefaults.colors(unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent),
            textStyle = TextStyle(fontSize = 13.sp),
            keyboardOptions = KeyboardSecurity.secureChatOptions
        )
        IconButton(onClick = if (text.isEmpty()) onStartRecord else onSend, modifier = Modifier.size(40.dp)) {
            Icon(if (text.isEmpty()) Icons.Default.Mic else Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RecordingRow(recordingTime: String, amplitudes: List<Float>, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer.copy(0.4f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, null, tint = Color.Gray) }
        Text(recordingTime, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        WaveformCanvas(amplitudes, modifier = Modifier.weight(1f).height(28.dp))
        IconButton(onClick = onSend, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Check, null, tint = Color.Green) }
    }
}
