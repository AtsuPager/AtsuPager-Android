/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.ui.components.CheckboxRow
import com.nax.atsupager.ui.components.RadioOptionRow
import com.nax.atsupager.ui.components.StyledTextField

@Composable
fun AddChoiceDialog(
    onAddContact: () -> Unit,
    onCreateGroup: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onAddContact,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Default.PersonAdd, null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.add_contact), style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = onCreateGroup,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(Icons.Default.Groups, null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.create_group), style = MaterialTheme.typography.labelLarge)
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
fun AddContactDialog(
    resolvedInfo: ResolvedContactInfo?,
    onResolve: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: (String, String?) -> Unit
) {
    var identity by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    
    LaunchedEffect(resolvedInfo) {
        if (resolvedInfo?.serverName != null && name.isBlank()) name = resolvedInfo.serverName
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_contact), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (resolvedInfo == null) {
                    Text(
                        stringResource(R.string.add_contact_msg, ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StyledTextField(
                        value = identity,
                        onValueChange = { identity = it },
                        placeholderText = "name@1A1zP1...",
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { identity = it.trim() }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                            }
                        },
                        keyboardOptions = KeyboardSecurity.secureChatOptions
                    )
                } else if (resolvedInfo.isResolving) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Text(
                        text = "ID: ${resolvedInfo.address.take(12)}...${resolvedInfo.address.takeLast(12)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StyledTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholderText = stringResource(R.string.contact_name),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardSecurity.secureChatOptions
                    )
                    if (resolvedInfo.serverName == null && name.isBlank()) {
                        Text(
                            text = stringResource(R.string.error_name_required),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (resolvedInfo != null && !resolvedInfo.isResolving) {
                Button(
                    onClick = { onAdd(resolvedInfo.address, name.takeIf { it.isNotBlank() }) },
                    enabled = resolvedInfo.serverName != null || name.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.add))
                }
            } else if (resolvedInfo == null) {
                Button(
                    onClick = { onResolve(identity) },
                    enabled = identity.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.next))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun RenameDialog(
    initialName: String,
    title: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            StyledTextField(
                value = name,
                onValueChange = { name = it },
                placeholderText = stringResource(R.string.contact_name),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardSecurity.secureChatOptions
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onRename(name) }, 
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun MergedDeleteDialog(
    count: Int,
    containsGroups: Boolean,
    isAdmin: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Boolean, Boolean) -> Unit,
    onDeleteGroup: () -> Unit = {}
) {
    var deleteFiles by remember { mutableStateOf(false) }
    var deleteForEveryone by remember { mutableStateOf(false) }
    var deleteMode by remember { mutableIntStateOf(0) }

    var showFinalLeaveConfirm by remember { mutableStateOf(false) }
    var showFinalDeleteConfirm by remember { mutableStateOf(false) }

    if (showFinalLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showFinalLeaveConfirm = false },
            title = { Text(stringResource(R.string.leave_group), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(stringResource(R.string.leave_group_confirm), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm(deleteFiles, true, deleteForEveryone)
                    showFinalLeaveConfirm = false
                }) {
                    Text(stringResource(R.string.leave_group), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
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
                TextButton(onClick = {
                    onDeleteGroup()
                    showFinalDeleteConfirm = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showFinalDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                text = stringResource(if (isAdmin && count == 1) R.string.group else R.string.delete),
                style = MaterialTheme.typography.headlineSmall
            ) 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when {
                        count > 1 -> stringResource(R.string.bulk_delete_chat_multiple, count)
                        isAdmin && count == 1 -> stringResource(R.string.chat_menu)
                        else -> stringResource(R.string.bulk_delete_chat_single)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                Column(modifier = Modifier.padding(top = 8.dp)) {
                    RadioOptionRow(
                        selected = deleteMode == 0,
                        text = stringResource(R.string.clear_history_only),
                        onClick = { deleteMode = 0 }
                    )
                    RadioOptionRow(
                        selected = deleteMode == 1,
                        text = if (containsGroups) stringResource(R.string.leave_group) else stringResource(R.string.delete_all_confirm),
                        onClick = { deleteMode = 1 }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                CheckboxRow(
                    checked = deleteForEveryone,
                    onCheckedChange = { deleteForEveryone = it },
                    text = stringResource(R.string.delete_for_everyone)
                )

                CheckboxRow(
                    checked = deleteFiles,
                    onCheckedChange = { deleteFiles = it },
                    text = stringResource(R.string.delete_files_on_device),
                    isSmall = true
                )

                if (isAdmin && count == 1) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showFinalDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.DeleteForever, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete_group))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (deleteMode == 1 && isAdmin && count == 1) {
                        showFinalLeaveConfirm = true
                    } else {
                        onConfirm(deleteFiles, deleteMode == 1, deleteForEveryone)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp)
            ) {
                val actionText = when {
                    deleteMode == 0 -> stringResource(R.string.clear_button)
                    containsGroups -> stringResource(R.string.leave_group)
                    else -> stringResource(R.string.delete_contact_action)
                }
                Text(text = actionText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ExportContactsDialog(onDismiss: () -> Unit, onExport: (String?, Boolean) -> Unit) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var usePassword by remember { mutableStateOf(false) }
    var includeHistory by remember { mutableStateOf(true) }

    val isPasswordValid = !usePassword || password.length >= 8

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_profile_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.contacts_export_method), style = MaterialTheme.typography.bodyMedium)
                
                ExportOptionRow(
                    selected = !usePassword,
                    title = stringResource(R.string.contacts_backup_mnemonic),
                    desc = stringResource(R.string.contacts_backup_mnemonic_desc),
                    onClick = { usePassword = false }
                )
                
                ExportOptionRow(
                    selected = usePassword,
                    title = stringResource(R.string.contacts_backup_password),
                    desc = stringResource(R.string.contacts_backup_password_desc),
                    onClick = { usePassword = true }
                )

                if (usePassword) {
                    StyledTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholderText = stringResource(R.string.contacts_backup_password_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null
                                )
                            }
                        },
                        keyboardOptions = KeyboardSecurity.securePasswordOptions
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                CheckboxRow(
                    checked = includeHistory,
                    onCheckedChange = { includeHistory = it },
                    text = stringResource(R.string.include_history)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onExport(if (usePassword) password else null, includeHistory) },
                enabled = isPasswordValid,
                shape = RoundedCornerShape(12.dp)
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun ImportPasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_import_password_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.contacts_import_password_msg), style = MaterialTheme.typography.bodyMedium)
                StyledTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholderText = stringResource(R.string.contacts_import_password_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide" else "Show"
                            )
                        }
                    },
                    keyboardOptions = KeyboardSecurity.securePasswordOptions
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) }, 
                enabled = password.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun GroupNameDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_group), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.group_name_hint).ifEmpty { "Enter group name" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StyledTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholderText = stringResource(R.string.group_name_hint),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardSecurity.secureChatOptions
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) }, 
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ExportOptionRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
