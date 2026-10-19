package com.jbdbms.karoo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JbdFrameParserTest {

    private val basicInfo = hexToBytes(
        "DD 03 00 1B 05 34 00 00 19 AB 27 10 00 00 2A 75 00 00 00 00 00 00 20 42 03 04 02 09 7F 0B A3 FC 71 77",
    )

    @Test
    fun `assembles a frame split across two notifications`() {
        val parser = JbdFrameParser()
        val first = parser.feed(basicInfo.copyOfRange(0, 20)) // 20 bytes (one BLE packet)
        assertTrue(first.frames.isEmpty())
        val second = parser.feed(basicInfo.copyOfRange(20, basicInfo.size))
        assertEquals(1, second.frames.size)
        val frame = second.frames.single()
        assertEquals(JbdCodec.REG_BASIC_INFO, frame.register)
        assertEquals(JbdCodec.STATUS_OK, frame.status)
        assertEquals(0x1B, frame.data.size)
        assertTrue(frame.isOk)
    }

    @Test
    fun `tolerates leading noise before the header`() {
        val parser = JbdFrameParser()
        val noise = byteArrayOf(0x00, 0x12, 0x34, 0x56)
        val result = parser.feed(noise + basicInfo)
        assertEquals(1, result.frames.size)
    }

    @Test
    fun `rejects a frame with a corrupt checksum`() {
        val parser = JbdFrameParser()
        val corrupt = basicInfo.copyOf()
        corrupt[31] = (corrupt[31].toInt() xor 0xFF).toByte() // break crc high byte
        val result = parser.feed(corrupt)
        assertTrue(result.frames.isEmpty())
        assertTrue(result.crcErrors >= 1)
    }

    @Test
    fun `parses two frames in one buffer`() {
        val cellFrame = hexToBytes("DD 04 00 08 0F A0 0F A0 0F A0 0F A0 FD 3C 77")
        val parser = JbdFrameParser()
        val result = parser.feed(basicInfo + cellFrame)
        assertEquals(2, result.frames.size)
        assertEquals(JbdCodec.REG_BASIC_INFO, result.frames[0].register)
        assertEquals(JbdCodec.REG_CELL_VOLTAGES, result.frames[1].register)
    }
}
