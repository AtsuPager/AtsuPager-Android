package com.nax.atsupager.ui.screens.games

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nax.atsupager.R
import com.nax.atsupager.data.model.User
import com.nax.atsupager.ui.screens.call.*
import com.nax.atsupager.ui.screens.main.*
import com.nax.atsupager.webrtc.CallStatusManager

enum class GameStatus { NONE, ACTIVE, SAVED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onReturnToCall: () -> Unit,
    onNavigateToHome: (Int) -> Unit,
    callStatusManager: CallStatusManager,
    viewModel: GamesViewModel = hiltViewModel(),
    callViewModel: CallViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val activeCall by callStatusManager.activeCall.collectAsState()
    val callUiState by callViewModel.uiState.collectAsState()
    val activeGameInSession by callStatusManager.activeGameType.collectAsState()
    val totalUnread by callStatusManager.totalUnreadCount.collectAsState()
    val chatUnread by callStatusManager.activeUserUnreadCount.collectAsState()

    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val displayName = uiState.targetUsername ?: stringResource(R.string.caller_unknown)
    val currentUserId = uiState.targetUserId

    val user = remember(currentUserId, displayName) {
        if (currentUserId.isNotEmpty()) User(id = currentUserId, username = displayName, publicKey = null) else null
    }

    val isGameActive = uiState.isChessActive || uiState.isBackgammonActive || uiState.isCheckersActive

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) callViewModel.onPauseVideoForNavigation()
            else if (event == Lifecycle.Event.ON_RESUME) callViewModel.onResumeVideoFromNavigation()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = true) {
        if (uiState.isChessActive) viewModel.exitChessToGallery()
        else if (uiState.isBackgammonActive) viewModel.exitBackgammonToGallery()
        else if (uiState.isCheckersActive) viewModel.exitCheckersToGallery()
        else onNavigateBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background, // Исправлено: теперь фон совпадает с темой
        topBar = {
            val statusText = when {
                uiState.isChessActive -> stringResource(R.string.chess)
                uiState.isBackgammonActive -> stringResource(R.string.backgammon)
                uiState.isCheckersActive -> stringResource(R.string.checkers)
                else -> stringResource(R.string.games)
            }

            AtsuTopAppBar(
                mode = TopAppBarMode.GAMES,
                user = user,
                statusText = statusText,
                activeCallInfo = uiState.activeCallInfo,
                activeCallUser = uiState.activeCallUser,
                callDuration = callUiState.callDuration,
                isGameActive = isGameActive,
                unreadCount = totalUnread,
                chatUnreadCount = chatUnread,
                onNavigateBack = {
                    if (uiState.isChessActive) viewModel.exitChessToGallery()
                    else if (uiState.isBackgammonActive) viewModel.exitBackgammonToGallery()
                    else if (uiState.isCheckersActive) viewModel.exitCheckersToGallery()
                    else onNavigateBack()
                },
                onActionClick = { action ->
                    when (action) {
                        TopAppBarAction.HOME, TopAppBarAction.HISTORY, TopAppBarAction.CONTACTS -> onNavigateToHome(-1)
                        TopAppBarAction.GAMES -> {
                            if (uiState.isChessActive) viewModel.exitChessToGallery()
                            else if (uiState.isBackgammonActive) viewModel.exitBackgammonToGallery()
                            else if (uiState.isCheckersActive) viewModel.exitCheckersToGallery()
                        }
                        TopAppBarAction.CHAT -> if (currentUserId.isNotEmpty()) onNavigateToChat(currentUserId)
                        TopAppBarAction.VOICE_CALL -> viewModel.initiateAudioCall()
                        TopAppBarAction.VIDEO_CALL -> viewModel.initiateVideoCall()
                        TopAppBarAction.RETURN_TO_CALL -> onReturnToCall()
                        else -> {}
                    }
                },
                onOpenSettings = onOpenSettings
            )
        }
    ) { padding ->
        ScreenFrame(
            modifier = Modifier.padding(padding)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isChessActive -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(top = 120.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top
                        ) {
                            ChessBoard(
                                fen = uiState.chessFen,
                                myColor = uiState.myChessColor,
                                isMyTurn = uiState.isMyChessTurn,
                                lastMove = uiState.lastChessMove,
                                isCheck = uiState.isChessCheck,
                                gameStatus = uiState.gameStatus,
                                pendingPromotion = uiState.pendingPromotion,
                                onMove = { r1, c1, r2, c2 ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.onChessMove(r1, c1, r2, c2)
                                },
                                onPromote = { piece -> viewModel.promotePawn(piece) }
                            )
                        }
                    }
                    uiState.isBackgammonActive -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(top = 120.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top
                        ) {
                            BackgammonBoard(
                                state = uiState.backgammonState,
                                myColor = uiState.myBackgammonColor,
                                isMyTurn = uiState.isMyBackgammonTurn,
                                onMove = { from, to, dice ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.onBackgammonMove(from, to, dice)
                                },
                                onRollDice = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.onRollBackgammonDice()
                                }
                            )
                        }
                    }
                    uiState.isCheckersActive -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(top = 120.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top
                        ) {
                            CheckersBoard(
                                state = uiState.checkersState,
                                myColor = uiState.myCheckersColor,
                                isMyTurn = uiState.isMyCheckersTurn,
                                lastMove = uiState.lastCheckersMove,
                                winner = uiState.checkersWinner,
                                onMove = { r1, c1, r2, c2 ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.onCheckersMove(r1, c1, r2, c2)
                                }
                            )
                        }
                    }
                    uiState.isWaitingForChessAccept || uiState.isWaitingForBackgammonAccept || uiState.isWaitingForCheckersAccept -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.waiting_accept, displayName), modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
                                Button(onClick = { viewModel.cancelInvite() }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.cancel)) }
                            }
                        }
                    }
                    else -> {
                        GameSelectionGrid(
                            hasChess = uiState.hasExistingChess,
                            hasBackgammon = uiState.hasExistingBackgammon,
                            hasCheckers = uiState.hasExistingCheckers,
                            activeGameType = activeGameInSession,
                            onChessClick = { if (uiState.hasExistingChess) viewModel.showChessMenu() else viewModel.startNewChessGame("white") },
                            onBackgammonClick = { if (uiState.hasExistingBackgammon) viewModel.showBackgammonMenu() else viewModel.startNewBackgammonGame("white") },
                            onCheckersClick = { if (uiState.hasExistingCheckers) viewModel.showCheckersMenu() else viewModel.startNewCheckersGame("white") }
                        )
                    }
                }

                if (activeCall != null && callUiState.isMinimized && (uiState.isChessActive || uiState.isBackgammonActive || uiState.isCheckersActive)) {
                    GameVideoOverlay(callUiState = callUiState, callViewModel = callViewModel, onReturnToCall = onReturnToCall)
                }

                if (isGameActive && currentUserId.isNotEmpty()) {
                    GameChatComponent(
                        contactId = currentUserId,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .imePadding()
                            .padding(bottom = 8.dp, end = 8.dp)
                    )
                }

                if (uiState.showChessMenu) {
                    ChessMenuDialog(hasExistingGame = uiState.hasExistingChess, onStartNew = viewModel::startNewChessGame, onContinue = viewModel::continueChessGame, onDismiss = viewModel::closeChessMenu)
                }
                if (uiState.showBackgammonMenu) {
                    BackgammonMenuDialog(hasExistingGame = uiState.hasExistingChess, onStartNew = viewModel::startNewBackgammonGame, onContinue = viewModel::continueBackgammonGame, onDismiss = viewModel::closeBackgammonMenu)
                }
                if (uiState.showCheckersMenu) {
                    CheckersMenuDialog(hasExistingGame = uiState.hasExistingCheckers, onStartNew = viewModel::startNewCheckersGame, onContinue = viewModel::continueCheckersGame, onDismiss = viewModel::closeCheckersMenu)
                }
            }
        }
    }
}

