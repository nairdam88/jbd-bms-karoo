package com.jbdbms.karoo.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.jbdbms.karoo.data.BmsSettings
import com.jbdbms.karoo.data.DeviceStore
import com.jbdbms.karoo.data.Direction
import com.jbdbms.karoo.data.RawFrame
import com.jbdbms.karoo.data.RawFrameBuffer
import com.jbdbms.karoo.data.SettingsStore
import com.jbdbms.karoo.protocol.BasicInfoDecoder
import com.jbdbms.karoo.protocol.BmsData
import com.jbdbms.karoo.protocol.CellVoltageDecoder
import com.jbdbms.karoo.protocol.JbdCodec
import com.jbdbms.karoo.protocol.JbdFrameParser
import com.jbdbms.karoo.data.ScanResult as DeviceScanResult
import com.jbdbms.karoo.service.BmsForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    AUTHENTICATING,

    /** Scanning itself failed (adapter/stack error). */
    ERROR,

    /** Scanning worked, but reaching the battery did not. */
    CONNECT_FAILED,
}

/** Diagnostic snapshot of the current/last GATT target. */
data class LinkInfo(
    val requestedAddress: String?,
    val connectedAddress: String?,
    val deviceType: Int?,
    val bondState: Int?,
)

private data class LinkStrategy(
    val name: String,
    val autoConnect: Boolean,
    val transportLe: Boolean,
    val discoveryDelayMs: Long,
)

private enum class PendingAction { NONE, SCAN, CONNECT }

/**
 * Single-connection BLE central for JBD / XiaoXiang / Overkill Solar BMS modules.
 *
 * Read-only: only register reads (0xA5) are ever sent. Characteristic roles are
 * discovered dynamically so both the "FF01 notify / FF02 write" and the
 * single-character "FF02 read+write+notify" module variants work.
 *
 * Battery/CPU posture:
 *  - Polling is demand-driven: it runs only while something consumes data
 *    (a Karoo data-type stream or a visible UI) and pauses after a grace period.
 *  - The BLE link is dropped after a longer idle period and re-established on
 *    demand by stored address (never by background scanning).
 *  - Low-power scan mode and connection interval are used by default.
 *  - Raw-frame logging is off unless the protocol console is open.
 */
class JbdBmsClient(private val context: Context) {

