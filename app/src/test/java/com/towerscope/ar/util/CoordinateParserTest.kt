package com.towerscope.ar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateParserTest {

    @Test
    fun parsePair_commaSeparated() {
        val result = CoordinateParser.parsePair("30.2672, -97.7431")
        assertTrue(result.isSuccess)
        assertEquals(30.2672, result.getOrNull()!!.latitude, 0.0001)
        assertEquals(-97.7431, result.getOrNull()!!.longitude, 0.0001)
    }

    @Test
    fun parsePair_spaceSeparated() {
        val result = CoordinateParser.parsePair("36.7 -97.0")
        assertTrue(result.isSuccess)
        assertEquals(36.7, result.getOrNull()!!.latitude, 0.0001)
        assertEquals(-97.0, result.getOrNull()!!.longitude, 0.0001)
    }

    @Test
    fun parsePair_withLabels() {
        val result = CoordinateParser.parsePair("lat 30.2672 lon -97.7431")
        assertTrue(result.isSuccess)
        assertEquals(30.2672, result.getOrNull()!!.latitude, 0.0001)
        assertEquals(-97.7431, result.getOrNull()!!.longitude, 0.0001)
    }

    @Test
    fun parseFields_valid() {
        val result = CoordinateParser.parseFields("30.2672", "-97.7431")
        assertTrue(result.isSuccess)
    }

    @Test
    fun parseFields_rejectsOutOfRangeLatitude() {
        val result = CoordinateParser.parseFields("95.0", "-97.0")
        assertTrue(result.isFailure)
    }

    @Test
    fun parseClipboard_pair() {
        val result = CoordinateParser.parseClipboard("30.2672, -97.7431")
        assertTrue(result.isSuccess)
    }

    @Test
    fun parseClipboard_singleFillsMissingLatitude() {
        val result = CoordinateParser.parseClipboard("30.5", longitudeInput = "-97.0")
        assertTrue(result.isSuccess)
        assertEquals(30.5, result.getOrNull()!!.latitude, 0.0001)
    }

    @Test
    fun parsePair_rejectsGarbage() {
        assertTrue(CoordinateParser.parsePair("not coords").isFailure)
    }
}
