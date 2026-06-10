package com.nax.atsupager.data.network

import com.google.gson.annotations.SerializedName
import java.util.UUID

enum class SignalType {
    @SerializedName("OFFER") OFFER,
    @SerializedName("ANSWER") ANSWER,
    @SerializedName("ICE_CANDIDATE") ICE_CANDIDATE,
    @SerializedName("BYE") BYE,
    @SerializedName("REKEY_SYNC") REKEY_SYNC,
    @SerializedName("MSG_DELETE") MSG_DELETE,
    @SerializedName("MSG_DELIVERED") MSG_DELIVERED,
    @SerializedName("MSG_READ") MSG_READ,
    @SerializedName("FILE_DOWNLOADED") FILE_DOWNLOADED,
    @SerializedName("GAME_INVITE") GAME_INVITE,
    @SerializedName("GAME_ACCEPT") GAME_ACCEPT,
    @SerializedName("GAME_REJECT") GAME_REJECT,
    @SerializedName("GAME_CLOSE") GAME_CLOSE,
    @SerializedName("CHESS_MOVE") CHESS_MOVE,
    @SerializedName("BACKGAMMON_MOVE") BACKGAMMON_MOVE,
    @SerializedName("CHECKERS_MOVE") CHECKERS_MOVE,
    @SerializedName("CHESS_INVITE") CHESS_INVITE,
    @SerializedName("CHESS_ACCEPT") CHESS_ACCEPT,
    @SerializedName("CHESS_REJECT") CHESS_REJECT,
    @SerializedName("CHESS_CLOSE") CHESS_CLOSE,
    @SerializedName("CHESS_SYNC") CHESS_SYNC,
    @SerializedName("BACKGAMMON_INVITE") BACKGAMMON_INVITE,
    @SerializedName("BACKGAMMON_ACCEPT") BACKGAMMON_ACCEPT,
    @SerializedName("BACKGAMMON_REJECT") BACKGAMMON_REJECT,
    @SerializedName("BACKGAMMON_CLOSE") BACKGAMMON_CLOSE,
    @SerializedName("BACKGAMMON_SYNC") BACKGAMMON_SYNC,
    @SerializedName("CHECKERS_INVITE") CHECKERS_INVITE,
    @SerializedName("CHECKERS_ACCEPT") CHECKERS_ACCEPT,
    @SerializedName("CHECKERS_REJECT") CHECKERS_REJECT,
    @SerializedName("CHECKERS_CLOSE") CHECKERS_CLOSE,
    @SerializedName("CHECKERS_SYNC") CHECKERS_SYNC,
    @SerializedName("VIDEO_STATE") VIDEO_STATE,
    @SerializedName("AUDIO_STATE") AUDIO_STATE
}

data class GameInvitePacket(
    @SerializedName("gameType") val gameType: String,
    @SerializedName("senderId") val senderId: String,
    @SerializedName("senderColor") val senderColor: String,
    @SerializedName("gameId") val gameId: String = UUID.randomUUID().toString(),
    @SerializedName("initialData") val initialData: String? = null
)

data class SignalData(
    @SerializedName("callId") val callId: String,
    @SerializedName("type") val type: SignalType,
    @SerializedName("payload") val payload: String? = null,
    @SerializedName("isVideo") val isVideo: Boolean = false,
    @SerializedName("isIceRestart") val isIceRestart: Boolean = false
)

data class DeleteMessagePacket(
    @SerializedName("remoteId") val remoteId: String
)

data class ReceiptPacket(
    @SerializedName("remoteId") val remoteId: String
)

data class ChessMovePacket(
    @SerializedName("fen") val fen: String,
    @SerializedName("fromR") val fromR: Int,
    @SerializedName("fromC") val fromC: Int,
    @SerializedName("toR") val toR: Int,
    @SerializedName("toC") val toC: Int,
    @SerializedName("promotion") val promotion: Char? = null,
    @SerializedName("moveIndex") val moveIndex: Int = 0,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class CheckersMovePacket(
    @SerializedName("state") val state: String,
    @SerializedName("fromR") val fromR: Int,
    @SerializedName("fromC") val fromC: Int,
    @SerializedName("toR") val toR: Int,
    @SerializedName("toC") val toC: Int,
    @SerializedName("moveIndex") val moveIndex: Int = 0,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class BackgammonMovePacket(
    @SerializedName("state") val state: String,
    @SerializedName("moveIndex") val moveIndex: Int = 0,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class ChessInvitePacket(
    @SerializedName("fen") val fen: String,
    @SerializedName("senderColor") val senderColor: String,
    @SerializedName("senderId") val senderId: String,
    @SerializedName("gameId") val gameId: String = UUID.randomUUID().toString()
)

data class CheckersInvitePacket(
    @SerializedName("state") val state: String,
    @SerializedName("senderColor") val senderColor: String,
    @SerializedName("senderId") val senderId: String,
    @SerializedName("gameId") val gameId: String = UUID.randomUUID().toString()
)
