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
import com.towerscope.ar.network.DiagnoseLayerResult
import com.towerscope.ar.network.DiagnoseStatus
import java.util.Locale

class DiagnoseLayerAdapter :
    ListAdapter<DiagnoseLayerResult, DiagnoseLayerAdapter.Holder>(DIFF) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val status: TextView = view.findViewById(R.id.diagnoseLayerStatus)
        val title: TextView = view.findViewById(R.id.diagnoseLayerTitle)
        val detail: TextView = view.findViewById(R.id.diagnoseLayerDetail)
        val latency: TextView = view.findViewById(R.id.diagnoseLayerLatency)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_diagnose_layer_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val layer = getItem(position)
        val ctx = holder.itemView.context
        holder.title.text = layer.title
        holder.detail.text = layer.detail
        holder.latency.text = layer.latencyMs?.let {
            String.format(Locale.US, "%.0f ms", it)
        }.orEmpty()

        when (layer.status) {
            DiagnoseStatus.PENDING -> {
                holder.status.text = "·"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            }
            DiagnoseStatus.RUNNING -> {
                holder.status.text = "…"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.accent_teal))
            }
            DiagnoseStatus.PASS -> {
                holder.status.text = "✓"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.status_clear))
            }
            DiagnoseStatus.FAIL -> {
                holder.status.text = "✕"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.chip_poor))
            }
            DiagnoseStatus.SKIPPED -> {
                holder.status.text = "–"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DiagnoseLayerResult>() {
            override fun areItemsTheSame(
                oldItem: DiagnoseLayerResult,
                newItem: DiagnoseLayerResult
            ): Boolean = oldItem.layer == newItem.layer

            override fun areContentsTheSame(
                oldItem: DiagnoseLayerResult,
                newItem: DiagnoseLayerResult
            ): Boolean = oldItem == newItem
        }
    }
}
