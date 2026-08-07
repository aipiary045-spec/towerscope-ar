package com.towerscope.ar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.round

/**
 * On-device LOS profile disk cache (7-day TTL).
 * Key rounds observer (~25 m) and tower (~1 m), matching the elevation API.
 */
class LosProfileDiskCache(
    context: Context,
    private val ttlMs: Long = 7L * 24L * 60L * 60L * 1000L
) {
    private val dir = File(context.cacheDir, "los_profiles").also { it.mkdirs() }

    fun cacheKey(
        towerId: String,
        observerLat: Double,
        observerLon: Double,
        towerLat: Double,
        towerLon: Double,
        sampleCount: Int
    ): String {
        val obsQ = 0.00025
        val twrQ = 0.00001
        val olat = round(observerLat / obsQ) * obsQ
        val olon = round(observerLon / obsQ) * obsQ
        val tlat = round(towerLat / twrQ) * twrQ
        val tlon = round(towerLon / twrQ) * twrQ
        return String.format(
            Locale.US,
            "%s_%.5f,%.5f_%.5f,%.5f_%d",
            towerId,
            olat,
            olon,
            tlat,
            tlon,
            sampleCount
        )
    }

    fun get(key: String): CachedLos? {
        val file = fileFor(key)
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > ttlMs) {
            file.delete()
            return null
        }
        return runCatching {
            val root = JSONObject(file.readText())
            parse(root)
        }.getOrNull()
    }

    fun put(key: String, cached: CachedLos) {
        runCatching {
            fileFor(key).writeText(toJson(cached).toString())
        }
    }

    private fun fileFor(key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.json")
    }

    private fun toJson(cached: CachedLos): JSONObject {
        val samples = JSONArray()
        for (s in cached.samples) {
            samples.put(
                JSONObject()
                    .put("index", s.index)
                    .put("distanceMeters", s.distanceMeters)
                    .put("latitude", s.latitude)
                    .put("longitude", s.longitude)
                    .put("groundElevationMeters", s.groundElevationMeters)
                    .put("curvatureDropMeters", s.curvatureDropMeters)
                    .put("source", s.source.name)
            )
        }
        return JSONObject()
            .put("towerId", cached.towerId)
            .put("towerName", cached.towerName)
            .put("observerEyeElevationMeters", cached.observerEyeElevationMeters)
            .put("towerTipElevationMeters", cached.towerTipElevationMeters)
            .put("totalDistanceMeters", cached.totalDistanceMeters)
            .put("lidarCoverageFraction", cached.lidarCoverageFraction)
            .put("samples", samples)
    }

    private fun parse(root: JSONObject): CachedLos {
        val arr = root.getJSONArray("samples")
        val samples = ArrayList<LosSample>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val source = runCatching {
                ElevationSource.valueOf(o.getString("source"))
            }.getOrDefault(ElevationSource.DEM)
            samples.add(
                LosSample(
                    index = o.getInt("index"),
                    distanceMeters = o.getDouble("distanceMeters"),
                    latitude = o.getDouble("latitude"),
                    longitude = o.getDouble("longitude"),
                    groundElevationMeters = o.getDouble("groundElevationMeters"),
                    curvatureDropMeters = o.getDouble("curvatureDropMeters"),
                    source = source
                )
            )
        }
        return CachedLos(
            towerId = root.getString("towerId"),
            towerName = root.getString("towerName"),
            samples = samples,
            observerEyeElevationMeters = root.getDouble("observerEyeElevationMeters"),
            towerTipElevationMeters = root.getDouble("towerTipElevationMeters"),
            totalDistanceMeters = root.getDouble("totalDistanceMeters"),
            lidarCoverageFraction = root.optDouble("lidarCoverageFraction", 0.0)
        )
    }

    data class CachedLos(
        val towerId: String,
        val towerName: String,
        val samples: List<LosSample>,
        val observerEyeElevationMeters: Double,
        val towerTipElevationMeters: Double,
        val totalDistanceMeters: Double,
        val lidarCoverageFraction: Double
    ) {
        fun toProfile(): LosProfile =
            LosProfileBuilder.build(
                towerId = towerId,
                towerName = towerName,
                samples = samples,
                observerEyeElevationMeters = observerEyeElevationMeters,
                towerTipElevationMeters = towerTipElevationMeters,
                lidarCoverageFraction = lidarCoverageFraction
            )
    }
}
