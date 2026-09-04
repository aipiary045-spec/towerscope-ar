package com.towerscope.ar.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.towerscope.ar.HomeActivity
import com.towerscope.ar.InstallDashboardActivity
import com.towerscope.ar.MainHostActivity
import com.towerscope.ar.NetworkHubActivity
import com.towerscope.ar.R

enum class BottomNavTab {
    HOME,
    NETWORK,
    INSTALL,
    SETTINGS
}

object BottomNav {

    private fun styleHostTab(
        root: android.view.View,
        selected: BottomNavTab,
        tab: BottomNavTab,
        containerId: Int,
        iconId: Int,
        labelId: Int
    ) {
        val ctx = root.context
        val on = tab == selected
        val active = ContextCompat.getColor(ctx, R.color.accent_yellow)
        val idle = ContextCompat.getColor(ctx, R.color.text_dim)
        val semibold = Typeface.create(ctx.resources.getFont(R.font.source_sans3_semibold), Typeface.NORMAL)
        val regular = Typeface.create(ctx.resources.getFont(R.font.source_sans3_regular), Typeface.NORMAL)

        root.findViewById<LinearLayout>(containerId).setBackgroundResource(
            if (on) R.drawable.bg_nav_item_selected else R.drawable.bg_nav_item_idle
        )
        root.findViewById<ImageView>(iconId).setColorFilter(if (on) active else idle)
        root.findViewById<TextView>(labelId).apply {
            setTextColor(if (on) active else idle)
            typeface = if (on) semibold else regular
        }
    }

    fun bind(activity: FragmentActivity, selected: BottomNavTab) {
        val root = activity.findViewById<android.view.View>(R.id.bottomNavBar) ?: return

        styleHostTab(root, selected, BottomNavTab.HOME, R.id.navHome, R.id.navHomeIcon, R.id.navHomeLabel)
        styleHostTab(root, selected, BottomNavTab.NETWORK, R.id.navNetwork, R.id.navNetworkIcon, R.id.navNetworkLabel)
        styleHostTab(root, selected, BottomNavTab.INSTALL, R.id.navInstall, R.id.navInstallIcon, R.id.navInstallLabel)
        styleHostTab(root, selected, BottomNavTab.SETTINGS, R.id.navSettings, R.id.navSettingsIcon, R.id.navSettingsLabel)

        root.findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            if (activity is HomeActivity) return@setOnClickListener
            activity.startActivity(
                Intent(activity, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            if (activity !is HomeActivity) activity.finish()
        }
        root.findViewById<android.view.View>(R.id.navNetwork).setOnClickListener {
            if (activity is NetworkHubActivity) return@setOnClickListener
            go(activity, NetworkHubActivity::class.java, clearHomeStack = activity is HomeActivity)
        }
        root.findViewById<android.view.View>(R.id.navInstall).setOnClickListener {
            if (activity is InstallDashboardActivity) return@setOnClickListener
            go(activity, InstallDashboardActivity::class.java, clearHomeStack = activity is HomeActivity)
        }
        root.findViewById<android.view.View>(R.id.navSettings).setOnClickListener {
            if (activity.supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) == null) {
                SettingsBottomSheet.newInstance()
                    .show(activity.supportFragmentManager, SettingsBottomSheet.TAG)
            }
        }
    }

    fun bindHost(activity: MainHostActivity, selected: BottomNavTab) {
        val root = activity.findViewById<android.view.View>(R.id.mainHostBottomNav) ?: return

        styleHostTab(root, selected, BottomNavTab.HOME, R.id.navHome, R.id.navHomeIcon, R.id.navHomeLabel)
        styleHostTab(root, selected, BottomNavTab.NETWORK, R.id.navNetwork, R.id.navNetworkIcon, R.id.navNetworkLabel)
        styleHostTab(root, selected, BottomNavTab.INSTALL, R.id.navInstall, R.id.navInstallIcon, R.id.navInstallLabel)
        styleHostTab(root, selected, BottomNavTab.SETTINGS, R.id.navSettings, R.id.navSettingsIcon, R.id.navSettingsLabel)

        root.findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            if (selected != BottomNavTab.HOME) activity.showTab(BottomNavTab.HOME)
        }
        root.findViewById<android.view.View>(R.id.navNetwork).setOnClickListener {
            if (selected != BottomNavTab.NETWORK) activity.showTab(BottomNavTab.NETWORK)
        }
        root.findViewById<android.view.View>(R.id.navInstall).setOnClickListener {
            if (selected != BottomNavTab.INSTALL) activity.showTab(BottomNavTab.INSTALL)
        }
        root.findViewById<android.view.View>(R.id.navSettings).setOnClickListener {
            if (activity.supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) == null) {
                SettingsBottomSheet.newInstance()
                    .show(activity.supportFragmentManager, SettingsBottomSheet.TAG)
            }
        }
    }

    private fun go(activity: Activity, clazz: Class<*>, clearHomeStack: Boolean) {
        if (activity is MainHostActivity) {
            val tab = when (clazz) {
                NetworkHubActivity::class.java -> BottomNavTab.NETWORK
                InstallDashboardActivity::class.java -> BottomNavTab.INSTALL
                else -> BottomNavTab.HOME
            }
            activity.showTab(tab)
            return
        }
        val intent = Intent(activity, clazz)
        activity.startActivity(intent)
        if (!clearHomeStack && activity !is HomeActivity) {
            activity.finish()
        }
    }
}
