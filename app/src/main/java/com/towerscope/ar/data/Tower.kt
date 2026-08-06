package com.towerscope.ar.data

/**
 * A tower / placemark loaded from KML or KMZ.
 */
data class Tower(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?
)
