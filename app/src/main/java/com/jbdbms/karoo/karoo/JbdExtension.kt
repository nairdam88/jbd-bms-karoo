package com.jbdbms.karoo.karoo

import com.jbdbms.karoo.BuildConfig
import com.jbdbms.karoo.data.BmsRepository
import io.hammerhead.karooext.extension.KarooExtension

/**
 * Karoo extension exposing JBD BMS readings as ride-screen data fields.
 *
 * The extension shares the same in-process [BmsRepository] as the dashboard, so
 * it shows live values while the app has an active connection.
 */
class JbdExtension : KarooExtension("jbd-bms", BuildConfig.VERSION_NAME) {

    override fun onCreate() {
        super.onCreate()
        BmsRepository.init(applicationContext)
    }

    override val types by lazy {
        listOf(
            BmsDataType(extension, "soc") { it.socPercent?.toDouble() },
            BmsDataType(extension, "voltage") { it.packVoltageV },
            BmsDataType(extension, "current") { it.currentA },
            BmsDataType(extension, "power") { it.powerW },
        )
    }
}
