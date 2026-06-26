package com.nax.atsupager.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "users")
data class User(
    @PrimaryKey
    @SerializedName("id") val id: String,
    @SerializedName("username") val username: String,
    @SerializedName("public_key") val publicKey: String?,
    @SerializedName("isMuted") val isMuted: Boolean = false
)
