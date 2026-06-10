package com.nax.atsupager.data.network

/**
 * Represents a signaling message received from another peer.
 */
data class IncomingSignal(
    val from: String, // The user ID of the sender
    val data: String, // The raw signal data (JSON string of SDP or ICE candidates)
    val timestamp: Long = 0 // Server-side creation timestamp in milliseconds
)