@Composable
fun GameSelectionGrid(
    hasChess: Boolean,
    hasBackgammon: Boolean,
    hasCheckers: Boolean,
    activeGameType: String?,
    onChessClick: () -> Unit,
    onBackgammonClick: () -> Unit,
    onCheckersClick: () -> Unit
) {
    LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            val status = when {
                activeGameType == "chess" -> GameStatus.ACTIVE
                hasChess -> GameStatus.SAVED
                else -> GameStatus.NONE
            }
            GameCard(name = stringResource(R.string.chess), painter = painterResource(id = R.drawable.ic_chess_wr), status = status, onClick = onChessClick)
        }
        item {
            val status = when {
                activeGameType == "backgammon" -> GameStatus.ACTIVE
                hasBackgammon -> GameStatus.SAVED
                else -> GameStatus.NONE
            }
            GameCard(name = stringResource(R.string.backgammon), icon = Icons.Default.Casino, status = status, onClick = onBackgammonClick)
        }
        item {
            val status = when {
                activeGameType == "checkers" -> GameStatus.ACTIVE
                hasCheckers -> GameStatus.SAVED
                else -> GameStatus.NONE
            }
            GameCard(name = stringResource(R.string.checkers), status = status, onClick = onCheckersClick) {
                Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(32.dp).offset(x = (-8).dp, y = (-4).dp)) { CheckersPiece(char = 'b') }
                    Box(modifier = Modifier.size(32.dp).offset(x = 8.dp, y = 4.dp)) { CheckersPiece(char = 'w') }
                }
            }
        }
        item { GameCard(name = stringResource(R.string.coming_soon), icon = Icons.Default.Games, onClick = {}) }
    }
}

