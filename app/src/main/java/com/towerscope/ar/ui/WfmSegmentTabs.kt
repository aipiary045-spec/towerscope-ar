package com.towerscope.ar.ui

import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.towerscope.ar.R

enum class NetworkHubTab {
    CONNECTION,
    WIFI,
    LOCAL,
    INTERNET
}

enum class InstallHubTab {
    OVERVIEW,
    TOOLS
}

object WfmSegmentTabs {

    fun bindNetworkHub(
        root: View,
        initial: NetworkHubTab = NetworkHubTab.CONNECTION,
        onTabSelected: ((NetworkHubTab) -> Unit)? = null
    ) {
        val tabs = listOf(
            Triple(R.id.hubTabConnection, R.id.hubPanelConnection, NetworkHubTab.CONNECTION),
            Triple(R.id.hubTabWifi, R.id.hubPanelWifi, NetworkHubTab.WIFI),
            Triple(R.id.hubTabLocal, R.id.hubPanelLocal, NetworkHubTab.LOCAL),
            Triple(R.id.hubTabInternet, R.id.hubPanelInternet, NetworkHubTab.INTERNET)
        )
        bind(root, tabs, initial, onTabSelected) { selected, tab ->
            selected == tab
        }
    }

    fun bindInstallHub(root: View, initial: InstallHubTab = InstallHubTab.OVERVIEW) {
        val tabs = listOf(
            Triple(R.id.installTabOverview, R.id.installPanelOverview, InstallHubTab.OVERVIEW),
            Triple(R.id.installTabTools, R.id.installPanelTools, InstallHubTab.TOOLS)
        )
        bind(root, tabs, initial, onTabSelected = null) { selected, tab ->
            selected == tab
        }
    }

    private fun <T> bind(
        root: View,
        tabs: List<Triple<Int, Int, T>>,
        initial: T,
        onTabSelected: ((T) -> Unit)? = null,
        isSelected: (T, T) -> Boolean
    ) {
        val ctx = root.context
        val activeColor = ContextCompat.getColor(ctx, R.color.accent_yellow)
        val idleColor = ContextCompat.getColor(ctx, R.color.text_dim)
        val semibold = Typeface.create(ctx.resources.getFont(R.font.source_sans3_semibold), Typeface.NORMAL)
        val regular = Typeface.create(ctx.resources.getFont(R.font.source_sans3_regular), Typeface.NORMAL)

        fun select(tab: T) {
            tabs.forEach { (tabId, panelId, value) ->
                val on = isSelected(tab, value)
                root.findViewById<View>(panelId).isVisible = on
                root.findViewById<View>(tabId).findViewById<View>(R.id.wfmTabPill).isVisible = on
                root.findViewById<View>(tabId).findViewById<TextView>(R.id.wfmTabLabel).apply {
                    setTextColor(if (on) activeColor else idleColor)
                    typeface = if (on) semibold else regular
                }
            }
            onTabSelected?.invoke(tab)
        }

        tabs.forEach { (tabId, _, value) ->
            root.findViewById<View>(tabId).setOnClickListener { select(value) }
        }
        select(initial)
    }
}
