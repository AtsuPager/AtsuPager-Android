package com.nax.atsupager.ui.screens.main.bubbles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nax.atsupager.R
import com.nax.atsupager.data.network.DownloadStatus

@Composable
fun DirectionAndSecureIcons(
    isFromMe: Boolean, 
    isSecure: Boolean, 
    isDecrypted: Boolean = false,
    showLock: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isFromMe) Icons.AutoMirrored.Filled.CallMade else Icons.Default.SouthWest,
            contentDescription = null,
            tint = if (isFromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp)
        )
        if (showLock) {
            Spacer(modifier = Modifier.width(2.dp))
            
            val lockIcon = if (isSecure || (!isSecure && !isDecrypted)) Icons.Default.Lock else Icons.Default.LockOpen
            val lockTint = when {
                isSecure -> Color(0xFF4CAF50) // Зеленый
                isDecrypted -> Color(0xFFF44336) // Красный
                else -> Color.Gray // Серый
            }

            Icon(
                imageVector = lockIcon,
                contentDescription = null,
                tint = lockTint,
                modifier = Modifier.size(20.dp)
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
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )
        Text(
            text = if (status == DownloadStatus.DOWNLOADING) 
                stringResource(R.string.downloading) else stringResource(R.string.decrypting),
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun formatSystemMessage(text: String, memberNames: Map<String, String> = emptyMap()): String {
    fun resolveName(idOrName: String): String {
        return memberNames[idOrName] ?: if (idOrName.length > 20) "User_${idOrName.takeLast(4)}" else idOrName
    }

    return when {
        text == "SYSTEM_JOINED_GROUP" -> stringResource(R.string.system_group_created)
        text.startsWith("SYSTEM_MEMBER_JOINED:") -> {
            val name = resolveName(text.substringAfter(":"))
            stringResource(R.string.system_member_joined, name)
        }
        text.startsWith("SYSTEM_MEMBER_LEFT:") -> {
            val name = resolveName(text.substringAfter(":"))
            stringResource(R.string.system_member_left, name)
        }
        text.startsWith("SYSTEM_MEMBER_KICKED:") -> {
            val name = resolveName(text.substringAfter(":"))
            stringResource(R.string.system_member_kicked, name)
        }
        text == "SYSTEM_YOU_KICKED" -> stringResource(R.string.system_you_kicked)
        text.startsWith("SYSTEM_NEW_OWNER:") -> {
            val name = resolveName(text.substringAfter(":"))
            stringResource(R.string.system_new_owner, name)
        }
        text.startsWith("SYSTEM_GROUP_RENAMED:") -> {
            val newName = text.substringAfter(":")
            stringResource(R.string.system_group_renamed, newName)
        }
        else -> text
    }
}
