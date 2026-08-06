package com.towerscope.ar.data

import android.content.Context
import android.net.Uri
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
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
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isIgnoringComments = true
        }
        val document = factory.newDocumentBuilder().parse(BufferedInputStream(input))
        document.documentElement.normalize()

        val towers = mutableListOf<Tower>()
        val placemarks = document.getElementsByTagNameNS("*", "Placemark").ifEmpty {
            document.getElementsByTagName("Placemark")
        }

        for (i in 0 until placemarks.length) {
            val placemark = placemarks.item(i) as? Element ?: continue
            val name = firstChildText(placemark, "name")?.takeIf { it.isNotBlank() }
                ?: "Unnamed tower"
            val coordinatesText = firstDescendantText(placemark, "coordinates") ?: continue
            parsePoint(coordinatesText)?.let { (lon, lat, alt) ->
                towers += Tower(
                    id = "${lat}_${lon}_${towers.size}",
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    altitudeMeters = alt
                )
            }
        }
        return towers
    }

    private fun NodeList.ifEmpty(fallback: () -> NodeList): NodeList =
        if (length == 0) fallback() else this

    private fun firstChildText(parent: Element, localName: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && localName(node) == localName) {
                return node.textContent?.trim()
            }
        }
        return null
    }

    private fun firstDescendantText(parent: Element, localName: String): String? {
        val matches = parent.getElementsByTagNameNS("*", localName).ifEmpty {
            parent.getElementsByTagName(localName)
        }
        if (matches.length == 0) return null
        return matches.item(0)?.textContent?.trim()
    }

    private fun localName(node: Node): String {
        return node.localName ?: node.nodeName.substringAfterLast(':')
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
