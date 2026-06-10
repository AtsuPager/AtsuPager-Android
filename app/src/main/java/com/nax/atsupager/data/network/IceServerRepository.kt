package com.nax.atsupager.data.network

import android.util.Log
import org.webrtc.PeerConnection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IceServerRepository @Inject constructor(
    private val fileApiService: FileApiService
) {
    private var cachedIceServers: List<PeerConnection.IceServer>? = null
    private var lastFetchTime = 0L
    private val CACHE_EXPIRATION_MS = 3600_000L // 1 час

    suspend fun getIceServers(): List<PeerConnection.IceServer> {
        // Возвращаем кэш, если он еще свежий
        if (cachedIceServers != null && (System.currentTimeMillis() - lastFetchTime < CACHE_EXPIRATION_MS)) {
            return cachedIceServers!!
        }

        val iceServers = mutableListOf<PeerConnection.IceServer>()
        
        // Стандартные STUN серверы (fallback)
        val fallbacks = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302"
        )
        fallbacks.forEach { uri ->
            iceServers.add(PeerConnection.IceServer.builder(uri).createIceServer())
        }

        try {
            Log.d("IceServerRepository", "Fetching fresh ICE servers from VPS...")
            val response = fileApiService.getIceServers()
            
            if (response.isSuccessful && response.body() != null) {
                val turnResponse = response.body()!!
                val freshServers = mutableListOf<PeerConnection.IceServer>()
                
                // Добавляем fallbacks в начало списка
                iceServers.forEach { freshServers.add(it) }

                // Добавляем наши TURN серверы из ответа VPS
                turnResponse.uris?.forEach { uri ->
                    try {
                        val builder = PeerConnection.IceServer.builder(uri)
                        if (!turnResponse.username.isNullOrEmpty() && !turnResponse.password.isNullOrEmpty()) {
                            builder.setUsername(turnResponse.username)
                            builder.setPassword(turnResponse.password)
                        }
                        
                        if (uri.startsWith("turns:")) {
                            builder.setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE)
                        }
                        
                        freshServers.add(builder.createIceServer())
                        Log.d("IceServerRepository", "Added VPS ICE Server: $uri")
                    } catch (e: Exception) {
                        Log.e("IceServerRepository", "Error building ICE server for uri: $uri", e)
                    }
                }
                
                cachedIceServers = freshServers
                lastFetchTime = System.currentTimeMillis()
                return freshServers
            } else {
                Log.e("IceServerRepository", "VPS TURN request failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("IceServerRepository", "Exception getting TURN servers from VPS", e)
        }
        
        return cachedIceServers ?: iceServers
    }
}
