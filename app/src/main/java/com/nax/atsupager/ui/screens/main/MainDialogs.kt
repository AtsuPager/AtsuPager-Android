/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.ui.components.CheckboxRow

@Composable
fun ClearChatConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: (deleteFiles: Boolean, forEveryone: Boolean) -> Unit
) {
    var deleteFiles by remember { mutableStateOf(false) }
    var forEveryone by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_chat_confirm_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.clear_chat_confirm_msg), style = MaterialTheme.typography.bodyMedium)
                CheckboxRow(
                    checked = forEveryone,
                    onCheckedChange = { forEveryone = it },
                    text = stringResource(R.string.delete_for_everyone)
                )
                CheckboxRow(
                    checked = deleteFiles,
                    onCheckedChange = { deleteFiles = it },
                    text = stringResource(R.string.delete_files_on_device)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteFiles, forEveryone) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.clear_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun BulkDeleteMessagesDialog(
    count: Int,
    canDeleteForEveryone: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (deleteFiles: Boolean, forEveryone: Boolean) -> Unit
) {
    var deleteFiles by remember { mutableStateOf(false) }
    var forEveryone by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_selected_messages_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_selected_messages_msg, count), style = MaterialTheme.typography.bodyMedium)
                if (canDeleteForEveryone) {
                    CheckboxRow(
                        checked = forEveryone,
                        onCheckedChange = { forEveryone = it },
                        text = stringResource(R.string.delete_for_everyone)
                    )
                }
                CheckboxRow(
                    checked = deleteFiles,
                    onCheckedChange = { deleteFiles = it },
                    text = stringResource(R.string.delete_files_on_device)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteFiles, forEveryone) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun LeaveGroupOptionsDialog(
    isAdmin: Boolean,
    onDismiss: () -> Unit,
    onLeave: (deleteFiles: Boolean, forEveryone: Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var deleteFiles by remember { mutableStateOf(false) }
    var forEveryone by remember { mutableStateOf(false) }

    var showFinalLeaveConfirm by remember { mutableStateOf(false) }
    var showFinalDeleteConfirm by remember { mutableStateOf(false) }

    if (showFinalLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showFinalLeaveConfirm = false },
            title = { Text(stringResource(R.string.leave_group), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(stringResource(R.string.leave_group_confirm), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = { 
                        onLeave(deleteFiles, forEveryone) 
                        showFinalLeaveConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.leave_group))
                }
            },
            dismissButton = { TextButton(onClick = { showFinalLeaveConfirm = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showFinalDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showFinalDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_group), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(stringResource(R.string.delete_group_confirm), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showFinalDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { showFinalDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.leave_group), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                
                CheckboxRow(
                    checked = forEveryone, 
                    onCheckedChange = { forEveryone = it }, 
                    text = stringResource(R.string.delete_for_everyone), 
                    isSmall = true
                )
                CheckboxRow(
                    checked = deleteFiles, 
                    onCheckedChange = { deleteFiles = it }, 
                    text = stringResource(R.string.delete_files_on_device), 
                    isSmall = true
                )
                
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showFinalLeaveConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer, 
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(stringResource(R.string.leave_group))
                }
                
                if (isAdmin) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    
                    Text(stringResource(R.string.delete_group), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showFinalDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer, 
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.delete_group))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun DownloadOptionsDialog(onDismiss: () -> Unit, onDownload: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.download_options_title),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.download_options_msg),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                DialogOptionCard(
                    title = stringResource(R.string.download_to_secure),
                    subtitle = stringResource(R.string.secure_storage_desc),
                    icon = Icons.Default.Lock,
                    iconColor = Color(0xFF4CAF50),
                    onClick = { onDownload(false) }
                )

                DialogOptionCard(
                    title = stringResource(R.string.download_to_public),
                    subtitle = stringResource(R.string.public_access_desc),
                    icon = Icons.Default.LockOpen,
                    iconColor = Color.Red,
                    onClick = { onDownload(true) }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cancel), textAlign = TextAlign.Center)
            }
        }
    )
}

@Composable
fun DialogOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = iconColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}
