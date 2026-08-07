package com.towerscope.ar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsgsElevationServiceTest {

    private val service = UsgsElevationService()

    @Test
    fun parsesModernEpqsJson() {
        val json = """
            {"location":{"x":-97.74,"y":30.27,"spatialReference":{"wkid":4326}},
             "value":165.4,"rasterId":1,"resolution":1}
        """.trimIndent()
        assertEquals(165.4, service.parseElevationMeters(json)!!, 0.001)
    }

    @Test
    fun parsesQuotedStringValueFromUsgs() {
        // Live EPQS returns elevation as a JSON string.
        val json =
            """{"location":{"x":-96.870365,"y":35.89701},"locationId":0,"value":"316.258789062","rasterId":5677}"""
        assertEquals(316.258789062, service.parseElevationMeters(json)!!, 0.0001)
    }

    @Test
    fun nullValueReturnsNull() {
        val json = """{"value":null}"""
        assertNull(service.parseElevationMeters(json))
    }
}

class LosProfileServiceTest {

    @Test
    fun fillMissingInterpolatesForwardAndBack() {
        val service = LosProfileService()
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
}
