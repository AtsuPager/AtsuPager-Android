package com.nax.atsupager.ui.screens.call

import android.app.Activity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nax.atsupager.R
import com.nax.atsupager.data.model.User
import com.nax.atsupager.ui.screens.main.AtsuTopAppBar
import com.nax.atsupager.ui.screens.main.TopAppBarAction
import com.nax.atsupager.ui.screens.main.TopAppBarMode
import com.nax.atsupager.webrtc.ActiveCallInfo
import com.nax.atsupager.webrtc.AudioDevice
import com.nax.atsupager.webrtc.NtfyService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(
    viewModel: CallViewModel,
    onHangup: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToContacts: () -> Unit = {},
    onNavigateToGames: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: (Int) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler {
        if (uiState.callState == CallState.DISCONNECTED || uiState.callState == CallState.ERROR || uiState.callState == CallState.IDLE) {
            onHangup()
        } else {
            onNavigateBack()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onPauseVideoForNavigation()
                Lifecycle.Event.ON_RESUME -> viewModel.onResumeVideoFromNavigation()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        viewModel.onResumeVideoFromNavigation()
        onDispose {
            if (uiState.callState != CallState.DISCONNECTED && uiState.callState != CallState.IDLE) {
                viewModel.onPauseVideoForNavigation()
            }
            (viewModel.bgRenderer.parent as? ViewGroup)?.removeView(viewModel.bgRenderer)
            (viewModel.pipRenderer.parent as? ViewGroup)?.removeView(viewModel.pipRenderer)
            NtfyService.cancelCallNotification(context)
        }
    }

    LaunchedEffect(uiState.isLocalVideoOnTop, uiState.remoteVideoTrack, uiState.isReady, uiState.isLocalCameraActive, uiState.isRemoteCameraOn, uiState.isCameraPaused) {
        if (uiState.isReady) viewModel.updateSinks(uiState.isLocalVideoOnTop, uiState.remoteVideoTrack)
    }

    DisposableEffect(uiState.callState) {
        val window = (context as? Activity)?.window
        if (uiState.callState == CallState.CONNECTED) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(uiState.callState) {
        if (uiState.callState == CallState.DISCONNECTED || uiState.callState == CallState.ERROR) {
            delay(1500)
            onHangup()
        }
    }

    val statusText = when (uiState.callState) {
        CallState.CREATING_OFFER -> stringResource(R.string.calling)
        CallState.CONNECTING -> stringResource(R.string.connecting)
        CallState.RECONNECTING -> stringResource(R.string.reconnecting)
        CallState.CONNECTED -> formatDuration(uiState.callDuration)
        CallState.INCOMING_CALL -> stringResource(R.string.incoming_call)
        CallState.DISCONNECTED -> stringResource(R.string.call_ended)
        CallState.ERROR -> stringResource(R.string.call_error, uiState.error ?: "")
        else -> ""
    }

    Scaffold(
        topBar = {
            AtsuTopAppBar(
                mode = TopAppBarMode.CALL,
                user = User(id = viewModel.getTargetUserId(), username = uiState.username ?: stringResource(R.string.caller_unknown), publicKey = null),
                group = null,
                currentUserId = "",
                statusText = statusText,
                activeCallInfo = ActiveCallInfo(
                    userId = viewModel.getTargetUserId(),
                    isCaller = false, 
                    isVideo = uiState.isVideoCall,
                    callId = "",
                    isEstablished = uiState.callState == CallState.CONNECTED,
                    isRemoteMicOn = uiState.isRemoteMicOn
                ),
                callDuration = uiState.callDuration,
                unreadCount = uiState.totalUnreadCount,
                chatUnreadCount = uiState.chatUnreadCount,
                onActionClick = { action ->
                    when (action) {
                        TopAppBarAction.HOME, TopAppBarAction.HISTORY, TopAppBarAction.CONTACTS -> onNavigateToHome(-1)
                        TopAppBarAction.GAMES -> onNavigateToGames()
                        TopAppBarAction.CHAT -> onNavigateToChat(viewModel.getTargetUserId())
                        else -> {}
                    }
                },
                onOpenSettings = onOpenSettings,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Видео-фрейм (Floating Card)
            Box(
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .animateContentSize()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.DarkGray)
                    .border(2.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
            ) {
                val isLocalActive = uiState.isLocalCameraActive && !uiState.isCameraPaused
                
                if (isLocalActive || uiState.remoteVideoTrack != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val isRemoteInBg = uiState.isLocalVideoOnTop
                        val showBgVideo = if (isRemoteInBg) (uiState.remoteVideoTrack != null && uiState.isRemoteCameraOn) else isLocalActive
                        
                        if (showBgVideo) {
                            AndroidView(
                                factory = { viewModel.bgRenderer.apply { (parent as? ViewGroup)?.removeView(this) } }, 
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, null, modifier = Modifier.size(80.dp), tint = Color.DarkGray)
                                val isLocalPaused = !isRemoteInBg && !isLocalActive
                                val isRemotePaused = isRemoteInBg && !uiState.isRemoteCameraOn
                                if (isLocalPaused || isRemotePaused) {
                                    Text(text = stringResource(R.string.camera_off), color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 100.dp))
                                }
                            }
                        }
                    }

                    val showPipVideo = if (uiState.isLocalVideoOnTop) isLocalActive else (uiState.remoteVideoTrack != null && uiState.isRemoteCameraOn)
                    val showPipPlaceholder = if (uiState.isLocalVideoOnTop) false else (uiState.remoteVideoTrack != null && !uiState.isRemoteCameraOn)

                    if (showPipVideo || showPipPlaceholder) {
                        Box(
                            modifier = Modifier
                                .padding(12.dp)
                                .size(100.dp)
                                .align(Alignment.TopEnd)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black)
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                                .clickable { viewModel.onToggleVideoLayout() }
                        ) {
                            if (showPipVideo) {
                                AndroidView(
                                    factory = { viewModel.pipRenderer.apply { (parent as? ViewGroup)?.removeView(this) } }, 
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.Person, null, modifier = Modifier.size(40.dp).align(Alignment.Center), tint = Color.DarkGray)
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.Person, contentDescription = null, modifier = Modifier.size(120.dp), tint = Color.Gray)
                        Text(text = stringResource(R.string.camera_off), color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 140.dp))
                    }
                }
            }

            // Нижняя панель управления
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (uiState.callState != CallState.IDLE && uiState.callState != CallState.DISCONNECTED && uiState.callState != CallState.ERROR) {
                    AudioControlPanel(uiState = uiState, viewModel = viewModel)
                    Spacer(modifier = Modifier.height(32.dp))
                }
                
                if (uiState.callState == CallState.INCOMING_CALL) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        IconButton(onClick = { viewModel.onAnswer() }, modifier = Modifier.size(72.dp).background(Color(0xFF4CAF50), CircleShape)) { 
                            Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(32.dp)) 
                        }
                        IconButton(onClick = { viewModel.onReject() }, modifier = Modifier.size(72.dp).background(Color(0xFFF44336), CircleShape)) { 
                            Icon(Icons.Default.CallEnd, null, tint = Color.White, modifier = Modifier.size(32.dp)) 
                        }
                    }
                } else {
                    IconButton(
                        onClick = { 
                            if (uiState.callState == CallState.DISCONNECTED || uiState.callState == CallState.ERROR || uiState.callState == CallState.IDLE) {
                                onHangup() 
                            } else {
                                viewModel.onHangup()
                            }
                        }, 
                        modifier = Modifier.size(80.dp).background(Color(0xFFF44336), CircleShape)
                    ) { 
                        Icon(Icons.Default.CallEnd, null, tint = Color.White, modifier = Modifier.size(40.dp)) 
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}

@Composable
fun AudioControlPanel(uiState: CallUiState, viewModel: CallViewModel) {
    var showDeviceMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(32.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp), 
        horizontalArrangement = Arrangement.spacedBy(16.dp), 
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlIcon(icon = if (uiState.isMicOn) Icons.Default.Mic else Icons.Default.MicOff, isActive = uiState.isMicOn, onClick = { viewModel.onToggleMic() })
        ControlIcon(icon = if (uiState.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, isActive = uiState.isCameraOn, onClick = { viewModel.onToggleCamera() })
        ControlIcon(icon = Icons.Default.Cameraswitch, isActive = true, onClick = { viewModel.onSwitchCamera() })
        Box {
            ControlIcon(icon = when (uiState.selectedAudioDevice) { is AudioDevice.Speaker -> Icons.Default.VolumeUp; is AudioDevice.Earpiece -> Icons.Default.PhoneAndroid; is AudioDevice.Bluetooth -> Icons.Default.Bluetooth; else -> Icons.Default.VolumeUp }, isActive = true, onClick = { showDeviceMenu = true })
            DropdownMenu(expanded = showDeviceMenu, onDismissRequest = { showDeviceMenu = false }) {
                uiState.availableAudioDevices.forEach { device ->
                    DropdownMenuItem(
                        text = { val label = when(device) { is AudioDevice.Speaker -> stringResource(R.string.speaker); is AudioDevice.Earpiece -> stringResource(R.string.earpiece); is AudioDevice.Bluetooth -> device.name }; Text(label) },
                        onClick = { viewModel.onAudioDeviceSelected(device); showDeviceMenu = false },
                        leadingIcon = { Icon(imageVector = when (device) { is AudioDevice.Speaker -> Icons.Default.VolumeUp; is AudioDevice.Earpiece -> Icons.Default.PhoneAndroid; is AudioDevice.Bluetooth -> Icons.Default.Bluetooth }, contentDescription = null) }
                    )
                }
            }
        }
    }
}

@Composable
fun ControlIcon(icon: ImageVector, isActive: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick, 
        modifier = Modifier
            .size(56.dp)
            .background(if (isActive) Color.White.copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.25f), CircleShape)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}
