package com.jbdbms.karoo.data

/**
 * User-tunable runtime behaviour, tuned for low battery and low memory.
 *
 * All intervals are in milliseconds. Defaults favour drawing as little power as
 * possible: relaxed background polling, demand-driven polling, low-power BLE,
 * and a bounded set of reconnect attempts.
 */
data class BmsSettings(
    /** Poll cadence while the app UI is visible and being looked at. */
    val pollIntervalVisibleMs: Long = 1_000L,
    /** Poll cadence while running in the background for Karoo ride-screen fields. */
    val pollIntervalBackgroundMs: Long = 2_000L,
    /** Stop polling once nothing consumes data (no Karoo stream and no visible UI). */
    val pauseWhenIdle: Boolean = true,
    /** How long to keep polling after the last consumer disappears before pausing. */
    val idleGraceMs: Long = 10_000L,
    /** Drop the BLE link after a long idle period to let the radio settle. */
    val disconnectWhenIdle: Boolean = true,
    /** How long to stay connected while completely idle before disconnecting. */
    val idleDisconnectMs: Long = 300_000L,
    /** Reconnect automatically (by stored address, no scanning) when needed. */
    val autoReconnect: Boolean = true,
    /** Maximum consecutive reconnect attempts before giving up until a manual connect. */
    val reconnectMaxRetries: Int = 6,
    /** First backoff delay for reconnect attempts (doubles each time). */
    val reconnectBaseDelayMs: Long = 2_000L,
    /** Upper bound for the reconnect backoff delay. */
    val reconnectMaxDelayMs: Long = 60_000L,
    /** How long a single connect attempt may take before it is considered failed. */
    val connectTimeoutMs: Long = 15_000L,
    /** Use low-power (slower) BLE scanning and auto-stop after [scanTimeoutMs]. */
    val lowPowerScan: Boolean = true,
    /** Auto-stop duration for an active scan. */
    val scanTimeoutMs: Long = 12_000L,
    /**
     * Request a low-power BLE connection interval after connecting. Off by
     * default because the slower interval can trip the link supervision
     * timeout and drop the battery on a weak signal.
     */
    val lowPowerConnection: Boolean = false,
    /** Send the speculative FF/AA password handshake when no data arrives. */
    val authFallbackEnabled: Boolean = false,
    /** Ask Android to bond before connecting (Karoo BLE workaround). */
    val tryBonding: Boolean = true,
    /** Keep the link/service alive when the app is swiped away. */
    val keepConnectedWhenClosed: Boolean = true,
    /** Maximum number of raw protocol frames retained for the in-app console. */
    val rawBufferSize: Int = 200,
) {
    companion object {
        /** Clamp raw buffer sizes into a sane range so a bad value cannot bloat memory. */
        fun clampRawBuffer(size: Int): Int = size.coerceIn(20, 1_000)
    }
}
