package com.jbdbms.karoo.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleReadinessTest {

    private fun readiness(
        sdk: Int = 31,
        hasBluetoothLe: Boolean = true,
        hasPermissions: Boolean = true,
        bluetoothEnabled: Boolean = true,
        locationEnabled: Boolean = true,
    ) = BleReadinessChecker.readinessFor(
        sdk = sdk,
        hasBluetoothLe = hasBluetoothLe,
        hasPermissions = hasPermissions,
        bluetoothEnabled = bluetoothEnabled,
        locationEnabled = locationEnabled,
    )

    @Test
    fun `unsupported phone is reported first`() {
        assertEquals(
            BleReadiness.UNSUPPORTED,
            readiness(hasBluetoothLe = false, hasPermissions = false, bluetoothEnabled = false),
        )
    }

    @Test
    fun `missing permission is reported even when bluetooth is off`() {
        assertEquals(
            BleReadiness.PERMISSION_MISSING,
            readiness(hasPermissions = false, bluetoothEnabled = false),
        )
    }

    @Test
    fun `bluetooth off is reported when permission is granted`() {
        assertEquals(BleReadiness.BLUETOOTH_OFF, readiness(bluetoothEnabled = false))
    }

    @Test
    fun `location off blocks scanning on older android`() {
        assertEquals(
            BleReadiness.LOCATION_OFF,
            readiness(sdk = 30, locationEnabled = false),
        )
    }

    @Test
    fun `location is irrelevant on android 12 and newer`() {
        assertEquals(BleReadiness.READY, readiness(sdk = 31, locationEnabled = false))
    }

    @Test
    fun `ready when everything is available`() {
        assertEquals(BleReadiness.READY, readiness())
    }
}
