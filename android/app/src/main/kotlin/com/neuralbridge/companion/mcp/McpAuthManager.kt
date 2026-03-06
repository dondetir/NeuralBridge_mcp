package com.neuralbridge.companion.mcp

import android.content.Context
import java.util.UUID

class McpAuthManager(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "neuralbridge_prefs"
        private const val KEY_API_KEY = "nb_mcp_api_key"
        const val HEADER_NAME = "NeuralBridge-API-Key"
    }

    // Cached in memory after first load to avoid SharedPreferences I/O on every request
    @Volatile
    private var cachedKey: String? = null

    fun getOrCreateApiKey(): String {
        cachedKey?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_API_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_API_KEY, it).apply()
        }
        cachedKey = key
        return key
    }

    fun validate(key: String?): Boolean = key != null && key == getOrCreateApiKey()
}
