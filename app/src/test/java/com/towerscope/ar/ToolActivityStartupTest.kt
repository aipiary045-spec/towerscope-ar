package com.towerscope.ar

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * Regression guard: field tool activities must survive onCreate without NPE / inflation crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TowerScopeApp::class, qualifiers = "en-rUS")
class ToolActivityStartupTest {

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

    @Test fun losProfiles_starts() = assertStarts(LosProfilesActivity::class.java)

    @Test fun map_starts() = assertStarts(MapActivity::class.java)

    @Test fun wifiMonitor_starts() = assertStarts(WifiMonitorActivity::class.java)

    @Test fun networkDiagnose_starts() = assertStarts(NetworkDiagnoseActivity::class.java)

    @Test fun lanScanner_starts() = assertStarts(LanScannerActivity::class.java)

    @Test fun speedTest_starts() = assertStarts(SpeedTestActivity::class.java)

    @Test fun pingMonitor_starts() = assertStarts(PingMonitorActivity::class.java)

    @Test fun siteBrowser_starts() = assertStarts(SiteBrowserActivity::class.java)

    @Test fun compass_starts() = assertStarts(MainActivity::class.java)

    private fun assertStarts(activity: Class<out AppCompatActivity>) {
        Robolectric.buildActivity(activity).create().start().get()
    }
}
