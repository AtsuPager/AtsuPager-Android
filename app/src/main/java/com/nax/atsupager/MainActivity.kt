/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.nax.atsupager.data.network.AuthRepository
import com.nax.atsupager.data.network.SignalRepository
import com.nax.atsupager.data.network.UserRepository
import com.nax.atsupager.security.ClipboardSecurityManager
import com.nax.atsupager.security.IntegrityManager
import com.nax.atsupager.security.MessageLifecycleManager
import com.nax.atsupager.security.SessionLockManager
import com.nax.atsupager.security.VisualPrivacyManager
import com.nax.atsupager.ui.navigation.AppNavigation
import com.nax.atsupager.ui.navigation.Screen
import com.nax.atsupager.ui.screens.settings.SettingsContent
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import com.nax.atsupager.ui.screens.settings.ThemeMode
import com.nax.atsupager.ui.theme.AppFont
import com.nax.atsupager.ui.theme.AtsuPagerTheme
import com.nax.atsupager.webrtc.CallStatusManager
import com.nax.atsupager.webrtc.NtfyService
import com.nax.atsupager.webrtc.SignalingClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var signalingClient: SignalingClient
    @Inject lateinit var signalRepository: SignalRepository
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var callStatusManager: CallStatusManager
    @Inject lateinit var sessionLockManager: SessionLockManager
    @Inject lateinit var messageLifecycleManager: MessageLifecycleManager

    private val intentState = mutableStateOf<Intent?>(null)
    private val isAppInForeground = mutableStateOf(true)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) checkConnectivityAutoStart()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callStatusManager.observeSignals(signalingClient)
        setupShowWhenLocked()
        
        enableEdgeToEdge()

        clearNotificationCounters()
        checkAndRequestPermissions()

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isAppInForeground.value = true
                Lifecycle.Event.ON_PAUSE -> isAppInForeground.value = false
                else -> {}
            }
        })

        intentState.value = intent
        handleIntent(intent)

        setContent {
            val navController = rememberNavController()
            val isLocked by sessionLockManager.isLocked.collectAsState()
            val appInForeground by isAppInForeground

            val themeModeString = remember { mutableStateOf(prefs.getString(SettingsViewModel.PREF_THEME_MODE, ThemeMode.DARK.name)) }
            val appFontString = remember { mutableStateOf(prefs.getString(SettingsViewModel.PREF_APP_FONT, AppFont.SYSTEM.name)) }
            
            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    when (key) {
                        SettingsViewModel.PREF_THEME_MODE -> {
                            themeModeString.value = p.getString(key, ThemeMode.DARK.name)
                        }
                        SettingsViewModel.PREF_APP_FONT -> {
                            appFontString.value = p.getString(key, AppFont.SYSTEM.name)
                        }
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            val darkTheme = when (ThemeMode.valueOf(themeModeString.value ?: ThemeMode.DARK.name)) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            val appFont = try {
                AppFont.valueOf(appFontString.value ?: AppFont.SYSTEM.name)
            } catch (e: Exception) {
                AppFont.SYSTEM
            }

            LaunchedEffect(darkTheme) {
                if (darkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        ),
                        navigationBarStyle = SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    )
                }
            }

            LaunchedEffect(Unit) { messageLifecycleManager.runCleanup() }

            var showRootWarning by remember { mutableStateOf(IntegrityManager.isDeviceRooted()) }
            val secureSetting = remember { mutableStateOf(prefs.getBoolean(SettingsViewModel.PREF_DISABLE_SCREENSHOTS, true)) }
            
            LaunchedEffect(secureSetting.value) {
                VisualPrivacyManager.updateSecureFlag(this@MainActivity, secureSetting.value)
            }

            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == SettingsViewModel.PREF_DISABLE_SCREENSHOTS) {
                        secureSetting.value = p.getBoolean(key, true)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            LaunchedEffect(isLocked) {
                if (isLocked) {
                    navController.navigate(Screen.Lock.route) { popUpTo(0) }
                }
            }

            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            var showSettingsSheet by rememberSaveable { mutableStateOf(false) }

            AtsuPagerTheme(darkTheme = darkTheme, appFont = appFont) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface) 
                ) {
                    AppNavigation(
                        navController = navController, 
                        callStatusManager = callStatusManager,
                        onOpenSettings = { showSettingsSheet = true }
                    )

                    if (showSettingsSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showSettingsSheet = false },
                            sheetState = sheetState,
                            dragHandle = { BottomSheetDefaults.DragHandle() },
                            containerColor = MaterialTheme.colorScheme.surface,
                            windowInsets = WindowInsets.statusBars,
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                        ) {
                            SettingsSheetHeader(onClose = { showSettingsSheet = false })
                            SettingsContent(hiltViewModel())
                        }
                    }

                    if (VisualPrivacyManager.shouldShowPrivacyMask(appInForeground)) {
                        PrivacyOverlay()
                    }

                    if (showRootWarning) {
                        AlertDialog(
                            onDismissRequest = { showRootWarning = false },
                            title = { 
                                Text(
                                    text = "Внимание: Небезопасная среда",
                                    style = MaterialTheme.typography.headlineSmall
                                ) 
                            },
                            text = { 
                                Text(
                                    text = "На вашем устройстве обнаружены Root-права. Это может снизить уровень безопасности AtsuPager.",
                                    style = MaterialTheme.typography.bodyMedium
                                ) 
                            },
                            confirmButton = {
                                Button(
                                    onClick = { showRootWarning = false },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Я понимаю риски")
                                }
                            }
                        )
                    }
                }
            }

            LaunchedEffect(intentState.value) {
                intentState.value?.let { intent ->
                    val isCallPush = intent.getBooleanExtra("incoming_call_push", false)
                    val chatWithId = intent.getStringExtra("chat_with_user_id")
                    val chatWithGroupId = intent.getStringExtra("chat_with_group_id")

                    if (isCallPush) {
                        callStatusManager.restore()
                        intent.removeExtra("incoming_call_push")
                    } else if (chatWithId != null) {
                        navController.navigate(Screen.Main.createRoute(chatWithId)) {
                            popUpTo(Screen.Contacts.route)
                            launchSingleTop = true
                        }
                        intent.removeExtra("chat_with_user_id")
                    } else if (chatWithGroupId != null) {
                        navController.navigate(Screen.GroupChat.createRoute(chatWithGroupId)) {
                            popUpTo(Screen.Contacts.route)
                            launchSingleTop = true
                        }
                        intent.removeExtra("chat_with_group_id")
                    }
                }
            }

            BackHandler(enabled = true) {
                if (showSettingsSheet) {
                    showSettingsSheet = false
                } else {
                    val activeCall = callStatusManager.activeCall.value
                    val isMinimized = callStatusManager.isMinimized.value
                    if (activeCall != null && !isMinimized) {
                        callStatusManager.minimize()
                    } else if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        moveTaskToBack(false)
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsSheetHeader(onClose: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.tab_settings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
            }
        }
        HorizontalDivider()
    }

    @Composable
    private fun PrivacyOverlay() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                modifier = Modifier.size(100.dp)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        sessionLockManager.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        sessionLockManager.onAppBackground()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            checkConnectivityAutoStart()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("incoming_call_push", false)) {
            val isEnabled = prefs.getBoolean(NtfyService.PREF_NTFY_ENABLED, true)
            if (isEnabled) {
                signalRepository.forcePoll()
                callStatusManager.restore()
                if (intent.getBooleanExtra("answer_call", false)) {
                    callStatusManager.answerCall()
                    intent.removeExtra("answer_call")
                }
            }
        }
    }

    private fun checkConnectivityAutoStart() {
        val isEnabled = prefs.getBoolean(NtfyService.PREF_NTFY_ENABLED, true)
        val userId = prefs.getString(AuthRepository.KEY_USER_ID, null)
        if (userId != null) {
            if (isEnabled) {
                if (!NtfyService.isRunning) NtfyService.start(this)
                signalRepository.startPolling()
            } else {
                NtfyService.stop(this)
                signalRepository.stopPolling()
            }
        }
    }

    private fun setupShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
    }

    override fun onResume() {
        super.onResume()
        setupShowWhenLocked()
        clearNotificationCounters()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent
        handleIntent(intent)
        setupShowWhenLocked()
        clearNotificationCounters()
    }

    private fun clearNotificationCounters() {
        prefs.edit().putInt(NtfyService.PREF_MISSED_CALLS, 0).putInt(NtfyService.PREF_NEW_MESSAGES, 0).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
