package com.nax.atsupager.data.network

import com.google.gson.annotations.SerializedName

data class SignalPayload(
    @SerializedName("data") val data: String,
    @SerializedName("type") val type: String? = null
)

data class MessagePayload(@SerializedName("message") val message: String)

data class SignalDataWrapper(
    @SerializedName("from") val from: String,
    @SerializedName("data") val data: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("pk") val pk: String? = null,
    @SerializedName("created_at") val createdAt: Long
)

data class UpdatesResponse(@SerializedName("signals") val signals: List<SignalDataWrapper>)

data class MessageWrapper(@SerializedName("message") val message: String)
