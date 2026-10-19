package com.jbdbms.karoo.ble

/**
 * What the client should do when demand appears (a Karoo data field starts
 * streaming) and there is no live GATT link.
 *
 * The policy is deliberately conservative: only the remembered battery is
 * ever auto-connected, and only while the app UI is not driving the
 * connection. Everything else waits for a manual connect.
 */
internal enum class AutoConnectAction {
    /** Do nothing; either no demand, the UI owns the flow, or auto-connect is off. */
    NONE,

    /** Ask Karoo for the radio and resume once the adapter reports ON. */
    WAIT_FOR_BLUETOOTH,

    /** Scan for the remembered address and connect if it shows up. */
    SCAN_KNOWN,

    /** A BluetoothDevice for the remembered address is still in memory. */
    DIRECT_RECONNECT,
}

internal fun decideAutoConnect(
    consumers: Int,
    uiVisible: Boolean,
    autoReconnect: Boolean,
    suppressed: Boolean,
    knownAddress: String?,
    bluetoothOn: Boolean,
    connected: Boolean,
    hasLiveDevice: Boolean,
): AutoConnectAction {
    if (consumers <= 0 || connected || uiVisible) return AutoConnectAction.NONE
    if (!autoReconnect || suppressed) return AutoConnectAction.NONE
    if (knownAddress.isNullOrBlank()) return AutoConnectAction.NONE
    if (!bluetoothOn) return AutoConnectAction.WAIT_FOR_BLUETOOTH
    return if (hasLiveDevice) {
        AutoConnectAction.DIRECT_RECONNECT
    } else {
        AutoConnectAction.SCAN_KNOWN
    }
}
