package com.towerscope.ar.ui

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.towerscope.ar.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object SwipeRefreshHelper {

    fun bind(
        swipeRefresh: SwipeRefreshLayout,
        scope: CoroutineScope,
        onRefresh: suspend () -> Unit
    ) {
        swipeRefresh.setColorSchemeResources(R.color.accent_teal, R.color.accent_yellow)
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface_elevated)
        swipeRefresh.setOnRefreshListener {
            scope.launch {
                try {
                    onRefresh()
                } finally {
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }
}
