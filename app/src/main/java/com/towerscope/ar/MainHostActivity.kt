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
 * Single-activity host for Home / Network / Install hub tabs.
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
            val tab = intent.getStringExtra(EXTRA_TAB)?.let {
                runCatching { BottomNavTab.valueOf(it) }.getOrNull()
            } ?: BottomNavTab.HOME
            showTab(tab, animate = false)
        } else {
            BottomNav.bindHost(this, currentTab())
        }
    }

    fun showTab(tab: BottomNavTab, animate: Boolean = true) {
        val tag = tab.name
        val current = supportFragmentManager.findFragmentById(R.id.mainHostContainer)
        if (current != null && current.tag == tag && current.isVisible) {
            BottomNav.bindHost(this, tab)
            return
        }
        val tx = supportFragmentManager.beginTransaction()
        if (animate) tx.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        tx.replace(R.id.mainHostContainer, fragmentFor(tab), tag).commit()
        BottomNav.bindHost(this, tab)
    }

    fun currentTab(): BottomNavTab {
        val tag = supportFragmentManager.findFragmentById(R.id.mainHostContainer)?.tag
        return runCatching { BottomNavTab.valueOf(tag.orEmpty()) }.getOrDefault(BottomNavTab.HOME)
    }

    private fun fragmentFor(tab: BottomNavTab): Fragment = when (tab) {
        BottomNavTab.HOME -> HomeFragment()
        BottomNavTab.NETWORK -> NetworkHubFragment()
        BottomNavTab.INSTALL -> InstallDashboardFragment()
        BottomNavTab.SETTINGS -> HomeFragment()
    }

    companion object {
        const val EXTRA_TAB = "main_host_tab"

        fun intent(activity: android.content.Context, tab: BottomNavTab): Intent =
            Intent(activity, MainHostActivity::class.java)
                .putExtra(EXTRA_TAB, tab.name)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
