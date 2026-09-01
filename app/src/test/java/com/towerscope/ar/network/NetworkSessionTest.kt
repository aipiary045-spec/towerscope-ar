package com.towerscope.ar.network

import android.content.Context
import com.towerscope.ar.TowerScopeApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TowerScopeApp::class, qualifiers = "en-rUS")
class NetworkSessionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("network_session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun recordQuickSpeed_persistsDownloadAndLatency() {
        NetworkSession.recordQuickSpeed(context, 120.0, 18.0)
        val snapshot = NetworkSession.lastSpeedSnapshot(context)
        assertNotNull(snapshot)
        assertEquals(120f, snapshot!!.downloadMbps, 0.1f)
        assertEquals(18f, snapshot.latencyMs, 0.1f)
        assertTrue(NetworkSession.isLastSpeedQuick(context))
    }

    @Test
    fun recordLivePing_writesSummary() {
        NetworkSession.recordLivePing(
            context = context,
            host = "1.1.1.1",
            avgMs = 24.0,
            jitterMs = 3.0,
            lossPercent = 0.0,
            sampleCount = 5
        )
        val summary = NetworkSession.livePingSummary(context)
        assertNotNull(summary)
        assertTrue(summary!!.contains("1.1.1.1"))
        assertTrue(summary.contains("24"))
    }

    @Test
    fun fullSpeedTest_clearsQuickFlag() {
        NetworkSession.recordQuickSpeed(context, 50.0, 20.0)
        NetworkSession.recordSpeedTest(context, 200.0, 40.0, 15.0)
        assertTrue(!NetworkSession.isLastSpeedQuick(context))
    }
}
