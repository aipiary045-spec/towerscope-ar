package com.towerscope.ar.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HudThemeTest {

    @Test
    fun fromStored_mapsLegacyNames() {
        assertEquals(HudTheme.LIGHT, HudTheme.fromStored("DAY"))
        assertEquals(HudTheme.LIGHT, HudTheme.fromStored("LIGHT"))
        assertEquals(HudTheme.DARK, HudTheme.fromStored("NIGHT"))
        assertEquals(HudTheme.DARK, HudTheme.fromStored("DARK"))
        assertEquals(HudTheme.DARK, HudTheme.fromStored("HIGH_CONTRAST"))
    }

    @Test
    fun fromStored_defaultsUnknownToDay() {
        assertEquals(HudTheme.LIGHT, HudTheme.fromStored(null))
        assertEquals(HudTheme.LIGHT, HudTheme.fromStored("nope"))
    }

    @Test
    fun outdoorHint_describesSunAndNight() {
        assertEquals("Day · full sun", HudTheme.LIGHT.outdoorHint)
        assertEquals("Night · low light", HudTheme.DARK.outdoorHint)
    }
}
