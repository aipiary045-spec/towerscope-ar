package com.towerscope.ar.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalWalkLoggerTest {

    @Test
    fun shouldPromptWeakSpot_whenCrossingBelowThreshold() {
        assertTrue(
            SignalWalkLogger.shouldPromptWeakSpot(
                previousRssi = -68,
                currentRssi = -72,
                armed = true
            )
        )
    }

    @Test
    fun shouldPromptWeakSpot_notWhenAlreadyWeak() {
        assertFalse(
            SignalWalkLogger.shouldPromptWeakSpot(
                previousRssi = -75,
                currentRssi = -78,
                armed = true
            )
        )
    }

    @Test
    fun shouldPromptWeakSpot_notWhenDisarmed() {
        assertFalse(
            SignalWalkLogger.shouldPromptWeakSpot(
                previousRssi = -68,
                currentRssi = -72,
                armed = false
            )
        )
    }

    @Test
    fun isWeakSpotRecovered_aboveRecoverThreshold() {
        assertTrue(SignalWalkLogger.isWeakSpotRecovered(-65))
        assertTrue(SignalWalkLogger.isWeakSpotRecovered(-60))
        assertFalse(SignalWalkLogger.isWeakSpotRecovered(-66))
    }

    @Test
    fun summarize_includesLoggedWeakSpots() {
        val text = SignalWalkLogger.summarize(
            SignalWalkSnapshot(
                samples = listOf(
                    SignalWalkSample(
                        timestampMs = 1L,
                        elapsedSec = 1.0,
                        rssiDbm = -72,
                        ssid = "Test",
                        channel = 6,
                        latitude = null,
                        longitude = null
                    )
                ),
                weakSpots = listOf(
                    SignalWalkWeakSpot(
                        timestampMs = 1L,
                        elapsedSec = 12.0,
                        rssiDbm = -72,
                        latitude = 40.1,
                        longitude = -74.2,
                        note = "Kitchen"
                    )
                ),
                running = false,
                durationSec = 12.0
            )
        )
        assertTrue(text.contains("Logged weak spots: 1"))
        assertTrue(text.contains("Kitchen"))
    }
}