@Composable
fun GameCard(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    painter: Painter? = null,
    status: GameStatus = GameStatus.NONE,
    onClick: () -> Unit,
    customContent: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(140.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = if (status == GameStatus.ACTIVE) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)) else CardDefaults.cardColors()
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (status != GameStatus.NONE) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(10.dp).background(if (status == GameStatus.ACTIVE) Color.Green else Color.Red, CircleShape).border(1.dp, Color.White, CircleShape))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (customContent != null) {
                    customContent()
                } else if (painter != null) {
                    Image(painter = painter, contentDescription = null, modifier = Modifier.size(56.dp))
                } else if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (status == GameStatus.ACTIVE) MaterialTheme.colorScheme.primary else Color.Unspecified)
                Text(text = if (status == GameStatus.ACTIVE) stringResource(R.string.status_active) else if (status == GameStatus.SAVED) stringResource(R.string.status_saved) else "", style = MaterialTheme.typography.labelSmall, color = if (status == GameStatus.ACTIVE) MaterialTheme.colorScheme.primary else Color.Gray)
            }
        }
    }
}

@Composable
fun BoxScope.GameVideoOverlay(callUiState: com.nax.atsupager.ui.screens.call.CallUiState, callViewModel: CallViewModel, onReturnToCall: () -> Unit) {
    LaunchedEffect(callUiState.remoteVideoTrack, callUiState.isRemoteCameraOn, callUiState.isLocalCameraActive, callUiState.isCameraPaused) {
        callViewModel.updateSinks(isVideoOnTop = true, callUiState.remoteVideoTrack, isDualPiP = true)
    }
    DisposableEffect(Unit) {
        onDispose {
            (callViewModel.pipRenderer.parent as? ViewGroup)?.removeView(callViewModel.pipRenderer)
            (callViewModel.bgRenderer.parent as? ViewGroup)?.removeView(callViewModel.bgRenderer)
        }
    }
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Box(modifier = Modifier.size(95.dp).align(Alignment.TopStart).clip(RoundedCornerShape(12.dp)).background(Color.Black).border(2.dp, if (callUiState.isCameraOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Red.copy(alpha = 0.5f), RoundedCornerShape(12.dp))) {
            if (callUiState.isLocalCameraActive && !callUiState.isCameraPaused) {
                AndroidView(factory = { callViewModel.pipRenderer.apply { (parent as? ViewGroup)?.removeView(this) } }, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.VideocamOff, null, modifier = Modifier.size(24.dp), tint = Color.DarkGray)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = if (callUiState.isCameraPaused) stringResource(R.string.pause) else stringResource(R.string.camera_off), color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, textAlign = TextAlign.Center, lineHeight = 10.sp)
                    }
                }
            }
            IconButton(onClick = { callViewModel.onToggleCamera() }, modifier = Modifier.align(Alignment.BottomEnd).size(28.dp).padding(4.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape) ) {
                Icon(imageVector = if (callUiState.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, contentDescription = null, tint = if (callUiState.isCameraOn) Color.White else Color.Red, modifier = Modifier.size(14.dp))
            }
        }
        Box(modifier = Modifier.size(95.dp).align(Alignment.TopEnd).clip(RoundedCornerShape(12.dp)).background(Color.Black).border(2.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))) {
            if (callUiState.remoteVideoTrack != null && callUiState.isRemoteCameraOn) {
                AndroidView(factory = { callViewModel.bgRenderer.apply { (parent as? ViewGroup)?.removeView(this) } }, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Person, null, modifier = Modifier.size(28.dp), tint = Color.DarkGray)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = stringResource(R.string.camera_off), color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, textAlign = TextAlign.Center, lineHeight = 10.sp)
                    }
                }
            }
            IconButton(onClick = onReturnToCall, modifier = Modifier.align(Alignment.BottomEnd).size(28.dp).padding(4.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
                Icon(Icons.Default.Fullscreen, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}
