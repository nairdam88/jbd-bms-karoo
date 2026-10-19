package com.jbdbms.karoo.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoConnectPolicyTest {

    private fun decide(
        consumers: Int = 1,
        uiVisible: Boolean = false,
        autoReconnect: Boolean = true,
        suppressed: Boolean = false,
        knownAddress: String? = "A5:C2:37:06:A4:4F",
        bluetoothOn: Boolean = true,
        connected: Boolean = false,
        hasLiveDevice: Boolean = false,
    ) = decideAutoConnect(
        consumers = consumers,
        uiVisible = uiVisible,
        autoReconnect = autoReconnect,
        suppressed = suppressed,
        knownAddress = knownAddress,
        bluetoothOn = bluetoothOn,
        connected = connected,
        hasLiveDevice = hasLiveDevice,
    )

    @Test
    fun `no consumers does nothing`() {
        assertEquals(AutoConnectAction.NONE, decide(consumers = 0))
    }

    @Test
    fun `visible UI owns the connection flow`() {
        assertEquals(AutoConnectAction.NONE, decide(uiVisible = true))
    }

    @Test
    fun `already connected does nothing`() {
        assertEquals(AutoConnectAction.NONE, decide(connected = true))
    }

    @Test
    fun `auto reconnect disabled does nothing`() {
        assertEquals(AutoConnectAction.NONE, decide(autoReconnect = false))
    }

    @Test
    fun `suppressed after a failed attempt does nothing`() {
        assertEquals(AutoConnectAction.NONE, decide(suppressed = true))
    }

    @Test
    fun `no known device does nothing`() {
        assertEquals(AutoConnectAction.NONE, decide(knownAddress = null))
        assertEquals(AutoConnectAction.NONE, decide(knownAddress = " "))
    }

    @Test
    fun `radio off asks the host first`() {
        assertEquals(AutoConnectAction.WAIT_FOR_BLUETOOTH, decide(bluetoothOn = false))
    }

    @Test
    fun `live device reconnects directly`() {
        assertEquals(AutoConnectAction.DIRECT_RECONNECT, decide(hasLiveDevice = true))
    }

    @Test
    fun `cold start scans for the known address`() {
        assertEquals(AutoConnectAction.SCAN_KNOWN, decide())
    }
}
