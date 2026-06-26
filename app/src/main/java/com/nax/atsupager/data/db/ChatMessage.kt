package com.nax.atsupager.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import java.util.UUID

enum class MessageType {
    @SerializedName("TEXT") TEXT,
    @SerializedName("SYSTEM") SYSTEM,
    @SerializedName("GAME_INVITE") GAME_INVITE,
    @SerializedName("OUTGOING_CALL") OUTGOING_CALL,
    @SerializedName("INCOMING_CALL") INCOMING_CALL,
    @SerializedName("MISSED_CALL") MISSED_CALL,
    @SerializedName("IMAGE") IMAGE,
    @SerializedName("VIDEO") VIDEO,
    @SerializedName("FILE") FILE,
    @SerializedName("AUDIO") AUDIO
}

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    @SerializedName("id")
    val id: Long = 0,

    @ColumnInfo(name = "remoteId")
    @SerializedName("remoteId")
    val remoteId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "fromUserId")
    @SerializedName("fromUserId")
    val fromUserId: String,

    @ColumnInfo(name = "toUserId")
    @SerializedName("toUserId")
    val toUserId: String,

    @ColumnInfo(name = "groupId")
    @SerializedName("groupId")
    val groupId: String? = null,

    @ColumnInfo(name = "text")
    @SerializedName("text")
    val text: String,

    @ColumnInfo(name = "timestamp")
    @SerializedName("timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "isRead")
    @SerializedName("isRead")
    val isRead: Boolean = false,

    @ColumnInfo(name = "type")
    @SerializedName("type")
    val type: MessageType = MessageType.TEXT,

    @ColumnInfo(name = "fileName")
    @SerializedName("fileName")
    val fileName: String? = null,

    @ColumnInfo(name = "fileUrl")
    @SerializedName("fileUrl")
    val fileUrl: String? = null,

    @ColumnInfo(name = "fileSize")
    @SerializedName("fileSize")
    val fileSize: Long? = null,

    @ColumnInfo(name = "audioDuration")
    @SerializedName("audioDuration")
    val audioDuration: Int? = null,

    @ColumnInfo(name = "localFilePath")
    @SerializedName("localFilePath")
    val localFilePath: String? = null,

    @ColumnInfo(name = "mimeType")
    @SerializedName("mimeType")
    val mimeType: String? = null,

    @ColumnInfo(name = "width")
    @SerializedName("width")
    val width: Int? = null,

    @ColumnInfo(name = "height")
    @SerializedName("height")
    val height: Int? = null,

    @ColumnInfo(name = "fileEncryptionKey")
    @SerializedName("fileEncryptionKey")
    val fileEncryptionKey: String? = null,

    @ColumnInfo(name = "fileHash")
    @SerializedName("fileHash")
    val fileHash: String? = null,

    @ColumnInfo(name = "isDelivered")
    @SerializedName("isDelivered")
    val isDelivered: Boolean = false,

    @ColumnInfo(name = "remoteRead")
    @SerializedName("remoteRead")
    val remoteRead: Boolean = false,

    @ColumnInfo(name = "replyToId")
    @SerializedName("replyToId")
    val replyToId: String? = null,

    @ColumnInfo(name = "replyToName")
    @SerializedName("replyToName")
    val replyToName: String? = null,

    @ColumnInfo(name = "replyToText")
    @SerializedName("replyToText")
    val replyToText: String? = null,

    @ColumnInfo(name = "replyToType")
    @SerializedName("replyToType")
    val replyToType: MessageType? = null
)