    companion object {
        private const val TAG = "JbdBmsClient"
        private val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    // ---------------------------------------------------------------- jobs
    private var pollJob: Job? = null
    private var graceJob: Job? = null
    private var idleDisconnectJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var authTimeoutJob: Job? = null

    // ---------------------------------------------------------------- state
    private val connectMutex = Mutex()
    private val connectSeq = AtomicInteger(0)
    private val scanDevices = mutableMapOf<String, BluetoothDevice>()
    private var lastDevice: BluetoothDevice? = null
    private var requestedAddress: String? = null
    private var lastAddress: String? = DeviceStore.lastAddress.value
    private var manualDisconnect = false
    private var uiVisible = false
    private var uiCounted = false
    private var connectResult: CompletableDeferred<Boolean>? = null

    @Volatile
    private var scanning = false
    private var linkDroppedEarly = false

    @Volatile
    private var discoveryDelayMs = 300L

    private val parser = JbdFrameParser()
    private val auth = JbdAuth { bytes, note -> sendRaw(bytes, note) }
    private val authTried = AtomicBoolean(false)
    private val gotResponse = AtomicBoolean(false)

    private val karooBluetooth = KarooBluetooth(context) { message -> log(message) }
    private var pendingAction = PendingAction.NONE
    private var pendingJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _data = MutableStateFlow(BmsData())
    val data: StateFlow<BmsData> = _data.asStateFlow()

    private val _scanResults = MutableStateFlow<List<DeviceScanResult>>(emptyList())
    val scanResults: StateFlow<List<DeviceScanResult>> = _scanResults.asStateFlow()

    private val _rawFrames = MutableStateFlow<List<RawFrame>>(emptyList())
    val rawFrames: StateFlow<List<RawFrame>> = _rawFrames.asStateFlow()

    private val _consumers = MutableStateFlow(0)
    /** Number of active data consumers (Karoo streams + visible UI). */
    val consumers: StateFlow<Int> = _consumers.asStateFlow()

    private val connectionLogBuffer = ConnectionLogBuffer()
    private val _connectionLog = MutableStateFlow<List<ConnectionLogEntry>>(emptyList())
    /** Timestamped connection events for the in-app debug log. */
    val connectionLog: StateFlow<List<ConnectionLogEntry>> = _connectionLog.asStateFlow()

    // ---------------------------------------------------------------- raw buffer
    private val rawLock = Any()
    @Volatile private var rawEnabled = false
    private var rawCapacity = SettingsStore.settings.value.rawBufferSize
    private var rawBuffer = RawFrameBuffer(rawCapacity)

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF -> onAdapterOff("turning off")
                BluetoothAdapter.STATE_OFF -> onAdapterOff("off")
                BluetoothAdapter.STATE_TURNING_ON -> log("Bluetooth turning on")
                BluetoothAdapter.STATE_ON -> onAdapterOn()
                else -> log("Bluetooth state=$state")
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            val device = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val state = intent?.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) ?: return
            log("Bond ${device?.address ?: "?"} -> ${bondLabel(state)}")
        }
    }

    init {
        scope.launch { _consumers.collect { onConsumersChanged() } }
        scope.launch { SettingsStore.settings.collect { onSettingsChanged() } }
        ContextCompat.registerReceiver(
            context,
            adapterStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context,
            bondStateReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        karooBluetooth.start()
    }

    private fun bondLabel(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "none"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_BONDED -> "bonded"
        else -> "state $state"
    }

    /**
     * The adapter is going away; drop our handle without leaving a half-open
     * link behind, so the next launch starts from a clean stack.
     */
    private fun onAdapterOff(stateName: String) {
        log("Bluetooth $stateName; releasing link")
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        graceJob?.cancel()
        graceJob = null
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        stopPolling()
        closeGatt()
        _data.value = BmsData()
        _connectionState.value = ConnectionState.DISCONNECTED
        connectResult?.complete(false)
    }

    private fun onAdapterOn() {
        log("Bluetooth turned on")
        if (_consumers.value == 0 || manualDisconnect || gatt != null) return
        karooBluetooth.request()
        when (pendingAction) {
            PendingAction.SCAN -> {
                pendingAction = PendingAction.NONE
                startScan()
            }
            PendingAction.CONNECT -> {
                pendingAction = PendingAction.NONE
                val address = lastAddress
                if (address != null) connect(address) else startScan()
            }
            PendingAction.NONE ->
                // A cold start only has the stored address, which loses the BLE
                // address type; scan so the battery is found as a real LE device.
                if (hasLiveDevice()) startReconnect() else startScan()
        }
    }

    /** Wait for Karoo OS to turn the radio on after a [RequestBluetooth]. */
    private fun armPending(action: PendingAction, timeoutMs: Long = 6_000L) {
        pendingAction = action
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(timeoutMs)
            if (pendingAction == action && !isBluetoothOn()) {
                log("Karoo did not enable Bluetooth within ${timeoutMs} ms")
                pendingAction = PendingAction.NONE
                _connectionState.value = if (action == PendingAction.SCAN) {
                    ConnectionState.ERROR
                } else {
                    ConnectionState.CONNECT_FAILED
                }
            }
        }
    }

    // ----------------------------------------------------------------- debug log

    private fun log(message: String) {
        Log.i(TAG, message)
        connectionLogBuffer.add(message)
        _connectionLog.value = connectionLogBuffer.snapshot()
    }

    fun clearConnectionLog() {
        connectionLogBuffer.clear()
        _connectionLog.value = emptyList()
    }

    // ----------------------------------------------------------------- permissions

    fun hasRequiredPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ----------------------------------------------------------------- consumers

    fun addConsumer() = _consumers.update { it + 1 }

    fun removeConsumer() = _consumers.update { (it - 1).coerceAtLeast(0) }

    /** Marks the app UI as foreground/background; counts as a consumer while visible. */
    fun setUiVisible(visible: Boolean) {
        if (visible == uiVisible) return
        uiVisible = visible
        if (visible && !uiCounted) {
            uiCounted = true
            addConsumer()
        } else if (!visible && uiCounted) {
            uiCounted = false
            removeConsumer()
        }
    }

    // ----------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (!isCandidate(result)) return
            val name = try {
                result.scanRecord?.deviceName ?: device.name
            } catch (_: SecurityException) {
                null
            }
            val entry = DeviceScanResult(name, device.address, result.rssi)
            val isNew = synchronized(scanDevices) { scanDevices.put(device.address, device) == null }
            if (isNew) {
                val label = name ?: "BMS"
                log(
                    "Found $label ${device.address} rssi=${result.rssi} " +
                        "type=${device.type} bond=${device.bondState}",
                )
            }
            _scanResults.update { current ->
                (current.filterNot { it.address == entry.address } + entry)
                    .sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Scan failed: error $errorCode")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private fun isCandidate(result: ScanResult): Boolean {
        val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
        if (uuids.any { it == SERVICE_UUID }) return true
        val name = try {
            result.scanRecord?.deviceName ?: result.device?.name
        } catch (_: SecurityException) {
            null
        } ?: return false
        val n = name.uppercase()
        return n.startsWith("BMS") || n.contains("XIAOXIANG") || n.startsWith("SP0") || n.contains("JBD")
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasRequiredPermissions()) {
            // The UI shows a dedicated "allow access" card.
            if (_connectionState.value != ConnectionState.CONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            return
        }
        manualDisconnect = false
        karooBluetooth.request()
        if (!isBluetoothOn()) {
            log("Scan waiting for Karoo to enable Bluetooth")
            _connectionState.value = ConnectionState.SCANNING
            armPending(PendingAction.SCAN)
            return
        }
        pendingJob?.cancel()
        pendingJob = null
        pendingAction = PendingAction.NONE
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Scan unavailable: no scanner")
            _connectionState.value = ConnectionState.ERROR
            return
        }
        if (scanning) {
            // A scan is already running; don't let a second start kill it.
            return
        }
        // Pending connect/reconnect work calls stopScan() and would swallow this
        // scan, so a user-requested scan wins.
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        _scanResults.value = emptyList()
        scanning = true
        _connectionState.value = ConnectionState.SCANNING
        log("Scan started")
        val s = SettingsStore.settings.value
        val scanSettings = ScanSettings.Builder()
            .setScanMode(
                if (s.lowPowerScan) ScanSettings.SCAN_MODE_LOW_POWER
                else ScanSettings.SCAN_MODE_LOW_LATENCY,
            )
            .build()
        try {
            scanner.startScan(null, scanSettings, scanCallback)
        } catch (_: SecurityException) {
            _connectionState.value = ConnectionState.ERROR
            return
        }
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(s.scanTimeoutMs)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val wasScanning = scanning
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        if (wasScanning) log("Scan stopped")
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // ----------------------------------------------------------------- connecting

    /** User-initiated connect. Remembers the address for later auto-reconnect. */
    fun connect(address: String) {
        val connectId = connectSeq.incrementAndGet()
        requestedAddress = address
        lastAddress = address
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        karooBluetooth.request()
        if (!hasRequiredPermissions()) {
            log("[#$connectId] Connect skipped: missing permission")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        if (!isBluetoothOn()) {
            log("[#$connectId] Waiting for Karoo to enable Bluetooth")
            _connectionState.value = ConnectionState.CONNECTING
            armPending(PendingAction.CONNECT)
            return
        }
        pendingJob?.cancel()
        pendingJob = null
        pendingAction = PendingAction.NONE
        connectJob = scope.launch {
            log("[#$connectId] User asked to connect to $address")
            if (!attemptConnect(address, autoConnect = false, connectId)) {
                val ready = hasRequiredPermissions() && isBluetoothOn()
                _connectionState.value =
                    if (ready) ConnectionState.CONNECT_FAILED else ConnectionState.DISCONNECTED
                if (ready && !manualDisconnect && _consumers.value > 0) {
                    // The BMS may still be releasing its previous link; keep
                    // trying in the background instead of giving up.
                    scope.launch {
                        delay(150)
                        startReconnect()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothOn(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    /** Clears a stale "needs attention" state after the user fixes permissions or Bluetooth. */
    fun clearError() {
        val stuck = _connectionState.value == ConnectionState.ERROR ||
            _connectionState.value == ConnectionState.CONNECT_FAILED
        if (gatt == null && stuck) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /** True when we hold a real BluetoothDevice (from a scan or a past link). */
    fun hasLiveDevice(): Boolean {
        val address = lastAddress ?: return false
        if (lastDevice != null) return true
        return synchronized(scanDevices) { scanDevices.containsKey(address) }
    }

    /** Diagnostic snapshot of the last GATT target. */
    fun linkInfo(): LinkInfo = LinkInfo(
        requestedAddress = requestedAddress,
        connectedAddress = lastDevice?.address ?: lastAddress,
        deviceType = lastDevice?.type,
        bondState = lastDevice?.bondState,
    )

    /** Forget the current target and the cached device handles (debug recovery). */
    fun resetLink() {
        log("Resetting link and cached device")
        disconnect()
        manualDisconnect = false
        requestedAddress = null
        lastDevice = null
        lastAddress = null
        synchronized(scanDevices) { scanDevices.clear() }
        _scanResults.value = emptyList()
        DeviceStore.set(null)
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Opens a GATT link and waits for the service/characteristic setup to
     * finish. Attempts are serialised so two `connectGatt()` calls can never
     * race, and the link is always closed on any non-success exit — including
     * coroutine cancellation, which previously leaked the connection and left
     * the BLE stack unable to connect again.
     */
    @SuppressLint("MissingPermission")
    private suspend fun attemptConnect(
        address: String,
        autoConnect: Boolean,
        connectId: Int,
    ): Boolean =
        connectMutex.withLock {
            if (!hasRequiredPermissions()) {
                log("[#$connectId] Connect skipped: missing permission")
                return false
            }
            if (!isBluetoothOn()) {
                log("[#$connectId] Connect skipped: Bluetooth off")
                return false
            }
            if (gatt != null && _connectionState.value == ConnectionState.CONNECTED) {
                log("[#$connectId] Already connected; ignoring connect attempt")
                return true
            }
            stopScan()
            // Prefer the BluetoothDevice object captured during scanning: it
            // keeps the correct BLE address type, which getRemoteDevice(String)
            // loses for random/private addresses.
            val scanned = synchronized(scanDevices) { scanDevices[address] }
            val remembered = lastDevice?.takeIf { it.address.equals(address, ignoreCase = true) }
            val device: BluetoothDevice? = scanned ?: remembered ?: try {
                adapter?.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (device == null) {
                log("[#$connectId] No BluetoothDevice for $address")
                return false
            }
            val source = when (device) {
                scanned -> "scan"
                remembered -> "remembered"
                else -> "address"
            }
            log(
                "[#$connectId] Target ${device.address} type=${device.type} " +
                    "bond=${device.bondState} via=$source",
            )

            if (SettingsStore.settings.value.tryBonding &&
                device.bondState == BluetoothDevice.BOND_NONE
            ) {
                try {
                    val requested = device.createBond()
                    log("[#$connectId] Bonding requested -> $requested")
                } catch (_: SecurityException) {
                }
            }

            val maxAttempts = 3
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                val strategy = when (attempt) {
                    1 -> LinkStrategy("A", autoConnect, transportLe = true, discoveryDelayMs = 300)
                    2 -> LinkStrategy("B", autoConnect = true, transportLe = true, discoveryDelayMs = 800)
                    else -> LinkStrategy("C", autoConnect = false, transportLe = false, discoveryDelayMs = 800)
                }
                linkDroppedEarly = false
                discoveryDelayMs = strategy.discoveryDelayMs
                val deferred = CompletableDeferred<Boolean>()
                connectResult = deferred
                closeGatt(refreshCache = true)
                parser.reset()
                authTried.set(false)
                gotResponse.set(false)
                _connectionState.value = ConnectionState.CONNECTING
                log(
                    "[#$connectId] Connecting to ${device.address} " +
                        "(strategy=${strategy.name}, autoConnect=${strategy.autoConnect}, " +
                        "le=${strategy.transportLe}, attempt $attempt/$maxAttempts)",
                )

                val timeoutMs = if (strategy.autoConnect) {
                    8_000L
                } else {
                    SettingsStore.settings.value.connectTimeoutMs
                }
                var success = false
                try {
                    val newGatt = try {
                        if (strategy.transportLe) {
                            device.connectGatt(
                                context,
                                strategy.autoConnect,
                                gattCallback,
                                BluetoothDevice.TRANSPORT_LE,
                            )
                        } else {
                            device.connectGatt(context, strategy.autoConnect, gattCallback)
                        }
                    } catch (_: SecurityException) {
                        null
                    }
                    if (newGatt == null) {
                        log("[#$connectId] connectGatt returned no handle")
                        linkDroppedEarly = true
                    } else {
                        gatt = newGatt
                        success = withTimeout(timeoutMs) { deferred.await() }
                        if (!success) {
                            log("[#$connectId] Connect attempt failed before services were ready")
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    log("[#$connectId] Connect timed out after $timeoutMs ms")
                    closeGatt(refreshCache = true)
                    if (attempt >= maxAttempts) return false
                    continue
                } catch (e: CancellationException) {
                    log("[#$connectId] Connect cancelled; releasing link")
                    throw e
                } finally {
                    if (connectResult === deferred) connectResult = null
                    if (!success) closeGatt(refreshCache = true)
                }

                if (success) return true
                if (!linkDroppedEarly) return false
                if (attempt < maxAttempts) {
                    val retryDelay = 400L * attempt
                    log("[#$connectId] Link dropped during setup; retrying in ${retryDelay} ms")
                    delay(retryDelay)
                }
            }
            false
        }

    /** User-initiated disconnect. Suppresses auto-reconnect until the next [connect]. */
    fun disconnect() {
        log("User disconnected")
        manualDisconnect = true
        pendingJob?.cancel()
        pendingJob = null
        pendingAction = PendingAction.NONE
        karooBluetooth.release()
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        graceJob?.cancel()
        graceJob = null
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        authTimeoutJob?.cancel()
        stopPolling()
        try {
            gatt?.disconnect()
        } catch (_: SecurityException) {
        }
        closeGatt()
        _data.value = BmsData()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Close the link when the app/service goes away, without treating it as a
     * user-initiated disconnect. Keeps the link if Karoo data streams still
     * need it.
     */
    fun releaseLink() {
        if (_consumers.value > 0) return
        log("Releasing idle link")
        karooBluetooth.release()
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        graceJob?.cancel()
        graceJob = null
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        stopPolling()
        try {
            gatt?.disconnect()
        } catch (_: SecurityException) {
        }
        closeGatt()
        _data.value = BmsData()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(refreshCache: Boolean = false) {
        val old = gatt
        // Null the fields first so late callbacks from the old link are ignored.
        gatt = null
        notifyChar = null
        writeChar = null
        if (old != null && refreshCache) refreshGattCache(old)
        try {
            old?.close()
        } catch (_: SecurityException) {
        }
    }

    /**
     * Best-effort clear of Android's cached GATT table. A stale cache commonly
     * makes the stack disconnect right after service discovery on the next
     * connection attempt.
     */
    private fun refreshGattCache(old: BluetoothGatt) {
        try {
            val refreshed = old.javaClass.getMethod("refresh").invoke(old) as? Boolean
            log("GATT cache refresh -> $refreshed")
        } catch (_: Throwable) {
            // Hidden API unavailable on this build; harmless.
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {
                log("Ignoring stale link event (status=$status state=$newState)")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLinkLost(g, "GATT error status=$status (state=$newState)")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Link up; waiting briefly before service discovery")
                    _connectionState.value = ConnectionState.CONNECTING
                    scope.launch {
                        // Let the link settle; some BMS modules drop the first
                        // connection if discovery starts immediately.
                        delay(discoveryDelayMs)
                        if (g !== gatt) return@launch
                        val started = try {
                            g.discoverServices()
                        } catch (_: SecurityException) {
                            false
                        }
                        log(
                            "discoverServices -> $started (${g.device.address}) " +
                                "after ${discoveryDelayMs} ms",
                        )
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> onLinkLost(g, "link disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: status=$status")
                _connectionState.value = ConnectionState.CONNECT_FAILED
                connectResult?.complete(false)
                return
            }
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                log("Service FF00 not found")
                _connectionState.value = ConnectionState.CONNECT_FAILED
                connectResult?.complete(false)
                return
            }
            val chars = service.characteristics ?: emptyList()
            notifyChar = chars.firstOrNull { it.uuid == NOTIFY_UUID && it.hasProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY) }
                ?: chars.firstOrNull { it.hasProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY) }
                ?: chars.firstOrNull { it.uuid == WRITE_UUID }
            writeChar = chars.firstOrNull { it.uuid == WRITE_UUID && it.isWritable() }
                ?: chars.firstOrNull { it.isWritable() }
                ?: chars.firstOrNull { it.uuid == NOTIFY_UUID }

            if (notifyChar == null || writeChar == null) {
                log("Required characteristics not found")
                _connectionState.value = ConnectionState.CONNECT_FAILED
                connectResult?.complete(false)
                return
            }
            g.setCharacteristicNotification(notifyChar, true)
            notifyChar?.getDescriptor(CCC_UUID)?.let { desc ->
                writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            _connectionState.value = ConnectionState.CONNECTED
            val requested = lastAddress
            if (requested != null && !requested.equals(g.device.address, ignoreCase = true)) {
                log("Note: connected address ${g.device.address} differs from $requested")
            }
            lastAddress = g.device.address
            lastDevice = g.device
            synchronized(scanDevices) {
                scanDevices[g.device.address] = g.device
                if (requested != null) scanDevices[requested] = g.device
            }
            DeviceStore.set(g.device.address)
            log(
                "Connected to ${g.device.address} " +
                    "(type=${g.device.type}, bond=${g.device.bondState}); notifications subscribed",
            )
            applyConnectionPriority()
            readRssi()
            sendRead(JbdCodec.REG_DEVICE_NAME, "read device name")
            connectResult?.complete(true)
            onConsumersChanged()
            scheduleAuthFallback()
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (g !== gatt) return
            onNotify(value)
        }

        // API < 33
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (g !== gatt) return
            onNotify(characteristic.value ?: return)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _data.value = _data.value.copy(rssi = rssi)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readRssi() {
        val g = gatt ?: return
        try {
            g.readRemoteRssi()
        } catch (_: SecurityException) {
        }
    }

    private fun BluetoothGattCharacteristic.hasProperty(property: Int): Boolean =
        (properties and property) != 0

    private fun BluetoothGattCharacteristic.isWritable(): Boolean =
        hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE) ||
            hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

    /** Called whenever the current GATT link drops, for any reason. */
    private fun onLinkLost(g: BluetoothGatt, reason: String) {
        if (g !== gatt) return
        val wasReady = _connectionState.value == ConnectionState.CONNECTED
        linkDroppedEarly = !wasReady
        log("Link lost: $reason (ready=$wasReady)")
        stopPolling()
        graceJob?.cancel()
        graceJob = null
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        closeGatt(refreshCache = true)
        _data.value = BmsData()
        _connectionState.value = ConnectionState.DISCONNECTED
        connectResult?.complete(false)
        if (!manualDisconnect && _consumers.value > 0) startReconnect()
    }

    // ----------------------------------------------------------------- polling

    private fun onConsumersChanged() {
        val active = _consumers.value > 0
        val s = SettingsStore.settings.value
        if (active) {
            graceJob?.cancel()
            graceJob = null
            idleDisconnectJob?.cancel()
            idleDisconnectJob = null
            if (gatt == null) {
                val connecting = connectJob?.isActive == true
                if (lastAddress != null && !manualDisconnect && !connecting) startReconnect()
            } else {
                startPolling()
            }
        } else if (s.pauseWhenIdle) {
            if (gatt != null && graceJob?.isActive != true) {
                graceJob = scope.launch {
                    delay(SettingsStore.settings.value.idleGraceMs)
                    stopPolling()
                    if (SettingsStore.settings.value.disconnectWhenIdle) armIdleDisconnect()
                }
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        log("Polling started")
        pollJob = scope.launch {
            var basicInfo = true
            while (isActive) {
                if (basicInfo) {
                    sendRead(JbdCodec.REG_BASIC_INFO, "read basic info")
                } else {
                    sendRead(JbdCodec.REG_CELL_VOLTAGES, "read cell voltages")
                }
                readRssi()
                basicInfo = !basicInfo
                delay(currentPollInterval())
            }
        }
    }

    private fun currentPollInterval(): Long {
        val s = SettingsStore.settings.value
        return if (uiVisible) s.pollIntervalVisibleMs else s.pollIntervalBackgroundMs
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun armIdleDisconnect() {
        if (idleDisconnectJob?.isActive == true) return
        idleDisconnectJob = scope.launch {
            delay(SettingsStore.settings.value.idleDisconnectMs)
            if (_consumers.value == 0) {
                log("Idle disconnect")
                stopPolling()
                try {
                    gatt?.disconnect()
                } catch (_: SecurityException) {
                }
                closeGatt()
                _data.value = BmsData()
                _connectionState.value = ConnectionState.DISCONNECTED
                karooBluetooth.release()
                if (!uiVisible) BmsForegroundService.stop(context)
            }
        }
    }

    // ----------------------------------------------------------------- reconnect

    private fun startReconnect() {
        val s = SettingsStore.settings.value
        val address = lastAddress ?: return
        if (!s.autoReconnect) return
        if (!hasRequiredPermissions() || !isBluetoothOn()) return
        if (reconnectJob?.isActive == true) return
        if (connectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val connectId = connectSeq.incrementAndGet()
            log("[#$connectId] Auto-reconnect started")
            var attempt = 0
            var backoff = s.reconnectBaseDelayMs
            // Let the BLE stack release the old link before dialling again.
            delay((s.reconnectBaseDelayMs / 4).coerceIn(300L, 1_000L))
            while (isActive && _consumers.value > 0 && gatt == null && attempt < s.reconnectMaxRetries) {
                if (!hasRequiredPermissions() || !isBluetoothOn()) return@launch
                if (attempt > 0) delay(backoff)
                backoff = (backoff * 2).coerceAtMost(s.reconnectMaxDelayMs)
                attempt++
                log("[#$connectId] Reconnect attempt $attempt")
                if (attemptConnect(address, autoConnect = true, connectId)) {
                    log("[#$connectId] Reconnected after $attempt attempt(s)")
                    return@launch
                }
            }
            if (_consumers.value > 0 && gatt == null) {
                log("[#$connectId] Auto-reconnect gave up after $attempt attempt(s)")
                _connectionState.value = ConnectionState.CONNECT_FAILED
                if (lastDevice == null) {
                    // After a process restart we only have the old address; it
                    // may have rotated. Scan so the user can pick it again.
                    log("[#$connectId] Falling back to a scan")
                    scope.launch {
                        delay(150)
                        startScan()
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- settings

    private fun onSettingsChanged() {
        val s = SettingsStore.settings.value
        val newCap = BmsSettings.clampRawBuffer(s.rawBufferSize)
        if (newCap != rawCapacity) {
            rawCapacity = newCap
            synchronized(rawLock) { rawBuffer = RawFrameBuffer(newCap) }
            _rawFrames.value = emptyList()
        }
        if (gatt != null) applyConnectionPriority()
        onConsumersChanged()
    }

    @SuppressLint("MissingPermission")
    private fun applyConnectionPriority() {
        val g = gatt ?: return
        val priority = if (SettingsStore.settings.value.lowPowerConnection) {
            BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        } else {
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        }
        try {
            g.requestConnectionPriority(priority)
        } catch (_: SecurityException) {
        }
    }

    // ----------------------------------------------------------------- i/o

    private fun onNotify(value: ByteArray) {
        addRaw(Direction.RX, value, "rx")
        // Auth packets use the FF header; route them to the handshake.
        if (value.size >= 2 && (value[0].toInt() and 0xFF) == 0xFF && (value[1].toInt() and 0xFF) == 0xAA) {
            if (auth.onPacket(value)) {
                authTried.set(true)
                gotResponse.set(false)
                _connectionState.value = ConnectionState.CONNECTED
            }
            return
        }

        val result = parser.feed(value)
        if (result.frames.isNotEmpty() && !gotResponse.get()) {
            log("Battery replied to a read request")
        }
        for (frame in result.frames) {
            gotResponse.set(true)
            if (_connectionState.value == ConnectionState.AUTHENTICATING) {
                _connectionState.value = ConnectionState.CONNECTED
            }
            handleFrame(frame.register, frame.data)
        }
    }

    private fun handleFrame(register: Int, data: ByteArray) {
        if (register == JbdCodec.REG_BASIC_INFO) {
            val decoded = BasicInfoDecoder.parse(data, _data.value.deviceName)
            _data.value = decoded.copy(cells = _data.value.cells)
        } else if (register == JbdCodec.REG_CELL_VOLTAGES) {
            _data.value = _data.value.copy(
                connected = true,
                cells = CellVoltageDecoder.parse(data),
                lastUpdateEpochMs = System.currentTimeMillis(),
            )
        } else if (register == JbdCodec.REG_DEVICE_NAME) {
            val name = decodeName(data)
            if (!name.isNullOrBlank()) {
                _data.value = _data.value.copy(deviceName = name)
            }
        }
    }

    private fun decodeName(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val len = data[0].toInt() and 0xFF
        if (len <= 0 || len + 1 > data.size) return null
        return String(data, 1, len, Charsets.US_ASCII).trim()
    }

    private fun sendRead(register: Int, note: String) {
        sendRaw(JbdCodec.buildRead(register), note)
    }

    private fun sendRaw(bytes: ByteArray, note: String) {
        addRaw(Direction.TX, bytes, note)
        val g = gatt ?: return
        val char = writeChar ?: return
        writeValue(g, char, bytes)
    }

    @SuppressLint("MissingPermission")
    private fun writeValue(g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                char.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "write failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(desc: BluetoothGattDescriptor, value: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeDescriptor(desc, value)
            } else {
                @Suppress("DEPRECATION")
                desc.value = value
                @Suppress("DEPRECATION")
                gatt?.writeDescriptor(desc)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "descriptor write failed", e)
        }
    }

    // ----------------------------------------------------------------- auth fallback

    private fun scheduleAuthFallback() {
        if (!SettingsStore.settings.value.authFallbackEnabled) return
        authTimeoutJob?.cancel()
        authTimeoutJob = scope.launch {
            delay(3500)
            if (!gotResponse.get() && !authTried.get()) {
                authTried.set(true)
                _connectionState.value = ConnectionState.AUTHENTICATING
                val mac = gatt?.device?.address ?: ""
                log("No 0xDD response; trying FF/AA password handshake")
                auth.start(mac)
                addRaw(Direction.TX, ByteArray(0), "no response to 0xDD read; attempting FF/AA auth")
            }
        }
    }

    // ----------------------------------------------------------------- raw console

    /** Enable/disable recording of raw frames. Disabling frees the buffer. */
    fun setRawEnabled(enabled: Boolean) {
        rawEnabled = enabled
        synchronized(rawLock) { rawBuffer.clear() }
        _rawFrames.value = emptyList()
    }

    private fun addRaw(direction: Direction, bytes: ByteArray, note: String) {
        if (!rawEnabled) return
        val frame = RawFrame(direction, bytes.copyOf(), note)
        val snapshot = synchronized(rawLock) {
            rawBuffer.add(frame)
            rawBuffer.snapshot()
        }
        _rawFrames.value = snapshot
    }

    fun clearRaw() {
        synchronized(rawLock) { rawBuffer.clear() }
        _rawFrames.value = emptyList()
    }

    /** Ask Karoo OS to hold the Bluetooth radio on for us. */
    fun requestBluetooth() {
        karooBluetooth.request()
    }

    fun shutdown() {
        try {
            context.unregisterReceiver(adapterStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        karooBluetooth.stop()
        disconnect()
        scope.cancel()
    }
}
