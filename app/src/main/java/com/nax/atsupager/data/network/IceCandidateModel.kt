package com.nax.atsupager.data.network

/**
 * Простая модель для надежной передачи ICE кандидатов через JSON
 */
data class IceCandidateModel(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val sdp: String?
)
