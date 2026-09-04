package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.towerscope.ar.ui.BottomNav
import com.towerscope.ar.ui.BottomNavTab
import com.towerscope.ar.ui.SystemBars

/**
 * Single-activity host for Network and Install hub tabs.
 */
class MainHostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main_host)
        SystemBars.apply(
            root = findViewById(R.id.mainHostRoot),
            alsoBottom = findViewById(R.id.mainHostBottomNav)
        )

        if (savedInstanceState == null) {
            val tab = resolveTab(intent.getStringExtra(EXTRA_TAB))
            showTab(tab, animate = false)
        } else {
            BottomNav.bindHost(this, currentTab())
        }
    }

    override fun onResume() {
        super.onResume()
        BottomNav.bindHost(this, currentTab())
    }

    fun showTab(tab: BottomNavTab, animate: Boolean = true) {
        val resolved = if (tab == BottomNavTab.SETTINGS) currentTab() else tab
        val tag = resolved.name
        val current = supportFragmentManager.findFragmentById(R.id.mainHostContainer)
        if (current != null && current.tag == tag && current.isVisible) {
            BottomNav.bindHost(this, resolved)
            return
        }
        val tx = supportFragmentManager.beginTransaction()
        if (animate) tx.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        tx.replace(R.id.mainHostContainer, fragmentFor(resolved), tag).commit()
        BottomNav.bindHost(this, resolved)
    }

    fun currentTab(): BottomNavTab {
        val tag = supportFragmentManager.findFragmentById(R.id.mainHostContainer)?.tag
        return runCatching { BottomNavTab.valueOf(tag.orEmpty()) }.getOrDefault(BottomNavTab.NETWORK)
    }

    private fun fragmentFor(tab: BottomNavTab): Fragment = when (tab) {
        BottomNavTab.NETWORK -> NetworkHubFragment()
        BottomNavTab.INSTALL -> InstallDashboardFragment()
        BottomNavTab.SETTINGS -> NetworkHubFragment()
    }

    companion object {
        const val EXTRA_TAB = "main_host_tab"

        fun intent(activity: android.content.Context, tab: BottomNavTab): Intent =
            Intent(activity, MainHostActivity::class.java)
                .putExtra(EXTRA_TAB, tab.name)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun resolveTab(raw: String?): BottomNavTab {
            if (raw == null) return BottomNavTab.NETWORK
            // Legacy "HOME" tab maps to Network hub.
            if (raw == "HOME") return BottomNavTab.NETWORK
            return runCatching { BottomNavTab.valueOf(raw) }.getOrDefault(BottomNavTab.NETWORK)
        }
    }
}
