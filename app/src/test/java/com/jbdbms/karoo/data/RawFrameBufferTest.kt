package com.jbdbms.karoo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFrameBufferTest {

    private fun frame(i: Int) =
        RawFrame(Direction.TX, byteArrayOf(i.toByte()), "n$i", i.toLong())

    @Test
    fun `caps at capacity keeping the newest frames`() {
        val buf = RawFrameBuffer(3)
        repeat(5) { buf.add(frame(it)) }

        assertEquals(3, buf.size)
        assertEquals(listOf(2L, 3L, 4L), buf.snapshot().map { it.timestampMs })
    }

    @Test
    fun `clear empties the buffer`() {
        val buf = RawFrameBuffer(3)
        buf.add(frame(1))
        buf.clear()

        assertTrue(buf.snapshot().isEmpty())
        assertEquals(0, buf.size)
    }

    @Test
    fun `capacity of one keeps only the latest`() {
        val buf = RawFrameBuffer(1)
        buf.add(frame(1))
        buf.add(frame(2))

        assertEquals(1, buf.size)
        assertEquals(2L, buf.snapshot().single().timestampMs)
    }
}
