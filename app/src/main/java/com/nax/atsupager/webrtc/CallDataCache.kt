package com.nax.atsupager.webrtc

import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallDataCache @Inject constructor() {
    private val cache = mutableMapOf<String, SessionDescription>()

    fun put(callId: String, sdp: SessionDescription) {
        cache[callId] = sdp
    }

    fun get(callId: String): SessionDescription? {
        return cache[callId]
    }

    fun remove(callId: String) {
        cache.remove(callId)
    }
}
