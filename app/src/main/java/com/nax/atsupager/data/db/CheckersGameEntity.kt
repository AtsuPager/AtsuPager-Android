package com.nax.atsupager.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "checkers_games")
data class CheckersGameEntity(
    @PrimaryKey
    @ColumnInfo(name = "contactId")
    @SerializedName("contactId")
    val contactId: String,

    @ColumnInfo(name = "gameId")
    @SerializedName("gameId")
    val gameId: String,

    @ColumnInfo(name = "state")
    @SerializedName("state")
    val state: String,

    @ColumnInfo(name = "myColor")
    @SerializedName("myColor")
    val myColor: String,

    @ColumnInfo(name = "lastUpdated")
    @SerializedName("lastUpdated")
    val lastUpdated: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "isMyTurn")
    @SerializedName("isMyTurn")
    val isMyTurn: Boolean,

    @ColumnInfo(name = "moveIndex")
    @SerializedName("moveIndex")
    val moveIndex: Int = 0,

    @ColumnInfo(name = "lastFromRow")
    @SerializedName("lastFromRow")
    val lastFromRow: Int = -1,

    @ColumnInfo(name = "lastFromCol")
    @SerializedName("lastFromCol")
    val lastFromCol: Int = -1,

    @ColumnInfo(name = "lastToRow")
    @SerializedName("lastToRow")
    val lastToRow: Int = -1,

    @ColumnInfo(name = "lastToCol")
    @SerializedName("lastToCol")
    val lastToCol: Int = -1
)
