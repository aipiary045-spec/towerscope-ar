package com.towerscope.ar.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortCheckerFormatTest {

    @Test
    fun format_includesHostAndPort() {
        val text = PortChecker.format(
            PortCheckResult("10.0.0.1", 443, open = true, connectMs = 12.0)
        )
        assertTrue(text.contains("10.0.0.1:443"))
        assertTrue(text.contains("OPEN"))
    }

    @Test
    fun format_closedPort() {
        val text = PortChecker.format(
            PortCheckResult("10.0.0.1", 22, open = false, connectMs = null, error = "timeout")
        )
        assertFalse(text.contains("OPEN"))
    }
}
