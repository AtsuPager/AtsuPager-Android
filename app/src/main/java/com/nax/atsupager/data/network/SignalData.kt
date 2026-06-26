/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

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
    @SerializedName("MSG_MULTI_DELETE") MSG_MULTI_DELETE,
    @SerializedName("MSG_BULK_DELETE") MSG_BULK_DELETE,
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
    @SerializedName("AUDIO_STATE") AUDIO_STATE,
    
    // Group related types
    @SerializedName("GROUP_INVITE") GROUP_INVITE,
    @SerializedName("GROUP_UPDATE") GROUP_UPDATE,
    @SerializedName("GROUP_LEAVE") GROUP_LEAVE,
    @SerializedName("GROUP_KICK") GROUP_KICK,
    @SerializedName("GROUP_RENAME") GROUP_RENAME,
    @SerializedName("GROUP_DELETE") GROUP_DELETE,

    // Access and subscription types
    @SerializedName("ACCESS_VERIFY") ACCESS_VERIFY,
    @SerializedName("ACCESS_RESULT") ACCESS_RESULT
}

data class SignalData(
    @SerializedName("callId") val callId: String,
    @SerializedName("type") val type: SignalType,
    @SerializedName("payload") val payload: String? = null,
    @SerializedName("isVideo") val isVideo: Boolean = false,
    @SerializedName("isIceRestart") val isIceRestart: Boolean = false,
    @SerializedName("senderName") val senderName: String? = null
)

data class GroupMemberPacket(
    @SerializedName("userId") val userId: String,
    @SerializedName("role") val role: String
)

data class GroupInvitePacket(
    @SerializedName("groupId") val groupId: String,
    @SerializedName("name") val name: String,
    @SerializedName("members") val members: List<GroupMemberPacket>,
    @SerializedName("ownerId") val ownerId: String
)

data class GroupUpdatePacket(
    @SerializedName("groupId") val groupId: String,
    @SerializedName("members") val members: List<GroupMemberPacket>,
    @SerializedName("ownerId") val ownerId: String? = null
)

data class GroupRenamePacket(
    @SerializedName("groupId") val groupId: String,
    @SerializedName("name") val name: String
)

data class GroupControlPacket(
    @SerializedName("groupId") val groupId: String,
    @SerializedName("userId") val userId: String
)

data class AccessCodePacket(
    @SerializedName("code") val code: String,
    @SerializedName("address") val address: String,
    @SerializedName("signature") val signature: String
)

data class AccessResultPacket(
    @SerializedName("success") val success: Boolean,
    @SerializedName("expiry") val expiry: Long,
    @SerializedName("error") val error: String? = null
)

data class DeleteMessagePacket(@SerializedName("remoteId") val remoteId: String)

data class MultiDeletePacket(@SerializedName("remoteIds") val remoteIds: List<String>)

data class BulkDeletePacket(
    @SerializedName("senderId") val senderId: String,
    @SerializedName("chatId") val chatId: String,
    @SerializedName("lastRemoteId") val lastRemoteId: String? = null,
    @SerializedName("beforeTimestamp") val beforeTimestamp: Long = 0,
    @SerializedName("isFullClear") val isFullClear: Boolean = false
)

data class ReceiptPacket(@SerializedName("remoteId") val remoteId: String)

// Game related packets
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

data class GameInvitePacket(
    @SerializedName("gameType") val gameType: String,
    @SerializedName("senderId") val senderId: String,
    @SerializedName("senderColor") val senderColor: String,
    @SerializedName("gameId") val gameId: String = UUID.randomUUID().toString(),
    @SerializedName("initialData") val initialData: String? = null
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
