package com.jbdbms.karoo.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogBufferTest {

    @Test
    fun `keeps events in insertion order with timestamps`() {
        val buffer = ConnectionLogBuffer()

        buffer.add("first", nowMs = 1_000L)
        buffer.add("second", nowMs = 2_000L)

        val snapshot = buffer.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("first", snapshot[0].message)
        assertEquals(1_000L, snapshot[0].timestampMs)
        assertEquals("second", snapshot[1].message)
    }

    @Test
    fun `drops the oldest events beyond capacity`() {
        val buffer = ConnectionLogBuffer(capacity = 3)

        buffer.add("a")
        buffer.add("b")
        buffer.add("c")
        buffer.add("d")

        val messages = buffer.snapshot().map { it.message }
        assertEquals(listOf("b", "c", "d"), messages)
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = ConnectionLogBuffer()
        buffer.add("event")

        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty())
    }
}
