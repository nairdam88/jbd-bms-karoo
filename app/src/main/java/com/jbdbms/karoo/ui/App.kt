package com.jbdbms.karoo.ui

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jbdbms.karoo.ble.BleReadiness
import com.jbdbms.karoo.ble.BleReadinessChecker
import com.jbdbms.karoo.ble.ConnectionState
import com.jbdbms.karoo.data.BmsRepository
import com.jbdbms.karoo.data.DeviceStore
import com.jbdbms.karoo.data.ScanResult
import com.jbdbms.karoo.protocol.BmsData
import com.jbdbms.karoo.service.BmsForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var readiness by remember { mutableStateOf(BleReadinessChecker.check(context)) }
    var showSettings by remember { mutableStateOf(false) }

    val connection by BmsRepository.connectionState.collectAsStateWithLifecycle()
    val data by BmsRepository.data.collectAsStateWithLifecycle()
    val scanResults by BmsRepository.scanResults.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val state = BleReadinessChecker.check(context)
        readiness = state
        if (state == BleReadiness.READY) startConnectionOrScan(context)
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val state = BleReadinessChecker.check(context)
        readiness = state
        if (state == BleReadiness.READY) startConnectionOrScan(context)
    }

    var askedAutomatically by rememberSaveable { mutableStateOf(false) }
    var previousReadiness by remember { mutableStateOf<BleReadiness?>(null) }

    LaunchedEffect(Unit) {
        val state = BleReadinessChecker.check(context)
        readiness = state
        previousReadiness = state
        if (state == BleReadiness.PERMISSION_MISSING && !askedAutomatically) {
            askedAutomatically = true
            permissionLauncher.launch(BleReadinessChecker.requiredPermissions())
        }
        // On Karoo the radio is normally off; ask the host to hold it for us.
        if (state == BleReadiness.BLUETOOTH_OFF) BmsRepository.requestBluetooth()
    }

    // Bluetooth can still be turning on when the app launches; re-check briefly
    // instead of showing "Bluetooth is off" until the next resume.
    LaunchedEffect(readiness) {
        if (readiness != BleReadiness.BLUETOOTH_OFF) return@LaunchedEffect
        repeat(3) {
            delay(700)
            val state = BleReadinessChecker.check(context)
            if (state != BleReadiness.BLUETOOTH_OFF) {
                readiness = state
                if (state == BleReadiness.READY) startConnectionOrScan(context)
                return@LaunchedEffect
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val state = BleReadinessChecker.check(context)
        readiness = state
        if (state == BleReadiness.READY) {
            BmsRepository.client().clearError()
            if (previousReadiness != BleReadiness.READY) startConnectionOrScan(context)
        }
        previousReadiness = state
        if (state == BleReadiness.BLUETOOTH_OFF) BmsRepository.requestBluetooth()
    }

    if (showSettings) {
        SettingsScreen(onClose = { showSettings = false })
        return
    }

    MainScreen(
        readiness = readiness,
        connection = connection,
        data = data,
        scanResults = scanResults,
        onScan = { BmsRepository.client().startScan() },
        onConnect = { result ->
            BmsRepository.client().connect(result.address)
            BmsForegroundService.start(context)
        },
        onDisconnect = {
            BmsRepository.client().disconnect()
            BmsForegroundService.stop(context)
        },
        onSettings = { showSettings = true },
        onAllowAccess = {
            permissionLauncher.launch(BleReadinessChecker.requiredPermissions())
        },
        onOpenAppSettings = { openAppSettings(context) },
        onOpenPrivacySettings = { openPrivacySettings(context) },
        onEnableBluetooth = {
            // Ask Karoo OS first; the system dialog is only a fallback.
            BmsRepository.requestBluetooth()
            scope.launch {
                delay(1_500)
                if (BleReadinessChecker.check(context) == BleReadiness.BLUETOOTH_OFF) {
                    try {
                        bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } catch (_: ActivityNotFoundException) {
                        openBluetoothSettings(context)
                    }
                }
            }
        },
        onOpenBluetoothSettings = { openBluetoothSettings(context) },
        onOpenLocationSettings = { openLocationSettings(context) },
    )
}

@Composable
private fun MainScreen(
    readiness: BleReadiness,
    connection: ConnectionState,
    data: BmsData,
    scanResults: List<ScanResult>,
    onScan: () -> Unit,
    onConnect: (ScanResult) -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
    onAllowAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Header(
            readiness = readiness,
            connection = connection,
            data = data,
            onSettings = onSettings,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        Box(modifier = Modifier.weight(1f)) {
            when {
                readiness != BleReadiness.READY -> SetupCard(
                    readiness = readiness,
                    onAllowAccess = onAllowAccess,
                    onOpenAppSettings = onOpenAppSettings,
                    onOpenPrivacySettings = onOpenPrivacySettings,
                    onEnableBluetooth = onEnableBluetooth,
                    onOpenBluetoothSettings = onOpenBluetoothSettings,
                    onOpenLocationSettings = onOpenLocationSettings,
                )
                connection == ConnectionState.CONNECTED ||
                    connection == ConnectionState.AUTHENTICATING -> Dashboard(
                    data = data,
                    onDisconnect = onDisconnect,
                )
                else -> ScanContent(
                    connection = connection,
                    results = scanResults,
                    onScan = onScan,
                    onConnect = onConnect,
                )
            }
        }
    }
}

@Composable
private fun Header(
    readiness: BleReadiness,
    connection: ConnectionState,
    data: BmsData,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "JBD BMS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = statusLine(readiness, connection, data),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onSettings) { Text("Settings") }
    }
}

