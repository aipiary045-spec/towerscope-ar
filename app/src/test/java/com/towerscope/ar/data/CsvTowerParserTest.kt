package com.towerscope.ar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class CsvTowerParserTest {
    @Test
    fun parsesGenericGisHeaders() {
        val csv = """
            name,latitude,longitude,height_agl
            Tower A,36.15,-95.99,30
            Tower B,36.16,-95.98,25
        """.trimIndent()
        val towers = CsvTowerParser.parse(BufferedReader(StringReader(csv)))
        assertEquals(2, towers.size)
        assertEquals("Tower A", towers[0].name)
        assertEquals(30.0, towers[0].altitudeMeters)
        assertEquals(AltitudeMode.RELATIVE_TO_GROUND, towers[0].altitudeMode)
    }

    @Test
    fun parsesUbiquitiStyleAliases() {
        val csv = """
            site_name,lat,lng,agl
            AP1,35.0,-97.0,12
        """.trimIndent()
        val towers = CsvTowerParser.parse(BufferedReader(StringReader(csv)))
        assertEquals(1, towers.size)
        assertEquals("AP1", towers[0].name)
        assertTrue(towers[0].latitude == 35.0)
    }
}
