package com.towerscope.ar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.towerscope.ar.R
import com.towerscope.ar.util.GeoUtils
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import kotlinx.coroutines.launch
import java.util.Locale

class TowerDetailsBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: TowerScopeViewModel by activityViewModels()
    private lateinit var towerId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        towerId = requireArguments().getString(ARG_TOWER_ID)
            ?: error("towerId required")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_tower_details, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val detailName = view.findViewById<TextView>(R.id.detailName)
        val detailDistance = view.findViewById<TextView>(R.id.detailDistance)
        val detailBearing = view.findViewById<TextView>(R.id.detailBearing)
        val detailCoords = view.findViewById<TextView>(R.id.detailCoords)
        val detailAltitude = view.findViewById<TextView>(R.id.detailAltitude)

        view.findViewById<Button>(R.id.closeSheetButton).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.showOnlyButton).setOnClickListener {
            viewModel.showOnlyTower(towerId)
            dismiss()
        }
        view.findViewById<Button>(R.id.hideTowerButton).setOnClickListener {
            viewModel.hideTower(towerId)
            dismiss()
        }
        view.findViewById<Button>(R.id.copyCoordsButton).setOnClickListener {
            val tower = viewModel.uiState.value.towerById(towerId) ?: return@setOnClickListener
            val text = String.format(
                Locale.US,
                "%s\n%.6f, %.6f",
                tower.name,
                tower.latitude,
                tower.longitude
            )
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("tower_coords", text))
            Toast.makeText(requireContext(), "Coordinates copied", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.openMapsButton).setOnClickListener {
            val tower = viewModel.uiState.value.towerById(towerId) ?: return@setOnClickListener
            val encodedName = Uri.encode(tower.name)
            val uri = Uri.parse(
                String.format(
                    Locale.US,
                    "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                    tower.latitude,
                    tower.longitude,
                    tower.latitude,
                    tower.longitude,
                    encodedName
                )
            )
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val tower = state.towerById(towerId)
                    if (tower == null) {
                        dismissAllowingStateLoss()
                        return@collect
                    }
                    detailName.text = tower.name
                    val distance = state.distanceTo(tower)
                    detailDistance.text = if (distance != null) {
                        "Distance  ${GeoUtils.formatDistance(distance)}"
                    } else {
                        "Distance  —"
                    }
                    val bearing = state.bearingTo(tower)
                    val heading = state.effectiveHeadingDegrees()
                    detailBearing.text = when {
                        bearing != null && heading != null -> {
                            val relative = GeoUtils.relativeBearingDegrees(heading, bearing)
                            "Bearing  ${GeoUtils.formatBearing(bearing)}  ·  ${GeoUtils.formatRelativeTurn(relative)}"
                        }
                        bearing != null -> "Bearing  ${GeoUtils.formatBearing(bearing)}"
                        else -> "Bearing  —"
                    }
                    detailCoords.text = String.format(
                        Locale.US,
                        "Lat/Lon  %.6f, %.6f",
                        tower.latitude,
                        tower.longitude
                    )
                    detailAltitude.text = tower.altitudeMeters?.let {
                        String.format(Locale.US, "Altitude  %.1f m", it)
                    } ?: "Altitude  —"
                }
            }
        }
    }

    companion object {
        const val TAG = "TowerDetailsBottomSheet"
        private const val ARG_TOWER_ID = "tower_id"

        fun newInstance(towerId: String): TowerDetailsBottomSheet =
            TowerDetailsBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_TOWER_ID, towerId) }
            }
    }
}
