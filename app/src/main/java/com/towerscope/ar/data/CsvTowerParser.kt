package com.towerscope.ar.data

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Parses vendor / GIS CSV site lists into [Tower] rows.
 *
 * Recognized header aliases (case-insensitive):
 * - name / site / site_name / ap / ssid / device
 * - lat / latitude / y
 * - lon / lng / long / longitude / x
 * - height / height_agl / alt / altitude / elevation / agl (optional, meters AGL)
 */
object CsvTowerParser {

    fun parseUri(context: Context, uri: Uri): List<Tower> {
        context.contentResolver.openInputStream(uri)?.use { input ->
            return parse(BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)))
        } ?: error("Unable to open CSV")
    }

    fun parse(reader: BufferedReader): List<Tower> {
        val lines = reader.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val delimiter = detectDelimiter(lines.first())
        val headerCells = splitCsvLine(lines.first(), delimiter).map { normalizeHeader(it) }
        val nameIdx = findColumn(headerCells, NAME_ALIASES)
            ?: error("CSV needs a name column (name, site, ap, …)")
        val latIdx = findColumn(headerCells, LAT_ALIASES)
            ?: error("CSV needs a latitude column (lat, latitude, …)")
        val lonIdx = findColumn(headerCells, LON_ALIASES)
            ?: error("CSV needs a longitude column (lon, lng, longitude, …)")
        val heightIdx = findColumn(headerCells, HEIGHT_ALIASES)

        val towers = mutableListOf<Tower>()
        for (i in 1 until lines.size) {
            val cells = splitCsvLine(lines[i], delimiter)
            if (cells.size <= maxOf(nameIdx, latIdx, lonIdx)) continue
            val lat = cells.getOrNull(latIdx)?.toDoubleOrNull() ?: continue
            val lon = cells.getOrNull(lonIdx)?.toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val name = cells.getOrNull(nameIdx)?.trim().orEmpty().ifBlank { "Site ${towers.size + 1}" }
            val height = heightIdx?.let { cells.getOrNull(it)?.toDoubleOrNull() }
            towers += Tower(
                id = "${lat}_${lon}_$i",
                name = name,
                latitude = lat,
                longitude = lon,
                altitudeMeters = height,
                altitudeMode = if (height != null) {
                    AltitudeMode.RELATIVE_TO_GROUND
                } else {
                    AltitudeMode.CLAMP_TO_GROUND
                }
            )
        }
        return towers
    }

    fun templateCsv(): String = buildString {
        appendLine("name,latitude,longitude,height_agl")
        appendLine("Example AP North,36.154000,-95.992800,30")
        appendLine("Example AP East,36.160000,-95.980000,25")
    }

    private fun detectDelimiter(header: String): Char {
        val commas = header.count { it == ',' }
        val semis = header.count { it == ';' }
        val tabs = header.count { it == '\t' }
        return when {
            tabs >= commas && tabs >= semis && tabs > 0 -> '\t'
            semis > commas -> ';'
            else -> ','
        }
    }

    private fun normalizeHeader(raw: String): String =
        raw.trim().lowercase(Locale.US).replace(' ', '_').replace('-', '_')

    private fun findColumn(headers: List<String>, aliases: Set<String>): Int? {
        headers.forEachIndexed { index, h ->
            if (h in aliases) return index
        }
        return null
    }

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == delimiter && !inQuotes -> {
                    out += sb.toString().trim()
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    private val NAME_ALIASES = setOf(
        "name", "site", "site_name", "sitename", "ap", "ap_name", "ssid",
        "device", "device_name", "tower", "tower_name", "label", "title"
    )
    private val LAT_ALIASES = setOf("lat", "latitude", "y", "gps_lat", "site_lat")
    private val LON_ALIASES = setOf(
        "lon", "lng", "long", "longitude", "x", "gps_lon", "gps_lng", "site_lon"
    )
    private val HEIGHT_ALIASES = setOf(
        "height", "height_agl", "agl", "alt", "altitude", "elevation",
        "antenna_height", "tower_height", "mast_height"
    )
}
