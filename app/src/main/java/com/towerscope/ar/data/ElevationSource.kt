package com.towerscope.ar.data

/**
 * Where a LOS sample elevation came from.
 * Clutter (trees) is only applied on top of [DEM] samples — LiDAR first-return
 * already includes canopy / structures.
 */
enum class ElevationSource {
    LIDAR,
    DEM
}
