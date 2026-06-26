/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.games

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nax.atsupager.R

@Composable
fun GameInviteDialog(
    gameTitle: String,
    senderName: String, 
    onAccept: () -> Unit, 
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject, 
        title = { 
            Text(
                text = gameTitle,
                style = MaterialTheme.typography.headlineSmall
            ) 
        }, 
        text = { 
            Text(
                text = stringResource(R.string.invite_msg, senderName, gameTitle),
                style = MaterialTheme.typography.bodyMedium
            ) 
        }, 
        confirmButton = { 
            Button(
                onClick = onAccept,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.accept)) 
            } 
        }, 
        dismissButton = { 
            TextButton(onClick = onReject) { 
                Text(stringResource(R.string.reject)) 
            } 
        },
        properties = DialogProperties(usePlatformDefaultWidth = true, dismissOnClickOutside = false)
    )
}
