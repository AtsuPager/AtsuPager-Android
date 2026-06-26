/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.security.ClipboardClearDelay
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.ui.components.RadioOptionRow
import com.nax.atsupager.ui.components.StyledTextField
import com.nax.atsupager.ui.theme.AppFont

@Composable
fun ThemeSelectionDialog(
    currentMode: ThemeMode,
    onDismiss: () -> Unit,
    onModeSelected: (ThemeMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    RadioOptionRow(
                        selected = currentMode == mode,
                        text = getThemeLabel(mode),
                        onClick = { onModeSelected(mode) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun FontSelectionDialog(
    currentFont: AppFont,
    onDismiss: () -> Unit,
    onFontSelected: (AppFont) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_font_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                AppFont.entries.forEach { font ->
                    RadioOptionRow(
                        selected = currentFont == font,
                        text = getFontLabel(font),
                        onClick = { onFontSelected(font) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun TtlSelectionDialog(
    currentTtl: MessageTTL,
    onDismiss: () -> Unit,
    onTtlSelected: (MessageTTL) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_ttl_dialog_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.message_ttl_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                MessageTTL.entries.forEach { ttl ->
                    RadioOptionRow(
                        selected = currentTtl == ttl,
                        text = getMessageTtlLabel(ttl),
                        onClick = { onTtlSelected(ttl) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun ClipboardSelectionDialog(
    currentDelay: ClipboardClearDelay,
    onDismiss: () -> Unit,
    onDelaySelected: (ClipboardClearDelay) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clipboard_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_clipboard_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ClipboardClearDelay.entries.forEach { delay ->
                    RadioOptionRow(
                        selected = currentDelay == delay,
                        text = getClipboardDelayLabel(delay),
                        onClick = { onDelaySelected(delay) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun EditNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var tempName by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.login_name), style = MaterialTheme.typography.headlineSmall) },
        text = {
            StyledTextField(
                value = tempName,
                onValueChange = { tempName = it },
                placeholderText = stringResource(R.string.username_label),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(tempName) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun DeleteProfileConfirmDialog(
    profileName: String,
    hasPin: Boolean,
    errorText: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit
) {
    var pinInput by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_contact_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.delete_contact_msg, profileName), style = MaterialTheme.typography.bodyMedium)
                if (hasPin) {
                    StyledTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 8) pinInput = it },
                        placeholderText = stringResource(R.string.old_pin),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardSecurity.securePasswordOptions
                    )
                }
                if (errorText != null) {
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pinInput.toCharArray()) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                enabled = !hasPin || pinInput.length >= 4,
                shape = RoundedCornerShape(12.dp)
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun CreateProfileDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newUsername by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_id), style = MaterialTheme.typography.headlineSmall) },
        text = {
            StyledTextField(
                value = newUsername,
                onValueChange = { newUsername = it },
                placeholderText = stringResource(R.string.username_label),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newUsername) },
                enabled = newUsername.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) { Text(stringResource(R.string.generate_id_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun MnemonicDisplayDialog(
    mnemonic: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.your_mnemonic), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(stringResource(R.string.mnemonic_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp)
                ) {
                    Text(
                        text = mnemonic.joinToString(" "),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
fun MnemonicImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var importText by remember { mutableStateOf("") }
    var importName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_id), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StyledTextField(
                    value = importName,
                    onValueChange = { importName = it },
                    placeholderText = stringResource(R.string.username_label),
                    modifier = Modifier.fillMaxWidth()
                )
                StyledTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholderText = stringResource(R.string.mnemonic_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardSecurity.securePasswordOptions
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(importText, importName) },
                enabled = importName.isNotBlank() && importText.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) { Text(stringResource(R.string.import_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// Helpers for labels
@Composable
fun getFontLabel(font: AppFont): String = when(font) {
    AppFont.SYSTEM -> stringResource(R.string.settings_font_system)
    AppFont.INTER -> stringResource(R.string.settings_font_inter)
    AppFont.MANROPE -> stringResource(R.string.settings_font_manrope)
    AppFont.JETBRAINS_MONO -> stringResource(R.string.settings_font_jetbrains)
}

@Composable
fun getThemeLabel(mode: ThemeMode): String = when(mode) {
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}

@Composable
fun getMessageTtlLabel(ttl: MessageTTL): String = when(ttl) {
    MessageTTL.OFF -> stringResource(R.string.ttl_disabled)
    MessageTTL.ONE_HOUR -> stringResource(R.string.ttl_1h)
    MessageTTL.ONE_DAY -> stringResource(R.string.ttl_24h)
    MessageTTL.ONE_WEEK -> stringResource(R.string.ttl_7d)
    MessageTTL.ONE_MONTH -> stringResource(R.string.ttl_30d)
}

@Composable
fun getClipboardDelayLabel(delay: ClipboardClearDelay): String = when(delay) {
    ClipboardClearDelay.THIRTY_SECONDS -> stringResource(R.string.settings_clipboard_30s)
    ClipboardClearDelay.ONE_MINUTE -> stringResource(R.string.settings_clipboard_1m)
    ClipboardClearDelay.FIVE_MINUTES -> stringResource(R.string.settings_clipboard_5m)
    ClipboardClearDelay.NEVER -> stringResource(R.string.settings_clipboard_never)
}
