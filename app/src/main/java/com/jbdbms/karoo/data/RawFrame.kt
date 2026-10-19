package com.jbdbms.karoo.data

enum class Direction { TX, RX }

/** One raw BLE chunk, for the in-app protocol console. */
data class RawFrame(
    val direction: Direction,
    val bytes: ByteArray,
    val note: String,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawFrame) return false
        return direction == other.direction &&
            bytes.contentEquals(other.bytes) &&
            note == other.note &&
            timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = direction.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + note.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/** A discovered BLE device. */
data class ScanResult(
    val name: String?,
    val address: String,
    val rssi: Int,
)
