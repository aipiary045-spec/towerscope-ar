package com.towerscope.ar.viewmodel

import com.towerscope.ar.data.AltitudeMode
import com.towerscope.ar.data.LosProfileBuilder
import com.towerscope.ar.data.LosSample
import com.towerscope.ar.data.Tower
import org.junit.Assert.assertEquals
import org.junit.Test

class LosRangeRankingTest {

    private fun tower(id: String) = Tower(
        id = id,
        name = id,
        latitude = 35.0,
        longitude = -96.0,
        altitudeMeters = 40.0,
        altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
    )

    /** Constant LOS height vs flat terrain so min clearance == [clearance]. */
    private fun row(
        id: String,
        distance: Double,
        clearance: Double?,
        error: String? = null
    ): LosRangeRow {
        val profile = if (clearance == null || error != null) {
            null
        } else {
            val ground = 100.0
            val losHeight = ground + clearance
            LosProfileBuilder.build(
                towerId = id,
                towerName = id,
                samples = listOf(
                    LosSample(0, 0.0, 0.0, 0.0, ground, 0.0),
                    LosSample(1, distance, 0.0, 0.0, ground, 0.0)
                ),
                observerEyeElevationMeters = losHeight,
                towerTipElevationMeters = losHeight
            )
        }
        return LosRangeRow(
            tower = tower(id),
            distanceMeters = distance,
            profile = profile,
            error = error,
            loading = false
        )
    }

    @Test
    fun ranksClearAboveBlockedAboveErrors() {
        val rows = listOf(
            row("err", 100.0, null, error = "fail"),
            row("block", 200.0, -20.0),
            row("clearFar", 500.0, 30.0),
            row("clearNear", 150.0, 10.0)
        )
        val ranked = TowerScopeViewModel.rankLosRangeRows(
            rows,
            clutterHeightMeters = 0.0,
            frequencyGhz = 5.8
        )
        assertEquals(listOf("clearFar", "clearNear", "block", "err"), ranked.map { it.tower.id })
    }
}
