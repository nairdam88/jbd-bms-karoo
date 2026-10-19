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
import android.content.Context
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
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, AUTHENTICATING, ERROR }

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
    private var lastAddress: String? = DeviceStore.lastAddress.value
    private var manualDisconnect = false
    private var uiVisible = false
    private var uiCounted = false
    private var connectResult: CompletableDeferred<Boolean>? = null

    private val parser = JbdFrameParser()
    private val auth = JbdAuth { bytes, note -> sendRaw(bytes, note) }
    private val authTried = AtomicBoolean(false)
    private val gotResponse = AtomicBoolean(false)

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

    // ---------------------------------------------------------------- raw buffer
    private val rawLock = Any()
    @Volatile private var rawEnabled = false
    private var rawCapacity = SettingsStore.settings.value.rawBufferSize
    private var rawBuffer = RawFrameBuffer(rawCapacity)

    init {
        scope.launch { _consumers.collect { onConsumersChanged() } }
        scope.launch { SettingsStore.settings.collect { onSettingsChanged() } }
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
            _scanResults.update { current ->
                (current.filterNot { it.address == entry.address } + entry)
                    .sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
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
        if (!hasRequiredPermissions() || !isBluetoothOn()) {
            // The UI shows a dedicated "allow access / turn on Bluetooth" card.
            if (_connectionState.value != ConnectionState.CONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = ConnectionState.ERROR
            return
        }
        _scanResults.value = emptyList()
        _connectionState.value = ConnectionState.SCANNING
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
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // ----------------------------------------------------------------- connecting

    /** User-initiated connect. Remembers the address for later auto-reconnect. */
    fun connect(address: String) {
        lastAddress = address
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = scope.launch {
            if (!attemptConnect(address)) {
                val ready = hasRequiredPermissions() && isBluetoothOn()
                _connectionState.value = if (ready) ConnectionState.ERROR else ConnectionState.DISCONNECTED
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
        if (gatt == null && _connectionState.value == ConnectionState.ERROR) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun attemptConnect(address: String): Boolean {
        if (!hasRequiredPermissions()) return false
        stopScan()
        val device: BluetoothDevice = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false

        val deferred = CompletableDeferred<Boolean>()
        connectResult = deferred
        closeGatt()
        parser.reset()
        authTried.set(false)
        gotResponse.set(false)
        _connectionState.value = ConnectionState.CONNECTING

        val started = try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            if (connectResult === deferred) connectResult = null
            return false
        }

        return try {
            withTimeout(SettingsStore.settings.value.connectTimeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            closeGatt()
            _connectionState.value = ConnectionState.DISCONNECTED
            false
        } finally {
            if (connectResult === deferred) connectResult = null
        }
    }

    /** User-initiated disconnect. Suppresses auto-reconnect until the next [connect]. */
    fun disconnect() {
        manualDisconnect = true
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

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val old = gatt
        // Null the fields first so late callbacks from the old link are ignored.
        gatt = null
        notifyChar = null
        writeChar = null
        try {
            old?.close()
        } catch (_: SecurityException) {
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTING
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> onLinkLost(g)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.ERROR
                connectResult?.complete(false)
                return
            }
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "service FF00 not found")
                _connectionState.value = ConnectionState.ERROR
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
                Log.w(TAG, "required characteristics not found")
                _connectionState.value = ConnectionState.ERROR
                connectResult?.complete(false)
                return
            }
            g.setCharacteristicNotification(notifyChar, true)
            notifyChar?.getDescriptor(CCC_UUID)?.let { desc ->
                writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            _connectionState.value = ConnectionState.CONNECTED
            lastAddress = g.device.address
            DeviceStore.set(g.device.address)
            applyConnectionPriority()
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
    }

    private fun BluetoothGattCharacteristic.hasProperty(property: Int): Boolean =
        (properties and property) != 0

    private fun BluetoothGattCharacteristic.isWritable(): Boolean =
        hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE) ||
            hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

    /** Called whenever the current GATT link drops, for any reason. */
    private fun onLinkLost(g: BluetoothGatt) {
        if (g !== gatt) return
        stopPolling()
        graceJob?.cancel()
        graceJob = null
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        closeGatt()
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
                if (lastAddress != null && !manualDisconnect) startReconnect()
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
        pollJob = scope.launch {
            var basicInfo = true
            while (isActive) {
                if (basicInfo) {
                    sendRead(JbdCodec.REG_BASIC_INFO, "read basic info")
                } else {
                    sendRead(JbdCodec.REG_CELL_VOLTAGES, "read cell voltages")
                }
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
                stopPolling()
                try {
                    gatt?.disconnect()
                } catch (_: SecurityException) {
                }
                closeGatt()
                _data.value = BmsData()
                _connectionState.value = ConnectionState.DISCONNECTED
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
        reconnectJob = scope.launch {
            var attempt = 0
            var backoff = s.reconnectBaseDelayMs
            while (isActive && _consumers.value > 0 && gatt == null && attempt < s.reconnectMaxRetries) {
                if (!hasRequiredPermissions() || !isBluetoothOn()) return@launch
                if (attempt > 0) delay(backoff)
                backoff = (backoff * 2).coerceAtMost(s.reconnectMaxDelayMs)
                attempt++
                if (attemptConnect(address)) return@launch
            }
            if (_consumers.value > 0 && gatt == null) {
                _connectionState.value = ConnectionState.ERROR
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
        authTimeoutJob?.cancel()
        authTimeoutJob = scope.launch {
            delay(3500)
            if (!gotResponse.get() && !authTried.get()) {
                authTried.set(true)
                _connectionState.value = ConnectionState.AUTHENTICATING
                val mac = gatt?.device?.address ?: ""
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

    fun shutdown() {
        disconnect()
        scope.cancel()
    }
}