private fun statusLine(
    readiness: BleReadiness,
    connection: ConnectionState,
    data: BmsData,
): String = when (readiness) {
    BleReadiness.PERMISSION_MISSING -> "Bluetooth access needed"
    BleReadiness.BLUETOOTH_OFF -> "Bluetooth is off"
    BleReadiness.LOCATION_OFF -> "Location is off"
    BleReadiness.UNSUPPORTED -> "Bluetooth LE not supported"
    BleReadiness.READY -> when (connection) {
        ConnectionState.DISCONNECTED -> "Not connected"
        ConnectionState.SCANNING -> "Looking for your battery…"
        ConnectionState.CONNECTING -> "Connecting…"
        ConnectionState.AUTHENTICATING -> "Finishing setup…"
        ConnectionState.CONNECTED -> buildString {
            append("Connected")
            data.deviceName?.let { append(" · $it") }
            data.socPercent?.let { append(" · $it%") }
        }
        ConnectionState.ERROR -> "Couldn't scan"
        ConnectionState.CONNECT_FAILED -> "Couldn't connect to the battery"
    }
}

@Composable
private fun SetupCard(
    readiness: BleReadiness,
    onAllowAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            when (readiness) {
                BleReadiness.PERMISSION_MISSING -> {
                    CardTitle("Allow battery access")
                    CardBody(
                        "JBD BMS needs the Nearby devices permission to find and connect " +
                            "to your battery. If no prompt appears, allow it in App settings.",
                    )
                    Button(
                        onClick = onAllowAccess,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow access") }
                    Row {
                        TextButton(onClick = onOpenAppSettings) { Text("App settings") }
                        TextButton(onClick = onOpenPrivacySettings) { Text("Privacy settings") }
                    }
                }

                BleReadiness.BLUETOOTH_OFF -> {
                    CardTitle("Turn on Bluetooth")
                    CardBody("Bluetooth is off. Turn it on to connect to your battery.")
                    Button(
                        onClick = onEnableBluetooth,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Turn on Bluetooth") }
                    TextButton(onClick = onOpenBluetoothSettings) { Text("Bluetooth settings") }
                }

                BleReadiness.LOCATION_OFF -> {
                    CardTitle("Turn on location")
                    CardBody(
                        "Android needs location services turned on to scan for Bluetooth " +
                            "devices on this phone.",
                    )
                    Button(
                        onClick = onOpenLocationSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open location settings") }
                }

                BleReadiness.UNSUPPORTED -> {
                    CardTitle("Not supported")
                    CardBody(
                        "This phone does not support Bluetooth LE, which is required to " +
                            "talk to the battery.",
                    )
                }

                BleReadiness.READY -> Unit
            }
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CardBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ScanContent(
    connection: ConnectionState,
    results: List<ScanResult>,
    onScan: () -> Unit,
    onConnect: (ScanResult) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = onScan,
            enabled = connection != ConnectionState.SCANNING,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(if (connection == ConnectionState.SCANNING) "Scanning…" else "Scan for battery")
        }
        Spacer(Modifier.height(12.dp))

        when {
            connection == ConnectionState.ERROR -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Couldn't start scanning", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Make sure Bluetooth is on and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            connection == ConnectionState.CONNECT_FAILED -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Couldn't reach the battery", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "It connected before but dropped. Make sure the battery is on and " +
                            "close any other app using it, then tap Connect again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            connection == ConnectionState.SCANNING && results.isEmpty() -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Looking for your battery…")
            }

            results.isEmpty() -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Before you scan", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("• Turn the battery on and keep it close.")
                    Text("• Close any other app connected to the battery.")
                    Text("• Tap Scan for battery.")
                }
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.address }) { result ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    result.name ?: "Battery found",
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(signalLabel(result.rssi), style = MaterialTheme.typography.bodySmall)
                                Text(result.address, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = { onConnect(result) },
                                enabled = connection != ConnectionState.CONNECTING,
                            ) { Text(if (connection == ConnectionState.CONNECTING) "…" else "Connect") }
                        }
                    }
                }
            }
        }
    }
}

private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Strong signal"
    rssi >= -75 -> "Good signal"
    else -> "Weak signal"
}

private fun signalText(rssi: Int?): String = when {
    rssi == null -> "–"
    rssi >= -60 -> "Strong"
    rssi >= -75 -> "Good"
    else -> "Weak"
}

