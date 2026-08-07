package com.towerscope.ar.data

import com.towerscope.ar.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * USGS Elevation Point Query Service (3DEP) — orthometric height in meters.
 * https://epqs.nationalmap.gov/v1/json?x={lon}&y={lat}&units=Meters&wkid=4326
 */
class UsgsElevationService(
    private val baseUrl: String = "https://epqs.nationalmap.gov/v1/json",
    private val maxConcurrent: Int = 4,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 12_000
) {

    suspend fun elevationMeters(latitude: Double, longitude: Double): Double? =
        withContext(Dispatchers.IO) {
            fetchOne(latitude, longitude)
        }

    /**
     * Fetch elevations for many points with bounded concurrency.
     * Failed points are null (caller may interpolate or drop).
     */
    suspend fun elevationsMeters(points: List<GeoUtils.LatLng>): List<Double?> =
        coroutineScope {
            val gate = Semaphore(maxConcurrent)
            points.map { point ->
                async(Dispatchers.IO) {
                    gate.withPermit { fetchOne(point.latitude, point.longitude) }
                }
            }.awaitAll()
        }

    private fun fetchOne(latitude: Double, longitude: Double): Double? {
        val url = URL(
            String.format(
                Locale.US,
                "%s?x=%.8f&y=%.8f&units=Meters&wkid=4326",
                baseUrl,
                longitude,
                latitude
            )
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TowerScopeAR/1.0 (Android; USGS EPQS)")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseElevationMeters(body)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Extracts EPQS `value` without Android org.json (unit-test friendly).
     * USGS currently returns the elevation as a JSON string, e.g. `"value":"316.25"`.
     */
    internal fun parseElevationMeters(jsonBody: String): Double? {
        if (Regex(""""value"\s*:\s*null""").containsMatchIn(jsonBody)) return null
        val match = VALUE_REGEX.find(jsonBody) ?: return null
        val raw = match.groups["num"]?.value ?: match.groupValues.getOrNull(1) ?: return null
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    companion object {
        // Accept both "value":316.25 and "value":"316.25" (USGS uses the quoted form).
        private val VALUE_REGEX =
            Regex(""""value"\s*:\s*"?(?<num>[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)"?""")
    }
}
