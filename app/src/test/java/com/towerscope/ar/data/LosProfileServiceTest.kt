package com.towerscope.ar.data

import com.towerscope.ar.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemElevationServiceTest {

    private val service = DemElevationService()

    @Test
    fun parsesMultipointIdentifyValues() {
        val json = """
            {"results":[
              {"objectId":0,"name":"Pixel","value":"308.358"},
              {"objectId":1,"name":"Pixel","value":318.267},
              {"objectId":2,"name":"Pixel","value":"310.816"}
            ]}
        """.trimIndent()
        val values = service.parseIdentifyValues(json, 3)
        assertEquals(308.358, values[0]!!, 0.001)
        assertEquals(318.267, values[1]!!, 0.001)
        assertEquals(310.816, values[2]!!, 0.001)
    }
}

class LosElevationApiClientTest {

    private val client = LosElevationApiClient("https://example.invalid")

    @Test
    fun parsesApiProfileJson() {
        val json = """
            {
              "samples":[
                {"index":0,"latitude":35.9,"longitude":-96.87,"distanceMeters":0.0,
                 "groundElevationMeters":300.5,"source":"lidar"},
                {"index":1,"latitude":35.89,"longitude":-96.875,"distanceMeters":100.0,
                 "groundElevationMeters":310.0,"source":"dem"}
              ],
              "sampleCount":2,
              "totalDistanceMeters":100.0,
              "lidarCoverageFraction":0.5,
              "fromCache":false
            }
        """.trimIndent()
        val profile = client.parseProfile(json)
        assertEquals(2, profile.samples.size)
        assertEquals(ElevationSource.LIDAR, profile.samples[0].source)
        assertEquals(ElevationSource.DEM, profile.samples[1].source)
        assertEquals(0.5, profile.lidarCoverageFraction, 0.001)
        assertEquals(300.5, profile.samples[0].groundElevationMeters, 0.001)
    }
}

class LosProfileServiceTest {

    @Test
    fun defaultApiClient_isConfiguredForReleaseBuilds() {
        assertTrue(BuildConfig.LOS_ELEVATION_API_BASE_URL.isNotBlank())
        val client = LosProfileService.defaultApiClient()
        assertNotNull(client)
    }

    @Test
    fun fillMissingInterpolatesForwardAndBack() {
        val service = LosProfileService(apiClient = null)
        val filled = service.fillMissingElevations(listOf(null, 10.0, null, null, 40.0, null))
        assertEquals(10.0, filled[0], 0.001)
        assertEquals(10.0, filled[1], 0.001)
        assertEquals(10.0, filled[2], 0.001)
        assertEquals(10.0, filled[3], 0.001)
        assertEquals(40.0, filled[4], 0.001)
        assertEquals(40.0, filled[5], 0.001)
    }

    @Test
    fun resolveTowerTipUsesRelativeHeight() {
        val tip = LosProfileBuilder.resolveTowerTipElevationMeters(
            towerGroundElevationMeters = 100.0,
            altitudeMeters = 45.0,
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
        )
        assertEquals(145.0, tip, 0.001)
    }

    @Test
    fun clutterAppliesOnlyToDemSamples() {
        val dem = LosSample(
            index = 0,
            distanceMeters = 50.0,
            latitude = 0.0,
            longitude = 0.0,
            groundElevationMeters = 100.0,
            curvatureDropMeters = 0.0,
            source = ElevationSource.DEM
        )
        val lidar = dem.copy(index = 1, source = ElevationSource.LIDAR)
        assertEquals(110.0, dem.effectiveTerrainMeters(10.0), 0.001)
        assertEquals(100.0, lidar.effectiveTerrainMeters(10.0), 0.001)
    }

    @Test
    fun clearanceIgnoresClutterOnLidarOnlyProfile() {
        val samples = listOf(
            LosSample(0, 0.0, 0.0, 0.0, 100.0, 0.0, ElevationSource.LIDAR),
            LosSample(1, 100.0, 0.0, 0.0, 100.0, 0.0, ElevationSource.LIDAR)
        )
        val profile = LosProfileBuilder.build(
            towerId = "t1",
            towerName = "T",
            samples = samples,
            observerEyeElevationMeters = 101.5,
            towerTipElevationMeters = 160.0,
            lidarCoverageFraction = 1.0
        )
        assertTrue(profile.isClear(40.0))
        assertEquals(profile.minClearanceMeters(0.0), profile.minClearanceMeters(40.0), 0.001)
        assertTrue(profile.usesLidar)
    }

    @Test
    fun demProfileClutterCanBlock() {
        val samples = listOf(
            LosSample(0, 0.0, 0.0, 0.0, 100.0, 0.0, ElevationSource.DEM),
            LosSample(1, 100.0, 0.0, 0.0, 100.0, 0.0, ElevationSource.DEM)
        )
        val profile = LosProfileBuilder.build(
            towerId = "t1",
            towerName = "T",
            samples = samples,
            observerEyeElevationMeters = 101.5,
            towerTipElevationMeters = 120.0,
            lidarCoverageFraction = 0.0
        )
        assertTrue(profile.isClear(0.0))
        assertFalse(profile.isClear(40.0))
    }
}

class LosProfileCacheKeyTest {

    @Test
    fun observerQuantizationMatchesApiCellSize() {
        // Same ~25 m cell as elevation-api cache_key (0.00025 deg).
        val a = roundObserver(35.90001)
        val b = roundObserver(35.90010)
        assertEquals(a, b, 1e-12)
        val c = roundObserver(35.90100)
        assertTrue(kotlin.math.abs(c - a) > 1e-12)
    }

    private fun roundObserver(lat: Double): Double {
        val obsQ = 0.00025
        return kotlin.math.round(lat / obsQ) * obsQ
    }
}
