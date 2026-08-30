package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestClientTest {

    @Test
    fun joinUrl_mergesBaseAndPath() {
        assertEquals(
            "https://ams.speedtest.clouvider.net/backend/empty.php",
            SpeedTestClient.joinUrl(
                "https://ams.speedtest.clouvider.net/backend",
                "empty.php"
            )
        )
        assertEquals(
            "https://amsspeed.sharktech.net/backend/empty.php",
            SpeedTestClient.joinUrl(
                "https://amsspeed.sharktech.net",
                "backend/empty.php"
            )
        )
    }

    @Test
    fun appendQuery_addsOrJoinsQueryString() {
        assertEquals(
            "https://example.com/ping?cors=true",
            SpeedTestClient.appendQuery("https://example.com/ping", "cors=true")
        )
        assertEquals(
            "https://example.com/ping?a=1&cors=true",
            SpeedTestClient.appendQuery("https://example.com/ping?a=1", "cors=true")
        )
    }

    @Test
    fun hetznerDownloadUrl_mapsToCurrentTestFiles() {
        assertTrue(SpeedTestClient.hetznerDownloadUrl(1_000_000L).contains("100MB.bin"))
        assertTrue(SpeedTestClient.hetznerDownloadUrl(500_000_000L).contains("1GB.bin"))
        assertTrue(SpeedTestClient.hetznerDownloadUrl(2_000_000_000L).contains("10GB.bin"))
        assertTrue(
            SpeedTestClient.hetznerDownloadUrl(1_000_000L).startsWith("https://fsn1-speed.hetzner.com/")
        )
    }
}
