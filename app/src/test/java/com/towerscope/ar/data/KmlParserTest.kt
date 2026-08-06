package com.towerscope.ar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class KmlParserTest {

    @Test
    fun parsesPlacemarksAndNestedFolders() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Placemark>
                  <name>Alpha Tower</name>
                  <Point><coordinates>-97.74,30.27,100</coordinates></Point>
                </Placemark>
                <Folder>
                  <Placemark>
                    <name>Beta Site</name>
                    <Point><coordinates>-97.75,30.28</coordinates></Point>
                  </Placemark>
                </Folder>
              </Document>
            </kml>
        """.trimIndent()

        val towers = KmlParser.parseKml(ByteArrayInputStream(kml.toByteArray()))
        assertEquals(2, towers.size)
        assertEquals("Alpha Tower", towers[0].name)
        assertEquals(30.27, towers[0].latitude, 0.0001)
        assertEquals(-97.74, towers[0].longitude, 0.0001)
        assertEquals(100.0, towers[0].altitudeMeters!!, 0.001)
        assertEquals(AltitudeMode.RELATIVE_TO_GROUND, towers[0].altitudeMode)
        assertEquals("Beta Site", towers[1].name)
        assertTrue(towers[1].altitudeMeters == null)
        assertEquals(AltitudeMode.CLAMP_TO_GROUND, towers[1].altitudeMode)
    }

    @Test
    fun explicitAbsoluteModeIsPreserved() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Placemark>
                  <name>Abs</name>
                  <Point>
                    <altitudeMode>absolute</altitudeMode>
                    <coordinates>-97.74,30.27,150</coordinates>
                  </Point>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val towers = KmlParser.parseKml(ByteArrayInputStream(kml.toByteArray()))
        assertEquals(1, towers.size)
        assertEquals(AltitudeMode.ABSOLUTE, towers[0].altitudeMode)
        assertEquals(150.0, towers[0].altitudeMeters!!, 0.001)
    }
}
