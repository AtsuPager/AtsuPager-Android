package com.nax.atsupager.ui.screens.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nax.atsupager.R
import com.nax.atsupager.data.db.GroupEntity
import com.nax.atsupager.data.model.User
import com.nax.atsupager.ui.components.ContactAvatar
import com.nax.atsupager.webrtc.ActiveCallInfo

enum class TopAppBarMode {
    HOME, CHAT, CALL, GAMES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtsuTopAppBar(
    mode: TopAppBarMode,
    user: User? = null,
    group: GroupEntity? = null,
    currentUserId: String = "",
    statusText: String? = null,
    activeCallInfo: ActiveCallInfo? = null,
    activeCallUser: User? = null,
    callDuration: Long = 0,
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    isSettingsOpen: Boolean = false,
    onNavigateBack: (() -> Unit)? = null,
    onActionClick: (TopAppBarAction) -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    isGameActive: Boolean = false,
    unreadCount: Int = 0,
    chatUnreadCount: Int = 0,
    isMuted: Boolean = false
) {
    val isCallEstablished = activeCallInfo?.isEstablished == true
    val effectiveStatus = when {
        isCallEstablished && callDuration > 0 -> formatDuration(callDuration)
        statusText != null -> statusText
        (user != null || group != null) && mode != TopAppBarMode.HOME -> {
            if (group != null) {
                if (group.ownerId == currentUserId) stringResource(R.string.owner_label)
                else stringResource(R.string.group)
            } else stringResource(R.string.chat)
        }
        else -> null
    }

    val titleText = when {
        group != null -> group.name
        user != null -> user.username
        mode == TopAppBarMode.HOME -> stringResource(R.string.app_name)
        else -> stringResource(R.string.loading)
    }

    val isDark = MaterialTheme.colorScheme.background == Color.Black
    val containerColor = if (mode == TopAppBarMode.CALL) Color.Black else MaterialTheme.colorScheme.background
    val contentColor = if (mode == TopAppBarMode.CALL) Color.White else MaterialTheme.colorScheme.onBackground

    // Разделенный режим
    val isSplitMode = (mode == TopAppBarMode.CHAT || mode == TopAppBarMode.GAMES) && 
                      activeCallInfo != null && (group != null || (user != null && activeCallInfo.userId != user.id))

    val horizontalPadding = 12.dp

    Surface(
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSplitMode) {
                    Surface(
                        color = Color.Transparent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (group != null) {
                                ContactAvatar(username = group.name, size = 24.dp, fontSize = 12.sp, isGroup = true, modifier = Modifier.padding(end = 8.dp))
                                Text(text = group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                ContactAvatar(username = user?.username ?: "", size = 24.dp, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                                Text(text = user?.username ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Surface(
                        color = Color.Green.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .border(1.dp, Color.Green.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .clickable { onActionClick(TopAppBarAction.RETURN_TO_CALL) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), 
                            verticalAlignment = Alignment.CenterVertically, 
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ContactAvatar(username = activeCallUser?.username ?: "...", size = 24.dp, fontSize = 10.sp, isActiveCall = true)
                            Text(
                                text = activeCallUser?.username ?: "...", 
                                style = MaterialTheme.typography.bodyMedium, 
                                fontWeight = FontWeight.Bold, 
                                maxLines = 1, 
                                overflow = TextOverflow.Ellipsis, 
                                modifier = Modifier.weight(1f)
                            )
                            PulsingStatusIcon(isVideo = activeCallInfo?.isVideo == true)
                            Text(
                                text = formatDuration(callDuration), 
                                style = MaterialTheme.typography.labelMedium, 
                                color = Color(0xFF00FF00),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    val isCallActiveOutside = activeCallInfo != null && mode != TopAppBarMode.CALL
                    val isFullCallMode = mode == TopAppBarMode.CALL
                    
                    val content = @Composable {
                        val stableHeight = 28.dp
                        if (user == null && group == null && mode == TopAppBarMode.HOME) {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                val brandColor = if (isDark) Color(0xFF05E5FF) else Color(0xFF0D47A1)
                                val brandTextStyle = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    color = brandColor,
                                    letterSpacing = 1.sp,
                                    fontSize = 14.sp
                                )
                                Text(text = "  ATSU", style = brandTextStyle, modifier = Modifier.graphicsLayer(scaleY = 0.8f))
                                Spacer(modifier = Modifier.width(3.dp))
                                Box(
                                    modifier = Modifier.size(36.dp).clip(CircleShape).background(brandColor.copy(alpha = 0.1f)).border(1.2.dp, brandColor.copy(alpha = 0.3f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(model = R.mipmap.ic_launcher_round, contentDescription = null, modifier = Modifier.size(32.dp), contentScale = ContentScale.Fit)
                                }
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(text = "PAGER", style = brandTextStyle, modifier = Modifier.graphicsLayer(scaleY = 0.8f))
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth().height(stableHeight), verticalAlignment = Alignment.CenterVertically) {
                                if (group != null) {
                                    ContactAvatar(username = group.name, size = 24.dp, fontSize = 12.sp, isGroup = true, modifier = Modifier.padding(end = 8.dp))
                                } else if (user != null) {
                                    ContactAvatar(username = user.username, size = 24.dp, fontSize = 12.sp, isActiveCall = activeCallInfo != null && user.id == activeCallInfo.userId, modifier = Modifier.padding(end = 8.dp))
                                }
                                Text(text = titleText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (effectiveStatus != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isCallEstablished && callDuration > 0) {
                                            PulsingStatusIcon(isVideo = activeCallInfo?.isVideo == true)
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text(text = effectiveStatus, style = MaterialTheme.typography.labelLarge, color = if (isCallEstablished) Color(0xFF00FF00) else if (mode == TopAppBarMode.CALL) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary)
                                        if (isCallEstablished && activeCallInfo?.isRemoteMicOn == false) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(imageVector = Icons.Default.MicOff, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isCallActiveOutside || isFullCallMode) {
                        Surface(
                            color = if (isFullCallMode) Color.White.copy(alpha = 0.1f) else Color.Green.copy(alpha = 0.15f), 
                            shape = RoundedCornerShape(12.dp), 
                            modifier = Modifier.fillMaxWidth().border(1.dp, if (isFullCallMode) Color.White.copy(alpha = 0.2f) else Color.Green.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .then(if (isCallActiveOutside) Modifier.clickable { onActionClick(TopAppBarAction.RETURN_TO_CALL) } else Modifier)
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) { content() }
                        }
                    } else {
                        Surface(color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.padding(horizontal = 0.dp, vertical = 7.dp)) { content() }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (mode == TopAppBarMode.HOME) Arrangement.Start else Arrangement.SpaceBetween
            ) {
                if (mode == TopAppBarMode.HOME) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onActionClick(TopAppBarAction.HOME) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Spacer(Modifier.width(4.dp))
                        Text(text = stringResource(R.string.tab_contacts_history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (unreadCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Badge { Text(unreadCount.toString(), fontSize = 10.sp) }
                        }
                    }
                    Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) { MoreMenuButton(mode, onActionClick, onOpenSettings, isOwner = false, isGroupChat = false, isMuted = isMuted) }
                } else {
                    ActionIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, selected = false, mode = mode, onClick = { onActionClick(TopAppBarAction.HOME) })
                    ActionIconButton(icon = Icons.Default.People, selected = false, mode = mode, unreadCount = (unreadCount - chatUnreadCount).coerceAtLeast(0), onClick = { onActionClick(TopAppBarAction.HOME) })
                    
                    ActionIconButton(
                        icon = Icons.Default.Extension, 
                        selected = mode == TopAppBarMode.GAMES, 
                        enabled = user != null, 
                        mode = mode, 
                        onClick = { onActionClick(TopAppBarAction.GAMES) }
                    )
                    
                    val isAudioCallActive = activeCallInfo != null && !activeCallInfo.isVideo
                    ActionIconButton(
                        icon = Icons.Default.Call, 
                        selected = mode == TopAppBarMode.CALL && isAudioCallActive, 
                        pulsing = isAudioCallActive && mode != TopAppBarMode.CALL, 
                        enabled = user != null && activeCallInfo == null, 
                        mode = mode, 
                        onClick = { onActionClick(TopAppBarAction.VOICE_CALL) }
                    )
                    
                    val isVideoCallActive = activeCallInfo != null && activeCallInfo.isVideo
                    ActionIconButton(
                        icon = Icons.Default.Videocam, 
                        selected = mode == TopAppBarMode.CALL && isVideoCallActive, 
                        pulsing = isVideoCallActive && mode != TopAppBarMode.CALL, 
                        enabled = user != null && activeCallInfo == null, 
                        mode = mode, 
                        onClick = { onActionClick(TopAppBarAction.VIDEO_CALL) }
                    )
                    
                    ActionIconButton(
                        icon = Icons.Default.Chat, 
                        selected = mode == TopAppBarMode.CHAT, 
                        enabled = user != null || group != null, 
                        mode = mode, 
                        unreadCount = chatUnreadCount, 
                        onClick = { onActionClick(TopAppBarAction.CHAT) }
                    )
                    
                    val isOwner = group?.ownerId == currentUserId
                    MoreMenuButton(mode, onActionClick, onOpenSettings, isOwner = isOwner, isGroupChat = group != null, isMuted = isMuted)
                }
            }
        }
    }
}

@Composable
private fun ActionIconButton(icon: ImageVector, selected: Boolean, mode: TopAppBarMode, pulsing: Boolean = false, enabled: Boolean = true, unreadCount: Int = 0, onClick: () -> Unit) {
    val isCallMode = mode == TopAppBarMode.CALL
    val baseColor = if (selected) (if (isCallMode) Color(0xFF64B5F6) else MaterialTheme.colorScheme.primary) else (if (isCallMode) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant)
    val tint by animateColorAsState(targetValue = if (enabled) baseColor else baseColor.copy(alpha = 0.38f), label = "tint")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).border(0.5.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        if (pulsing && enabled) PulseEffect()
        if (unreadCount > 0) {
            BadgedBox(badge = { Badge { Text(unreadCount.toString(), fontSize = 10.sp) } }) {
                Icon(imageVector = icon, contentDescription = null, tint = if (pulsing && enabled) Color.Green else tint, modifier = Modifier.size(24.dp))
            }
        } else {
            Icon(imageVector = icon, contentDescription = null, tint = if (pulsing && enabled) Color.Green else tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun MoreMenuButton(
    mode: TopAppBarMode, 
    onActionClick: (TopAppBarAction) -> Unit, 
    onOpenSettings: (() -> Unit)?, 
    isOwner: Boolean = false, 
    isGroupChat: Boolean = false,
    isMuted: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }
    val size = if (mode == TopAppBarMode.HOME) 48.dp else 42.dp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(size)) { Icon(imageVector = Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_menu), modifier = Modifier.size(24.dp)) }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tab_settings)) }, 
                leadingIcon = { Icon(Icons.Default.Settings, null) }, 
                onClick = { showMenu = false; onOpenSettings?.invoke() }
            )
            
            if (mode == TopAppBarMode.CHAT) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.menu_sounds), modifier = Modifier.weight(1f))
                            Switch(
                                checked = !isMuted, 
                                onCheckedChange = null,
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    },
                    leadingIcon = { Icon(if (isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications, null) },
                    onClick = { onActionClick(TopAppBarAction.TOGGLE_MUTE) }
                )
            }
            
            if (mode == TopAppBarMode.CHAT) {
                if (isGroupChat) {
                    if (isOwner) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_member)) }, leadingIcon = { Icon(Icons.Default.PersonAdd, null) }, onClick = { onActionClick(TopAppBarAction.ADD_MEMBER); showMenu = false })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.group_members)) }, leadingIcon = { Icon(Icons.Default.People, null) }, onClick = { onActionClick(TopAppBarAction.GROUP_MEMBERS); showMenu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.leave_group)) }, leadingIcon = { Icon(Icons.Default.ExitToApp, null) }, onClick = { onActionClick(TopAppBarAction.LEAVE_GROUP); showMenu = false })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.clear_chat)) }, leadingIcon = { Icon(Icons.Default.DeleteSweep, null) }, onClick = { onActionClick(TopAppBarAction.CLEAR_CHAT); showMenu = false })
            }
            
            if (mode == TopAppBarMode.HOME) {
                DropdownMenuItem(text = { Text(stringResource(R.string.export_profile_title)) }, leadingIcon = { Icon(Icons.Default.Upload, null) }, onClick = { onActionClick(TopAppBarAction.EXPORT_PROFILE); showMenu = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.import_profile_title)) }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { onActionClick(TopAppBarAction.IMPORT_PROFILE); showMenu = false })
            }
        }
    }
}

