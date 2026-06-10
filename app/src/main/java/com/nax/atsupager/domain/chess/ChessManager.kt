package com.nax.atsupager.domain.chess

import android.util.Log
import com.google.gson.Gson
import com.nax.atsupager.data.db.ChessDao
import com.nax.atsupager.data.db.ChessGameEntity
import com.nax.atsupager.data.network.ChessMovePacket
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

private const val TAG = "ChessManager"

data class ChessMove(val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int)
data class PromotionData(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

@Singleton
class ChessManager @Inject constructor(
    private val chessDao: ChessDao,
    private val signalRepository: SignalRepository,
    private val signalingClient: SignalingClient,
    private val gson: Gson
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null

    private val _gameState = MutableStateFlow<ChessGameState>(ChessGameState.Inactive)
    val gameState = _gameState.asStateFlow()

    fun updateTargetUser(targetUserId: String) {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            chessDao.getGame(targetUserId).collect { entity ->
                if (entity != null) {
                    val isMyTurn = (entity.fen.split(" ")[1] == "w" && entity.myColor == "white") ||
                                   (entity.fen.split(" ")[1] == "b" && entity.myColor == "black")
                    val lastMove = if (entity.lastFromRow != -1) ChessMove(entity.lastFromRow, entity.lastFromCol, entity.lastToRow, entity.lastToCol) else null
                    _gameState.update { ChessGameState.Active(entity.gameId, entity.fen, entity.myColor, isMyTurn, entity.history, entity.moveIndex, lastMove) }
                } else {
                    _gameState.update { ChessGameState.Inactive }
                }
            }
        }
    }

    suspend fun deleteGame(targetUserId: String) {
        chessDao.deleteGame(targetUserId)
    }

    private fun calculateMoveIndex(fen: String): Int {
        val parts = fen.split(" "); if (parts.size < 6) return 0
        val fullMove = parts[5].toIntOrNull() ?: 1
        return (fullMove - 1) * 2 + (if (parts[1] == "b") 1 else 0)
    }

    suspend fun initGameLocally(targetUserId: String, gameId: String, fen: String, myColor: String) {
        val idx = calculateMoveIndex(fen)
        saveGame(targetUserId, gameId, fen, myColor, "", -1, -1, -1, -1, idx)
    }

    suspend fun makeMove(targetUserId: String, fromR: Int, fromC: Int, toR: Int, toCol: Int, promotion: Char? = null): Boolean {
        val currentState = _gameState.value as? ChessGameState.Active ?: return false
        if (!currentState.isMyTurn) return false
        val newFen = ChessEngine.move(currentState.fen, fromR, fromC, toR, toCol, promotion)
        if (newFen == currentState.fen) return false
        val nextIdx = calculateMoveIndex(newFen)
        val packet = ChessMovePacket(newFen, fromR, fromC, toR, toCol, promotion, nextIdx)
        return try {
            signalRepository.sendSignal(targetUserId, SignalData(currentState.gameId, SignalType.CHESS_MOVE, gson.toJson(packet)))
            val moveText = "${ChessEngine.toChessCoords(fromR, fromC)}-${ChessEngine.toChessCoords(toR, toCol)}${promotion?.let { "($it)" } ?: ""}"
            saveGame(targetUserId, currentState.gameId, newFen, currentState.myColor, if (currentState.history.isEmpty()) moveText else "${currentState.history}, $moveText", fromR, fromC, toR, toCol, nextIdx)
            true
        } catch (e: Exception) { false }
    }

    fun handleIncomingMove(targetUserId: String, packet: ChessMovePacket) {
        scope.launch {
            val currentState = _gameState.value as? ChessGameState.Active ?: return@launch
            if (packet.moveIndex <= currentState.moveIndex) return@launch
            val moveText = "${ChessEngine.toChessCoords(packet.fromR, packet.fromC)}-${ChessEngine.toChessCoords(packet.toR, packet.toC)}${packet.promotion?.let { "($it)" } ?: ""}"
            saveGame(targetUserId, currentState.gameId, packet.fen, currentState.myColor, if (currentState.history.isEmpty()) moveText else "${currentState.history}, $moveText", packet.fromR, packet.fromC, packet.toR, packet.toC, packet.moveIndex)
        }
    }

    private suspend fun saveGame(cId: String, gId: String, fen: String, color: String, hist: String, fR: Int, fC: Int, tR: Int, tC: Int, idx: Int) {
        val myTurn = (fen.split(" ")[1] == "w" && color == "white") || (fen.split(" ")[1] == "b" && color == "black")
        chessDao.saveGame(ChessGameEntity(cId, gId, fen, color, System.currentTimeMillis(), myTurn, idx, hist, fR, fC, tR, tC))
    }

    sealed class ChessGameState {
        object Inactive : ChessGameState()
        data class Active(val gameId: String, val fen: String, val myColor: String, val isMyTurn: Boolean, val history: String, val moveIndex: Int, val lastMove: ChessMove?) : ChessGameState()
    }
}
