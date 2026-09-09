package com.towerscope.ar.ui

import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.towerscope.ar.HomeActivity
import com.towerscope.ar.R
import com.towerscope.ar.util.ToolFocus

enum class BottomNavTab {
    HOME,
    NETWORK,
    INSTALL,
    SETTINGS
}

object BottomNav {
    fun bind(activity: FragmentActivity, selected: BottomNavTab) {
        val root = activity.findViewById<android.view.View>(R.id.bottomNavBar) ?: return
        val active = ContextCompat.getColor(activity, R.color.accent_teal)
        val idle = ContextCompat.getColor(activity, R.color.text_dim)

        fun style(tab: BottomNavTab, iconId: Int, labelId: Int) {
            val on = tab == selected
            root.findViewById<ImageView>(iconId).setColorFilter(if (on) active else idle)
            root.findViewById<TextView>(labelId).setTextColor(if (on) active else idle)
        }

        style(BottomNavTab.HOME, R.id.navHomeIcon, R.id.navHomeLabel)
        style(BottomNavTab.NETWORK, R.id.navNetworkIcon, R.id.navNetworkLabel)
        style(BottomNavTab.INSTALL, R.id.navInstallIcon, R.id.navInstallLabel)
        style(BottomNavTab.SETTINGS, R.id.navSettingsIcon, R.id.navSettingsLabel)

        root.findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            openTools(activity, ToolFocus.ALL)
        }
        root.findViewById<android.view.View>(R.id.navNetwork).setOnClickListener {
            openTools(activity, ToolFocus.NETWORK)
        }
        root.findViewById<android.view.View>(R.id.navInstall).setOnClickListener {
            openTools(activity, ToolFocus.INSTALL)
        }
        root.findViewById<android.view.View>(R.id.navSettings).setOnClickListener {
            if (activity.supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) == null) {
                SettingsBottomSheet.newInstance()
                    .show(activity.supportFragmentManager, SettingsBottomSheet.TAG)
            }
        }
    }

    private fun openTools(activity: FragmentActivity, focus: ToolFocus) {
        if (activity is HomeActivity) {
            activity.applyFocus(focus)
            return
        }
        activity.startActivity(HomeActivity.intent(activity, focus))
        activity.finish()
    }
}
