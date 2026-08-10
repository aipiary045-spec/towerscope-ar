package com.towerscope.ar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Client for the TowerScope elevation API (LiDAR + DEM). Always requests live data.
 */
class LosElevationApiClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 60_000
) {

    data class ApiSample(
        val index: Int,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double,
        val groundElevationMeters: Double,
        val source: ElevationSource
    )

    data class ApiProfile(
        val samples: List<ApiSample>,
        val totalDistanceMeters: Double,
        val lidarCoverageFraction: Double,
        val fromCache: Boolean
    )

    suspend fun fetchProfile(
        observerLat: Double,
        observerLon: Double,
        towerLat: Double,
        towerLon: Double,
        sampleCount: Int,
        bypassCache: Boolean = true
    ): ApiProfile = withContext(Dispatchers.IO) {
        val root = baseUrl.trim().trimEnd('/')
        require(root.isNotEmpty()) { "Elevation API base URL is not configured" }
        val body = String.format(
            Locale.US,
            """{"observer":{"lat":%.8f,"lon":%.8f},"tower":{"lat":%.8f,"lon":%.8f},"sampleCount":%d,"bypassCache":%s}""",
            observerLat,
            observerLon,
            towerLat,
            towerLon,
            sampleCount,
            if (bypassCache) "true" else "false"
        )
        val connection = (URL("$root/v1/los-profile").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "TowerScopeAR/1.0 (Android)")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error(extractDetail(text) ?: "Elevation API HTTP $code")
            }
            parseProfile(text)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseProfile(json: String): ApiProfile {
        val total = readDouble(json, "totalDistanceMeters") ?: 0.0
        val fraction = readDouble(json, "lidarCoverageFraction") ?: 0.0
        val fromCache = Regex(""""fromCache"\s*:\s*true""").containsMatchIn(json)
        val samplesBlock = extractArrayObject(json, "samples") ?: "[]"
        val samples = mutableListOf<ApiSample>()
        val objectRegex = Regex("""\{[^{}]*\}""")
        for (obj in objectRegex.findAll(samplesBlock)) {
            val s = obj.value
            val index = readInt(s, "index") ?: continue
            val lat = readDouble(s, "latitude") ?: continue
            val lon = readDouble(s, "longitude") ?: continue
            val dist = readDouble(s, "distanceMeters") ?: 0.0
            val elev = readDouble(s, "groundElevationMeters") ?: continue
            val sourceRaw = readString(s, "source")?.lowercase(Locale.US)
            val source = when (sourceRaw) {
                "lidar" -> ElevationSource.LIDAR
                else -> ElevationSource.DEM
            }
            samples.add(
                ApiSample(
                    index = index,
                    latitude = lat,
                    longitude = lon,
                    distanceMeters = dist,
                    groundElevationMeters = elev,
                    source = source
                )
            )
        }
        samples.sortBy { it.index }
        require(samples.size >= 2) { "Elevation API returned too few samples" }
        return ApiProfile(
            samples = samples,
            totalDistanceMeters = total,
            lidarCoverageFraction = fraction,
            fromCache = fromCache
        )
    }

    private fun extractDetail(json: String): String? {
        val m = Regex(""""detail"\s*:\s*"([^"]+)"""").find(json) ?: return null
        return m.groupValues.getOrNull(1)
    }

    private fun extractArrayObject(json: String, key: String): String? {
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return null
        val start = json.indexOf('[', keyIdx)
        if (start < 0) return null
        var depth = 0
        for (i in start until json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun readDouble(json: String, key: String): Double? {
        val m = Regex(""""$key"\s*:\s*(?<num>[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)""")
            .find(json) ?: return null
        return m.groups["num"]?.value?.toDoubleOrNull()
    }

    private fun readInt(json: String, key: String): Int? =
        readDouble(json, key)?.toInt()

    private fun readString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"([^"]*)"""").find(json) ?: return null
        return m.groupValues.getOrNull(1)
    }
}
