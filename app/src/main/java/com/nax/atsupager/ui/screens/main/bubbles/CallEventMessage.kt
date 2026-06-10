package com.nax.atsupager.ui.screens.main.bubbles

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.ui.screens.main.MainUiUtils

@Composable
fun CallEventMessage(
    type: MessageType,
    timestamp: Long,
    text: String = ""
) {
    // Определяем, был ли входящий звонок принят
    val isAccepted = text == "CALL_ACCEPTED" || 
                     text.contains("(accepted)", ignoreCase = true) || 
                     text.contains("(принят)", ignoreCase = true)

    val resId = when {
        type == MessageType.MISSED_CALL || text == "CALL_MISSED" -> R.string.missed_call
        type == MessageType.OUTGOING_CALL || text == "CALL_OUTGOING" -> R.string.outgoing_call
        // Для всех входящих (и принятых, и нет) теперь показываем просто "Входящий звонок"
        type == MessageType.INCOMING_CALL || text == "CALL_INCOMING" || isAccepted -> R.string.incoming_call
        else -> return
    }

    val icon = when (type) {
        MessageType.OUTGOING_CALL -> Icons.AutoMirrored.Filled.CallMade
        MessageType.INCOMING_CALL -> Icons.Filled.SouthWest
        MessageType.MISSED_CALL -> Icons.Filled.CallMissed
        else -> null
    }

    // Цвет стрелочки: красный для пропущенных, зеленый для принятых, основной для остальных
    val tint = when {
        type == MessageType.MISSED_CALL -> MaterialTheme.colorScheme.error
        isAccepted -> Color(0xFF4CAF50) // Зеленый (Material Green 500)
        else -> MaterialTheme.colorScheme.primary
    }

    val displayText = stringResource(resId)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = displayText, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = MainUiUtils.formatTime(timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
