package com.nax.atsupager.ui.screens.main.bubbles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nax.atsupager.data.db.ChatMessage

@Composable
fun TextMessage(
    message: ChatMessage,
    isFromMe: Boolean,
    isSecure: Boolean,
    isSelectionEnabled: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        DirectionAndSecureIcons(isFromMe, isSecure)
        Spacer(modifier = Modifier.width(6.dp))

        if (isSelectionEnabled) {
            SelectionContainer {
                Text(text = message.text, modifier = Modifier.padding(bottom = 4.dp))
            }
        } else {
            Text(text = message.text, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
