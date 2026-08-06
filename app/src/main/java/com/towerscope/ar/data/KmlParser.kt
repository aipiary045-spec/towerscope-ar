package com.towerscope.ar.data

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Parses KML and KMZ (zipped KML) files into [Tower] placemarks.
 * Supports nested Document/Folder structures and Point coordinates.
 */
object KmlParser {

    fun parseUri(context: Context, uri: Uri): List<Tower> {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val name = context.contentResolver.getType(uri).orEmpty()
            val path = uri.lastPathSegment.orEmpty().lowercase()
            val isKmz = path.endsWith(".kmz") || name.contains("kmz")
            return if (isKmz) parseKmz(input) else parseKml(input)
        } ?: error("Unable to open file")
    }

    fun parseAsset(context: Context, assetPath: String): List<Tower> {
        context.assets.open(assetPath).use { input ->
            return if (assetPath.lowercase().endsWith(".kmz")) {
                parseKmz(input)
            } else {
                parseKml(input)
            }
        }
    }

    fun parseKmz(input: InputStream): List<Tower> {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            var preferred: ByteArray? = null
            var fallback: ByteArray? = null
            while (entry != null) {
                val entryName = entry.name.lowercase()
                if (!entry.isDirectory && entryName.endsWith(".kml")) {
                    val bytes = zip.readBytes()
                    if (entryName.endsWith("doc.kml") || entryName == "doc.kml") {
                        preferred = bytes
                    } else if (fallback == null) {
                        fallback = bytes
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            val kmlBytes = preferred ?: fallback
                ?: error("KMZ archive does not contain a .kml file")
            return parseKml(ByteArrayInputStream(kmlBytes))
        }
    }

    fun parseKml(input: InputStream): List<Tower> {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newPullParser()
        parser.setInput(BufferedInputStream(input), null)

        val towers = mutableListOf<Tower>()
        var event = parser.eventType
        var inPlacemark = false
        var placemarkName: String? = null
        var coordinatesText: String? = null
        var currentText = StringBuilder()
        var depthName = 0

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = localName(parser)
                    currentText = StringBuilder()
                    when (tag) {
                        "Placemark" -> {
                            inPlacemark = true
                            placemarkName = null
                            coordinatesText = null
                        }
                        "name" -> if (inPlacemark) depthName++
                    }
                }
                XmlPullParser.TEXT -> {
                    currentText.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> {
                    val tag = localName(parser)
                    val text = currentText.toString().trim()
                    when {
                        inPlacemark && tag == "name" && placemarkName == null && text.isNotEmpty() -> {
                            placemarkName = text
                        }
                        inPlacemark && tag == "coordinates" && text.isNotEmpty() -> {
                            coordinatesText = text
                        }
                        tag == "Placemark" -> {
                            parsePoint(coordinatesText)?.let { (lon, lat, alt) ->
                                val name = placemarkName?.takeIf { it.isNotBlank() } ?: "Unnamed tower"
                                towers += Tower(
                                    id = "${lat}_${lon}_${towers.size}",
                                    name = name,
                                    latitude = lat,
                                    longitude = lon,
                                    altitudeMeters = alt
                                )
                            }
                            inPlacemark = false
                            placemarkName = null
                            coordinatesText = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return towers
    }

    private fun localName(parser: XmlPullParser): String {
        return parser.name?.substringAfterLast(':') ?: ""
    }

    /**
     * KML Point coordinates: longitude,latitude[,altitude]
     * May contain multiple tuples; the first is used.
     */
    private fun parsePoint(raw: String?): Triple<Double, Double, Double?>? {
        if (raw.isNullOrBlank()) return null
        val firstTuple = raw.split(Regex("\\s+"))
            .map { it.trim() }
            .firstOrNull { it.contains(',') }
            ?: return null
        val parts = firstTuple.split(',')
        if (parts.size < 2) return null
        val lon = parts[0].toDoubleOrNull() ?: return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        if (abs(lat) > 90.0 || abs(lon) > 180.0) return null
        val alt = parts.getOrNull(2)?.toDoubleOrNull()
        return Triple(lon, lat, alt)
    }
}
