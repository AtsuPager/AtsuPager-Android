package com.nax.atsupager.ui.screens.main.bubbles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.network.DownloadProgress
import com.nax.atsupager.data.network.DownloadStatus
import com.nax.atsupager.data.network.PlaybackState
import com.nax.atsupager.ui.screens.main.MainUiUtils
import kotlin.random.Random

@Composable
fun AudioMessage(
    currentUserId: String,
    message: ChatMessage,
    isDownloading: Boolean,
    downloadProgress: DownloadProgress?,
    isUploading: Boolean,
    isSecure: Boolean,
    isDecrypted: Boolean = false,
    playbackState: PlaybackState?,
    onPlayAudio: (ChatMessage) -> Unit,
    onStopAudio: () -> Unit,
    onSeekAudio: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isFromMe = message.fromUserId == currentUserId
    val isPlaying = playbackState?.isPlaying ?: false
    val isPreparing = playbackState?.isDecrypting ?: false
    val progress = playbackState?.progress ?: 0f

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            DirectionAndSecureIcons(isFromMe = isFromMe, isSecure = isSecure, isDecrypted = isDecrypted)
            Spacer(modifier = Modifier.width(4.dp))

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                if (isDownloading || isUploading || isPreparing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { onPlayAudio(message) }, modifier = Modifier.size(32.dp)) {
                        val icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                        Icon(imageVector = icon, contentDescription = stringResource(R.string.play), modifier = Modifier.size(24.dp))
                    }
                }
            }

            if (isPlaying || progress > 0) {
                IconButton(onClick = onStopAudio, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = stringResource(R.string.stop), modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            AudioWaveform(
                progress = progress,
                onSeek = onSeekAudio,
                modifier = Modifier.weight(1f).height(18.dp),
                color = if (isFromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = if (isPlaying || progress > 0) MainUiUtils.formatDurationMillis(playbackState?.currentPosition ?: 0) else MainUiUtils.formatDuration(message.audioDuration),
                style = MaterialTheme.typography.labelSmall,
                color = if (isFromMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (downloadProgress != null) {
            Spacer(modifier = Modifier.height(2.dp))
            FileTransferProgress(
                progress = downloadProgress.progress,
                status = downloadProgress.status,
                color = if (isFromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (isPreparing) {
            Spacer(modifier = Modifier.height(2.dp))
            FileTransferProgress(
                progress = playbackState?.decryptionProgress ?: 0f,
                status = DownloadStatus.DECRYPTING,
                color = if (isFromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AudioWaveform(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek(offset.x / size.width)
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val barWidth = 2.dp.toPx()
        val gap = 2.dp.toPx()
        val count = (width / (barWidth + gap)).toInt()
        val random = Random(42)

        for (i in 0 until count) {
            val x = i * (barWidth + gap)
            val randomHeight = 0.3f + random.nextFloat() * 0.7f
            val barHeight = height * randomHeight
            val y = (height - barHeight) / 2
            val isPlayed = (i.toFloat() / count) <= progress
            val barColor = if (isPlayed) color else color.copy(alpha = 0.3f)

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2)
            )
        }
    }
}
