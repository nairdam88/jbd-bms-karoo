package com.jbdbms.karoo.protocol

/** A single validated response frame from the BMS. */
data class JbdFrame(
    val register: Int,
    val status: Int,
    val data: ByteArray,
    val raw: ByteArray,
) {
    val isOk: Boolean get() = status == JbdCodec.STATUS_OK
}

/** Result of attempting to parse buffered bytes. */
data class ParseResult(
    val frames: List<JbdFrame>,
    val crcErrors: Int,
)

/**
 * Assembles BLE notifications into complete JBD frames.
 *
 * Notifications are frequently split across two BLE packets (a basic-info
 * response is ~33 bytes while a single notification carries ~20), so callers
 * must feed every received chunk into [feed] and use the emitted frames.
 *
 * The parser is tolerant of leading noise: it scans for the 0xDD start byte and
 * only emits a frame once the whole advertised length plus 0x77 tail is present.
 *
 * Backing storage is a primitively-typed ring buffer: no per-byte boxing and
 * O(1) front-drops, which keeps allocation churn near zero on the BLE callback
 * thread.
 */
class JbdFrameParser(private val maxBuffer: Int = 512) {

    private val buf = ByteArray(maxBuffer)
    private var head = 0
    private var count = 0

    fun reset() {
        head = 0
        count = 0
    }

    private fun at(i: Int): Byte = buf[(head + i) % maxBuffer]

    private fun dropFront() {
        head = (head + 1) % maxBuffer
        count--
    }

    private fun append(b: Byte) {
        if (count == maxBuffer) dropFront()
        buf[(head + count) % maxBuffer] = b
        count++
    }

    fun feed(chunk: ByteArray): ParseResult {
        for (b in chunk) append(b)

        val frames = ArrayList<JbdFrame>()
        var crcErrors = 0

        while (true) {
            // Drop anything before the header byte.
            while (count > 0 && (at(0).toInt() and 0xFF) != JbdCodec.HEADER) {
                dropFront()
            }
            // Need at least header + register + status + length.
            if (count < 4) break

            val length = at(3).toInt() and 0xFF
            val total = length + 7
            if (count < total) break // wait for the rest

            val frame = ByteArray(total) { at(it) }
            val tail = frame[total - 1].toInt() and 0xFF
            val data = frame.copyOfRange(4, 4 + length)
            val crcHi = frame[4 + length].toInt() and 0xFF
            val crcLo = frame[5 + length].toInt() and 0xFF
            val expected = JbdCodec.responseChecksum(data)
            val actual = (crcHi shl 8) or crcLo

            if (tail != JbdCodec.TAIL || expected != actual) {
                crcErrors++
                // Resync: drop just the header we validated against and retry.
                dropFront()
                continue
            }

            frames.add(
                JbdFrame(
                    register = frame[1].toInt() and 0xFF,
                    status = frame[2].toInt() and 0xFF,
                    data = data,
                    raw = frame,
                ),
            )
            repeat(total) { dropFront() }
        }

        return ParseResult(frames, crcErrors)
    }
}
