package com.jbdbms.karoo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderTest {

    private val basicInfoData = hexToBytes(
        "05 34 00 00 19 AB 27 10 00 00 2A 75 00 00 00 00 00 00 20 42 03 04 02 09 7F 0B A3",
    )

    @Test
    fun `basic info decodes documented sample`() {
        val d = BasicInfoDecoder.parse(basicInfoData, deviceName = "BMS-1")

        assertEquals(13.32, d.packVoltageV!!, 0.001)
        assertEquals(0.0, d.currentA!!, 0.001)
        assertEquals(65.71, d.balanceCapacityAh!!, 0.001)
        assertEquals(100.0, d.fullCapacityAh!!, 0.001)
        assertEquals(0, d.cycleCount)
        assertEquals(66, d.socPercent)
        assertEquals("2.0", d.swVersion)
        assertEquals("2021-03-21", d.manufactureDate)
        assertTrue(d.chargeFetOn!!)
        assertTrue(d.dischargeFetOn!!)
        assertTrue(d.errorFlags.isEmpty())
        assertEquals("BMS-1", d.deviceName)
        assertEquals(2, d.temperaturesC.size)
        assertEquals(-30.05, d.temperaturesC[0], 0.01)
        assertEquals(24.75, d.temperaturesC[1], 0.01)
    }

    @Test
    fun `cell voltages decode as millivolts`() {
        val cells = CellVoltageDecoder.parse(hexToBytes("0F A0 0F B4 0F 8C 0F A0"))
        assertEquals(4, cells.size)
        assertEquals(4.000, cells[0], 0.0001)
        assertEquals(4.020, cells[1], 0.0001)
        assertEquals(3.980, cells[2], 0.0001)
    }

    @Test
    fun `derived cell statistics`() {
        val d = BmsData(cells = listOf(4.0, 4.02, 3.98, 4.0))
        assertEquals(3.98, d.cellMinV!!, 1e-9)
        assertEquals(4.02, d.cellMaxV!!, 1e-9)
        assertEquals(40.0, d.cellDeltaV!! * 1000, 1e-6)
        assertEquals(2, d.cellMinIndex)
        assertEquals(1, d.cellMaxIndex)
        assertFalse(d.cells.isEmpty())
    }
}
