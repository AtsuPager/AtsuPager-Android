package com.nax.atsupager.domain.checkers

import android.util.Log
import com.google.gson.Gson
import com.nax.atsupager.data.db.CheckersDao
import com.nax.atsupager.data.db.CheckersGameEntity
import com.nax.atsupager.data.network.CheckersMovePacket
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

private const val TAG = "CheckersManager"

data class CheckersMove(val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int)

@Singleton
class CheckersManager @Inject constructor(
    private val checkersDao: CheckersDao,
    private val signalRepository: SignalRepository,
    private val signalingClient: SignalingClient,
    private val gson: Gson
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null

    private val _gameState = MutableStateFlow<CheckersGameState>(CheckersGameState.Inactive)
    val gameState = _gameState.asStateFlow()

    fun updateTargetUser(targetUserId: String) {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            checkersDao.getGame(targetUserId).collect { entity ->
                if (entity != null) {
                    val turn = entity.state.split(" ")[1]
                    val isMyTurn = (turn == "w" && entity.myColor == "white") ||
                                   (turn == "b" && entity.myColor == "black")
                    val lastMove = if (entity.lastFromRow != -1) CheckersMove(entity.lastFromRow, entity.lastFromCol, entity.lastToRow, entity.lastToCol) else null
                    _gameState.update { CheckersGameState.Active(entity.gameId, entity.state, entity.myColor, isMyTurn, entity.moveIndex, lastMove) }
                } else {
                    _gameState.update { CheckersGameState.Inactive }
                }
            }
        }
    }

    suspend fun deleteGame(targetUserId: String) {
        checkersDao.deleteGame(targetUserId)
    }

    suspend fun initGameLocally(targetUserId: String, gameId: String, state: String, myColor: String) {
        saveGame(targetUserId, gameId, state, myColor, -1, -1, -1, -1, 0)
    }

    suspend fun makeMove(targetUserId: String, fromR: Int, fromC: Int, toR: Int, toC: Int): Boolean {
        val currentState = _gameState.value as? CheckersGameState.Active ?: return false
        if (!currentState.isMyTurn) return false
        
        val newState = CheckersEngine.makeMove(currentState.state, fromR, fromC, toR, toC) ?: return false
        val nextIdx = currentState.moveIndex + 1
        
        val packet = CheckersMovePacket(newState, fromR, fromC, toR, toC, nextIdx)
        return try {
            signalRepository.sendSignal(targetUserId, SignalData(currentState.gameId, SignalType.CHECKERS_MOVE, gson.toJson(packet)))
            saveGame(targetUserId, currentState.gameId, newState, currentState.myColor, fromR, fromC, toR, toC, nextIdx)
            true
        } catch (e: Exception) { false }
    }

    fun handleIncomingMove(targetUserId: String, packet: CheckersMovePacket) {
        scope.launch {
            val currentState = _gameState.value as? CheckersGameState.Active ?: return@launch
            if (packet.moveIndex <= currentState.moveIndex) return@launch
            saveGame(targetUserId, currentState.gameId, packet.state, currentState.myColor, packet.fromR, packet.fromC, packet.toR, packet.toC, packet.moveIndex)
        }
    }

    private suspend fun saveGame(cId: String, gId: String, state: String, color: String, fR: Int, fC: Int, tR: Int, tC: Int, idx: Int) {
        val turn = state.split(" ")[1]
        val myTurn = (turn == "w" && color == "white") || (turn == "b" && color == "black")
        checkersDao.saveGame(CheckersGameEntity(cId, gId, state, color, System.currentTimeMillis(), myTurn, idx, fR, fC, tR, tC))
    }

    sealed class CheckersGameState {
        object Inactive : CheckersGameState()
        data class Active(val gameId: String, val state: String, val myColor: String, val isMyTurn: Boolean, val moveIndex: Int, val lastMove: CheckersMove?) : CheckersGameState()
    }
}
