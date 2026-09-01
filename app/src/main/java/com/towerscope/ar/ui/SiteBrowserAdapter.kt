package com.towerscope.ar.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.R

class SiteBrowserAdapter(
    private val onDetails: (String) -> Unit,
    private val onAim: (String) -> Unit,
    private val onMap: (String) -> Unit,
    private val onLos: (String) -> Unit
) : ListAdapter<SiteBrowserAdapter.Row, SiteBrowserAdapter.Holder>(DIFF) {

    data class Row(
        val towerId: String,
        val name: String,
        val meta: String,
        val inRange: Boolean
    )

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.siteRowName)
        val rangeBadge: TextView = view.findViewById(R.id.siteRowRangeBadge)
        val meta: TextView = view.findViewById(R.id.siteRowMeta)
        val detailsButton: MaterialButton = view.findViewById(R.id.siteRowDetailsButton)
        val aimButton: MaterialButton = view.findViewById(R.id.siteRowAimButton)
        val mapButton: MaterialButton = view.findViewById(R.id.siteRowMapButton)
        val losButton: MaterialButton = view.findViewById(R.id.siteRowLosButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_site_browser_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.name.text = row.name
        holder.rangeBadge.isVisible = row.inRange
        holder.meta.text = row.meta
        holder.detailsButton.setOnClickListener { onDetails(row.towerId) }
        holder.aimButton.setOnClickListener { onAim(row.towerId) }
        holder.mapButton.setOnClickListener { onMap(row.towerId) }
        holder.losButton.setOnClickListener { onLos(row.towerId) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.towerId == newItem.towerId

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    }
}
