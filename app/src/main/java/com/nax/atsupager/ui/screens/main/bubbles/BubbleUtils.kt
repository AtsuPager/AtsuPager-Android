package com.nax.atsupager.ui.screens.main.bubbles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.data.network.DownloadStatus

@Composable
fun DirectionAndSecureIcons(isFromMe: Boolean, isSecure: Boolean, isDecrypted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isFromMe) Icons.AutoMirrored.Filled.CallMade else Icons.Filled.SouthWest,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        if (isSecure) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Secure",
                modifier = Modifier.size(18.dp),
                tint = Color(0xFF4CAF50) // Зеленый для зашифрованного
            )
        } else if (isDecrypted) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.LockOpen,
                contentDescription = "Decrypted",
                modifier = Modifier.size(18.dp),
                tint = Color.Red // Красный для расшифрованного
            )
        }
    }
}

@Composable
fun FileTransferProgress(
    progress: Float,
    status: DownloadStatus,
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val label = when (status) {
            DownloadStatus.DOWNLOADING -> stringResource(R.string.downloading)
            DownloadStatus.DECRYPTING -> stringResource(R.string.decrypting)
        }
        Text(
            text = if (progress >= 0) "$label ${(progress * 100).toInt()}%" else label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = if (color == Color.White) MaterialTheme.colorScheme.primary else color,
            trackColor = color.copy(alpha = 0.3f)
        )
    }
}
