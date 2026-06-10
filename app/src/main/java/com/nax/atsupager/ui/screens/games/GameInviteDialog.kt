package com.nax.atsupager.ui.screens.games

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
        title = { Text(gameTitle) }, 
        text = { Text(stringResource(R.string.invite_msg, senderName, gameTitle)) }, 
        confirmButton = { Button(onClick = onAccept) { Text(stringResource(R.string.accept)) } }, 
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.reject)) } }, 
        properties = DialogProperties(usePlatformDefaultWidth = true, dismissOnClickOutside = false)
    )
}
