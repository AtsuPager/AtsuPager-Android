package com.nax.atsupager.data.model

import com.google.gson.annotations.SerializedName

data class UnreadCountResponse(
    @SerializedName("unread_count")
    val unreadCount: Int
)
