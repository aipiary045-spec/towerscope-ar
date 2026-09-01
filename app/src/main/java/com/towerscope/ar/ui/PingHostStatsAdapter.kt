package com.towerscope.ar.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.towerscope.ar.R
import com.towerscope.ar.network.PingHostStats
import java.util.Locale

class PingHostStatsAdapter :
    ListAdapter<PingHostStats, PingHostStatsAdapter.Holder>(DIFF) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val host: TextView = view.findViewById(R.id.pingRowHost)
        val last: TextView = view.findViewById(R.id.pingRowLast)
        val stats: TextView = view.findViewById(R.id.pingRowStats)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ping_host_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val host = getItem(position)
        val ctx = holder.itemView.context
        holder.host.text = host.displayTarget
        holder.last.text = host.lastMs?.let { String.format(Locale.US, "%.0f ms", it) } ?: "timeout"
        holder.last.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (host.lastSuccess) R.color.accent_teal else R.color.chip_poor
            )
        )
        holder.stats.text = String.format(
            Locale.US,
            "%s · Sent %d · Recv %d · Loss %.0f%% · avg %s",
            host.method,
            host.sent,
            host.received,
            host.lossPercent,
            formatMs(host.avgMs)
        )
    }

    private fun formatMs(value: Double?): String =
        if (value == null || !value.isFinite()) "—" else String.format(Locale.US, "%.0f", value)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PingHostStats>() {
            override fun areItemsTheSame(oldItem: PingHostStats, newItem: PingHostStats): Boolean =
                oldItem.displayTarget == newItem.displayTarget

            override fun areContentsTheSame(oldItem: PingHostStats, newItem: PingHostStats): Boolean =
                oldItem.sent == newItem.sent &&
                    oldItem.received == newItem.received &&
                    oldItem.lossPercent == newItem.lossPercent &&
                    oldItem.lastMs == newItem.lastMs &&
                    oldItem.lastSuccess == newItem.lastSuccess &&
                    oldItem.avgMs == newItem.avgMs
        }
    }
}
