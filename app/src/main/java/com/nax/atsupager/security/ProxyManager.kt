package com.nax.atsupager.security

import android.content.SharedPreferences
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxyManager @Inject constructor(
    private val prefs: SharedPreferences
) {
    companion object {
        const val PREF_PROXY_ENABLED = "proxy_enabled"
        const val PREF_PROXY_HOST = "proxy_host"
        const val PREF_PROXY_PORT = "proxy_port"

        const val DEFAULT_TOR_HOST = "127.0.0.1"
        const val DEFAULT_TOR_PORT = 9050 // Default for Orbot SOCKS
    }

    /**
     * Returns a configured Proxy object for OkHttp or Socket.io.
     */
    fun getProxy(): Proxy {
        if (!prefs.getBoolean(PREF_PROXY_ENABLED, false)) return Proxy.NO_PROXY

        val host = prefs.getString(PREF_PROXY_HOST, DEFAULT_TOR_HOST) ?: DEFAULT_TOR_HOST
        val port = prefs.getInt(PREF_PROXY_PORT, DEFAULT_TOR_PORT)

        return try {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        } catch (e: Exception) {
            Proxy.NO_PROXY
        }
    }
}
