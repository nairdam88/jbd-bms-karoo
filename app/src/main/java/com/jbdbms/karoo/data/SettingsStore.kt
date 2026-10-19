package com.jbdbms.karoo.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists [BmsSettings] in [SharedPreferences] (no extra dependency) and exposes
 * them as a [StateFlow] so the BLE client and UI react to changes live.
 */
object SettingsStore {

    private const val PREFS = "bms_settings"

    private const val K_POLL_VISIBLE = "poll_interval_visible_ms"
    private const val K_POLL_BACKGROUND = "poll_interval_background_ms"
    private const val K_PAUSE_WHEN_IDLE = "pause_when_idle"
    private const val K_IDLE_GRACE = "idle_grace_ms"
    private const val K_DISCONNECT_WHEN_IDLE = "disconnect_when_idle"
    private const val K_IDLE_DISCONNECT = "idle_disconnect_ms"
    private const val K_AUTO_RECONNECT = "auto_reconnect"
    private const val K_RECONNECT_RETRIES = "reconnect_max_retries"
    private const val K_LOW_POWER_SCAN = "low_power_scan"
    private const val K_SCAN_TIMEOUT = "scan_timeout_ms"
    private const val K_LOW_POWER_CONNECTION = "low_power_connection"
    private const val K_AUTH_FALLBACK = "auth_fallback_enabled"
    // v2 so the short-lived 0.2.6 default (true) is not carried over.
    private const val K_TRY_BONDING = "try_bonding_v2"
    private const val K_KEEP_CONNECTED = "keep_connected_when_closed"
    private const val K_RAW_BUFFER = "raw_buffer_size"

    private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(BmsSettings())
    val settings: StateFlow<BmsSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _settings.value = load(p)
    }

    fun update(transform: (BmsSettings) -> BmsSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        persist(next)
    }

    fun resetToDefaults() = update { BmsSettings() }

    private fun load(p: SharedPreferences): BmsSettings {
        val d = BmsSettings()
        return BmsSettings(
            pollIntervalVisibleMs = p.getLong(K_POLL_VISIBLE, d.pollIntervalVisibleMs),
            pollIntervalBackgroundMs = p.getLong(K_POLL_BACKGROUND, d.pollIntervalBackgroundMs),
            pauseWhenIdle = p.getBoolean(K_PAUSE_WHEN_IDLE, d.pauseWhenIdle),
            idleGraceMs = p.getLong(K_IDLE_GRACE, d.idleGraceMs),
            disconnectWhenIdle = p.getBoolean(K_DISCONNECT_WHEN_IDLE, d.disconnectWhenIdle),
            idleDisconnectMs = p.getLong(K_IDLE_DISCONNECT, d.idleDisconnectMs),
            autoReconnect = p.getBoolean(K_AUTO_RECONNECT, d.autoReconnect),
            reconnectMaxRetries = p.getInt(K_RECONNECT_RETRIES, d.reconnectMaxRetries),
            lowPowerScan = p.getBoolean(K_LOW_POWER_SCAN, d.lowPowerScan),
            scanTimeoutMs = p.getLong(K_SCAN_TIMEOUT, d.scanTimeoutMs),
            lowPowerConnection = p.getBoolean(K_LOW_POWER_CONNECTION, d.lowPowerConnection),
            authFallbackEnabled = p.getBoolean(K_AUTH_FALLBACK, d.authFallbackEnabled),
            tryBonding = p.getBoolean(K_TRY_BONDING, d.tryBonding),
            keepConnectedWhenClosed = p.getBoolean(K_KEEP_CONNECTED, d.keepConnectedWhenClosed),
            rawBufferSize = BmsSettings.clampRawBuffer(p.getInt(K_RAW_BUFFER, d.rawBufferSize)),
        )
    }

    private fun persist(s: BmsSettings) {
        prefs?.edit()
            ?.putLong(K_POLL_VISIBLE, s.pollIntervalVisibleMs)
            ?.putLong(K_POLL_BACKGROUND, s.pollIntervalBackgroundMs)
            ?.putBoolean(K_PAUSE_WHEN_IDLE, s.pauseWhenIdle)
            ?.putLong(K_IDLE_GRACE, s.idleGraceMs)
            ?.putBoolean(K_DISCONNECT_WHEN_IDLE, s.disconnectWhenIdle)
            ?.putLong(K_IDLE_DISCONNECT, s.idleDisconnectMs)
            ?.putBoolean(K_AUTO_RECONNECT, s.autoReconnect)
            ?.putInt(K_RECONNECT_RETRIES, s.reconnectMaxRetries)
            ?.putBoolean(K_LOW_POWER_SCAN, s.lowPowerScan)
            ?.putLong(K_SCAN_TIMEOUT, s.scanTimeoutMs)
            ?.putBoolean(K_LOW_POWER_CONNECTION, s.lowPowerConnection)
            ?.putBoolean(K_AUTH_FALLBACK, s.authFallbackEnabled)
            ?.putBoolean(K_TRY_BONDING, s.tryBonding)
            ?.putBoolean(K_KEEP_CONNECTED, s.keepConnectedWhenClosed)
            ?.putInt(K_RAW_BUFFER, s.rawBufferSize)
            ?.apply()
    }
}
