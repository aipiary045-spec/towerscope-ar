package com.towerscope.ar.network

/**
 * Ranks Wi‑Fi channels from scan data and suggests cleaner options per band.
 */
object ChannelPlanner {

    data class ChannelRecommendation(
        val channel: Int,
        val band: String,
        val apCount: Int,
        val strongestRssiDbm: Int?,
        /** Lower is better (fewer neighbors / weaker interferers). */
        val congestionScore: Int
    )

    data class PlannerReport(
        val recommendations24: List<ChannelRecommendation>,
        val recommendations5: List<ChannelRecommendation>,
        val recommendations6: List<ChannelRecommendation>,
        val best24: ChannelRecommendation?,
        val best5: ChannelRecommendation?,
        val best6: ChannelRecommendation?
    )

    fun analyze(scan: List<WifiScanAp>): PlannerReport {
        val grouped = scan
            .filter { it.channel > 0 }
            .groupBy { it.band to it.channel }

        fun bandRecommendations(bandLabel: String): List<ChannelRecommendation> {
            val channelsInBand = grouped.filter { it.key.first == bandLabel }
            val seen = channelsInBand.keys.map { it.second }.distinct()
            val candidates = when (bandLabel) {
                "2.4 GHz" -> CHANNELS_24
                "5 GHz" -> (CHANNELS_5 + seen).distinct().sorted()
                "6 GHz" -> seen.sorted()
                else -> seen
            }
            if (candidates.isEmpty()) return emptyList()

            return candidates.map { ch ->
                val neighbors = scan.filter { ap ->
                    ap.band == bandLabel &&
                        WifiMonitor.overlaps(ch, bandMhz(bandLabel), ap.channel, ap.frequencyMhz)
                }
                val strongest = neighbors.maxByOrNull { it.rssiDbm }?.rssiDbm
                val score = neighbors.size * 10 + (strongest?.let { -it } ?: 0)
                ChannelRecommendation(
                    channel = ch,
                    band = bandLabel,
                    apCount = neighbors.size,
                    strongestRssiDbm = strongest,
                    congestionScore = score
                )
            }.sortedBy { it.congestionScore }
        }

        val r24 = bandRecommendations("2.4 GHz")
        val r5 = bandRecommendations("5 GHz")
        val r6 = bandRecommendations("6 GHz")

        return PlannerReport(
            recommendations24 = r24,
            recommendations5 = r5,
            recommendations6 = r6,
            best24 = r24.firstOrNull(),
            best5 = r5.firstOrNull(),
            best6 = r6.firstOrNull()
        )
    }

    fun formatReport(report: PlannerReport): String = buildString {
        appendLine("Wi‑Fi channel recommendations")
        fun section(label: String, best: ChannelRecommendation?, list: List<ChannelRecommendation>) {
            appendLine()
            appendLine(label)
            if (best == null) {
                appendLine("  No scan data")
            } else {
                appendLine(formatBestLine(best))
                list.take(6).forEach { rec ->
                    val rssi = rec.strongestRssiDbm?.let { ", strongest $it dBm" } ?: ""
                    appendLine("  ch ${rec.channel}: ${rec.apCount} nearby APs$rssi")
                }
            }
        }
        section("2.4 GHz", report.best24, report.recommendations24)
        section("5 GHz", report.best5, report.recommendations5)
        section("6 GHz", report.best6, report.recommendations6)
    }.trim()

    fun formatBestLine(best: ChannelRecommendation): String =
        "  Best: ch ${best.channel} (${best.apCount} nearby APs)"

    private fun bandMhz(band: String): Int = when (band) {
        "2.4 GHz" -> 2437
        "5 GHz" -> 5180
        "6 GHz" -> 5975
        else -> 0
    }

    private val CHANNELS_24 = listOf(1, 6, 11)
    private val CHANNELS_5 = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
}
