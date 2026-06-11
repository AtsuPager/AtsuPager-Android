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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nax.atsupager.R
import com.nax.atsupager.data.network.ConnectionStatus
import com.nax.atsupager.security.ClipboardClearDelay
import com.nax.atsupager.security.ClipboardUiHelper
import com.nax.atsupager.security.KeyboardSecurity
import com.nax.atsupager.ui.theme.AppFont
import com.nax.atsupager.ui.components.StyledTextField
import com.nax.atsupager.ui.components.PinSetupDialog
import com.nax.atsupager.ui.components.AtsuSnackbarHost
import com.nax.atsupager.ui.utils.QRUtils
import com.nax.atsupager.webrtc.NtfyStatus

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

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var isNetworkExpanded by remember { mutableStateOf(false) }
    var isKeysExpanded by remember { mutableStateOf(false) }
    var isIdentityExpanded by remember { mutableStateOf(false) }
    var isProfilesExpanded by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showMnemonicImportDialog by remember { mutableStateOf(false) }
    var showCreateProfileDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showMnemonicDisplayDialog by remember { mutableStateOf(false) }
    var showTtlDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showAccessDialog by remember { mutableStateOf(false) }
    
    var profileToDelete by remember { mutableStateOf<String?>(null) }
    var deletePinInput by remember { mutableStateOf("") }
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
                modifier = Modifier.clickable { showLanguageDialog = true }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.theme)) },
                supportingContent = { Text(getThemeLabel(uiState.themeMode)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.Palette) },
                modifier = Modifier.clickable { showThemeDialog = true }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_font_title)) },
                supportingContent = { Text(getFontLabel(uiState.appFont)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.FontDownload) },
                modifier = Modifier.clickable { showFontDialog = true }
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
                        modifier = Modifier.clickable { showEditNameDialog = true },
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
                                    deletePinInput = ""
                                    deleteError = null
                                    profileToDelete = id
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
                            onClick = { showCreateProfileDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.create_id))
                        }
                        FilledTonalButton(
                            onClick = { showMnemonicImportDialog = true },
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
                                showMnemonicDisplayDialog = true 
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
                                fontWeight = FontWeight.Bold
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
                        onClick = { showAccessDialog = true },
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
                    StyledTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        placeholderText = stringResource(R.string.ntfy_placeholder),
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        keyboardOptions = KeyboardSecurity.secureChatOptions
                    )
                    Button(
                        onClick = { viewModel.setNtfyServerUrl(serverUrl) }, 
                        modifier = Modifier.align(Alignment.End).padding(end = 16.dp), 
                        enabled = serverUrl != uiState.ntfyServerUrl
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
                modifier = Modifier.clickable { showTtlDialog = true }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_clipboard_title)) },
                supportingContent = { Text(getClipboardDelayLabel(uiState.clipboardClearDelay)) },
                leadingContent = { SettingsLeadingIcon(Icons.Default.ContentPasteGo) },
                modifier = Modifier.clickable { showClipboardDialog = true }
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
                        modifier = Modifier.clickable { showPinDialog = true },
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

    // Access Activation Dialog
    if (showAccessDialog) {
        var codeInput by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }
        var errorText by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!isVerifying) showAccessDialog = false },
            title = { Text(stringResource(R.string.access_settings)) },
            text = {
                Column {
                    Text(stringResource(R.string.buy_code_info), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    StyledTextField(
                        value = codeInput,
                        onValueChange = { 
                            // Allow letters, digits and hyphens
                            val filtered = it.uppercase().filter { char -> char.isLetterOrDigit() || char == '-' }
                            if (filtered.length <= 25) codeInput = filtered 
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
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                // Count only alphanumeric characters
                val cleanLength = codeInput.filter { it.isLetterOrDigit() }.length
                Button(
                    onClick = {
                        isVerifying = true
                        viewModel.applyAccessCode(codeInput) { success, error ->
                            isVerifying = false
                            if (success) {
                                showAccessDialog = false
                            } else {
                                errorText = error
                            }
                        }
                    },
                    // Enabled only if exactly 16 meaningful symbols are present
                    enabled = cleanLength == 16 && !isVerifying
                ) {
                    if (isVerifying) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Text(stringResource(R.string.apply_code))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessDialog = false }, enabled = !isVerifying) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showEditNameDialog) {
        var tempName by remember { mutableStateOf(uiState.loginName) }
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text(stringResource(R.string.login_name)) },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = { Text(stringResource(R.string.username_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setLoginName(tempName)
                        showEditNameDialog = false
                    }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (profileToDelete != null) {
        val hasPin = uiState.profilesWithPin.contains(profileToDelete)
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_contact_msg, uiState.profiles[profileToDelete] ?: ""))
                    if (hasPin) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = deletePinInput,
                            onValueChange = { deletePinInput = it },
                            label = { Text(stringResource(R.string.old_pin)) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardSecurity.securePasswordOptions.copy(keyboardType = KeyboardType.NumberPassword),
                            isError = deleteError != null,
                            supportingText = { deleteError?.let { Text(it) } }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProfile(
                            userId = profileToDelete!!,
                            pinChars = deletePinInput.toCharArray(),
                            onSuccess = { 
                                deletePinInput = ""
                                profileToDelete = null 
                            },
                            onError = { deleteError = it }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !hasPin || deletePinInput.length >= 4
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { 
                    deletePinInput = ""
                    profileToDelete = null 
                }) { Text(stringResource(R.string.cancel)) } 
            }
        )
    }

    if (showCreateProfileDialog) {
        var newUsername by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateProfileDialog = false },
            title = { Text(stringResource(R.string.create_id)) },
            text = {
                OutlinedTextField(
                    value = newUsername,
                    onValueChange = { newUsername = it },
                    label = { Text(stringResource(R.string.username_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newUsername.isNotBlank()) {
                            viewModel.createNewProfile(newUsername)
                            showCreateProfileDialog = false
                        }
                    },
                    enabled = newUsername.isNotBlank()
                ) { Text(stringResource(R.string.generate_id_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateProfileDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showTtlDialog) {
        TtlSelectionDialog(
            currentTtl = uiState.messageTTL,
            onDismiss = { showTtlDialog = false },
            onTtlSelected = { viewModel.setMessageTTL(it); showTtlDialog = false }
        )
    }

    if (showClipboardDialog) {
        ClipboardSelectionDialog(
            currentDelay = uiState.clipboardClearDelay,
            onDismiss = { showClipboardDialog = false },
            onDelaySelected = { viewModel.setClipboardClearDelay(it); showClipboardDialog = false }
        )
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentMode = uiState.themeMode,
            onDismiss = { showThemeDialog = false },
            onModeSelected = { viewModel.setThemeMode(it); showThemeDialog = false }
        )
    }

    if (showFontDialog) {
        FontSelectionDialog(
            currentFont = uiState.appFont,
            onDismiss = { showFontDialog = false },
            onFontSelected = { viewModel.setAppFont(it); showFontDialog = false }
        )
    }

    if (showMnemonicDisplayDialog && uiState.mnemonic != null) {
        AlertDialog(
            onDismissRequest = { 
                viewModel.clearMnemonicFromState()
                showMnemonicDisplayDialog = false 
            },
            title = { Text(stringResource(R.string.your_mnemonic)) },
            text = {
                Column {
                    Text(stringResource(R.string.mnemonic_warning), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
                        Text(text = uiState.mnemonic!!.joinToString(" "), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = { 
                TextButton(onClick = { 
                    viewModel.clearMnemonicFromState()
                    showMnemonicDisplayDialog = false 
                }) { Text(stringResource(R.string.close)) } 
            }
        )
    }

    if (showMnemonicImportDialog) {
        var importText by remember { mutableStateOf("") }
        var importName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { 
                importText = ""
                showMnemonicImportDialog = false 
            },
            title = { Text(stringResource(R.string.import_id)) },
            text = { 
                Column {
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = { Text(stringResource(R.string.username_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text(stringResource(R.string.mnemonic_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardSecurity.securePasswordOptions
                    )
                }
            },
            confirmButton = { 
                Button(
                    onClick = {
                        viewModel.importNewProfile(importText.trim().split("\\s+".toRegex()), importName);
                        importText = ""
                        showMnemonicImportDialog = false
                    },
                    enabled = importName.isNotBlank() && importText.isNotBlank()
                ) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { 
                TextButton(onClick = { 
                    importText = ""
                    showMnemonicImportDialog = false 
                }) { Text(stringResource(R.string.cancel)) } 
            }
        )
    }

    if (showPinDialog) {
        PinSetupDialog(
            onDismiss = { showPinDialog = false },
            onPinSet = { 
                viewModel.setPin(it)
                showPinDialog = false 
            },
            isPinSet = uiState.isPinSet,
            verifyOldPin = { viewModel.verifyOldPin(it) }
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = uiState.currentLanguage,
            onDismiss = { showLanguageDialog = false },
            onLanguageSelected = { viewModel.changeLanguage(it); showLanguageDialog = false }
        )
    }
}

@Composable
fun SettingsLeadingIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(32.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun FontSelectionDialog(currentFont: AppFont, onDismiss: () -> Unit, onFontSelected: (AppFont) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_font_title)) },
        text = {
            Column {
                AppFont.entries.forEach { font ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFontSelected(font) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentFont == font, onClick = { onFontSelected(font) })
                        Spacer(Modifier.width(8.dp))
                        Text(getFontLabel(font))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun getFontLabel(font: AppFont): String {
    return when(font) {
        AppFont.SYSTEM -> stringResource(R.string.settings_font_system)
        AppFont.INTER -> stringResource(R.string.settings_font_inter)
        AppFont.MANROPE -> stringResource(R.string.settings_font_manrope)
        AppFont.JETBRAINS_MONO -> stringResource(R.string.settings_font_jetbrains)
    }
}

@Composable
fun ThemeSelectionDialog(currentMode: ThemeMode, onDismiss: () -> Unit, onModeSelected: (ThemeMode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme)) },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeSelected(mode) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentMode == mode, onClick = { onModeSelected(mode) })
                        Spacer(Modifier.width(8.dp))
                        Text(getThemeLabel(mode))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun getThemeLabel(mode: ThemeMode): String {
    return when(mode) {
        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
        ThemeMode.DARK -> stringResource(R.string.theme_dark)
        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    }
}

@Composable
fun TtlSelectionDialog(currentTtl: MessageTTL, onDismiss: () -> Unit, onTtlSelected: (MessageTTL) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_ttl_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.message_ttl_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 16.dp)
                )
                MessageTTL.entries.forEach { ttl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTtlSelected(ttl) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentTtl == ttl, onClick = { onTtlSelected(ttl) })
                        Spacer(Modifier.width(8.dp))
                        Text(getMessageTtlLabel(ttl))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun getMessageTtlLabel(ttl: MessageTTL): String {
    return when(ttl) {
        MessageTTL.OFF -> stringResource(R.string.ttl_disabled)
        MessageTTL.ONE_HOUR -> stringResource(R.string.ttl_1h)
        MessageTTL.ONE_DAY -> stringResource(R.string.ttl_24h)
        MessageTTL.ONE_WEEK -> stringResource(R.string.ttl_7d)
        MessageTTL.ONE_MONTH -> stringResource(R.string.ttl_30d)
    }
}

@Composable
fun ClipboardSelectionDialog(currentDelay: ClipboardClearDelay, onDismiss: () -> Unit, onDelaySelected: (ClipboardClearDelay) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clipboard_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_clipboard_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 16.dp)
                )
                ClipboardClearDelay.entries.forEach { delay ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDelaySelected(delay) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentDelay == delay, onClick = { onDelaySelected(delay) })
                        Spacer(Modifier.width(8.dp))
                        Text(getClipboardDelayLabel(delay))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun getClipboardDelayLabel(delay: ClipboardClearDelay): String {
    return when(delay) {
        ClipboardClearDelay.THIRTY_SECONDS -> stringResource(R.string.settings_clipboard_30s)
        ClipboardClearDelay.ONE_MINUTE -> stringResource(R.string.settings_clipboard_1m)
        ClipboardClearDelay.FIVE_MINUTES -> stringResource(R.string.settings_clipboard_5m)
        ClipboardClearDelay.NEVER -> stringResource(R.string.settings_clipboard_never)
    }
}

@Composable
fun getLanguageName(code: String): String {
    return when {
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
}

@Composable
fun localizeNtfyStatus(status: NtfyStatus): String {
    return when(status) {
        NtfyStatus.CONNECTED -> stringResource(R.string.status_connected)
        NtfyStatus.CONNECTING -> stringResource(R.string.status_connecting)
        NtfyStatus.ERROR -> stringResource(R.string.status_error)
        else -> stringResource(R.string.status_idle)
    }
}

@Composable
fun localizeVpsStatus(status: ConnectionStatus): String {
    return when(status) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.status_connected)
        ConnectionStatus.AUTHENTICATING, ConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting)
        ConnectionStatus.ERROR -> stringResource(R.string.status_error)
        else -> stringResource(R.string.status_idle)
    }
}

@Composable
fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun LanguageSelectionDialog(currentLanguage: String, onDismiss: () -> Unit, onLanguageSelected: (String) -> Unit) {
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
        title = { Text(stringResource(R.string.choose_language)) },
        text = {
            Column {
                languages.forEach { (code, name) ->
                    val isSelected = if (code == "system") currentLanguage == "system" else currentLanguage.startsWith(code)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(code) }
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onLanguageSelected(code) },
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
