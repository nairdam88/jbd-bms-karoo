package com.jbdbms.karoo.protocol

/**
 * Decoder for register 0x03 ("Basic Info").
 *
 * Field offsets follow the canonical JBD register map (FurTrader / Overkill
 * Solar). The fixed prefix (0x00..0x16) is stable across firmware revisions;
 * the trailing NTC / capacity block depends on the reported NTC count, so it is
 * parsed defensively and only when enough bytes are present.
 */
object BasicInfoDecoder {

    private val ERROR_LABELS = listOf(
        "Cell overvoltage",
        "Cell undervoltage",
        "Pack overvoltage",
        "Pack undervoltage",
        "Charge overtemp",
        "Charge undertemp",
        "Discharge overtemp",
        "Discharge undertemp",
        "Charge overcurrent",
        "Discharge overcurrent",
        "Short circuit",
        "Frontend IC error",
        "FET locked",
    )

    fun parse(data: ByteArray, deviceName: String? = null, nowMs: Long = System.currentTimeMillis()): BmsData {
        fun u8(off: Int): Int? = if (off < data.size) data[off].toInt() and 0xFF else null
        fun u16(off: Int): Int? =
            if (off + 1 < data.size) (((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)) else null
        fun s16(off: Int): Int? = u16(off)?.let { if (it >= 0x8000) it - 0x10000 else it }

        val packMv = u16(0x00)?.let { it * 10.0 / 1000.0 }           // 10 mV  -> V
        val currentA = s16(0x02)?.let { it * 10.0 / 1000.0 }          // 10 mA  -> A
        val balanceCapAh = u16(0x04)?.let { it * 10.0 / 1000.0 }      // 10 mAh -> Ah
        val fullCapAh = u16(0x06)?.let { it * 10.0 / 1000.0 }         // 10 mAh -> Ah
        val cycles = u16(0x08)
        val mfgDate = u16(0x0A)?.let { decodeDate(it) }

        val balLow = u16(0x0C) ?: 0
        val balHigh = u16(0x0E) ?: 0
        val balancing = buildList {
            for (i in 0 until 16) if ((balLow shr i) and 1 == 1) add(i + 1)
            for (i in 0 until 16) if ((balHigh shr i) and 1 == 1) add(i + 17)
        }

        val errors = u16(0x10)?.let { mask ->
            ERROR_LABELS.filterIndexed { bit, _ -> (mask shr bit) and 1 == 1 }
        } ?: emptyList()

        val swVersion = u8(0x12)?.let { "%d.%d".format(it shr 4, it and 0x0F) }
        val soc = u8(0x13)
        val fet = u8(0x14)
        val ntcCount = u8(0x16) ?: 0

        val temperatures = ArrayList<Double>()
        val ntcStart = 0x17
        for (i in 0 until ntcCount) {
            val off = ntcStart + i * 2
            val raw = u16(off) ?: break
            temperatures.add(raw / 10.0 - 273.15) // 0.1 K -> °C
        }

        // Trailing block: humidity(1) alarm(2) fullCap(2) remainingCap(2) balanceCurrent(2)
        var remainingCapAh: Double? = null
        val tail = ntcStart + ntcCount * 2
        if (data.size >= tail + 9) {
            remainingCapAh = u16(tail + 5)?.let { it * 10.0 / 1000.0 }
        }

        val powerW = if (packMv != null && currentA != null) packMv * currentA else null

        return BmsData(
            connected = true,
            packVoltageV = packMv,
            currentA = currentA,
            powerW = powerW,
            remainingCapacityAh = remainingCapAh,
            fullCapacityAh = fullCapAh,
            balanceCapacityAh = balanceCapAh,
            cycleCount = cycles,
            socPercent = soc,
            temperaturesC = temperatures,
            chargeFetOn = fet?.let { (it and 0x01) != 0 },
            dischargeFetOn = fet?.let { (it and 0x02) != 0 },
            balancingCells = balancing,
            errorFlags = errors,
            swVersion = swVersion,
            manufactureDate = mfgDate,
            deviceName = deviceName,
            lastUpdateEpochMs = nowMs,
        )
    }

    private fun decodeDate(value: Int): String {
        val year = (value shr 9) + 2000
        val month = (value shr 5) and 0x0F
        val day = value and 0x1F
        return "%04d-%02d-%02d".format(year, month, day)
    }
}