@Composable
private fun Dashboard(data: BmsData, onDisconnect: () -> Unit) {
    var showDetails by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Battery level", style = MaterialTheme.typography.labelLarge)
                    Text(
                        data.socPercent?.let { "$it%" } ?: "–",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column {
                    Text(
                        batteryStatus(data),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        data.packVoltageV?.let { "%.2f V".format(it) } ?: "–",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Voltage", data.packVoltageV?.let { "%.2f V".format(it) } ?: "–", Modifier.weight(1f))
            Metric("Current", data.currentA?.let { "%.2f A".format(it) } ?: "–", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Power", data.powerW?.let { "%.1f W".format(it) } ?: "–", Modifier.weight(1f))
            Metric("Remaining", data.remainingCapacityAh?.let { "%.2f Ah".format(it) } ?: "–", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Full capacity", data.fullCapacityAh?.let { "%.2f Ah".format(it) } ?: "–", Modifier.weight(1f))
            Metric("Cycles", data.cycleCount?.toString() ?: "–", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Signal", signalText(data.rssi), Modifier.weight(1f))
            Metric("Cells", if (data.cells.isEmpty()) "–" else data.cells.size.toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "Hide battery details" else "Show battery details")
        }
        if (showDetails) {
            BatteryDetails(data)
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Disconnect") }
        Spacer(Modifier.height(12.dp))
    }
}

private fun batteryStatus(data: BmsData): String {
    val current = data.currentA
    return when {
        data.chargeFetOn == false && data.dischargeFetOn == false -> "Battery disabled"
        current == null -> "Waiting for data…"
        current > 0.05 -> "Charging"
        current < -0.05 -> "Discharging"
        else -> "Idle"
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun BatteryDetails(data: BmsData) {
    Column {
        SectionTitle("Status")
        Text(
            buildString {
                append("Charging allowed: ${yesNo(data.chargeFetOn)}")
                append("   Discharging allowed: ${yesNo(data.dischargeFetOn)}")
            },
        )
        if (data.manufactureDate != null || data.swVersion != null) {
            Text(
                listOfNotNull(
                    data.swVersion?.let { "Firmware $it" },
                    data.manufactureDate?.let { "Manufactured $it" },
                ).joinToString("   "),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (data.temperaturesC.isNotEmpty()) {
            SectionTitle("Temperatures")
            Text(
                data.temperaturesC.mapIndexed { i, t -> "Sensor ${i + 1}: %.1f°C".format(t) }
                    .joinToString("   "),
            )
        }

        SectionTitle("Protection")
        if (data.errorFlags.isEmpty()) {
            Text("No protection alerts")
        } else {
            Text(data.errorFlags.joinToString(", "), color = MaterialTheme.colorScheme.error)
        }

        if (data.balancingCells.isNotEmpty()) {
            SectionTitle("Balancing")
            Text("Cells ${data.balancingCells.joinToString(", ")}")
        }

        SectionTitle("Cells (${data.cells.size})")
        CellGrid(data)
    }
}

private fun yesNo(value: Boolean?): String = when (value) {
    true -> "Yes"
    false -> "No"
    null -> "–"
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CellGrid(data: BmsData) {
    if (data.cells.isEmpty()) {
        Text("Waiting for cell data…")
        return
    }
    val minIdx = data.cellMinIndex
    val maxIdx = data.cellMaxIndex
    val balancing = data.balancingCells.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        data.cells.chunked(4).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { colIndex, voltage ->
                    val index = rowIndex * 4 + colIndex
                    val color = when {
                        index == maxIdx -> MaterialTheme.colorScheme.primaryContainer
                        index == minIdx -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(color, RoundedCornerShape(6.dp))
                            .padding(6.dp),
                    ) {
                        Text(
                            "Cell ${index + 1}" + if (balancing.contains(index + 1)) " ●" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text("%.3f V".format(voltage), style = MaterialTheme.typography.bodySmall)
                    }
                }
                repeat(4 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Reconnects to the last battery if we know one, otherwise starts a fresh scan. */
private fun startConnectionOrScan(context: Context) {
    BmsRepository.client().clearError()
    if (BmsRepository.connectionState.value != ConnectionState.DISCONNECTED) return
    val address = DeviceStore.lastAddress.value
    // Without a live BluetoothDevice the stored address loses its BLE address
    // type, so scan to rediscover the battery instead of guessing.
    if (address != null && BmsRepository.client().hasLiveDevice()) {
        BmsRepository.client().connect(address)
        BmsForegroundService.start(context)
    } else {
        BmsRepository.client().startScan()
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    startSafely(context, intent)
}

private fun openPrivacySettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_PRIVACY_SETTINGS)
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
    }
    startSafely(context, intent) { openAppSettings(context) }
}

private fun openBluetoothSettings(context: Context) {
    startSafely(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) {
        openAppSettings(context)
    }
}

private fun openLocationSettings(context: Context) {
    startSafely(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) {
        openAppSettings(context)
    }
}

private fun startSafely(
    context: Context,
    intent: Intent,
    fallback: (() -> Unit)? = null,
) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        fallback?.invoke()
    } catch (_: SecurityException) {
        fallback?.invoke()
    }
}
