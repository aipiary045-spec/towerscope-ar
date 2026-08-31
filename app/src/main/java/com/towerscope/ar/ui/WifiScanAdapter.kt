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
import com.towerscope.ar.network.WifiScanAp
import java.util.Locale

class WifiScanAdapter : ListAdapter<WifiScanAp, WifiScanAdapter.Holder>(DIFF) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val ssid: TextView = view.findViewById(R.id.wifiRowSsid)
        val meta: TextView = view.findViewById(R.id.wifiRowMeta)
        val rssi: TextView = view.findViewById(R.id.wifiRowRssi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wifi_scan_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ap = getItem(position)
        val ctx = holder.itemView.context
        holder.ssid.text = ap.ssid
        val flag = when {
            ap.coChannelWithActive -> " · CO‑CH"
            ap.overlapWithActive -> " · OVERLAP"
            else -> ""
        }
        holder.meta.text = String.format(
            Locale.US,
            "ch %d · %s · %s%s",
            ap.channel,
            ap.band,
            ap.bssid,
            flag
        )
        holder.rssi.text = String.format(Locale.US, "%d", ap.rssiDbm)
        holder.rssi.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (ap.overlapWithActive) R.color.accent_yellow else R.color.accent_teal
            )
        )
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WifiScanAp>() {
            override fun areItemsTheSame(oldItem: WifiScanAp, newItem: WifiScanAp): Boolean =
                oldItem.bssid == newItem.bssid

            override fun areContentsTheSame(oldItem: WifiScanAp, newItem: WifiScanAp): Boolean =
                oldItem == newItem
        }

        fun sortForDisplay(results: List<WifiScanAp>): List<WifiScanAp> =
            results.sortedWith(
                compareByDescending<WifiScanAp> { it.coChannelWithActive }
                    .thenByDescending { it.overlapWithActive }
                    .thenByDescending { it.rssiDbm }
            ).take(40)
    }
}
