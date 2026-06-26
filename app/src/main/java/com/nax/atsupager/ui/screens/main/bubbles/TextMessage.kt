package com.nax.atsupager.ui.screens.main.bubbles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageType

@Composable
fun TextMessage(
    message: ChatMessage,
    isFromMe: Boolean,
    isSecure: Boolean,
    isDecrypted: Boolean = false,
    isSelectionEnabled: Boolean = false,
    memberNames: Map<String, String> = emptyMap()
) {
    // SECURITY: Теперь полагаемся на тип сообщения, а не на префикс в тексте.
    // Тип SYSTEM может быть присвоен только локально при обработке системных событий.
    val isSystem = message.type == MessageType.SYSTEM
    val isGameInvite = message.type == MessageType.GAME_INVITE
    
    if (isSystem) {
        val displayState = formatSystemMessage(message.text, memberNames)
        Text(
            text = displayState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontSize = 11.sp
            ),
            textAlign = TextAlign.Center
        )
    } else if (isGameInvite) {
        // Специальное отображение для приглашений в игры
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                tonalElevation = 1.dp
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            DirectionAndSecureIcons(isFromMe, isSecure, isDecrypted, showLock = false)
            Spacer(modifier = Modifier.width(8.dp))

            if (isSelectionEnabled) {
                SelectionContainer {
                    Text(text = message.text, modifier = Modifier.padding(bottom = 4.dp))
                }
            } else {
                Text(text = message.text, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}
