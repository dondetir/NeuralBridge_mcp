package com.neuralbridge.companion.mcp

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object McpNetworkUtils {
    fun getWifiIpAddress(context: Context): String? {
        return try {
            // Try wlan0 directly first (most reliable on Android)
            NetworkInterface.getByName("wlan0")
                ?.inetAddresses?.toList()
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
                ?: run {
                    // Fallback: any up, non-loopback IPv4 interface
                    NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.filter { it.isUp && !it.isLoopback }
                        ?.flatMap { it.inetAddresses.toList() }
                        ?.filterIsInstance<Inet4Address>()
                        ?.firstOrNull { !it.isLoopbackAddress }
                        ?.hostAddress
                }
        } catch (e: Exception) {
            null
        }
    }
}
