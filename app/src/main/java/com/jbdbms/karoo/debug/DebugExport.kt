package com.jbdbms.karoo.debug

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.jbdbms.karoo.BuildConfig
import com.jbdbms.karoo.ble.BleReadinessChecker
import com.jbdbms.karoo.data.BmsRepository
import com.jbdbms.karoo.data.Direction
import com.jbdbms.karoo.data.SettingsStore
import com.jbdbms.karoo.protocol.JbdCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class DebugReport(
    val generatedAt: String,
    val appVersion: String,
    val appCode: Int,
    val androidRelease: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val readiness: String,
    val adapter: String,
    val settings: String,
    val link: String,
    val hold: String,
    val gattLinks: String,
    val scans: List<String>,
    val log: List<String>,
    val raw: List<String>,
)

/**
 * Builds a plain-text snapshot of everything needed to diagnose a Karoo/BLE
 * failure. Designed to be copied or served over the local Wi-Fi export.
 */
object DebugExport {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun build(context: Context): String {
        val settings = SettingsStore.settings.value
        val initialized = BmsRepository.isInitialized()
        val client = if (initialized) BmsRepository.client() else null
        val link = client?.linkInfo()
        val hold = client?.holdInfo()
        val scans = if (initialized) BmsRepository.scanResults.value else emptyList()
        val log = if (initialized) BmsRepository.connectionLog.value else emptyList()
        val raw = if (initialized) BmsRepository.rawFrames.value else emptyList()

        val settingsText = buildString {
            append("pollVisible=${settings.pollIntervalVisibleMs}ms")
            append(", pollBackground=${settings.pollIntervalBackgroundMs}ms")
            append(", pauseWhenIdle=${settings.pauseWhenIdle}")
            append(", autoReconnect=${settings.autoReconnect}")
            append(", lowPowerScan=${settings.lowPowerScan}")
            append(", lowPowerConnection=${settings.lowPowerConnection}")
            append(", authFallback=${settings.authFallbackEnabled}")
            append(", tryBonding=${settings.tryBonding}")
            append(", keepConnected=${settings.keepConnectedWhenClosed}")
        }

        val linkText = if (link == null) {
            "not initialized"
        } else {
            "requested=${link.requestedAddress ?: "-"} " +
                "connected=${link.connectedAddress ?: "-"} " +
                "type=${link.deviceType ?: "-"} " +
                "bond=${bondLabel(link.bondState)}"
        }

        val holdText = if (hold == null) {
            "not initialized"
        } else {
            "wanted=${hold.wanted} held=${hold.held} " +
                "consumers=${hold.consumers} uiVisible=${hold.uiVisible} " +
                "scanning=${hold.scanning} pending=${hold.pending} " +
                "autoSuppressed=${hold.autoSuppressed} " +
                "autoAddress=${hold.autoAddress ?: "-"} " +
                "known=${hold.knownAddress ?: "-"}"
        }

        return format(
            DebugReport(
                generatedAt = timeFormat.format(Date()),
                appVersion = BuildConfig.VERSION_NAME,
                appCode = BuildConfig.VERSION_CODE,
                androidRelease = Build.VERSION.RELEASE ?: "?",
                sdk = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER ?: "?",
                model = Build.MODEL ?: "?",
                readiness = BleReadinessChecker.check(context).name,
                adapter = adapterState(context),
                settings = settingsText,
                link = linkText,
                hold = holdText,
                gattLinks = gattLinks(context),
                scans = scans.map { "${it.name ?: "?"} ${it.address} rssi=${it.rssi}" },
                log = log.map { "${logTimeFormat.format(Date(it.timestampMs))} ${it.message}" },
                raw = raw.takeLast(100).map { frame ->
                    val arrow = if (frame.direction == Direction.TX) "TX" else "RX"
                    "$arrow ${JbdCodec.toHex(frame.bytes)} ${frame.note}"
                },
            ),
        )
    }

    internal fun format(report: DebugReport): String = buildString {
        appendLine("=== JBD BMS Karoo debug ===")
        appendLine("Generated: ${report.generatedAt}")
        appendLine("App: ${report.appVersion} (${report.appCode})")
        appendLine("Android: ${report.androidRelease} (SDK ${report.sdk})")
        appendLine("Device: ${report.manufacturer} ${report.model}")
        appendLine("Readiness: ${report.readiness}")
        appendLine("Bluetooth adapter: ${report.adapter}")
        appendLine()
        appendLine("--- Settings ---")
        appendLine(report.settings)
        appendLine()
        appendLine("--- GATT target ---")
        appendLine(report.link)
        appendLine()
        appendLine("--- Bluetooth hold ---")
        appendLine(report.hold)
        appendLine("System GATT links: ${report.gattLinks}")
        appendLine()
        appendLine("--- Scan results (${report.scans.size}) ---")
        report.scans.forEach { appendLine(it) }
        appendLine()
        appendLine("--- Connection log (${report.log.size}) ---")
        report.log.forEach { appendLine(it) }
        if (report.raw.isNotEmpty()) {
            appendLine()
            appendLine("--- Raw frames (last ${report.raw.size}) ---")
            report.raw.forEach { appendLine(it) }
        }
    }

    /** Other BLE links the system reports, useful for coexistence checks. */
    private fun gattLinks(context: Context): String {
        val manager = try {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        } catch (_: SecurityException) {
            null
        } ?: return "unavailable"
        return try {
            manager.getConnectedDevices(BluetoothProfile.GATT)
                .joinToString(", ") { it.address }
                .ifEmpty { "none" }
        } catch (_: SecurityException) {
            "permission"
        } catch (_: Exception) {
            "unavailable"
        }
    }

    private fun adapterState(context: Context): String {
        val adapter = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (_: SecurityException) {
            null
        } ?: return "unavailable"
        return try {
            val state = when (adapter.state) {
                BluetoothAdapter.STATE_OFF -> "OFF"
                BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                BluetoothAdapter.STATE_ON -> "ON"
                BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                else -> "state=${adapter.state}"
            }
            val hasLe = context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE,
            )
            "$state enabled=${adapter.isEnabled} le=$hasLe"
        } catch (_: SecurityException) {
            "permission denied"
        }
    }

    private fun bondLabel(state: Int?): String = when (state) {
        null -> "-"
        BluetoothDevice.BOND_NONE -> "none"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_BONDED -> "bonded"
        else -> "state=$state"
    }
}
