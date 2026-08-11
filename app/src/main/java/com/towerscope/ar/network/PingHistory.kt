package com.towerscope.ar.network

import android.content.Context

/**
 * Remembers the last ping input and a short MRU list of individual hosts.
 */
object PingHistory {

    private const val PREFS = "towerscope_prefs"
    private const val KEY_LAST_INPUT = "ping_last_input"
    private const val KEY_RECENT = "ping_recent_hosts"
    const val MAX_RECENT = 8
    private const val SEP = "\n"

    fun lastInput(context: Context): String? =
        prefs(context).getString(KEY_LAST_INPUT, null)?.takeIf { it.isNotBlank() }

    fun recentHosts(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_RECENT, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEP).map { it.trim() }.filter { it.isNotBlank() }.take(MAX_RECENT)
    }

    /**
     * Persist the raw input box and bump each probed host to the front of recent.
     */
    fun remember(context: Context, rawInput: String, hosts: List<String>) {
        val cleaned = hosts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val merged = LinkedHashSet<String>()
        cleaned.forEach { merged += it }
        recentHosts(context).forEach { merged += it }
        val recent = merged.take(MAX_RECENT)
        prefs(context).edit()
            .putString(KEY_LAST_INPUT, rawInput.trim())
            .putString(KEY_RECENT, recent.joinToString(SEP))
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
