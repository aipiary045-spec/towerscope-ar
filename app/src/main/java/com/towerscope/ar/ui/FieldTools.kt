package com.towerscope.ar.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.towerscope.ar.BandwidthMonitorActivity
import com.towerscope.ar.DataMenuActivity
import com.towerscope.ar.DnsLookupActivity
import com.towerscope.ar.LosProfilesActivity
import com.towerscope.ar.MainActivity
import com.towerscope.ar.MapActivity
import com.towerscope.ar.NetworkDiagnoseActivity
import com.towerscope.ar.PingMonitorActivity
import com.towerscope.ar.R
import com.towerscope.ar.SpeedTestActivity
import com.towerscope.ar.SubnetScannerActivity
import com.towerscope.ar.TraceRouteActivity
import com.towerscope.ar.WifiMonitorActivity
import com.towerscope.ar.util.ToolGroup
import com.towerscope.ar.util.ToolIndex

data class FieldTool(
    val id: String,
    val group: ToolGroup,
    val iconRes: Int,
    val titleRes: Int,
    val subtitleRes: Int,
    val target: Class<out Activity>,
    val extraKeywords: String = ""
)

/**
 * Single catalog for every field tool. Home inflates this list so tools are
 * one tap from launch instead of nested behind hub screens.
 */
object FieldTools {
    val all: List<FieldTool> = listOf(
        FieldTool(
            id = "wifi",
            group = ToolGroup.NETWORK,
            iconRes = R.drawable.ic_wifi_signal,
            titleRes = R.string.home_job_wifi,
            subtitleRes = R.string.home_job_wifi_sub,
            target = WifiMonitorActivity::class.java,
            extraKeywords = "rssi rf channel ap scan"
        ),
        FieldTool(
            id = "speed",
            group = ToolGroup.NETWORK,
            iconRes = R.drawable.ic_speed_test,
            titleRes = R.string.home_job_speed,
            subtitleRes = R.string.home_job_speed_sub,
            target = SpeedTestActivity::class.java,
            extraKeywords = "throughput download upload"
        ),
        FieldTool(
            id = "ping",
            group = ToolGroup.NETWORK,
            iconRes = R.drawable.ic_ping_graph,
            titleRes = R.string.home_job_ping,
            subtitleRes = R.string.home_job_ping_sub,
            target = PingMonitorActivity::class.java,
            extraKeywords = "icmp latency packet loss"
        ),
        FieldTool(
            id = "diagnose",
            group = ToolGroup.NETWORK,
            iconRes = R.drawable.ic_network_diagnose,
            titleRes = R.string.home_job_diagnose,
            subtitleRes = R.string.home_job_diagnose_sub,
            target = NetworkDiagnoseActivity::class.java,
            extraKeywords = "path doctor http tls"
        ),
        FieldTool(
            id = "dns",
            group = ToolGroup.NETWORK,
            iconRes = R.drawable.ic_dns_lookup,
            titleRes = R.string.home_job_dns,
            subtitleRes = R.string.home_job_dns_sub,
            target = DnsLookupActivity::class.java,
            extraKeywords = "lookup resolver ptr"
        ),
        FieldTool(
            id = "trace",
            group = ToolGroup.NETWORK,
            iconRes = R.drawable.ic_traceroute,
            titleRes = R.string.home_job_traceroute,
            subtitleRes = R.string.home_job_traceroute_sub,
            target = TraceRouteActivity::class.java,
            extraKeywords = "hops ttl path"
        ),
        FieldTool(
            id = "bandwidth",
            group = ToolGroup.NETWORK,
            iconRes = R.drawable.ic_bandwidth,
            titleRes = R.string.home_job_bandwidth,
            subtitleRes = R.string.home_job_bandwidth_sub,
            target = BandwidthMonitorActivity::class.java,
            extraKeywords = "rx tx live traffic"
        ),
        FieldTool(
            id = "subnet",
            group = ToolGroup.NETWORK,
            iconRes = R.drawable.ic_subnet_scan,
            titleRes = R.string.home_job_subnet,
            subtitleRes = R.string.home_job_subnet_sub,
            target = SubnetScannerActivity::class.java,
            extraKeywords = "lan hosts mac scan"
        ),
        FieldTool(
            id = "compass",
            group = ToolGroup.INSTALL,
            iconRes = R.drawable.ic_compass_rose,
            titleRes = R.string.home_job_aim,
            subtitleRes = R.string.home_job_aim_sub,
            target = MainActivity::class.java,
            extraKeywords = "aim bearing azimuth heading"
        ),
        FieldTool(
            id = "locate",
            group = ToolGroup.INSTALL,
            iconRes = R.drawable.ic_satellite_map,
            titleRes = R.string.home_job_locate,
            subtitleRes = R.string.home_job_locate_sub,
            target = MapActivity::class.java,
            extraKeywords = "map satellite pin gps"
        ),
        FieldTool(
            id = "los",
            group = ToolGroup.INSTALL,
            iconRes = R.drawable.ic_terrain_profile,
            titleRes = R.string.home_job_los,
            subtitleRes = R.string.home_job_los_sub,
            target = LosProfilesActivity::class.java,
            extraKeywords = "fresnel clearance terrain profile"
        ),
        FieldTool(
            id = "sites",
            group = ToolGroup.INSTALL,
            iconRes = R.drawable.ic_tower_lattice,
            titleRes = R.string.home_job_sites,
            subtitleRes = R.string.home_job_sites_sub,
            target = DataMenuActivity::class.java,
            extraKeywords = "import kml kmz csv"
        )
    )

    fun indexFrom(resolve: (FieldTool) -> String): List<ToolIndex> =
        all.map { tool ->
            ToolIndex(
                id = tool.id,
                group = tool.group,
                searchText = resolve(tool)
            )
        }

    fun inflateRow(
        inflater: LayoutInflater,
        parent: ViewGroup,
        tool: FieldTool,
        onClick: () -> Unit
    ): View {
        val row = inflater.inflate(R.layout.item_tool_row, parent, false)
        val icon = row.findViewById<ImageView>(R.id.toolRowIcon)
        val title = row.findViewById<TextView>(R.id.toolRowTitle)
        val subtitle = row.findViewById<TextView>(R.id.toolRowSubtitle)
        icon.setImageResource(tool.iconRes)
        val tint = ContextCompat.getColor(
            row.context,
            if (tool.group == ToolGroup.NETWORK) R.color.accent_teal else R.color.accent_yellow
        )
        icon.setColorFilter(tint)
        title.setText(tool.titleRes)
        subtitle.setText(tool.subtitleRes)
        row.contentDescription = title.text
        row.setOnClickListener { onClick() }
        return row
    }
}
