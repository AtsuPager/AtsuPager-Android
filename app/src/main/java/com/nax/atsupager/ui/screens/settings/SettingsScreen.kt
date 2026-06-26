/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.ui.screens.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nax.atsupager.R
import com.nax.atsupager.data.network.ConnectionStatus
import com.nax.atsupager.security.ClipboardUiHelper
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.ui.components.*
import com.nax.atsupager.ui.utils.QRUtils
import com.nax.atsupager.webrtc.NtfyStatus

sealed class SettingsDialog {
    object Language : SettingsDialog()
    object Theme : SettingsDialog()
    object Font : SettingsDialog()
    object Pin : SettingsDialog()
    object MnemonicImport : SettingsDialog()
    object CreateProfile : SettingsDialog()
    object EditName : SettingsDialog()
    object MnemonicDisplay : SettingsDialog()
    object Ttl : SettingsDialog()
    object Clipboard : SettingsDialog()
    object Access : SettingsDialog()
    data class DeleteProfile(val id: String, val name: String) : SettingsDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            SettingsContent(viewModel)
        }
    }
}

@Composable
fun SettingsContent(viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var serverUrl by remember(uiState.ntfyServerUrl) { mutableStateOf(uiState.ntfyServerUrl) }
    
    var proxyHost by remember(uiState.proxyHost) { mutableStateOf(uiState.proxyHost) }
    var proxyPort by remember(uiState.proxyPort) { mutableStateOf(uiState.proxyPort.toString()) }

    var activeDialog by remember { mutableStateOf<SettingsDialog?>(null) }
    
    var isNetworkExpanded by remember { mutableStateOf(false) }
    var isKeysExpanded by remember { mutableStateOf(false) }
    var isIdentityExpanded by remember { mutableStateOf(false) }
    var isProfilesExpanded by remember { mutableStateOf(false) }
    
    var deleteError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.language)) }, 
                supportingContent = { Text(getLanguageName(uiState.currentLanguage)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.Language) }, 
                modifier = Modifier.clickable { activeDialog = SettingsDialog.Language }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.theme)) },
                supportingContent = { Text(getThemeLabel(uiState.themeMode)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.Palette) },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.Theme }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_font_title)) },
                supportingContent = { Text(getFontLabel(uiState.appFont)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.FontDownload) },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.Font }
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.profile_settings), 
                style = MaterialTheme.typography.labelMedium, 
                color = MaterialTheme.colorScheme.primary
            )

            // Profile Section
            val profilesRotation by animateFloatAsState(targetValue = if (isProfilesExpanded) 180f else 0f, label = "rotate")
            ListItem(
                headlineContent = { Text(stringResource(R.string.profile_settings)) },
                supportingContent = { Text(uiState.loginName.ifEmpty { stringResource(R.string.empty_value_placeholder) }) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.AccountCircle) },
                trailingContent = { Icon(Icons.Default.ExpandMore, null, modifier = Modifier.rotate(profilesRotation)) },
                modifier = Modifier.clickable { isProfilesExpanded = !isProfilesExpanded }
            )

            AnimatedVisibility(visible = isProfilesExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 16.dp)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.login_name)) },
                        supportingContent = { Text(uiState.loginName.ifEmpty { stringResource(R.string.empty_value_placeholder) }) },
                        leadingContent = { SettingsLeadingIcon(Icons.Default.Edit, modifier = Modifier.size(20.dp)) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable { activeDialog = SettingsDialog.EditName },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

                    uiState.profiles.forEach { (id, name) ->
                        val isActive = id == uiState.activeProfileId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                                .clickable { if (!isActive) viewModel.switchProfile(id) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                if (isActive) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = id.take(12) + "...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (!isActive) {
                                IconButton(onClick = {
                                    deleteError = null
                                    activeDialog = SettingsDialog.DeleteProfile(id, name)
                                }, modifier = Modifier.align(Alignment.CenterVertically)) {
                                    Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { activeDialog = SettingsDialog.CreateProfile },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.create_id))
                        }
                        FilledTonalButton(
                            onClick = { activeDialog = SettingsDialog.MnemonicImport },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.import_id))
                        }
                    }
                }
            }

            if (uiState.bitcoinAddress != null) {
                val fullIdentity = if (uiState.loginName.isNotEmpty()) "${uiState.loginName}@${uiState.bitcoinAddress}" else uiState.bitcoinAddress!!
                val identityRotation by animateFloatAsState(targetValue = if (isIdentityExpanded) 180f else 0f, label = "rotate")

                ListItem(
                    headlineContent = { Text(stringResource(R.string.your_id)) },
                    supportingContent = {
                        Text(
                            text = fullIdentity,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.Top) {
                            IconButton(onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_id_text, fullIdentity))
                                }
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_id_text, fullIdentity)))
                            }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_action))
                            }
                            IconButton(onClick = {
                                ClipboardUiHelper.copyWithNotification(
                                    context = context,
                                    scope = scope,
                                    snackbarHostState = snackbarHostState,
                                    label = context.getString(R.string.clipboard_label_id),
                                    text = fullIdentity
                                )
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
                            }
                            Icon(
                                Icons.Default.ExpandMore,
                                null,
                                modifier = Modifier.rotate(identityRotation).padding(top = 12.dp)
                            )
                        }
                    },
                    leadingContent = { SettingsLeadingIcon(Icons.Default.Badge) },
                    modifier = Modifier.clickable { isIdentityExpanded = !isIdentityExpanded }
                )

                AnimatedVisibility(visible = isIdentityExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val qrBitmap = remember(fullIdentity) { QRUtils.generateQRCode(fullIdentity, 400) }
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            qrBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = stringResource(R.string.qr_code_description),
                                    modifier = Modifier.fillMaxSize()
                                )
                            } ?: CircularProgressIndicator()
                        }

                        Spacer(Modifier.height(16.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = fullIdentity,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = stringResource(R.string.identity_ready_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            val keysRotation by animateFloatAsState(targetValue = if (isKeysExpanded) 180f else 0f, label = "rotate")
            ListItem(
                headlineContent = { Text(stringResource(R.string.keys_and_identity)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.Key) },
                trailingContent = { Icon(Icons.Default.ExpandMore, null, modifier = Modifier.rotate(keysRotation)) },
                modifier = Modifier.clickable { isKeysExpanded = !isKeysExpanded }
            )

            AnimatedVisibility(visible = isKeysExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 16.dp)) {
                    if (uiState.bitcoinAddress != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(end = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(text = stringResource(R.string.bitcoin_address), style = MaterialTheme.typography.labelSmall)
                                Text(text = uiState.bitcoinAddress ?: "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { 
                                viewModel.prepareMnemonic()
                                activeDialog = SettingsDialog.MnemonicDisplay
                            }, 
                            modifier = Modifier.fillMaxWidth().padding(end = 16.dp)
                        ) {
                            Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.show_mnemonic))
                        }
                    }
                }
            }

            HorizontalDivider()

            // Server Access Section (Updated to Accent Card with Wide Button)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, 
                    if (uiState.accessStatus == AccessStatus.ACTIVE) MaterialTheme.colorScheme.outlineVariant 
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = if (uiState.accessStatus == AccessStatus.ACTIVE) Icons.Default.Shield else Icons.Default.ShieldMoon
                        val color = if (uiState.accessStatus == AccessStatus.ACTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.access_settings),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Normal
                            )
                            val detailText = if (uiState.accessStatus == AccessStatus.ACTIVE) {
                                stringResource(R.string.access_expires_label, uiState.accessExpiry)
                            } else {
                                stringResource(R.string.access_status_expired)
                            }
                            Text(
                                text = detailText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.accessStatus == AccessStatus.ACTIVE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { activeDialog = SettingsDialog.Access },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = if (uiState.accessStatus != AccessStatus.ACTIVE) 
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Icon(Icons.Default.VpnKey, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.activate_access))
                    }
                }
            }

            HorizontalDivider()

            val netRotation by animateFloatAsState(targetValue = if (isNetworkExpanded) 180f else 0f, label = "rotate")
            ListItem(
                headlineContent = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.network_settings))
                        Spacer(Modifier.width(12.dp))
                        StatusDot(color = if(uiState.ntfyStatus == NtfyStatus.CONNECTED) Color.Green else if(uiState.ntfyStatus == NtfyStatus.CONNECTING) Color.Yellow else Color.Gray)
                        Spacer(Modifier.width(4.dp))
                        StatusDot(color = if(uiState.vpsStatus == ConnectionStatus.CONNECTED) Color.Cyan else if(uiState.vpsStatus != ConnectionStatus.DISCONNECTED) Color.Yellow else Color.Gray)
                    }
                },
                leadingContent = { SettingsLeadingIcon(Icons.Default.NetworkCheck) },
                trailingContent = { Icon(Icons.Default.ExpandMore, null, modifier = Modifier.rotate(netRotation)) },
                modifier = Modifier.clickable { isNetworkExpanded = !isNetworkExpanded }
            )

            AnimatedVisibility(visible = isNetworkExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 16.dp)) {
                    Text(stringResource(R.string.ntfy_status_label, localizeNtfyStatus(uiState.ntfyStatus)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.vps_status_label, localizeVpsStatus(uiState.vpsStatus)), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.enable_ntfy)) },
                        trailingContent = { Switch(checked = uiState.isNtfyEnabled, onCheckedChange = { viewModel.toggleNtfy(it) }) },
                        leadingContent = { SettingsLeadingIcon(Icons.Default.NotificationsActive) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    var isServerUrlVisible by remember { mutableStateOf(false) }
                    val isServerSaved = serverUrl == uiState.ntfyServerUrl

                    StyledTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        placeholderText = stringResource(R.string.ntfy_placeholder),
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        keyboardOptions = KeyboardSecurity.secureChatOptions,
                        visualTransformation = if (isServerUrlVisible && !isServerSaved) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            if (!isServerSaved) {
                                IconButton(onClick = { isServerUrlVisible = !isServerUrlVisible }) {
                                    Icon(
                                        imageVector = if (isServerUrlVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(end = 12.dp).size(20.dp)
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { 
                            viewModel.setNtfyServerUrl(serverUrl)
                            isServerUrlVisible = false
                        }, 
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp), 
                        enabled = serverUrl != uiState.ntfyServerUrl,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.save_server)) }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), thickness = 0.5.dp)

                    Text(stringResource(R.string.proxy_settings_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.enable_proxy)) },
                        supportingContent = { Text(stringResource(R.string.enable_proxy_description)) },
                        trailingContent = { Switch(checked = uiState.isProxyEnabled, onCheckedChange = { viewModel.toggleProxy(it) }) },
                        leadingContent = { SettingsLeadingIcon(Icons.Default.Security) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        label = { Text(stringResource(R.string.proxy_host)) },
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        singleLine = true,
                        enabled = uiState.isProxyEnabled,
                        keyboardOptions = KeyboardSecurity.secureChatOptions
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { if (it.all { char -> char.isDigit() }) proxyPort = it },
                        label = { Text(stringResource(R.string.proxy_port)) },
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardSecurity.securePasswordOptions.copy(keyboardType = KeyboardType.Number),
                        enabled = uiState.isProxyEnabled
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.setProxySettings(proxyHost, proxyPort.toIntOrNull() ?: 9050) },
                        modifier = Modifier.align(Alignment.End).padding(end = 16.dp),
                        enabled = uiState.isProxyEnabled && (proxyHost != uiState.proxyHost || proxyPort != uiState.proxyPort.toString())
                    ) { Text(stringResource(R.string.save)) }
                }
            }

            HorizontalDivider()
            
            Text(
                text = stringResource(R.string.privacy_security),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.message_ttl_title)) },
                supportingContent = { Text(getMessageTtlLabel(uiState.messageTTL)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.History) },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.Ttl }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_clipboard_title)) },
                supportingContent = { Text(getClipboardDelayLabel(uiState.clipboardClearDelay)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.ContentPasteGo) },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.Clipboard }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.read_receipts)) },
                supportingContent = { Text(stringResource(R.string.read_receipts_desc)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.DoneAll) },
                trailingContent = { Switch(checked = uiState.isReadReceiptsEnabled, onCheckedChange = { viewModel.toggleReadReceipts(it) }) }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.only_contacts)) },
                supportingContent = { Text(stringResource(R.string.only_contacts_description)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.PersonSearch) },
                trailingContent = { Switch(checked = uiState.onlyContacts, onCheckedChange = { viewModel.toggleOnlyContacts(it) }) }
            )
            
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_lock)) }, 
                leadingContent = { SettingsLeadingIcon(Icons.Default.Lock) }, 
                trailingContent = { Switch(checked = uiState.isAppLockEnabled, onCheckedChange = { viewModel.toggleAppLock(it) }) }
            )

            AnimatedVisibility(visible = uiState.isAppLockEnabled) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.set_pin)) },
                        supportingContent = { Text(if (uiState.isPinSet) stringResource(R.string.pin_set) else stringResource(R.string.pin_not_set)) },
                        leadingContent = { SettingsLeadingIcon(Icons.Default.Password) },
                        modifier = Modifier.clickable { activeDialog = SettingsDialog.Pin },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.biometric_unlock)) },
                        leadingContent = { SettingsLeadingIcon(Icons.Default.Fingerprint) },
                        trailingContent = {
                            Switch(
                                checked = uiState.isBiometricEnabled,
                                onCheckedChange = { viewModel.toggleBiometric(it) }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.disable_screenshots)) }, 
                leadingContent = { SettingsLeadingIcon(Icons.Default.NoPhotography) }, 
                trailingContent = { Switch(checked = uiState.isScreenshotsDisabled, onCheckedChange = { viewModel.toggleScreenshotsDisabled(it) }) }
            )
        }
        
        AtsuSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // Dialog Handling
    when (val dialog = activeDialog) {
        is SettingsDialog.Language -> {
            LanguageSelectionDialog(
                currentLanguage = uiState.currentLanguage,
                onDismiss = { activeDialog = null },
                onLanguageSelected = { viewModel.changeLanguage(it); activeDialog = null }
            )
        }
        is SettingsDialog.Theme -> {
            ThemeSelectionDialog(
                currentMode = uiState.themeMode,
                onDismiss = { activeDialog = null },
                onModeSelected = { viewModel.setThemeMode(it); activeDialog = null }
            )
        }
        is SettingsDialog.Font -> {
            FontSelectionDialog(
                currentFont = uiState.appFont,
                onDismiss = { activeDialog = null },
                onFontSelected = { viewModel.setAppFont(it); activeDialog = null }
            )
        }
        is SettingsDialog.Ttl -> {
            TtlSelectionDialog(
                currentTtl = uiState.messageTTL,
                onDismiss = { activeDialog = null },
                onTtlSelected = { viewModel.setMessageTTL(it); activeDialog = null }
            )
        }
        is SettingsDialog.Clipboard -> {
            ClipboardSelectionDialog(
                currentDelay = uiState.clipboardClearDelay,
                onDismiss = { activeDialog = null },
                onDelaySelected = { viewModel.setClipboardClearDelay(it); activeDialog = null }
            )
        }
        is SettingsDialog.EditName -> {
            EditNameDialog(
                initialName = uiState.loginName,
                onDismiss = { activeDialog = null },
                onConfirm = { viewModel.setLoginName(it); activeDialog = null }
            )
        }
        is SettingsDialog.CreateProfile -> {
            CreateProfileDialog(
                onDismiss = { activeDialog = null },
                onConfirm = { viewModel.createNewProfile(it); activeDialog = null }
            )
        }
        is SettingsDialog.MnemonicImport -> {
            MnemonicImportDialog(
                onDismiss = { activeDialog = null },
                onConfirm = { text, name -> 
                    viewModel.importNewProfile(text.trim().split("\\s+".toRegex()), name)
                    activeDialog = null
                }
            )
        }
        is SettingsDialog.MnemonicDisplay -> {
            uiState.mnemonic?.let {
                MnemonicDisplayDialog(
                    mnemonic = it,
                    onDismiss = { viewModel.clearMnemonicFromState(); activeDialog = null }
                )
            }
        }
        is SettingsDialog.Pin -> {
            PinSetupDialog(
                onDismiss = { activeDialog = null },
                onPinSet = { viewModel.setPin(it); activeDialog = null },
                isPinSet = uiState.isPinSet,
                verifyOldPin = { viewModel.verifyOldPin(it) }
            )
        }
        is SettingsDialog.DeleteProfile -> {
            DeleteProfileConfirmDialog(
                profileName = dialog.name,
                hasPin = uiState.profilesWithPin.contains(dialog.id),
                onDismiss = { activeDialog = null },
                onConfirm = { pin ->
                    viewModel.deleteProfile(
                        userId = dialog.id,
                        pinChars = pin,
                        onSuccess = { activeDialog = null },
                        onError = { deleteError = it }
                    )
                }
            )
        }
        is SettingsDialog.Access -> {
            AccessCodeDialog(
                onDismiss = { activeDialog = null },
                onVerify = { code, onResult -> viewModel.applyAccessCode(code, onResult) }
            )
        }
        null -> {}
    }
}

