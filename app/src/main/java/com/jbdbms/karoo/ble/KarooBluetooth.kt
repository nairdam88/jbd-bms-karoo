package com.jbdbms.karoo.ble

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth

/**
 * Holds the Karoo's Bluetooth radio for this app.
 *
 * On Karoo OS the radio is owned by Hammerhead's sensor service and is normally
 * off. Third-party apps must ask the host to enable it with [RequestBluetooth]
 * and release it when done, otherwise the OS turns it back off after a moment.
 */
class KarooBluetooth(
    context: Context,
    private val log: (String) -> Unit,
) {

    companion object {
        private const val RESOURCE_ID = "jbd-bms"
    }

    private val service = KarooSystemService(context.applicationContext)
    private var started = false
    private var connected = false
    private var wanted = false
    private var held = false

    val isWanted: Boolean get() = wanted
    val isHeld: Boolean get() = held

    fun start() {
        if (started) return
        started = true
        try {
            service.connect { isConnected ->
                connected = isConnected
                log("KarooSystem connected=$isConnected lib=${service.libVersion ?: "?"}")
                if (isConnected && wanted) dispatchRequest()
            }
        } catch (e: Exception) {
            log("KarooSystem bind failed: ${e.message}")
        }
    }

    /** Ask Karoo OS to keep Bluetooth on for us. */
    fun request() {
        wanted = true
        if (connected) {
            dispatchRequest()
        } else {
            log("Karoo Bluetooth requested; waiting for KarooSystem")
        }
    }

    /** Let Karoo OS turn the radio off again. */
    fun release() {
        wanted = false
        if (connected && held) dispatchRelease()
    }

    fun stop() {
        release()
        if (started) {
            try {
                service.disconnect()
            } catch (_: Exception) {
            }
            started = false
            connected = false
        }
    }

    private fun dispatchRequest() {
        if (held) return
        val ok = try {
            service.dispatch(RequestBluetooth(RESOURCE_ID))
        } catch (e: Exception) {
            log("RequestBluetooth failed: ${e.message}")
            false
        }
        held = ok
        log("RequestBluetooth -> $ok")
    }

    private fun dispatchRelease() {
        val ok = try {
            service.dispatch(ReleaseBluetooth(RESOURCE_ID))
        } catch (e: Exception) {
            log("ReleaseBluetooth failed: ${e.message}")
            false
        }
        if (ok) held = false
        log("ReleaseBluetooth -> $ok")
    }
}
