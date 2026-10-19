package com.jbdbms.karoo.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** What the user needs to fix before the app can talk to the battery. */
enum class BleReadiness {
    READY,
    UNSUPPORTED,
    PERMISSION_MISSING,
    BLUETOOTH_OFF,
    LOCATION_OFF,
}

/**
 * Single source of truth for "can we use Bluetooth right now?".
 *
 * The UI uses this to explain the exact problem instead of showing a generic
 * error, and the BLE client uses it to avoid failed reconnect loops while the
 * user still has to grant access or turn Bluetooth on.
 */
object BleReadinessChecker {

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        if (!hasPermissions(context)) return false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
    }

    fun check(context: Context): BleReadiness {
        val hasBluetoothLe = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val permissions = hasPermissions(context)
        val bluetoothEnabled = permissions && isBluetoothEnabled(context)
        val locationEnabled = permissions && isLocationEnabled(context)
        return readinessFor(
            sdk = Build.VERSION.SDK_INT,
            hasBluetoothLe = hasBluetoothLe,
            hasPermissions = permissions,
            bluetoothEnabled = bluetoothEnabled,
            locationEnabled = locationEnabled,
        )
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    @Suppress("DEPRECATION")
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun readinessFor(
        sdk: Int,
        hasBluetoothLe: Boolean,
        hasPermissions: Boolean,
        bluetoothEnabled: Boolean,
        locationEnabled: Boolean,
    ): BleReadiness = when {
        !hasBluetoothLe -> BleReadiness.UNSUPPORTED
        !hasPermissions -> BleReadiness.PERMISSION_MISSING
        !bluetoothEnabled -> BleReadiness.BLUETOOTH_OFF
        sdk < Build.VERSION_CODES.S && !locationEnabled -> BleReadiness.LOCATION_OFF
        else -> BleReadiness.READY
    }
}
