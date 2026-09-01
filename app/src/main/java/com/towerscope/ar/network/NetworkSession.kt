package com.towerscope.ar.network

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Lightweight cross-tool network session state for home dashboard summaries.
 */
object NetworkSession {
    private const val PREFS = "network_session"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordSpeedTest(
        context: Context,
        downloadMbps: Double,
        uploadMbps: Double,
        latencyMs: Double
    ) {
        prefs(context).edit()
            .putLong(KEY_SPEED_AT, System.currentTimeMillis())
            .putFloat(KEY_SPEED_DOWN, downloadMbps.toFloat())
            .putFloat(KEY_SPEED_UP, uploadMbps.toFloat())
            .putFloat(KEY_SPEED_LATENCY, latencyMs.toFloat())
            .apply()
    }

    fun recordPing(
        context: Context,
        hosts: String,
        summary: String
    ) {
        prefs(context).edit()
            .putLong(KEY_PING_AT, System.currentTimeMillis())
            .putString(KEY_PING_HOSTS, hosts.take(200))
            .putString(KEY_PING_SUMMARY, summary.take(500))
            .apply()
    }

    fun speedSummary(context: Context): String? {
        val p = prefs(context)
        if (!p.contains(KEY_SPEED_AT)) return null
        val down = p.getFloat(KEY_SPEED_DOWN, 0f)
        val up = p.getFloat(KEY_SPEED_UP, 0f)
        val lat = p.getFloat(KEY_SPEED_LATENCY, 0f)
        return String.format(Locale.US, "%.0f↓ / %.0f↑ Mbps · %.0f ms", down, up, lat)
    }

    fun pingSummary(context: Context): String? {
        val p = prefs(context)
        val hosts = p.getString(KEY_PING_HOSTS, null) ?: return null
        val summary = p.getString(KEY_PING_SUMMARY, null)
        return if (summary.isNullOrBlank()) hosts else "$hosts · $summary"
    }

    data class SpeedSnapshot(
        val downloadMbps: Float,
        val uploadMbps: Float,
        val latencyMs: Float
    )

    fun lastSpeedSnapshot(context: Context): SpeedSnapshot? {
        val p = prefs(context)
        if (!p.contains(KEY_SPEED_AT)) return null
        return SpeedSnapshot(
            downloadMbps = p.getFloat(KEY_SPEED_DOWN, 0f),
            uploadMbps = p.getFloat(KEY_SPEED_UP, 0f),
            latencyMs = p.getFloat(KEY_SPEED_LATENCY, 0f)
        )
    }

    private const val KEY_SPEED_AT = "speed_at"
    private const val KEY_SPEED_DOWN = "speed_down"
    private const val KEY_SPEED_UP = "speed_up"
    private const val KEY_SPEED_LATENCY = "speed_latency"
    private const val KEY_PING_AT = "ping_at"
    private const val KEY_PING_HOSTS = "ping_hosts"
    private const val KEY_PING_SUMMARY = "ping_summary"
}
