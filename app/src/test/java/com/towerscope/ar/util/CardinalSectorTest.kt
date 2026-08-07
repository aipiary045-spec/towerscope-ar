package com.towerscope.ar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CardinalSectorTest {

    @Test
    fun facingSite_mapsCardinalQuarters() {
        assertEquals(CardinalSector.NORTH, CardinalSector.facingSite(0.0))
        assertEquals(CardinalSector.NORTH, CardinalSector.facingSite(44.0))
        assertEquals(CardinalSector.NORTH, CardinalSector.facingSite(320.0))
        assertEquals(CardinalSector.EAST, CardinalSector.facingSite(90.0))
        assertEquals(CardinalSector.SOUTH, CardinalSector.facingSite(180.0))
        assertEquals(CardinalSector.WEST, CardinalSector.facingSite(270.0))
        assertEquals(CardinalSector.WEST, CardinalSector.facingSite(314.0))
    }
}
