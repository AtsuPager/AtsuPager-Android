package com.nax.atsupager.ui.screens.games

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.data.model.User
import com.nax.atsupager.data.network.BackgammonMovePacket
import com.nax.atsupager.data.network.CheckersMovePacket
import com.nax.atsupager.data.network.GameInvitePacket
import com.nax.atsupager.data.network.ChessMovePacket
import com.nax.atsupager.data.network.IncomingSignal
import com.nax.atsupager.data.network.SignalData
import com.nax.atsupager.data.network.SignalRepository
import com.nax.atsupager.data.network.SignalType
import com.nax.atsupager.data.network.UserRepository
import com.nax.atsupager.data.network.AuthRepository
import com.nax.atsupager.domain.backgammon.BackgammonEngine
import com.nax.atsupager.domain.backgammon.BackgammonManager
import com.nax.atsupager.domain.checkers.CheckersEngine
import com.nax.atsupager.domain.checkers.CheckersManager
import com.nax.atsupager.domain.checkers.CheckersMove
import com.nax.atsupager.domain.chess.ChessEngine
import com.nax.atsupager.domain.chess.ChessManager
import com.nax.atsupager.domain.chess.ChessMove
import com.nax.atsupager.domain.chess.PromotionData
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import com.nax.atsupager.ui.screens.settings.AccessStatus
import com.nax.atsupager.webrtc.ActiveCallInfo
import com.nax.atsupager.webrtc.CallAudioManager
import com.nax.atsupager.webrtc.CallStatusManager
import com.nax.atsupager.webrtc.SignalingClient
import com.nax.atsupager.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import javax.inject.Inject

private const val TAG = "GamesViewModel"

