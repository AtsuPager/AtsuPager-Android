package com.nax.atsupager.ui.screens.main.bubbles

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.network.DownloadProgress
import com.nax.atsupager.data.network.DownloadStatus
import com.nax.atsupager.data.network.PlaybackState
import com.nax.atsupager.ui.screens.main.MainUiUtils

@Composable
fun FileMessage(
    message: ChatMessage,
    isDownloading: Boolean,
    downloadProgress: DownloadProgress?,
    isUploading: Boolean,
    isFromMe: Boolean,
    isSecure: Boolean,
    isDecrypted: Boolean = false,
    playbackState: PlaybackState?,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isAudio = message.mimeType?.startsWith("audio/") == true
    val isPlaying = playbackState?.isPlaying ?: false
    val isPreparing = playbackState?.isDecrypting ?: false
    val playProgress = playbackState?.progress ?: 0f

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DirectionAndSecureIcons(isFromMe, isSecure, isDecrypted)
            
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                if (isDownloading || isUploading || isPreparing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { if (isAudio) onPlayPause() }) {
                        val icon = if (isAudio) {
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                        } else {
                            Icons.Default.AttachFile
                        }
                        Icon(imageVector = icon, contentDescription = "File", modifier = Modifier.size(32.dp))
                    }
                }
            }

            if (isAudio && (isPlaying || playProgress > 0)) {
                IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = stringResource(R.string.stop), modifier = Modifier.size(24.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.fileName ?: "File",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isPlaying || playProgress > 0)
                        "${MainUiUtils.formatDurationMillis(playbackState?.currentPosition ?: 0)} / ${MainUiUtils.formatDurationMillis(playbackState?.duration ?: 0)}"
                    else
                        MainUiUtils.formatFileSize(message.fileSize ?: 0),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (downloadProgress != null) {
            Spacer(modifier = Modifier.height(4.dp))
            FileTransferProgress(
                progress = downloadProgress.progress,
                status = downloadProgress.status,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (isPreparing) {
            Spacer(modifier = Modifier.height(4.dp))
            FileTransferProgress(
                progress = playbackState?.decryptionProgress ?: 0f,
                status = DownloadStatus.DECRYPTING,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (isAudio && (isPlaying || playProgress > 0)) {
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = playProgress,
                onValueChange = onSeek,
                modifier = Modifier.fillMaxWidth().height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
            )
        }
    }
}
