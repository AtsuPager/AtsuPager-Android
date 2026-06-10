package com.nax.atsupager.ui.screens.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.nax.atsupager.R
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.ui.components.StyledTextField

@Composable
fun MessageInput(
    text: String,
    uiState: MainUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachFile: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onSendRecordedAudio: () -> Unit,
    onTakePhoto: () -> Unit,
    onCaptureVideo: () -> Unit
) {
    val isRecordingOrReady = uiState.isRecording || uiState.isAudioReadyToSend

    AnimatedContent(
        targetState = isRecordingOrReady,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "MessageInputSwitch"
    ) { recordingMode ->
        if (recordingMode) {
            RecordingLayout(
                uiState = uiState,
                onCancel = onCancelRecording,
                onStop = onStopRecording,
                onSend = onSendRecordedAudio
            )
        } else {
            InputLayout(
                text = text,
                onTextChange = onTextChange,
                onSend = onSend,
                onAttachFile = onAttachFile,
                onTakePhoto = onTakePhoto,
                onCaptureVideo = onCaptureVideo,
                onStartRecording = onStartRecording
            )
        }
    }
}

@Composable
private fun RecordingLayout(
    uiState: MainUiState,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 12.dp)
            .heightIn(min = 42.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = Color.Gray, modifier = Modifier.size(20.dp))
        }

        Spacer(modifier = Modifier.width(4.dp))

        if (uiState.isRecording) {
            RecordingPulseIndicator()
        } else {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.Red))
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = uiState.recordingTime,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false
        )

        WaveformCanvas(
            amplitudes = uiState.amplitudes,
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .padding(horizontal = 8.dp)
        )

        if (uiState.isRecording) {
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop), tint = Color.Red, modifier = Modifier.size(20.dp))
            }
        } else {
            IconButton(onClick = onSend, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Send, contentDescription = stringResource(R.string.send), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun InputLayout(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onCaptureVideo: () -> Unit,
    onStartRecording: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom // Текст растет вверх
    ) {
        StyledTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 42.dp, max = 150.dp), // Минимальная высота как у поиска
            placeholderText = stringResource(R.string.message_placeholder),
            shape = RoundedCornerShape(12.dp),
            singleLine = false, // Позволяем перенос строк в чате
            keyboardOptions = KeyboardSecurity.secureChatOptions
        )
        
        Spacer(modifier = Modifier.width(8.dp))

        if (text.isNotBlank()) {
            IconButton(
                onClick = onSend,
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Default.Send, contentDescription = stringResource(R.string.send), modifier = Modifier.size(22.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onAttachFile, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attach_file), modifier = Modifier.size(22.dp))
                }
                
                var showCameraMenu by remember { mutableStateOf(false) }
                Box(contentAlignment = Alignment.Center) {
                    if (showCameraMenu) {
                        val density = LocalDensity.current
                        Popup(
                            alignment = Alignment.TopCenter,
                            onDismissRequest = { showCameraMenu = false },
                            offset = IntOffset(0, with(density) { (-104).dp.roundToPx() })
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 3.dp,
                                shadowElevation = 3.dp
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(4.dp)
                                ) {
                                    IconButton(onClick = {
                                        showCameraMenu = false
                                        onCaptureVideo()
                                    }) {
                                        Icon(
                                            Icons.Default.Videocam,
                                            contentDescription = stringResource(R.string.capture_video),
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = {
                                        showCameraMenu = false
                                        onTakePhoto()
                                    }) {
                                        Icon(
                                            Icons.Default.PhotoCamera,
                                            contentDescription = stringResource(R.string.take_photo),
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    IconButton(onClick = { showCameraMenu = !showCameraMenu }, modifier = Modifier.size(42.dp)) {
                        Icon(
                            if (showCameraMenu) Icons.Default.Close else Icons.Default.PhotoCamera,
                            contentDescription = stringResource(R.string.take_photo),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                IconButton(onClick = onStartRecording, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.record_audio), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun RecordingPulseIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Alpha"
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(Color.Red.copy(alpha = alpha))
    )
}

@Composable
fun WaveformCanvas(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = 2.dp.toPx()
        val gap = 2.dp.toPx()
        val maxBars = (width / (barWidth + gap)).toInt()

        val displayAmplitudes = amplitudes.takeLast(maxBars)

        displayAmplitudes.forEachIndexed { index, amplitude ->
            val x = index * (barWidth + gap)
            val barHeight = (amplitude * height).coerceAtLeast(2.dp.toPx())
            val y = (height - barHeight) / 2
            drawRoundRect(
                color = Color.Red,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2)
            )
        }
    }
}
