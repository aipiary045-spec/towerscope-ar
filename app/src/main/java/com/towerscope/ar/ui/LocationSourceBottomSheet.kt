package com.towerscope.ar.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.towerscope.ar.R
import com.towerscope.ar.viewmodel.TowerScopeViewModel
import kotlinx.coroutines.launch

/**
 * Bottom sheet for choosing check location: GPS, custom pin, or typed coordinates.
 */
class LocationSourceBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: TowerScopeViewModel by activityViewModels()
    var onModeChanged: (() -> Unit)? = null
    var onCoordinatesApplied: ((latitude: Double, longitude: Double) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_location_source, container, false)

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val modeControls = LocationModeControls(
            root = view.findViewById(R.id.sheetLocationMode),
            viewModel = viewModel
        ) {
            onModeChanged?.invoke()
        }
        val coordinateControls = CustomCoordinateEntryControls(
            root = view.findViewById(R.id.sheetCoordinateEntry),
            viewModel = viewModel
        ) { latitude, longitude ->
            onCoordinatesApplied?.invoke(latitude, longitude)
            dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    modeControls.render(state, requireContext())
                    coordinateControls.render(state)
                }
            }
        }
    }

    companion object {
        const val TAG = "location_source_sheet"
    }
}
