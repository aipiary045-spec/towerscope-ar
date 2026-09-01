package com.towerscope.ar

import android.Manifest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import com.towerscope.ar.ui.BottomNavTab

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TowerScopeApp::class, qualifiers = "en-rUS")
class MainHostNavTest {
    @Before
    fun grantPermissions() {
        ShadowApplication.getInstance().grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
    }

    private fun flushFragments(activity: MainHostActivity) {
        activity.supportFragmentManager.executePendingTransactions()
    }

    @Test
    fun tabSwitching_doesNotCrash() {
        val controller = Robolectric.buildActivity(MainHostActivity::class.java).create().start().resume()
        val activity = controller.get()
        activity.showTab(BottomNavTab.NETWORK)
        flushFragments(activity)
        activity.showTab(BottomNavTab.INSTALL)
        flushFragments(activity)
        activity.showTab(BottomNavTab.HOME)
        flushFragments(activity)
        activity.findViewById<android.view.View>(R.id.navNetwork)?.performClick()
        flushFragments(activity)
        activity.findViewById<android.view.View>(R.id.navInstall)?.performClick()
        flushFragments(activity)
        activity.findViewById<android.view.View>(R.id.navHome)?.performClick()
        flushFragments(activity)
    }

    @Test
    fun networkHubSegmentTabs_doNotCrash() {
        val controller = Robolectric.buildActivity(MainHostActivity::class.java).create().start().resume()
        val activity = controller.get()
        activity.showTab(BottomNavTab.NETWORK)
        flushFragments(activity)
        activity.findViewById<android.view.View>(R.id.hubTabWifi)?.performClick()
        activity.findViewById<android.view.View>(R.id.hubTabLocal)?.performClick()
        activity.findViewById<android.view.View>(R.id.hubTabInternet)?.performClick()
        activity.findViewById<android.view.View>(R.id.hubTabConnection)?.performClick()
    }
}
