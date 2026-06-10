package com.nax.atsupager.data.network

import com.google.gson.annotations.SerializedName

data class IceServerResponse(
    @SerializedName("username") val username: String?,
    @SerializedName("password") val password: String?,
    @SerializedName("uris") val uris: List<String>?,
    @SerializedName("ttl") val ttl: Int?
)