@Composable
fun SettingsLeadingIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier.width(24.dp).height(32.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = modifier.padding(top = 4.dp))
    }
}

@Composable
fun getLanguageName(code: String): String = when {
    code == "system" -> stringResource(R.string.system_default)
    code.startsWith("en") -> "English"
    code.startsWith("ru") -> "Русский"
    code.startsWith("de") -> "Deutsch"
    code.startsWith("fr") -> "Français"
    code.startsWith("es") -> "Español"
    code.startsWith("it") -> "Italiano"
    code.startsWith("cs") -> "Čeština"
    else -> code
}

@Composable
fun localizeNtfyStatus(status: NtfyStatus): String = when(status) {
    NtfyStatus.CONNECTED -> stringResource(R.string.status_connected)
    NtfyStatus.CONNECTING -> stringResource(R.string.status_connecting)
    NtfyStatus.ERROR -> stringResource(R.string.status_error)
    else -> stringResource(R.string.status_idle)
}

@Composable
fun localizeVpsStatus(status: ConnectionStatus): String = when(status) {
    ConnectionStatus.CONNECTED -> stringResource(R.string.status_connected)
    ConnectionStatus.AUTHENTICATING, ConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting)
    ConnectionStatus.ERROR -> stringResource(R.string.status_error)
    else -> stringResource(R.string.status_idle)
}

@Composable
fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
}
