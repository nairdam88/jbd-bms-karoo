package com.jbdbms.karoo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BmsPresetTest {

    @Test
    fun `battery saver slows polling and keeps low power modes`() {
        val result = BmsPreset.BATTERY_SAVER.applyTo(BmsSettings())

        assertEquals(2_000L, result.pollIntervalVisibleMs)
        assertEquals(5_000L, result.pollIntervalBackgroundMs)
        assertEquals(true, result.lowPowerScan)
        assertEquals(true, result.lowPowerConnection)
    }

    @Test
    fun `fast preset increases polling and disables low power modes`() {
        val result = BmsPreset.FAST.applyTo(BmsSettings())

        assertEquals(500L, result.pollIntervalVisibleMs)
        assertEquals(1_000L, result.pollIntervalBackgroundMs)
        assertFalse(result.lowPowerScan)
        assertFalse(result.lowPowerConnection)
    }

    @Test
    fun `apply keeps unrelated preferences untouched`() {
        val original = BmsSettings(autoReconnect = false, pauseWhenIdle = false)
        val result = BmsPreset.FAST.applyTo(original)

        assertFalse(result.autoReconnect)
        assertFalse(result.pauseWhenIdle)
    }

    @Test
    fun `preset is detected from settings`() {
        assertEquals(BmsPreset.BALANCED, BmsPreset.of(BmsSettings()))
        assertEquals(
            BmsPreset.BATTERY_SAVER,
            BmsPreset.of(BmsPreset.BATTERY_SAVER.applyTo(BmsSettings())),
        )
        assertEquals(
            BmsPreset.FAST,
            BmsPreset.of(BmsPreset.FAST.applyTo(BmsSettings())),
        )
    }

    @Test
    fun `modified interval is custom`() {
        val custom = BmsSettings(pollIntervalVisibleMs = 1_500L)
        assertEquals(BmsPreset.CUSTOM, BmsPreset.of(custom))
    }

    @Test
    fun `custom preset leaves settings unchanged`() {
        val original = BmsSettings(pollIntervalVisibleMs = 1_234L)
        assertEquals(original, BmsPreset.CUSTOM.applyTo(original))
    }
}
