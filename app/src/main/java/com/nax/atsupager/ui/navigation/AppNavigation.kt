package com.nax.atsupager.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nax.atsupager.R
import com.nax.atsupager.ui.screens.call.CallScreen
import com.nax.atsupager.ui.screens.call.CallViewModel
import com.nax.atsupager.ui.screens.contacts.ContactsScreen
import com.nax.atsupager.ui.screens.login.LoginScreen
import com.nax.atsupager.ui.screens.main.MainScreen
import com.nax.atsupager.ui.screens.settings.SettingsScreen
import com.nax.atsupager.ui.screens.splash.SplashScreen
import com.nax.atsupager.ui.screens.splash.SplashDestination
import com.nax.atsupager.ui.screens.splash.SplashViewModel
import com.nax.atsupager.ui.screens.games.GamesScreen
import com.nax.atsupager.ui.screens.games.GamesViewModel
import com.nax.atsupager.ui.screens.games.GameInviteDialog
import com.nax.atsupager.ui.screens.lock.LockScreen
import com.nax.atsupager.webrtc.CallStatusManager

@Composable
fun AppNavigation(
    navController: NavHostController, 
    callStatusManager: CallStatusManager,
    onOpenSettings: () -> Unit
) {
    val activeCall by callStatusManager.activeCall.collectAsState()
    val isMinimized by callStatusManager.isMinimized.collectAsState()
    val invite by callStatusManager.incomingInvite.collectAsState()
    val senderName by callStatusManager.inviteSenderName.collectAsState()
    val totalUnread by callStatusManager.totalUnreadCount.collectAsState()
    val hasChats by callStatusManager.hasChats.collectAsState()
    
    val callViewModel: CallViewModel = hiltViewModel()

    // Универсальное действие для перехода "Домой"
    val navigateToHomeAction: (Int) -> Unit = { tab ->
        val targetTab = if (tab == -1) {
            if (totalUnread > 0 || hasChats) 0 else 1
        } else tab
        
        val route = Screen.Contacts.createRoute(targetTab)
        navController.navigate(route) {
            // Очищаем ВЕСЬ стек до самого начала, чтобы избежать накопления окон
            popUpTo(navController.graph.id) {
                inclusive = true
            }
            launchSingleTop = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController, 
            startDestination = Screen.Splash.route,
            enterTransition = { 
                fadeIn(animationSpec = tween(220, delayMillis = 90, easing = LinearOutSlowInEasing)) + 
                scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90, easing = LinearOutSlowInEasing))
            },
            exitTransition = { 
                fadeOut(animationSpec = tween(90, easing = FastOutLinearInEasing))
            },
            popEnterTransition = { 
                fadeIn(animationSpec = tween(220, delayMillis = 90, easing = LinearOutSlowInEasing)) +
                scaleIn(initialScale = 1.08f, animationSpec = tween(220, delayMillis = 90, easing = LinearOutSlowInEasing))
            },
            popExitTransition = { 
                fadeOut(animationSpec = tween(90, easing = FastOutLinearInEasing))
            }
        ) {
            composable(Screen.Splash.route) {
                val splashViewModel: SplashViewModel = hiltViewModel()
                val destination by splashViewModel.destination.collectAsState()
                
                LaunchedEffect(destination) {
                    if (destination == SplashDestination.Loading) return@LaunchedEffect
                    
                    val targetTab = if (totalUnread > 0 || hasChats) 0 else 1
                    val route = when (destination) {
                        SplashDestination.Login -> Screen.Login.route
                        SplashDestination.Lock -> Screen.Lock.route
                        SplashDestination.Main -> Screen.Contacts.createRoute(targetTab)
                        else -> return@LaunchedEffect
                    }
                    
                    navController.navigate(route) {
                        // Обязательно удаляем Splash из истории
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
                SplashScreen()
            }
            
            composable(Screen.Lock.route) {
                LockScreen(
                    onUnlockSuccess = {
                        val targetTab = if (totalUnread > 0 || hasChats) 0 else 1
                        navController.navigate(Screen.Contacts.createRoute(targetTab)) {
                            popUpTo(Screen.Lock.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Login.route) {
                LoginScreen(onLoginComplete = {
                    val targetTab = if (totalUnread > 0 || hasChats) 0 else 1
                    navController.navigate(Screen.Contacts.createRoute(targetTab)) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                })
            }

            composable(
                route = Screen.Contacts.route,
                arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = -1 })
            ) { backStackEntry ->
                val tab = backStackEntry.arguments?.getInt("tab") ?: -1
                ContactsScreen(
                    initialTab = tab,
                    onNavigateToMain = { route ->
                        navController.navigate(route) {
                            // Не удаляем Contacts, чтобы можно было вернуться назад
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = onOpenSettings,
                    onReturnToCall = { callStatusManager.restore() }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(onNavigateBack = { navController.popBackStack() })
            }

            composable(
                route = Screen.Games.route,
                arguments = listOf(navArgument("userId") { type = NavType.StringType })
            ) {
                val gamesViewModel: GamesViewModel = hiltViewModel()
                GamesScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToChat = { userId ->
                        navController.navigate(Screen.Main.createRoute(userId)) {
                            popUpTo(Screen.Contacts.route)
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = onOpenSettings,
                    onReturnToCall = { callStatusManager.restore() },
                    onNavigateToHome = navigateToHomeAction,
                    callStatusManager = callStatusManager,
                    viewModel = gamesViewModel,
                    callViewModel = callViewModel
                )
            }

            composable(
                route = Screen.Main.route,
                arguments = listOf(
                    navArgument("userId") { type = NavType.StringType },
                    navArgument("call") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val userId = backStackEntry.arguments?.getString("userId") ?: ""
                MainScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToCall = { _, _ -> callStatusManager.restore() },
                    onNavigateToGames = {
                        if (userId.isNotEmpty()) {
                            navController.navigate(Screen.Games.createRoute(userId)) {
                                popUpTo(Screen.Contacts.route)
                                launchSingleTop = true
                            }
                        }
                    },
                    onOpenSettings = onOpenSettings,
                    onNavigateToHome = navigateToHomeAction,
                    callStatusManager = callStatusManager
                )
            }
        }

        // Окно звонка
        AnimatedVisibility(
            visible = activeCall != null && !isMinimized,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(500)),
            modifier = Modifier.zIndex(10f)
        ) {
            CallScreen(
                viewModel = callViewModel,
                onHangup = { callViewModel.onHangup() },
                onNavigateToChat = { userId ->
                    navController.navigate(Screen.Main.createRoute(userId)) {
                        popUpTo(Screen.Contacts.route)
                        launchSingleTop = true
                    }
                    callStatusManager.minimize()
                },
                onOpenSettings = onOpenSettings,
                onNavigateBack = { callStatusManager.minimize() },
                onNavigateToContacts = {
                    navigateToHomeAction(1)
                    callStatusManager.minimize()
                },
                onNavigateToGames = {
                    val userId = activeCall?.userId
                    if (!userId.isNullOrEmpty()) {
                        navController.navigate(Screen.Games.createRoute(userId)) {
                            popUpTo(Screen.Contacts.route)
                            launchSingleTop = true
                        }
                        callStatusManager.minimize()
                    }
                },
                onNavigateToHome = {
                    navigateToHomeAction(-1)
                    callStatusManager.minimize()
                }
            )
        }

        invite?.let { gameInvite ->
            val gameTitle = when(gameInvite.gameType) {
                "chess" -> stringResource(R.string.chess)
                "backgammon" -> stringResource(R.string.backgammon)
                "checkers" -> stringResource(R.string.checkers)
                else -> stringResource(R.string.games)
            }
            GameInviteDialog(
                gameTitle = gameTitle,
                senderName = senderName ?: stringResource(R.string.caller_unknown),
                onAccept = {
                    callStatusManager.acceptInvite()
                    callStatusManager.minimize()
                    navController.navigate(Screen.Games.createRoute(gameInvite.senderId)) {
                        popUpTo(Screen.Contacts.route)
                        launchSingleTop = true
                    }
                },
                onReject = { callStatusManager.rejectInvite() }
            )
        }
    }
}
