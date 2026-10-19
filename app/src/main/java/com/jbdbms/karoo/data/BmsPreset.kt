package com.jbdbms.karoo.data

/**
 * User-facing update speed presets.
 *
 * Internals like polling intervals and BLE power modes stay out of the main
 * settings screen; the presets bundle them into choices people understand.
 */
enum class BmsPreset {
    BATTERY_SAVER,
    BALANCED,
    FAST,
    CUSTOM;

    fun applyTo(settings: BmsSettings): BmsSettings = when (this) {
        BATTERY_SAVER -> settings.copy(
            pollIntervalVisibleMs = 2_000L,
            pollIntervalBackgroundMs = 5_000L,
            lowPowerScan = true,
            lowPowerConnection = true,
        )
        BALANCED -> settings.copy(
            pollIntervalVisibleMs = 1_000L,
            pollIntervalBackgroundMs = 2_000L,
            lowPowerScan = true,
            lowPowerConnection = true,
        )
        FAST -> settings.copy(
            pollIntervalVisibleMs = 500L,
            pollIntervalBackgroundMs = 1_000L,
            lowPowerScan = false,
            lowPowerConnection = false,
        )
        CUSTOM -> settings
    }

    companion object {
        fun of(settings: BmsSettings): BmsPreset = when {
            settings.pollIntervalVisibleMs == 2_000L &&
                settings.pollIntervalBackgroundMs == 5_000L &&
                settings.lowPowerScan &&
                settings.lowPowerConnection -> BATTERY_SAVER
            settings.pollIntervalVisibleMs == 1_000L &&
                settings.pollIntervalBackgroundMs == 2_000L &&
                settings.lowPowerScan &&
                settings.lowPowerConnection -> BALANCED
            settings.pollIntervalVisibleMs == 500L &&
                settings.pollIntervalBackgroundMs == 1_000L &&
                !settings.lowPowerScan &&
                !settings.lowPowerConnection -> FAST
            else -> CUSTOM
        }
    }
}
