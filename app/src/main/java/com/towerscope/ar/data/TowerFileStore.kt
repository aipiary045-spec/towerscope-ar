package com.towerscope.ar.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the last imported KML/KMZ (and parsed towers) across app launches.
 */
class TowerFileStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val towersDir = File(appContext.filesDir, "towers").also { it.mkdirs() }

    fun hasPersistedImport(): Boolean =
        prefs.contains(KEY_SOURCE_NAME) && File(towersDir, META_FILE).exists()

    fun persistedSourceName(): String? = prefs.getString(KEY_SOURCE_NAME, null)

    fun saveImport(sourceName: String, sourceUri: Uri?, towers: List<Tower>, rawBytes: ByteArray?) {
        if (rawBytes != null) {
            val ext = when {
                sourceName.lowercase().endsWith(".kmz") -> ".kmz"
                else -> ".kml"
            }
            File(towersDir, "import$ext").writeBytes(rawBytes)
            prefs.edit().putString(KEY_RAW_FILE, "import$ext").apply()
        }
        writeTowerMeta(sourceName, towers)
        prefs.edit()
            .putString(KEY_SOURCE_NAME, sourceName)
            .putString(KEY_SOURCE_URI, sourceUri?.toString())
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastUpdatedEpochMs(): Long = prefs.getLong(KEY_UPDATED_AT, 0L)

    fun loadPersistedTowers(): Pair<String, List<Tower>>? {
        val name = persistedSourceName() ?: return null
        val meta = File(towersDir, META_FILE)
        if (!meta.exists()) return null
        return try {
            name to readTowerMeta(meta)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        towersDir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
    }

    private fun writeTowerMeta(sourceName: String, towers: List<Tower>) {
        val array = JSONArray()
        towers.forEach { tower ->
            array.put(
                JSONObject()
                    .put("id", tower.id)
                    .put("name", tower.name)
                    .put("lat", tower.latitude)
                    .put("lng", tower.longitude)
                    .put("alt", tower.altitudeMeters)
                    .put("altitudeMode", tower.altitudeMode.name)
            )
        }
        File(towersDir, META_FILE).writeText(
            JSONObject()
                .put("sourceName", sourceName)
                .put("towers", array)
                .toString()
        )
    }

    private fun readTowerMeta(file: File): List<Tower> {
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("towers")
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val alt = if (obj.isNull("alt")) null else obj.getDouble("alt")
                val mode = if (obj.has("altitudeMode") && !obj.isNull("altitudeMode")) {
                    runCatching { AltitudeMode.valueOf(obj.getString("altitudeMode")) }.getOrNull()
                } else {
                    null
                } ?: KmlParser.resolveAltitudeMode(null, alt)
                add(
                    Tower(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lng"),
                        altitudeMeters = alt,
                        altitudeMode = mode
                    )
                )
            }
        }
    }

    companion object {
        private const val PREFS = "tower_file_store"
        private const val KEY_SOURCE_NAME = "source_name"
        private const val KEY_SOURCE_URI = "source_uri"
        private const val KEY_RAW_FILE = "raw_file"
        private const val KEY_UPDATED_AT = "updated_at_ms"
        private const val META_FILE = "towers.json"
    }
}