data class GamesUiState(
    val targetUserId: String = "",
    val targetUsername: String? = null,
    val isChessActive: Boolean = false,
    val isBackgammonActive: Boolean = false,
    val isCheckersActive: Boolean = false,
    val isWaitingForChessAccept: Boolean = false,
    val isWaitingForBackgammonAccept: Boolean = false,
    val isWaitingForCheckersAccept: Boolean = false,
    val showChessMenu: Boolean = false,
    val showBackgammonMenu: Boolean = false,
    val showCheckersMenu: Boolean = false,
    val hasExistingChess: Boolean = false,
    val hasExistingBackgammon: Boolean = false,
    val hasExistingCheckers: Boolean = false,
    val chessFen: String = ChessEngine.INITIAL_FEN,
    val myChessColor: String = "white",
    val isMyChessTurn: Boolean = false,
    val gameStatus: ChessEngine.GameStatus = ChessEngine.GameStatus.ONGOING,
    val backgammonState: BackgammonEngine.GameState = BackgammonEngine.createInitialState(),
    val myBackgammonColor: String = "white",
    val isMyBackgammonTurn: Boolean = false,
    val checkersState: String = CheckersEngine.INITIAL_STATE,
    val myCheckersColor: String = "white",
    val isMyCheckersTurn: Boolean = false,
    val checkersWinner: String? = null,
    val lastChessMove: ChessMove? = null,
    val lastCheckersMove: CheckersMove? = null,
    val isChessCheck: Boolean = false,
    val pendingPromotion: PromotionData? = null,
    val activeCallInfo: ActiveCallInfo? = null,
    val activeCallUser: User? = null,
    val accessStatus: AccessStatus = AccessStatus.ACTIVE,
    val showAccessDialog: Boolean = false
)

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val chessManager: ChessManager,
    private val backgammonManager: BackgammonManager,
    private val checkersManager: CheckersManager,
    private val signalRepository: SignalRepository,
    private val signalingClient: SignalingClient,
    private val userRepository: UserRepository,
    private val callStatusManager: CallStatusManager,
    private val callAudioManager: CallAudioManager,
    private val messageDao: MessageDao,
    private val sharedPreferences: android.content.SharedPreferences,
    private val savedStateHandle: SavedStateHandle,
    private val gson: Gson,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val targetUserId: String = savedStateHandle.get<String>("userId") ?: ""
    private val myId: String = sharedPreferences.getString(AuthRepository.KEY_USER_ID, "") ?: ""
    
    private val _uiState = MutableStateFlow(GamesUiState(targetUserId = targetUserId))
    val uiState = _uiState.asStateFlow()

    init {
        if (targetUserId.isNotEmpty()) {
            loadTargetUsername()
            observeChessState()
            observeBackgammonState()
            observeCheckersState()
            
            chessManager.updateTargetUser(targetUserId)
            backgammonManager.updateTargetUser(targetUserId)
            checkersManager.updateTargetUser(targetUserId)
            
            observeIncomingSignals()
            observeGameActions()
            observeExternalStates()
            checkAcceptedInvites()
            signalRepository.forcePoll()
            
            val active = callStatusManager.activeGameType.value
            _uiState.update { it.copy(
                isChessActive = active == "chess",
                isBackgammonActive = active == "backgammon",
                isCheckersActive = active == "checkers"
            ) }

            checkAccessOnStart()
            
            signalRepository.accessRequiredEvent
                .onEach { _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.EXPIRED) } }
                .launchIn(viewModelScope)
        }
    }

    private fun checkAccessOnStart() {
        val expiry = sharedPreferences.getLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$myId", 0L)
        val isActiveStatus = when {
            expiry == -1L -> true
            expiry > System.currentTimeMillis() -> true
            else -> false
        }
        if (!isActiveStatus) {
            _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.NO_ACCESS) }
        } else {
            _uiState.update { it.copy(accessStatus = AccessStatus.ACTIVE) }
        }
    }

    private fun checkAccess(onAuthorized: () -> Unit) {
        val expiry = sharedPreferences.getLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$myId", 0L)
        val isActiveStatus = when {
            expiry == -1L -> true
            expiry > System.currentTimeMillis() -> true
            else -> false
        }
        if (isActiveStatus) {
            _uiState.update { it.copy(accessStatus = AccessStatus.ACTIVE) }
            onAuthorized()
        } else {
            _uiState.update { it.copy(showAccessDialog = true, accessStatus = AccessStatus.EXPIRED) }
        }
    }

    fun applyAccessCode(code: String, onResult: (Boolean, String?) -> Unit) {
        val cleanCode = code.replace(Regex("[^A-Z0-9]"), "").uppercase()
        if (cleanCode.length != 16) {
            onResult(false, context.getString(R.string.invalid_code))
            return
        }
        viewModelScope.launch {
            signalRepository.verifyAccessCode(cleanCode) { success, error, expiry ->
                viewModelScope.launch {
                    if (success && expiry != null) {
                        sharedPreferences.edit().putLong("${SettingsViewModel.PREF_ACCESS_EXPIRY}_$myId", expiry).apply()
                        _uiState.update { it.copy(showAccessDialog = false, accessStatus = AccessStatus.ACTIVE) }
                        onResult(true, null)
                    } else {
                        onResult(false, error ?: context.getString(R.string.invalid_code))
                    }
                }
            }
        }
    }

    fun closeAccessDialog() { _uiState.update { it.copy(showAccessDialog = false) } }

    private fun observeExternalStates() {
        viewModelScope.launch {
            callStatusManager.activeCall.collect { call ->
                _uiState.update { it.copy(activeCallInfo = call) }
                if (call != null && call.userId != targetUserId) {
                    val callUser = userRepository.getUser(call.userId)
                    _uiState.update { it.copy(activeCallUser = callUser) }
                } else {
                    _uiState.update { it.copy(activeCallUser = null) }
                }
            }
        }
    }

    private fun checkAcceptedInvites() {
        val accepted = callStatusManager.acceptedInvite.value
        if (accepted != null && accepted.senderId == targetUserId) {
            handleAcceptedInvite(accepted)
            callStatusManager.consumeAcceptedInvite()
        }
    }

    private fun loadTargetUsername() {
        viewModelScope.launch {
            val user = userRepository.getUser(targetUserId)
            _uiState.update { it.copy(targetUsername = user?.username) }
        }
    }

    private fun observeChessState() {
        chessManager.gameState.onEach { state ->
            when (state) {
                is ChessManager.ChessGameState.Active -> {
                    val status = ChessEngine.getGameStatus(state.fen)
                    if (state.fen != _uiState.value.chessFen) handleChessSounds(state.fen, status)
                    _uiState.update { it.copy(
                        hasExistingChess = true,
                        chessFen = state.fen,
                        myChessColor = state.myColor,
                        isMyChessTurn = state.isMyTurn,
                        gameStatus = status,
                        lastChessMove = state.lastMove,
                        isChessCheck = ChessEngine.isInCheck(state.fen, true) || ChessEngine.isInCheck(state.fen, false)
                    ) }
                }
                ChessManager.ChessGameState.Inactive -> {
                    _uiState.update { it.copy(hasExistingChess = false, isChessActive = false) }
                }
            }
        }.launchIn(viewModelScope)
    }

    private fun observeBackgammonState() {
        backgammonManager.gameState.onEach { state ->
            when (state) {
                is BackgammonManager.BackgammonGameState.Active -> {
                    if (state.state.moveIndex != _uiState.value.backgammonState.moveIndex) {
                        handleBackgammonSounds(state.state)
                    }
                    _uiState.update { it.copy(
                        hasExistingBackgammon = true,
                        backgammonState = state.state,
                        myBackgammonColor = state.myColor,
                        isMyBackgammonTurn = state.isMyTurn
                    ) }
                }
                BackgammonManager.BackgammonGameState.Inactive -> {
                    _uiState.update { it.copy(hasExistingBackgammon = false, isBackgammonActive = false) }
                }
            }
        }.launchIn(viewModelScope)
    }

    private fun observeCheckersState() {
        checkersManager.gameState.onEach { state ->
            when (state) {
                is CheckersManager.CheckersGameState.Active -> {
                    val winner = CheckersEngine.getWinner(state.state)
                    if (state.state != _uiState.value.checkersState) {
                        handleCheckersSounds(winner)
                    }
                    _uiState.update { it.copy(
                        hasExistingCheckers = true,
                        checkersState = state.state,
                        myCheckersColor = state.myColor,
                        isMyCheckersTurn = state.isMyTurn,
                        checkersWinner = winner,
                        lastCheckersMove = state.lastMove
                    ) }
                }
                CheckersManager.CheckersGameState.Inactive -> {
                    _uiState.update { it.copy(hasExistingCheckers = false, isCheckersActive = false) }
                }
            }
        }.launchIn(viewModelScope)
    }

    private fun handleBackgammonSounds(state: BackgammonEngine.GameState) {
        if (state.winner != null) {
            val iWon = state.winner == _uiState.value.myBackgammonColor
            if (iWon) callAudioManager.playVictoryTone() else callAudioManager.playDefeatTone()
        } else if (state.dice.isNotEmpty() && state.usedDice.isEmpty()) {
            callAudioManager.playDrawTone() 
        } else {
            callAudioManager.playMoveTone()
        }
    }

    private fun handleCheckersSounds(winner: String?) {
        if (winner != null) {
            val iWon = winner == _uiState.value.myCheckersColor
            if (iWon) callAudioManager.playVictoryTone() else callAudioManager.playDefeatTone()
        } else {
            callAudioManager.playMoveTone()
        }
    }

    private fun observeGameActions() {
        callStatusManager.gameActions.onEach { action ->
            if (action is CallStatusManager.GameAction.ACCEPT && action.invite.senderId == targetUserId) {
                handleAcceptedInvite(action.invite)
            } else if (action is CallStatusManager.GameAction.REJECT) {
                _uiState.update { it.copy(
                    isWaitingForChessAccept = false, 
                    isWaitingForBackgammonAccept = false,
                    isWaitingForCheckersAccept = false
                ) }
            }
        }.launchIn(viewModelScope)
    }

    private fun handleSignal(signal: IncomingSignal) {
        try {
            val signalData = gson.fromJson(signal.data, SignalData::class.java) ?: return
            
            when (signalData.type) {
                SignalType.GAME_ACCEPT, SignalType.CHESS_ACCEPT, SignalType.BACKGAMMON_ACCEPT, SignalType.CHECKERS_ACCEPT -> {
                    val acceptedType = signalData.payload ?: "chess"
                    _uiState.update { 
                        it.copy(
                            isChessActive = acceptedType == "chess",
                            isBackgammonActive = acceptedType == "backgammon",
                            isCheckersActive = acceptedType == "checkers",
                            isWaitingForChessAccept = false,
                            isWaitingForBackgammonAccept = false,
                            isWaitingForCheckersAccept = false
                        )
                    }
                    callStatusManager.setGameActive(acceptedType)
                    callAudioManager.playMoveTone()
                }
                SignalType.BACKGAMMON_MOVE -> {
                    val move = gson.fromJson(signalData.payload, BackgammonMovePacket::class.java)
                    backgammonManager.handleIncomingMove(targetUserId, move)
                }
                SignalType.CHESS_MOVE -> {
                    val move = gson.fromJson(signalData.payload, ChessMovePacket::class.java)
                    chessManager.handleIncomingMove(targetUserId, move)
                }
                SignalType.CHECKERS_MOVE -> {
                    val move = gson.fromJson(signalData.payload, CheckersMovePacket::class.java)
                    checkersManager.handleIncomingMove(targetUserId, move)
                }
                SignalType.GAME_CLOSE, SignalType.CHESS_CLOSE, SignalType.BACKGAMMON_CLOSE, SignalType.CHECKERS_CLOSE -> {
                    val closedType = signalData.payload ?: "chess"
                    _uiState.update { 
                        when(closedType) {
                            "chess" -> it.copy(isChessActive = false)
                            "backgammon" -> it.copy(isBackgammonActive = false)
                            "checkers" -> it.copy(isCheckersActive = false)
                            else -> it
                        }
                    }
                    if (callStatusManager.activeGameType.value == closedType) {
                        callStatusManager.setGameActive(null)
                    }
                }
                else -> {}
            }
        } catch (e: Exception) { Log.e(TAG, "Signal error", e) }
    }

    private fun observeIncomingSignals() {
        signalingClient.incomingSignals
            .filter { it.from == targetUserId }
            .onEach { handleSignal(it) }
            .launchIn(viewModelScope)
    }

    private fun handleAcceptedInvite(invite: GameInvitePacket) {
        when (invite.gameType) {
            "chess" -> acceptChessInvite(invite)
            "backgammon" -> acceptBackgammonInvite(invite)
            "checkers" -> acceptCheckersInvite(invite)
        }
    }

    private fun sendInviteAsChatMessage(gameType: String) {
        viewModelScope.launch {
            val gameTitle = when(gameType) {
                "chess" -> context.getString(R.string.chess)
                "backgammon" -> context.getString(R.string.backgammon)
                "checkers" -> context.getString(R.string.checkers)
                else -> gameType
            }
            val myName = sharedPreferences.getString(SettingsViewModel.PREF_LOGIN_NAME, "").takeIf { !it.isNullOrBlank() } ?: "User"
            val inviteText = "$myName " + context.getString(R.string.invite_msg_chat, "", gameTitle).trim()
            
            val inviteMsg = ChatMessage(
                fromUserId = myId, 
                toUserId = targetUserId, 
                text = inviteText, 
                timestamp = System.currentTimeMillis(), 
                isRead = true, 
                type = MessageType.GAME_INVITE
            )
            
            // SECURITY: Отправляем как файл/структуру, чтобы тип GAME_INVITE сохранился при десериализации на той стороне
            signalRepository.sendFileMessage(targetUserId, inviteMsg)
            messageDao.insertMessage(inviteMsg)
        }
    }

    fun startNewChessGame(myColor: String) {
        checkAccess {
            viewModelScope.launch {
                _uiState.update { it.copy(isBackgammonActive = false, isCheckersActive = false, showChessMenu = false, isWaitingForChessAccept = true) }
                val gameId = UUID.randomUUID().toString()
                val invite = GameInvitePacket("chess", myId, myColor, gameId, ChessEngine.INITIAL_FEN)
                chessManager.initGameLocally(targetUserId, gameId, ChessEngine.INITIAL_FEN, myColor)
                signalRepository.sendSignal(targetUserId, SignalData(callId = gameId, type = SignalType.GAME_INVITE, payload = gson.toJson(invite)))
                sendInviteAsChatMessage("chess")
            }
        }
    }

    fun acceptChessInvite(invite: GameInvitePacket) {
        viewModelScope.launch {
            val myColor = if (invite.senderColor == "white") "black" else "white"
            chessManager.initGameLocally(targetUserId, invite.gameId, invite.initialData ?: ChessEngine.INITIAL_FEN, myColor)
            _uiState.update { it.copy(isChessActive = true, isBackgammonActive = false, isCheckersActive = false) }
            callStatusManager.setGameActive("chess")
            signalRepository.sendSignal(targetUserId, SignalData(callId = invite.gameId, type = SignalType.CHESS_ACCEPT, payload = "chess"))
        }
    }

    fun startNewBackgammonGame(myColor: String) {
        checkAccess {
            viewModelScope.launch {
                _uiState.update { it.copy(isChessActive = false, isCheckersActive = false, showBackgammonMenu = false, isWaitingForBackgammonAccept = true) }
                val gameId = UUID.randomUUID().toString()
                val invite = GameInvitePacket("backgammon", myId, myColor, gameId)
                backgammonManager.initGameLocally(targetUserId, gameId, BackgammonEngine.createInitialState(), myColor)
                signalRepository.sendSignal(targetUserId, SignalData(callId = gameId, type = SignalType.GAME_INVITE, payload = gson.toJson(invite)))
                sendInviteAsChatMessage("backgammon")
            }
        }
    }

    fun acceptBackgammonInvite(invite: GameInvitePacket) {
        viewModelScope.launch {
            val myColor = if (invite.senderColor == "white") "black" else "white"
            val initialState = if (invite.initialData != null) BackgammonEngine.stringToState(invite.initialData) else BackgammonEngine.createInitialState()
            backgammonManager.initGameLocally(targetUserId, invite.gameId, initialState, myColor)
            _uiState.update { it.copy(isBackgammonActive = true, isChessActive = false, isCheckersActive = false) }
            callStatusManager.setGameActive("backgammon")
            signalRepository.sendSignal(targetUserId, SignalData(callId = invite.gameId, type = SignalType.BACKGAMMON_ACCEPT, payload = "backgammon"))
        }
    }

    fun startNewCheckersGame(myColor: String) {
        checkAccess {
            viewModelScope.launch {
                _uiState.update { it.copy(isChessActive = false, isBackgammonActive = false, showCheckersMenu = false, isWaitingForCheckersAccept = true) }
                val gameId = UUID.randomUUID().toString()
                val invite = GameInvitePacket("checkers", myId, myColor, gameId, CheckersEngine.INITIAL_STATE)
                checkersManager.initGameLocally(targetUserId, gameId, CheckersEngine.INITIAL_STATE, myColor)
                signalRepository.sendSignal(targetUserId, SignalData(callId = gameId, type = SignalType.GAME_INVITE, payload = gson.toJson(invite)))
                sendInviteAsChatMessage("checkers")
            }
        }
    }

    fun acceptCheckersInvite(invite: GameInvitePacket) {
        viewModelScope.launch {
            val myColor = if (invite.senderColor == "white") "black" else "white"
            checkersManager.initGameLocally(targetUserId, invite.gameId, invite.initialData ?: CheckersEngine.INITIAL_STATE, myColor)
            _uiState.update { it.copy(isCheckersActive = true, isChessActive = false, isBackgammonActive = false) }
            callStatusManager.setGameActive("checkers")
            signalRepository.sendSignal(targetUserId, SignalData(callId = invite.gameId, type = SignalType.CHECKERS_ACCEPT, payload = "checkers"))
        }
    }

    fun exitChessToGallery() { _uiState.update { it.copy(isChessActive = false) } }
    fun exitBackgammonToGallery() { _uiState.update { it.copy(isBackgammonActive = false) } }
    fun exitCheckersToGallery() { _uiState.update { it.copy(isCheckersActive = false) } }

    fun surrenderChess() {
        viewModelScope.launch {
            signalRepository.sendSignal(targetUserId, SignalData(callId = "game", type = SignalType.GAME_CLOSE, payload = "chess"))
            chessManager.deleteGame(targetUserId); _uiState.update { it.copy(isChessActive = false) }; callStatusManager.setGameActive(null)
        }
    }

    fun surrenderBackgammon() {
        viewModelScope.launch {
            signalRepository.sendSignal(targetUserId, SignalData(callId = "game", type = SignalType.GAME_CLOSE, payload = "backgammon"))
            backgammonManager.deleteGame(targetUserId); _uiState.update { it.copy(isBackgammonActive = false) }; callStatusManager.setGameActive(null)
        }
    }

    fun surrenderCheckers() {
        viewModelScope.launch {
            signalRepository.sendSignal(targetUserId, SignalData(callId = "game", type = SignalType.GAME_CLOSE, payload = "checkers"))
            checkersManager.deleteGame(targetUserId); _uiState.update { it.copy(isCheckersActive = false) }; callStatusManager.setGameActive(null)
        }
    }

    fun onChessMove(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
        if (!uiState.value.isMyChessTurn || uiState.value.gameStatus != ChessEngine.GameStatus.ONGOING) return
        if (!ChessEngine.isValidMove(uiState.value.chessFen, fromRow, fromCol, toRow, toCol)) return
        val board = ChessEngine.fenToBoard(uiState.value.chessFen)
        if (board[fromRow][fromCol]?.lowercaseChar() == 'p' && (toRow == 0 || toRow == 7)) {
            _uiState.update { it.copy(pendingPromotion = PromotionData(fromRow, fromCol, toRow, toCol)) }
        } else executeChessMove(fromRow, fromCol, toRow, toCol, null)
    }

    private fun executeChessMove(fR: Int, fC: Int, tR: Int, tC: Int, prom: Char?) {
        viewModelScope.launch { chessManager.makeMove(targetUserId, fR, fC, tR, tC, prom) }
    }

    fun onCheckersMove(fromR: Int, fromC: Int, toR: Int, toCol: Int) {
        if (!uiState.value.isMyCheckersTurn || uiState.value.checkersWinner != null) return
        viewModelScope.launch { checkersManager.makeMove(targetUserId, fromR, fromC, toR, toCol) }
    }

    fun onRollBackgammonDice() { viewModelScope.launch { backgammonManager.rollDice(targetUserId) } }
    fun onBackgammonMove(f: Int, t: Int, d: Int) { viewModelScope.launch { backgammonManager.makeMove(targetUserId, f, t, d) } }

    fun showChessMenu() { _uiState.update { it.copy(showChessMenu = true) } }
    fun collapseExpanded() { /* No-op */ }
    fun closeChessMenu() { _uiState.update { it.copy(showChessMenu = false) } }
    fun showBackgammonMenu() { _uiState.update { it.copy(showBackgammonMenu = true) } }
    fun closeBackgammonMenu() { _uiState.update { it.copy(showBackgammonMenu = false) } }
    fun showCheckersMenu() { _uiState.update { it.copy(showCheckersMenu = true) } }
    fun closeCheckersMenu() { _uiState.update { it.copy(showCheckersMenu = false) } }
    
    fun continueChessGame() { 
        checkAccess {
            val state = chessManager.gameState.value
            if (state is ChessManager.ChessGameState.Active) {
                viewModelScope.launch {
                    val newGameId = UUID.randomUUID().toString()
                    _uiState.update { it.copy(isWaitingForChessAccept = true, showChessMenu = false) }
                    chessManager.initGameLocally(targetUserId, newGameId, state.fen, state.myColor)
                    signalRepository.sendSignal(targetUserId, SignalData(callId = newGameId, type = SignalType.GAME_INVITE, payload = gson.toJson(GameInvitePacket("chess", myId, state.myColor, newGameId, state.fen))))
                    sendInviteAsChatMessage("chess")
                }
            }
        }
    }
    
    fun continueBackgammonGame() { 
        checkAccess {
            val state = backgammonManager.gameState.value
            if (state is BackgammonManager.BackgammonGameState.Active) {
                viewModelScope.launch {
                    val newGameId = UUID.randomUUID().toString()
                    _uiState.update { it.copy(isWaitingForBackgammonAccept = true, showBackgammonMenu = false) }
                    val stateStr = BackgammonEngine.stateToString(state.state)
                    backgammonManager.initGameLocally(targetUserId, newGameId, state.state, state.myColor)
                    signalRepository.sendSignal(targetUserId, SignalData(callId = newGameId, type = SignalType.GAME_INVITE, payload = gson.toJson(GameInvitePacket("backgammon", myId, state.myColor, newGameId, stateStr))))
                    sendInviteAsChatMessage("backgammon")
                }
            }
        }
    }

    fun continueCheckersGame() { 
        checkAccess {
            val state = checkersManager.gameState.value
            if (state is CheckersManager.CheckersGameState.Active) {
                viewModelScope.launch {
                    val newGameId = UUID.randomUUID().toString()
                    _uiState.update { it.copy(isWaitingForCheckersAccept = true, showCheckersMenu = false) }
                    checkersManager.initGameLocally(targetUserId, newGameId, state.state, state.myColor)
                    signalRepository.sendSignal(targetUserId, SignalData(callId = newGameId, type = SignalType.GAME_INVITE, payload = gson.toJson(GameInvitePacket("checkers", myId, state.myColor, newGameId, state.state))))
                    sendInviteAsChatMessage("checkers")
                }
            }
        }
    }

    fun cancelInvite() {
        _uiState.update { it.copy(isWaitingForChessAccept = false, isWaitingForBackgammonAccept = false, isWaitingForCheckersAccept = false) }
        viewModelScope.launch { signalRepository.sendSignal(targetUserId, SignalData(callId = "game", type = SignalType.GAME_CLOSE)) }
    }

    fun promotePawn(piece: Char) {
        val p = uiState.value.pendingPromotion ?: return
        _uiState.update { it.copy(pendingPromotion = null) }
        executeChessMove(p.fromR, p.fromC, p.toR, p.toC, piece)
    }

    fun refreshGame() { viewModelScope.launch { signalRepository.forcePoll() } }

    fun initiateAudioCall() {
        checkAccess {
            viewModelScope.launch {
                viewModelScope.launch(Dispatchers.IO) {
                    messageDao.insertMessage(ChatMessage(fromUserId = myId, toUserId = targetUserId, text = "", timestamp = System.currentTimeMillis(), isRead = true, type = MessageType.OUTGOING_CALL))
                }
                callStatusManager.startCall(targetUserId, isCaller = true, isVideo = false, callId = null)
            }
        }
    }

    fun initiateVideoCall() {
        checkAccess {
            viewModelScope.launch {
                viewModelScope.launch(Dispatchers.IO) {
                    messageDao.insertMessage(ChatMessage(fromUserId = myId, toUserId = targetUserId, text = "", timestamp = System.currentTimeMillis(), isRead = true, type = MessageType.OUTGOING_CALL))
                }
                callStatusManager.startCall(targetUserId, isCaller = true, isVideo = true, callId = null)
            }
        }
    }

    private fun handleChessSounds(fen: String, status: ChessEngine.GameStatus) {
        when {
            status == ChessEngine.GameStatus.WHITE_WIN || status == ChessEngine.GameStatus.BLACK_WIN -> {
                val win = (status == ChessEngine.GameStatus.WHITE_WIN && _uiState.value.myChessColor == "white") || (status == ChessEngine.GameStatus.BLACK_WIN && _uiState.value.myChessColor == "black")
                if (win) callAudioManager.playVictoryTone() else callAudioManager.playDefeatTone()
            }
            status == ChessEngine.GameStatus.DRAW_STALEMATE -> callAudioManager.playDrawTone()
            ChessEngine.isInCheck(fen, true) || ChessEngine.isInCheck(fen, false) -> callAudioManager.playCheckTone()
            else -> callAudioManager.playMoveTone()
        }
    }

    override fun onCleared() { super.onCleared(); signalRepository.setFastPolling(false) }
}
