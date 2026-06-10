package com.nax.atsupager.domain.backgammon

import android.util.Log
import com.google.gson.Gson
import com.nax.atsupager.data.db.BackgammonDao
import com.nax.atsupager.data.db.BackgammonGameEntity
import com.nax.atsupager.data.network.BackgammonMovePacket
import com.nax.atsupager.data.network.SignalData
import com.nax.atsupager.data.network.SignalRepository
import com.nax.atsupager.data.network.SignalType
import com.nax.atsupager.webrtc.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BackgammonManager"

@Singleton
class BackgammonManager @Inject constructor(
    private val backgammonDao: BackgammonDao,
    private val signalRepository: SignalRepository,
    private val signalingClient: SignalingClient,
    private val gson: Gson
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null

    private val _gameState = MutableStateFlow<BackgammonGameState>(BackgammonGameState.Inactive)
    val gameState = _gameState.asStateFlow()

    fun updateTargetUser(targetUserId: String) {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            backgammonDao.getGame(targetUserId).collect { entity ->
                if (entity != null) {
                    val state = BackgammonEngine.stringToState(entity.state)
                    _gameState.update {
                        BackgammonGameState.Active(
                            gameId = entity.gameId,
                            state = state,
                            myColor = entity.myColor,
                            isMyTurn = (state.currentPlayer == entity.myColor),
                            moveIndex = entity.moveIndex
                        )
                    }
                } else {
                    _gameState.update { BackgammonGameState.Inactive }
                }
            }
        }
    }

    suspend fun rollDice(targetUserId: String) {
        val current = _gameState.value as? BackgammonGameState.Active ?: return
        if (!current.isMyTurn || current.state.dice.isNotEmpty()) return

        val rolledDice = BackgammonEngine.rollDice()
        // Используем onDiceRolled для автоматической проверки доступности ходов
        val newState = BackgammonEngine.onDiceRolled(current.state, rolledDice)
        val nextIdx = current.moveIndex + 1
        
        val packet = BackgammonMovePacket(state = BackgammonEngine.stateToString(newState), moveIndex = nextIdx)
        val signal = SignalData(callId = current.gameId, type = SignalType.BACKGAMMON_MOVE, payload = gson.toJson(packet))
        
        try {
            signalRepository.sendSignal(targetUserId, signal)
            saveGame(targetUserId, current.gameId, newState, current.myColor, (newState.currentPlayer == current.myColor), nextIdx)
        } catch (e: Exception) {
            Log.e(TAG, "Error rolling dice: ${e.message}")
        }
    }

    suspend fun makeMove(targetUserId: String, from: Int, to: Int, diceValue: Int): Boolean {
        val current = _gameState.value as? BackgammonGameState.Active ?: return false
        if (!current.isMyTurn) return false

        val newState = BackgammonEngine.applyMove(current.state, from, to, diceValue)
        val nextIdx = current.moveIndex + 1
        
        val movePacket = BackgammonMovePacket(state = BackgammonEngine.stateToString(newState), moveIndex = nextIdx)
        val signal = SignalData(callId = current.gameId, type = SignalType.BACKGAMMON_MOVE, payload = gson.toJson(movePacket))
        
        return try {
            signalRepository.sendSignal(targetUserId, signal)
            saveGame(targetUserId, current.gameId, newState, current.myColor, (newState.currentPlayer == current.myColor), nextIdx)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error making move: ${e.message}")
            false
        }
    }

    fun handleIncomingMove(targetUserId: String, packet: BackgammonMovePacket) {
        scope.launch {
            val current = _gameState.value as? BackgammonGameState.Active ?: return@launch
            if (packet.moveIndex <= current.moveIndex) return@launch
            
            val newState = BackgammonEngine.stringToState(packet.state)
            saveGame(targetUserId, current.gameId, newState, current.myColor, (newState.currentPlayer == current.myColor), packet.moveIndex)
        }
    }

    suspend fun deleteGame(targetUserId: String) {
        backgammonDao.deleteGame(targetUserId)
    }

    suspend fun initGameLocally(targetUserId: String, gameId: String, state: BackgammonEngine.GameState, myColor: String) {
        saveGame(targetUserId, gameId, state, myColor, (state.currentPlayer == myColor), 0)
    }

    private suspend fun saveGame(contactId: String, gameId: String, state: BackgammonEngine.GameState, myColor: String, isMyTurn: Boolean, moveIndex: Int) {
        backgammonDao.saveGame(BackgammonGameEntity(
            contactId = contactId, gameId = gameId,
            state = BackgammonEngine.stateToString(state),
            myColor = myColor, isMyTurn = isMyTurn,
            moveIndex = moveIndex, lastUpdated = System.currentTimeMillis()
        ))
    }

    sealed class BackgammonGameState {
        object Inactive : BackgammonGameState()
        data class Active(val gameId: String, val state: BackgammonEngine.GameState, val myColor: String, val isMyTurn: Boolean, val moveIndex: Int) : BackgammonGameState()
    }
}
