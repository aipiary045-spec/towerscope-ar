package com.towerscope.ar.data

import com.towerscope.ar.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * USGS 3DEP Bare-Earth DEM via ImageServer multipoint identify.
 * Used as on-device fallback when the elevation API is unreachable.
 */
class DemElevationService(
    private val identifyUrl: String =
        "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/identify",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
    private val chunkSize: Int = 40
) {

    suspend fun elevationsMeters(points: List<GeoUtils.LatLng>): List<Double?> =
        withContext(Dispatchers.IO) {
            if (points.isEmpty()) return@withContext emptyList()
            val out = ArrayList<Double?>(points.size)
            var i = 0
            while (i < points.size) {
                val end = minOf(i + chunkSize, points.size)
                out.addAll(fetchChunk(points.subList(i, end)))
                i = end
            }
            out
        }

    private fun fetchChunk(points: List<GeoUtils.LatLng>): List<Double?> {
        val pointsJson = points.joinToString(prefix = "[", postfix = "]") { p ->
            String.format(Locale.US, "[%.8f,%.8f]", p.longitude, p.latitude)
        }
        val geometry =
            """{"points":$pointsJson,"spatialReference":{"wkid":4326}}"""
        val mosaic =
            """{"ascending":true,"mosaicMethod":"esriMosaicAttribute","sortField":"Best"}"""
        val query = buildString {
            append("geometry=")
            append(urlEncode(geometry))
            append("&geometryType=esriGeometryMultipoint")
            append("&mosaicRule=")
            append(urlEncode(mosaic))
            append("&returnGeometry=false&returnCatalogItems=false&f=json")
        }
        val connection = (URL("$identifyUrl?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TowerScopeAR/1.0 (Android; 3DEP DEM)")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                return List(points.size) { null }
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseIdentifyValues(body, points.size)
        } catch (_: Exception) {
            List(points.size) { null }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseIdentifyValues(jsonBody: String, expected: Int): List<Double?> {
        val values = ArrayList<Double?>(expected)
        val regex = Regex(""""value"\s*:\s*"?(?<num>[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)"?""")
        // Prefer results array entries; skip top-level if present by scanning all matches
        // after the first "results".
        val resultsIdx = jsonBody.indexOf("\"results\"")
        val searchIn = if (resultsIdx >= 0) jsonBody.substring(resultsIdx) else jsonBody
        for (match in regex.findAll(searchIn)) {
            val raw = match.groups["num"]?.value ?: continue
            values.add(raw.toDoubleOrNull()?.takeIf { it.isFinite() })
            if (values.size >= expected) break
        }
        while (values.size < expected) values.add(null)
        return values.take(expected)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
