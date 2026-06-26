package com.nax.atsupager.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    @SerializedName("groupId")
    val groupId: String,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("ownerId")
    val ownerId: String,
    
    @SerializedName("createdAt")
    val createdAt: Long,
    
    @SerializedName("avatarUrl")
    val avatarUrl: String? = null,
    
    @SerializedName("groupKey")
    val groupKey: String? = null, // For future E2EE implementation
    
    @SerializedName("isMuted")
    val isMuted: Boolean = false
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "userId"])
data class GroupMemberEntity(
    @SerializedName("groupId")
    val groupId: String,
    
    @SerializedName("userId")
    val userId: String,
    
    @SerializedName("role")
    val role: String = "MEMBER" // "ADMIN" or "MEMBER"
)
