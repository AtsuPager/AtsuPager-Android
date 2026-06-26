/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.security.KeyboardSecurity

@Composable
fun AccessCodeDialog(
    onDismiss: () -> Unit,
    onVerify: (String, (Boolean, String?) -> Unit) -> Unit
) {
    var codeInput by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        title = { 
            Text(
                text = stringResource(R.string.access_settings),
                style = MaterialTheme.typography.headlineSmall
            ) 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.buy_code_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                StyledTextField(
                    value = codeInput,
                    onValueChange = { 
                        val filtered = it.uppercase().filter { char -> char.isLetterOrDigit() || char == '-' }
                        if (filtered.length <= 25) {
                            codeInput = filtered
                            errorText = null
                        }
                    },
                    placeholderText = stringResource(R.string.enter_code_hint),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardSecurity.secureChatOptions
                )
                
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            val cleanLength = codeInput.filter { it.isLetterOrDigit() }.length
            Button(
                onClick = {
                    isVerifying = true
                    onVerify(codeInput) { success, error ->
                        isVerifying = false
                        if (!success) errorText = error
                    }
                },
                enabled = cleanLength == 16 && !isVerifying,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.apply_code))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isVerifying
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun LanguageSelectionDialog(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    val languages = listOf(
        "system" to stringResource(R.string.system_default),
        "en" to "English",
        "ru" to "Русский",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "cs" to "Čeština"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_language), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                languages.forEach { (code, name) ->
                    val isSelected = if (code == "system") currentLanguage == "system" else currentLanguage.startsWith(code)
                    RadioOptionRow(
                        selected = isSelected,
                        text = name,
                        onClick = { onLanguageSelected(code) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    desc: String? = null,
    isSmall: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text, style = if (isSmall) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
            if (desc != null) Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RadioOptionRow(
    selected: Boolean,
    text: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = text, 
            style = MaterialTheme.typography.bodyLarge, 
            modifier = Modifier.padding(start = 8.dp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
