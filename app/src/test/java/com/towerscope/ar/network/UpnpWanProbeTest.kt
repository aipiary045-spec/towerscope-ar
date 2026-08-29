package com.towerscope.ar.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpWanProbeTest {

  private val sampleDescription = """
    <root>
      <device>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType>
            <controlURL>/igdupnp/control/WANCommonIFC1</controlURL>
          </service>
        </serviceList>
      </device>
    </root>
  """.trimIndent()

  private val sampleSoap = """
    <s:Envelope>
      <s:Body>
        <u:GetCommonLinkPropertiesResponse>
          <NewLayer1DownstreamMaxBitRate>1000000000</NewLayer1DownstreamMaxBitRate>
          <NewLayer1UpstreamMaxBitRate>1000000000</NewLayer1UpstreamMaxBitRate>
          <NewPhysicalLinkStatus>Up</NewPhysicalLinkStatus>
          <NewWANAccessType>DSL</NewWANAccessType>
        </u:GetCommonLinkPropertiesResponse>
      </s:Body>
    </s:Envelope>
  """.trimIndent()

    @Test
    fun parseWanControlUrl_findsControlPath() {
        assertEquals("/igdupnp/control/WANCommonIFC1", UpnpWanProbe.parseWanControlUrl(sampleDescription))
    }

    @Test
    fun parseCommonLinkProperties_mapsGigabit() {
        val info = UpnpWanProbe.parseCommonLinkProperties(sampleSoap, "test")
        assertNotNull(info)
        assertEquals(1_000_000_000L, info?.downstreamBps)
        assertEquals("Up", info?.physicalStatus)
        assertTrue(UpnpWanProbe.format(info!!).contains("1000 Mbps"))
    }
}
