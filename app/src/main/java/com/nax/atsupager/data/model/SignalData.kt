package com.nax.atsupager.data.model

import com.google.gson.annotations.SerializedName

/**
 * Represents a single signal object within the 'signals' array from the server.
 */
data class SignalData(
    @SerializedName("from")
    val from: String, // Sender's user ID

    // This can be a WebRTC signal or empty if it's a text message
    @SerializedName("data")
    val data: String?,

    // This will contain the encrypted text message
    @SerializedName("message")
    val message: String?
)
