package com.nax.atsupager.ui.screens.main.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.network.DownloadProgress
import com.nax.atsupager.ui.screens.main.MainUiUtils

@Composable
fun VideoMessage(
    message: ChatMessage,
    isDownloading: Boolean,
    downloadProgress: DownloadProgress?,
    isUploading: Boolean,
    isFromMe: Boolean,
    isSecure: Boolean,
    isDecrypted: Boolean = false
) {
    val displayPath = message.localFilePath
    val coilModel = remember(displayPath) { MainUiUtils.getCoilModel(message) }
    val context = LocalContext.current

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        // Иконки теперь слева от контента
        Box(modifier = Modifier.padding(top = 2.dp)) {
            DirectionAndSecureIcons(isFromMe = isFromMe, isSecure = isSecure, isDecrypted = isDecrypted)
        }
        
        Spacer(modifier = Modifier.width(6.dp))

        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            if (coilModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(coilModel)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                    contentDescription = "Video thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            when {
                isUploading -> CircularProgressIndicator(color = Color.White)
                downloadProgress != null -> {
                    FileTransferProgress(
                        progress = downloadProgress.progress,
                        status = downloadProgress.status,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                isDownloading -> CircularProgressIndicator(color = Color.White)
                displayPath == null -> {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(R.string.download),
                        modifier = Modifier.size(48.dp),
                        tint = Color.White
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = stringResource(R.string.play),
                        modifier = Modifier.size(48.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}
