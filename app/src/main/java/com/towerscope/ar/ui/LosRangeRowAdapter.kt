package com.towerscope.ar.ui

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.towerscope.ar.R
import com.towerscope.ar.data.LosProfileBuilder
import com.towerscope.ar.util.CardinalSector
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.util.LinkEstimate
import com.towerscope.ar.viewmodel.LosRangeRow
import com.towerscope.ar.viewmodel.TowerUiState
import java.util.Locale

class LosRangeRowAdapter(
    private val onRowClick: (String) -> Unit,
    private val onShimmerStart: (View, View) -> Unit
) : ListAdapter<LosRangeRowAdapter.Item, LosRangeRowAdapter.Holder>(DIFF) {

    data class Item(
        val row: LosRangeRow,
        val rank: Int,
        val state: TowerUiState
    )

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val statusBar: View = view.findViewById(R.id.rowStatusBar)
        val rank: TextView = view.findViewById(R.id.rowRank)
        val name: TextView = view.findViewById(R.id.rowName)
        val pill: TextView = view.findViewById(R.id.rowStatusPill)
        val meta: TextView = view.findViewById(R.id.rowMeta)
        val clearance: TextView = view.findViewById(R.id.rowClearance)
        val linkEstimate: TextView = view.findViewById(R.id.rowLinkEstimate)
        val shimmer1: View = view.findViewById(R.id.rowShimmer)
        val shimmer2: View = view.findViewById(R.id.rowShimmer2)
        val chart: LosProfileChartView = view.findViewById(R.id.rowChart)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_los_range_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        val row = item.row
        val state = item.state
        val clutter = state.clutterHeightMeters.toDouble()
        val freq = state.frequencyGhz.toDouble()

        holder.rank.text = String.format(Locale.US, "%02d", item.rank)
        holder.name.text = row.tower.name

        val bearing = state.bearingTo(row.tower)
        val az = bearing?.let { GeoUtils.formatAzimuthPadded(it) } ?: "—"
        val height = towerHeightLabel(state, row)
        val origin = state.positioningLocation()
        val sectorLabel = if (origin != null) {
            val fromAp = GeoUtils.bearingDegrees(
                row.tower.latitude,
                row.tower.longitude,
                origin.latitude,
                origin.longitude
            )
            "  ·  ${CardinalSector.facingSite(fromAp).shortLabel} sec"
        } else {
            ""
        }
        holder.meta.text =
            "${GeoUtils.formatDistance(row.distanceMeters)}  ·  Az $az  ·  $height$sectorLabel"

        holder.itemView.setOnClickListener { onRowClick(row.tower.id) }

        when {
            row.loading -> bindLoading(holder, onShimmerStart)
            row.error != null -> bindError(holder, row.error)
            row.profile != null -> bindProfile(holder, row, state, clutter, freq)
            else -> bindEmpty(holder)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.shimmer1.clearAnimation()
        holder.shimmer2.clearAnimation()
        holder.shimmer1.alpha = 1f
        holder.shimmer2.alpha = 1f
        super.onViewRecycled(holder)
    }

    private fun bindLoading(holder: Holder, onShimmerStart: (View, View) -> Unit) {
        val ctx = holder.itemView.context
        holder.pill.isVisible = false
        holder.statusBar.setBackgroundColor(ContextCompat.getColor(ctx, R.color.text_dim))
        holder.clearance.text = "Profiling elevation…"
        holder.clearance.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
        holder.linkEstimate.isVisible = false
        holder.chart.isVisible = false
        holder.shimmer1.isVisible = true
        holder.shimmer2.isVisible = true
        onShimmerStart(holder.shimmer1, holder.shimmer2)
    }

    private fun bindError(holder: Holder, error: String) {
        val ctx = holder.itemView.context
        holder.pill.isVisible = false
        holder.statusBar.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_blocked))
        holder.clearance.text = error
        holder.clearance.setTextColor(ContextCompat.getColor(ctx, R.color.status_blocked))
        holder.linkEstimate.isVisible = false
        holder.chart.isVisible = false
        holder.shimmer1.isVisible = false
        holder.shimmer2.isVisible = false
    }

    private fun bindProfile(
        holder: Holder,
        row: LosRangeRow,
        state: TowerUiState,
        clutter: Double,
        freq: Double
    ) {
        val ctx = holder.itemView.context
        val profile = row.profile ?: return bindEmpty(holder)
        val geometric = profile.minClearanceMeters(clutter)
        val fresnel = profile.minFresnelClearanceMeters(clutter, freq)
        val obstruction = LinkEstimate.obstructionLossDb(geometric, fresnel)
        val dbm = LinkEstimate.estimatedReceiveLevelDbm(
            distanceMeters = row.distanceMeters,
            frequencyGhz = freq,
            txPowerDbm = state.txPowerDbm.toDouble(),
            apGainDbi = state.apAntennaGainDbi.toDouble(),
            cpeGainDbi = state.cpeAntennaGainDbi.toDouble(),
            geometricClearanceMeters = geometric,
            fresnelClearanceMeters = fresnel
        )
        val quality = LinkEstimate.signalQuality(dbm)
        holder.pill.isVisible = true
        holder.pill.text = quality.label
        when (quality) {
            LinkEstimate.SignalQuality.STRONG -> {
                holder.pill.setTextColor(ContextCompat.getColor(ctx, R.color.status_clear))
                holder.pill.setBackgroundResource(R.drawable.bg_pill_clear)
                holder.statusBar.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_clear))
                holder.clearance.setTextColor(ContextCompat.getColor(ctx, R.color.status_clear))
            }
            LinkEstimate.SignalQuality.OK -> {
                holder.pill.setTextColor(ContextCompat.getColor(ctx, R.color.status_clear))
                holder.pill.setBackgroundResource(R.drawable.bg_pill_clear)
                holder.statusBar.setBackgroundColor(ContextCompat.getColor(ctx, R.color.accent_teal))
                holder.clearance.setTextColor(ContextCompat.getColor(ctx, R.color.accent_teal))
            }
            LinkEstimate.SignalQuality.WEAK -> {
                holder.pill.setTextColor(ContextCompat.getColor(ctx, R.color.accent_yellow))
                holder.pill.setBackgroundResource(R.drawable.bg_pill_clear)
                holder.statusBar.setBackgroundColor(ContextCompat.getColor(ctx, R.color.accent_yellow))
                holder.clearance.setTextColor(ContextCompat.getColor(ctx, R.color.accent_yellow))
            }
            LinkEstimate.SignalQuality.POOR -> {
                holder.pill.setTextColor(ContextCompat.getColor(ctx, R.color.status_blocked))
                holder.pill.setBackgroundResource(R.drawable.bg_pill_blocked)
                holder.statusBar.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_blocked))
                holder.clearance.setTextColor(ContextCompat.getColor(ctx, R.color.status_blocked))
            }
        }
        holder.clearance.text = LinkEstimate.formatReceiveLevel(dbm)
        holder.linkEstimate.isVisible = true
        val pathNote = when {
            obstruction >= 20.0 -> " · path blocked"
            obstruction >= 6.0 -> " · Fresnel tight"
            else -> ""
        }
        holder.linkEstimate.text = String.format(
            Locale.US,
            "%.1f GHz · Tx %.0f · AP %.0f · CPE %.0f dBi%s",
            freq,
            state.txPowerDbm,
            state.apAntennaGainDbi,
            state.cpeAntennaGainDbi,
            pathNote
        )
        holder.chart.isVisible = true
        holder.chart.setProfile(profile, clutter, freq)
        holder.shimmer1.isVisible = false
        holder.shimmer2.isVisible = false
    }

    private fun bindEmpty(holder: Holder) {
        holder.pill.isVisible = false
        holder.clearance.text = "—"
        holder.linkEstimate.isVisible = false
        holder.chart.isVisible = false
        holder.shimmer1.isVisible = false
        holder.shimmer2.isVisible = false
    }

    private fun towerHeightLabel(state: TowerUiState, row: LosRangeRow): String {
        val tip = row.profile?.towerTipElevationMeters
        val groundHint = row.tower.altitudeMeters
        return when {
            tip != null && groundHint != null && row.tower.altitudeMode.name == "RELATIVE_TO_GROUND" ->
                String.format(Locale.US, "Ht %.0f m", groundHint)
            tip != null -> String.format(Locale.US, "Tip %.0f m", tip)
            groundHint != null && row.tower.altitudeMode.name == "RELATIVE_TO_GROUND" ->
                String.format(Locale.US, "Ht %.0f m", groundHint)
            groundHint != null && row.tower.altitudeMode.name == "ABSOLUTE" ->
                String.format(Locale.US, "Alt %.0f m", groundHint)
            else -> String.format(Locale.US, "Ht ~%.0f m", LosProfileBuilder.DEFAULT_TOWER_HEIGHT_METERS)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem.row.tower.id == newItem.row.tower.id

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem == newItem
        }

        fun pulse(view: View, animators: MutableList<ObjectAnimator>) {
            view.clearAnimation()
            val anim = ObjectAnimator.ofFloat(view, View.ALPHA, 0.35f, 1f).apply {
                duration = 700L
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
            animators.add(anim)
        }
    }
}
