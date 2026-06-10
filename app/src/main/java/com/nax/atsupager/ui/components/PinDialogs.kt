package com.nax.atsupager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.security.SecureDataHandler

@Composable
fun PinSetupDialog(
    onDismiss: () -> Unit,
    onPinSet: (CharArray) -> Unit, // Передаем массив
    isPinSet: Boolean = false,
    verifyOldPin: ((CharArray) -> Boolean)? = null // Принимаем массив
) {
    var step by remember { mutableStateOf(if (isPinSet) 0 else 1) }
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    
    var pinVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                when(step) {
                    0 -> stringResource(R.string.old_pin)
                    1 -> stringResource(R.string.new_pin)
                    else -> stringResource(R.string.confirm_pin)
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val value = when(step) {
                    0 -> oldPin
                    1 -> newPin
                    else -> confirmPin
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = { 
                        if (it.length <= 8 && it.all { char -> char.isDigit() }) {
                            when(step) {
                                0 -> oldPin = it
                                1 -> newPin = it
                                2 -> confirmPin = it
                            }
                            error = null
                        }
                    },
                    label = { Text(stringResource(if (step == 0) R.string.old_pin else if (step == 1) R.string.new_pin else R.string.confirm_pin)) },
                    visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { pinVisible = !pinVisible }) {
                            Icon(if (pinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    keyboardOptions = KeyboardSecurity.securePasswordOptions.copy(
                        keyboardType = KeyboardType.Number
                    ),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else if (step == 1) {
                    Text(stringResource(R.string.pin_hint_min_length), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when(step) {
                        0 -> {
                            val chars = oldPin.toCharArray()
                            if (verifyOldPin?.invoke(chars) == true) {
                                step = 1
                                error = null
                                oldPin = "" // Сбрасываем строку немедленно
                            } else {
                                error = context.getString(R.string.error_wrong_pin)
                                SecureDataHandler.wipe(chars)
                            }
                        }
                        1 -> {
                            if (newPin.length >= 4) {
                                step = 2
                                error = null
                            } else {
                                error = context.getString(R.string.pin_hint_min_length)
                            }
                        }
                        2 -> {
                            if (newPin == confirmPin) {
                                val chars = newPin.toCharArray()
                                onPinSet(chars)
                                newPin = ""
                                confirmPin = ""
                            } else {
                                error = context.getString(R.string.error_pin_mismatch)
                            }
                        }
                    }
                },
                enabled = when(step) {
                    0 -> oldPin.length >= 4
                    1 -> newPin.length >= 4
                    2 -> confirmPin.length >= 4
                    else -> false
                }
            ) {
                Text(if (step < 2) stringResource(R.string.next) else stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                oldPin = ""; newPin = ""; confirmPin = ""
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        }
    )
}
