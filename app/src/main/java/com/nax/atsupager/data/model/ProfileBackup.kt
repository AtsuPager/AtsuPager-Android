package com.nax.atsupager.data.model

import com.google.gson.annotations.SerializedName
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.GroupEntity
import com.nax.atsupager.data.db.GroupMemberEntity

data class ProfileBackup(
    @SerializedName("version")
    val version: Int = 1,
    
    @SerializedName("contacts")
    val users: List<User>,
    
    @SerializedName("contactIds")
    val contactIds: List<String>,
    
    @SerializedName("groups")
    val groups: List<GroupEntity>,
    
    @SerializedName("groupMembers")
    val groupMembers: List<GroupMemberEntity>,
    
    @SerializedName("messages")
    val messages: List<ChatMessage>? = null,
    
    @SerializedName("settings")
    val settings: Map<String, Any?>,
    
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
