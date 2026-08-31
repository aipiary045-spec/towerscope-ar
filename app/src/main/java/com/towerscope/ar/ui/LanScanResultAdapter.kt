package com.towerscope.ar.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.R
import com.towerscope.ar.network.SubnetHost
import java.util.Locale

class LanScanResultAdapter(
    private val onOpenHost: (SubnetHost) -> Unit,
    private val onPortScanHost: (String) -> Unit,
    private val onOpenUrl: (String) -> Unit
) : ListAdapter<LanScanResultAdapter.Item, RecyclerView.ViewHolder>(DIFF) {

    sealed class Item {
        abstract val itemId: String

        data class Host(val host: SubnetHost) : Item() {
            override val itemId: String = "host:${host.ip}"
        }

        data class PortHeader(val host: String) : Item() {
            override val itemId: String = "header:$host"
        }

        data class OpenPort(
            val host: String,
            val port: Int,
            val service: String?,
            val connectMs: Double,
            val url: String
        ) : Item() {
            override val itemId: String = "port:$host:$port"
        }

        data class Note(val message: String) : Item() {
            override val itemId: String = "note:$message"
        }
    }

    private companion object {
        private const val TYPE_HOST = 0
        private const val TYPE_PORT_HEADER = 1
        private const val TYPE_OPEN_PORT = 2
        private const val TYPE_NOTE = 3

        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem.itemId == newItem.itemId

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem == newItem
        }
    }

    class HostHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ip: TextView = view.findViewById(R.id.subnetRowIp)
        val url: TextView = view.findViewById(R.id.subnetRowUrl)
        val meta: TextView = view.findViewById(R.id.subnetRowMeta)
        val openButton: MaterialButton = view.findViewById(R.id.subnetRowOpenButton)
        val portButton: MaterialButton = view.findViewById(R.id.subnetRowPortButton)
    }

    class PortHolder(view: View) : RecyclerView.ViewHolder(view) {
        val port: TextView = view.findViewById(R.id.portScanRowPort)
        val url: TextView = view.findViewById(R.id.portScanRowUrl)
        val ms: TextView = view.findViewById(R.id.portScanRowMs)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.lanHostHeader)
    }

    class NoteHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.lanNoteText)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Item.Host -> TYPE_HOST
        is Item.PortHeader -> TYPE_PORT_HEADER
        is Item.OpenPort -> TYPE_OPEN_PORT
        is Item.Note -> TYPE_NOTE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HOST -> HostHolder(
                inflater.inflate(R.layout.item_subnet_host_row, parent, false)
            )
            TYPE_PORT_HEADER -> HeaderHolder(
                inflater.inflate(R.layout.item_lan_host_header, parent, false)
            )
            TYPE_OPEN_PORT -> PortHolder(
                inflater.inflate(R.layout.item_port_scan_row, parent, false)
            )
            else -> NoteHolder(
                inflater.inflate(R.layout.item_lan_note, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Host -> bindHost(holder as HostHolder, item.host)
            is Item.PortHeader -> (holder as HeaderHolder).label.text = item.host
            is Item.OpenPort -> bindPort(holder as PortHolder, item)
            is Item.Note -> (holder as NoteHolder).text.text = item.message
        }
    }

    private fun bindHost(holder: HostHolder, host: SubnetHost) {
        holder.ip.text = host.ip
        holder.url.text = host.httpUrl
        val meta = buildString {
            append(host.deviceType ?: "Host")
            append("  ·  MAC  ")
            append(host.macAddress ?: "—")
            append("\n")
            append(host.hostname ?: "no reverse DNS")
            if (host.openPorts.isNotEmpty()) {
                append("  ·  ports ")
                append(host.openPorts.joinToString(","))
            } else {
                host.openPort?.let { append("  ·  port ").append(it) }
            }
            if (host.ipv6Addresses.isNotEmpty()) {
                append("\nIPv6  ")
                append(host.ipv6Addresses.joinToString(", "))
            }
        }
        holder.meta.text = meta
        holder.openButton.setOnClickListener { onOpenHost(host) }
        holder.portButton.setOnClickListener { onPortScanHost(host.ip) }
    }

    private fun bindPort(holder: PortHolder, item: Item.OpenPort) {
        val label = item.service?.let { "${item.port} ($it)" } ?: item.port.toString()
        holder.port.text = label
        holder.url.text = item.url
        holder.ms.text = String.format(Locale.US, "%.0f ms", item.connectMs)
        holder.itemView.setOnClickListener { onOpenUrl(item.url) }
    }
}
