package com.jbdbms.karoo.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class JbdCodecTest {

    private fun hex(bytes: ByteArray) = JbdCodec.toHex(bytes)

    @Test
    fun `read basic info frame matches documented bytes`() {
        assertEquals("DD A5 03 00 FF FD 77", hex(JbdCodec.buildRead(0x03)))
    }

    @Test
    fun `read cell voltages frame matches documented bytes`() {
        assertEquals("DD A5 04 00 FF FC 77", hex(JbdCodec.buildRead(0x04)))
    }

    @Test
    fun `read device name frame matches documented bytes`() {
        assertEquals("DD A5 05 00 FF FB 77", hex(JbdCodec.buildRead(0x05)))
    }

    @Test
    fun `request checksum excludes command byte`() {
        // sum(reg=0x03, len=0x00) = 3 -> 0x10000 - 3 = 0xFFFD
        assertEquals(0xFFFD, JbdCodec.requestChecksum(0x03, ByteArray(0)))
    }

    @Test
    fun `response checksum covers length and data only`() {
        // Documented example: length 0x1B with 27 data bytes -> 0xFC71
        val data = hexToBytes("05 34 00 00 19 AB 27 10 00 00 2A 75 00 00 00 00 00 00 20 42 03 04 02 09 7F 0B A3")
        assertEquals(0xFC71, JbdCodec.responseChecksum(data))
    }

    @Test
    fun `write frame checksum example`() {
        // DD 5A E1 02 00 02 FF 1B 77 (disable discharge MOSFET)
        val frame = JbdCodec.buildWrite(0xE1, hexToBytes("00 02"))
        assertEquals("DD 5A E1 02 00 02 FF 1B 77", hex(frame))
    }
}

fun hexToBytes(hex: String): ByteArray {
    val clean = hex.replace(" ", "").replace("\n", "")
    return ByteArray(clean.length / 2) {
        ((Character.digit(clean[it * 2], 16) shl 4) + Character.digit(clean[it * 2 + 1], 16)).toByte()
    }
}
