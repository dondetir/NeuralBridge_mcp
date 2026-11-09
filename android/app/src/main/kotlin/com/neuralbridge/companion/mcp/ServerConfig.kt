package com.neuralbridge.companion.mcp

import android.content.Context

/**
 * Persisted configuration for the MCP HTTP server bind address.
 *
 * Two independent knobs, both stored in the shared "neuralbridge_prefs" file:
 *  - [hostMode]: which interface the server binds to.
 *  - [port]: the TCP port.
 *
 * Changing either requires restarting the server (toggle off/on, or the service
 * re-reads this on next start) because Ktor binds at start().
 */
object ServerConfig {

    private const val PREFS_NAME = "neuralbridge_prefs"
    private const val KEY_HOST_MODE = "nb_host_mode"
    private const val KEY_PORT = "nb_port"

    const val DEFAULT_PORT = 7474
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    /** How the server should choose its bind address. */
    enum class HostMode {
        /** Bind to 0.0.0.0 — reachable on every interface (LAN + loopback). */
        ALL,
        /** Bind to the device's current Wi-Fi/LAN IPv4 address only. */
        LAN,
        /** Bind to 127.0.0.1 — loopback only (reachable via `adb forward`). */
        LOOPBACK;

        companion object {
            fun fromStorage(value: String?): HostMode = when (value) {
                "LAN" -> LAN
                "LOOPBACK" -> LOOPBACK
                else -> ALL
            }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getHostMode(context: Context): HostMode =
        HostMode.fromStorage(prefs(context).getString(KEY_HOST_MODE, HostMode.ALL.name))

    fun setHostMode(context: Context, mode: HostMode) {
        prefs(context).edit().putString(KEY_HOST_MODE, mode.name).apply()
    }

    fun getPort(context: Context): Int {
        val p = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)
        return if (p in MIN_PORT..MAX_PORT) p else DEFAULT_PORT
    }

    /** Persist a port, clamped to the valid range. Returns the value actually stored. */
    fun setPort(context: Context, port: Int): Int {
        val clamped = port.coerceIn(MIN_PORT, MAX_PORT)
        prefs(context).edit().putInt(KEY_PORT, clamped).apply()
        return clamped
    }

    /**
     * Resolve the concrete bind-host string Ktor should use for the current [hostMode].
     * For [HostMode.LAN], falls back to 0.0.0.0 if no Wi-Fi IPv4 is currently available
     * (binding to a non-existent address would crash the server).
     */
    fun resolveBindHost(context: Context): String = when (getHostMode(context)) {
        HostMode.ALL -> "0.0.0.0"
        HostMode.LOOPBACK -> "127.0.0.1"
        HostMode.LAN -> McpNetworkUtils.getWifiIpAddress(context) ?: "0.0.0.0"
    }
}
