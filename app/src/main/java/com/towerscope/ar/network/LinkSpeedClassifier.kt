package com.towerscope.ar.network

import java.util.Locale

/** Maps negotiated link rates to installer-friendly 10 / 100 / 1000 Mbps labels. */
object LinkSpeedClassifier {

    fun labelFromMbps(mbps: Long): String? {
        if (mbps <= 0) return null
        return when {
            mbps <= 15 -> "10 Mbps"
            mbps <= 150 -> "100 Mbps"
            mbps <= 1_500 -> "1000 Mbps"
            mbps < 10_000 -> String.format(Locale.US, "%d Mbps", mbps)
            else -> String.format(Locale.US, "%.1f Gbps", mbps / 1000.0)
        }
    }

    fun labelFromBps(bps: Long): String? =
        labelFromMbps((bps / 1_000_000L).coerceAtLeast(1L))
}
