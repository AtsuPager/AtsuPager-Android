/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.login

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.nax.atsupager.R
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.ui.components.StyledTextField
import com.nax.atsupager.ui.components.LanguageSelectionDialog

enum class LoginMode {
    CHOOSE, CREATE, IMPORT, IDENTITY_READY
}

@Composable
fun LoginScreen(
    onLoginComplete: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    var mode by remember { mutableStateOf(LoginMode.CHOOSE) }
    var usernameInput by remember { mutableStateOf("") }
    var mnemonicInput by remember { mutableStateOf("") }
    var finalUsername by remember { mutableStateOf("") }
    var finalUserId by remember { mutableStateOf("") }
    var showLanguageDialog by remember { mutableStateOf(false) }
    
    val uiState by viewModel.uiState.collectAsState()
    val currentLanguage by viewModel.currentLanguage.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.Success) {
            val success = uiState as LoginUiState.Success
            finalUsername = success.username
            finalUserId = success.userId
            mode = LoginMode.IDENTITY_READY
        }
    }

    Scaffold(
        containerColor = Color.Black 
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LoginLogoHeader(onLanguageClick = { showLanguageDialog = true })

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "mode_switch"
                ) { targetMode ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (targetMode) {
                            LoginMode.CHOOSE -> {
                                Text(
                                    text = stringResource(R.string.login_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = { mode = LoginMode.CREATE },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.create_profile))
                                }
                                OutlinedButton(
                                    onClick = { mode = LoginMode.IMPORT },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) {
                                    Icon(Icons.Default.Key, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.import_profile))
                                }
                            }

                            LoginMode.CREATE -> {
                                Text(stringResource(R.string.how_seen_others), color = Color.White, style = MaterialTheme.typography.titleMedium)
                                StyledTextField(
                                    value = usernameInput,
                                    onValueChange = { usernameInput = it },
                                    placeholderText = stringResource(R.string.username_hint),
                                    label = { Text(stringResource(R.string.username_label)) },
                                    keyboardOptions = KeyboardSecurity.secureChatOptions
                                )
                                Button(
                                    onClick = { viewModel.createIdentity(usernameInput) },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    enabled = uiState !is LoginUiState.Loading && usernameInput.isNotBlank()
                                ) {
                                    if (uiState is LoginUiState.Loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                                    else Text(stringResource(R.string.generate_id_action))
                                }
                                TextButton(onClick = { mode = LoginMode.CHOOSE }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) { 
                                    Text(stringResource(R.string.back)) 
                                }
                            }

                            LoginMode.IMPORT -> {
                                Text(stringResource(R.string.enter_secret_phrase), color = Color.White, style = MaterialTheme.typography.titleMedium)
                                StyledTextField(
                                    value = usernameInput,
                                    onValueChange = { usernameInput = it },
                                    placeholderText = stringResource(R.string.username_hint),
                                    label = { Text(stringResource(R.string.username_label)) },
                                    keyboardOptions = KeyboardSecurity.secureChatOptions
                                )
                                StyledTextField(
                                    value = mnemonicInput,
                                    onValueChange = { mnemonicInput = it },
                                    placeholderText = stringResource(R.string.mnemonic_placeholder),
                                    label = { Text(stringResource(R.string.mnemonic_placeholder)) },
                                    keyboardOptions = KeyboardSecurity.securePasswordOptions
                                )
                                Button(
                                    onClick = { 
                                        viewModel.importIdentity(mnemonicInput.toCharArray(), usernameInput)
                                        mnemonicInput = ""
                                    },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    enabled = uiState !is LoginUiState.Loading && mnemonicInput.isNotBlank()
                                ) {
                                    if (uiState is LoginUiState.Loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                                    else Text(stringResource(R.string.import_phrase_action))
                                }
                                TextButton(onClick = { mode = LoginMode.CHOOSE }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) { 
                                    Text(stringResource(R.string.back)) 
                                }
                            }

                            LoginMode.IDENTITY_READY -> {
                                SuccessContent(
                                    username = finalUsername, 
                                    onStart = { viewModel.completeLogin(finalUserId) }
                                )
                            }
                        }
                    }
                }

                if (uiState is LoginUiState.Error) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = (uiState as LoginUiState.Error).message, 
                        color = MaterialTheme.colorScheme.error, 
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = currentLanguage,
            onDismiss = { showLanguageDialog = false }, 
            onLanguageSelected = { viewModel.changeLanguage(it); showLanguageDialog = false }
        )
    }
}

@Composable
fun SuccessContent(username: String, onStart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.username_nickname),
            style = MaterialTheme.typography.labelLarge,
            color = Color.Gray
        )
        
        Text(
            text = username,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = stringResource(R.string.profile_ready),
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF05E5FF)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.identity_hint_settings),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = Color.Gray.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(stringResource(R.string.get_started))
        }
    }
}

@Composable
fun LoginLogoHeader(onLanguageClick: () -> Unit) {
    Box(
        modifier = Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val brandColor = Color(0xFF05E5FF)
            val brandTextStyle = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Black,
                color = brandColor,
                letterSpacing = 1.sp,
                fontSize = 14.sp
            )
            Text(
                text = "  ATSU",
                style = brandTextStyle,
                modifier = Modifier.graphicsLayer(scaleY = 0.8f)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(brandColor.copy(alpha = 0.1f))
                    .border(1.2.dp, brandColor.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = R.mipmap.ic_launcher_round,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "PAGER", 
                style = brandTextStyle,
                modifier = Modifier.graphicsLayer(scaleY = 0.8f)
            )
        }

        IconButton(
            onClick = onLanguageClick,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}
