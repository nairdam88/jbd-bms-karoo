package com.jbdbms.karoo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BmsSettingsTest {

    @Test
    fun `defaults favour long battery life`() {
        val s = BmsSettings()

        assertEquals(1_000L, s.pollIntervalVisibleMs)
        assertEquals(2_000L, s.pollIntervalBackgroundMs)
        assertTrue(s.pauseWhenIdle)
        assertTrue(s.disconnectWhenIdle)
        assertTrue(s.autoReconnect)
        assertTrue(s.lowPowerScan)
        assertTrue(s.lowPowerConnection)
        assertEquals(200, s.rawBufferSize)
    }

    @Test
    fun `raw buffer size is clamped to a sane range`() {
        assertEquals(20, BmsSettings.clampRawBuffer(0))
        assertEquals(20, BmsSettings.clampRawBuffer(-5))
        assertEquals(1_000, BmsSettings.clampRawBuffer(100_000))
        assertEquals(200, BmsSettings.clampRawBuffer(200))
    }
}
