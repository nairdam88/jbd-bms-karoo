package com.jbdbms.karoo.protocol

/**
 * Decoder for register 0x04 ("Cell voltages").
 *
 * The payload is a big-endian array of unsigned 16-bit millivolt values, one per
 * cell, starting at data offset 0. The cell count comes from register 0x03.
 */
object CellVoltageDecoder {
    fun parse(data: ByteArray): List<Double> {
        val out = ArrayList<Double>(data.size / 2)
        var i = 0
        while (i + 1 < data.size) {
            val mv = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            out.add(mv / 1000.0)
            i += 2
        }
        return out
    }
}
