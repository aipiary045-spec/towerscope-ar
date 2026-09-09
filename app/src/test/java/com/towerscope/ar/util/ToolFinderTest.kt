package com.towerscope.ar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFinderTest {

    private val tools = listOf(
        ToolIndex("wifi", ToolGroup.NETWORK, "Wi-Fi signal live channel overlap rssi"),
        ToolIndex("speed", ToolGroup.NETWORK, "Speed test download upload"),
        ToolIndex("ping", ToolGroup.NETWORK, "Ping & loss ICMP latency"),
        ToolIndex("diagnose", ToolGroup.NETWORK, "Path Doctor link dns tls http"),
        ToolIndex("compass", ToolGroup.INSTALL, "Compass high-precision bearings aim"),
        ToolIndex("locate", ToolGroup.INSTALL, "Locate pin satellite map"),
        ToolIndex("los", ToolGroup.INSTALL, "Line of sight clearance fresnel"),
        ToolIndex("sites", ToolGroup.INSTALL, "Sites import KML KMZ CSV")
    )

    @Test
    fun allFocusShowsEveryToolWhenQueryEmpty() {
        val visible = ToolFinder.visible(tools, ToolFocus.ALL, "")
        assertEquals(tools.map { it.id }, visible.map { it.id })
    }

    @Test
    fun networkFocusHidesInstallTools() {
        val visible = ToolFinder.visible(tools, ToolFocus.NETWORK, "  ")
        assertEquals(listOf("wifi", "speed", "ping", "diagnose"), visible.map { it.id })
    }

    @Test
    fun installFocusHidesNetworkTools() {
        val visible = ToolFinder.visible(tools, ToolFocus.INSTALL, "")
        assertEquals(listOf("compass", "locate", "los", "sites"), visible.map { it.id })
    }

    @Test
    fun searchNarrowsTheCurrentTab() {
        val onToolsTab = ToolFinder.visible(tools, ToolFocus.ALL, "compass")
        assertEquals(listOf("compass"), onToolsTab.map { it.id })

        val onNetworkTab = ToolFinder.visible(tools, ToolFocus.NETWORK, "ping")
        assertEquals(listOf("ping"), onNetworkTab.map { it.id })

        val compassHiddenOnNetwork = ToolFinder.visible(tools, ToolFocus.NETWORK, "compass")
        assertTrue(compassHiddenOnNetwork.isEmpty())
    }

    @Test
    fun searchIsCaseInsensitiveAndMatchesKeywords() {
        val visible = ToolFinder.visible(tools, ToolFocus.ALL, "FRESNEL")
        assertEquals(listOf("los"), visible.map { it.id })
    }

    @Test
    fun unknownQueryYieldsEmpty() {
        val visible = ToolFinder.visible(tools, ToolFocus.ALL, "not-a-tool")
        assertTrue(visible.isEmpty())
    }

    @Test
    fun parseFocusReadsNavExtras() {
        assertEquals(ToolFocus.ALL, ToolFinder.parseFocus(null))
        assertEquals(ToolFocus.ALL, ToolFinder.parseFocus("all"))
        assertEquals(ToolFocus.NETWORK, ToolFinder.parseFocus("network"))
        assertEquals(ToolFocus.INSTALL, ToolFinder.parseFocus("INSTALL"))
    }
}
