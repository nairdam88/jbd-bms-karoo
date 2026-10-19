package com.jbdbms.karoo.data

import android.content.Context
import com.jbdbms.karoo.ble.ConnectionLogEntry
import com.jbdbms.karoo.ble.ConnectionState
import com.jbdbms.karoo.ble.JbdBmsClient
import com.jbdbms.karoo.protocol.BmsData
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide holder for the single BMS connection so both the UI and the
 * Karoo ride-screen extension read from the same source.
 */
object BmsRepository {

    private var instance: JbdBmsClient? = null

    fun init(context: Context) {
        SettingsStore.init(context)
        DeviceStore.init(context)
        if (instance == null) {
            instance = JbdBmsClient(context.applicationContext)
        }
    }

    fun client(): JbdBmsClient = requireNotNull(instance) { "BmsRepository.init() not called" }

    val connectionState: StateFlow<ConnectionState> get() = client().connectionState
    val data: StateFlow<BmsData> get() = client().data
    val scanResults: StateFlow<List<ScanResult>> get() = client().scanResults
    val rawFrames: StateFlow<List<RawFrame>> get() = client().rawFrames
    val consumers: StateFlow<Int> get() = client().consumers
    val connectionLog: StateFlow<List<ConnectionLogEntry>> get() = client().connectionLog

    val settings: StateFlow<BmsSettings> get() = SettingsStore.settings

    fun clearConnectionLog() = client().clearConnectionLog()
    fun setRawEnabled(enabled: Boolean) = client().setRawEnabled(enabled)
    fun setUiVisible(visible: Boolean) = client().setUiVisible(visible)
    fun clearError() = client().clearError()
    fun addConsumer() = client().addConsumer()
    fun removeConsumer() = client().removeConsumer()
    fun clearRaw() = client().clearRaw()
}