enum class TopAppBarAction { SETTINGS, GAMES, CHAT, CONTACTS, HISTORY, HOME, VOICE_CALL, VIDEO_CALL, RETURN_TO_CALL, CREATE_GROUP, CLEAR_CHAT, TOGGLE_FULLSCREEN, EXPORT_PROFILE, IMPORT_PROFILE, DELETE_SELECTED, GROUP_MEMBERS, LEAVE_GROUP, ADD_MEMBER, TOGGLE_MUTE }

@Composable
private fun PulseEffect() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.6f, animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "scale")
    val alpha by infiniteTransition.animateFloat(initialValue = 0.5f, targetValue = 0f, animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "alpha")
    Box(Modifier.size(28.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }.background(Color.Green, CircleShape))
}

@Composable
private fun PulsingStatusIcon(isVideo: Boolean) {
    val density = LocalDensity.current
    val iconSize = with(density) { 14.sp.toDp() }
    val boxSize = with(density) { 18.sp.toDp() }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(boxSize)) {
        val infiniteTransition = rememberInfiniteTransition(label = "icon_pulse")
        val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.4f, animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "scale")
        val alpha by infiniteTransition.animateFloat(initialValue = 0.6f, targetValue = 0f, animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "alpha")
        Box(Modifier.size(iconSize).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }.background(Color.Green, CircleShape))
        Icon(imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Call, contentDescription = null, tint = Color.Green, modifier = Modifier.size(iconSize))
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
}

@Composable
fun ScreenFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(modifier = Modifier.fillMaxSize(), content = content) }
}
