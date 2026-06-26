/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

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
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("username") val networkUsername: String? = null
)

data class UpdatesResponse(@SerializedName("signals") val signals: List<SignalDataWrapper>)

data class MessageWrapper(@SerializedName("message") val message: String)

/**
 * Result of a file upload operation.
 */
data class FileUploadResult(val url: String, val encryptedKey: String?)
