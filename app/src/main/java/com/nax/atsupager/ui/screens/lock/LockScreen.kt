package com.nax.atsupager.ui.screens.lock

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.nax.atsupager.R
import com.nax.atsupager.security.BiometricHelper

@Composable
fun LockScreen(
    onUnlockSuccess: () -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
    biometricHelper: BiometricHelper = BiometricHelper() // Ideally injected but simplified for now
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    LaunchedEffect(uiState.isUnlocked) {
        if (uiState.isUnlocked) {
            onUnlockSuccess()
        }
    }

    // Auto-show biometric if enabled
    LaunchedEffect(Unit) {
        if (uiState.isBiometricEnabled && activity != null) {
            biometricHelper.showBiometricPrompt(
                activity = activity,
                title = context.getString(R.string.biometric_title),
                subtitle = context.getString(R.string.biometric_subtitle),
                negativeButtonText = context.getString(R.string.biometric_negative_button),
                onSuccess = { viewModel.onBiometricSuccess() },
                onError = { /* Handle error if needed */ }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.app_lock),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // PIN Indicator Dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(vertical = 24.dp)
        ) {
            repeat(4) { index ->
                val isFilled = uiState.pinLength > index
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            if (isFilled) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape
                        )
                )
            }
        }

        if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Number Pad
        val numbers = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "back")
        
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            numbers.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.forEach { item ->
                        when (item) {
                            "back" -> {
                                IconButton(
                                    onClick = { viewModel.onBackspace() },
                                    modifier = Modifier.size(72.dp)
                                ) {
                                    Icon(Icons.Default.Backspace, contentDescription = "Backspace")
                                }
                            }
                            "" -> {
                                if (uiState.isBiometricEnabled && activity != null) {
                                    IconButton(
                                        onClick = {
                                            biometricHelper.showBiometricPrompt(
                                                activity = activity,
                                                title = context.getString(R.string.biometric_title),
                                                subtitle = context.getString(R.string.biometric_subtitle),
                                                negativeButtonText = context.getString(R.string.biometric_negative_button),
                                                onSuccess = { viewModel.onBiometricSuccess() },
                                                onError = { }
                                            )
                                        },
                                        modifier = Modifier.size(72.dp)
                                    ) {
                                        Icon(Icons.Default.Fingerprint, contentDescription = "Biometric")
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(72.dp))
                                }
                            }
                            else -> {
                                KeypadButton(text = item) { viewModel.onPinInput(item[0]) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KeypadButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(72.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
