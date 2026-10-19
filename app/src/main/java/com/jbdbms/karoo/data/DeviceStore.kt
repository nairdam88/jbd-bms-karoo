package com.jbdbms.karoo.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers the last successfully used BMS address so an on-demand reconnect
 * (no scanning) can still happen after the process is restarted, e.g. when the
 * Karoo host re-binds the ride-screen extension.
 */
object DeviceStore {

    private const val PREFS = "bms_device"
    private const val K_ADDRESS = "last_address"

    private var prefs: SharedPreferences? = null

    private val _lastAddress = MutableStateFlow<String?>(null)
    val lastAddress: StateFlow<String?> = _lastAddress.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _lastAddress.value = p.getString(K_ADDRESS, null)
    }

    fun set(address: String?) {
        _lastAddress.value = address
        prefs?.edit()?.apply {
            if (address == null) remove(K_ADDRESS) else putString(K_ADDRESS, address)
        }?.apply()
    }
}
