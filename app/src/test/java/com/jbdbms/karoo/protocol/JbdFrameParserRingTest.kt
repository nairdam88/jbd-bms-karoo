package com.jbdbms.karoo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Exercises the primitive ring-buffer parser under chunking and overflow
 * conditions that the original boxed [java.util.ArrayList] implementation was
 * not tested against.
 */
class JbdFrameParserRingTest {

    private val cellFrame = hexToBytes("DD 04 00 08 0F A0 0F A0 0F A0 0F A0 FD 3C 77")

    @Test
    fun `parses a frame fed one byte at a time`() {
        val parser = JbdFrameParser()
        var last: JbdFrame? = null
        for (b in cellFrame) {
            val result = parser.feed(byteArrayOf(b))
            if (result.frames.isNotEmpty()) last = result.frames.first()
        }

        assertNotNull(last)
        assertEquals(JbdCodec.REG_CELL_VOLTAGES, last!!.register)
    }

    @Test
    fun `recovers after garbage longer than the ring capacity`() {
        val parser = JbdFrameParser(maxBuffer = 64)
        parser.feed(ByteArray(500) { 0x12 })

        val result = parser.feed(cellFrame)
        assertEquals(1, result.frames.size)
        assertEquals(JbdCodec.REG_CELL_VOLTAGES, result.frames.first().register)
    }

    @Test
    fun `reset clears a partial frame`() {
        val parser = JbdFrameParser()
        parser.feed(cellFrame.copyOfRange(0, 5))
        parser.reset()

        val result = parser.feed(cellFrame)
        assertEquals(1, result.frames.size)
    }
}
