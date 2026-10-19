package com.jbdbms.karoo.ble

/** A single timestamped connection event shown in the in-app debug log. */
data class ConnectionLogEntry(
    val timestampMs: Long,
    val message: String,
)

/**
 * Fixed-size, in-memory ring buffer of connection events.
 *
 * Always recording (events are rare) so a drop that already happened is still
 * visible when the user opens the debug log. The buffer is never persisted.
 */
class ConnectionLogBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<ConnectionLogEntry>()

    @Synchronized
    fun add(message: String, nowMs: Long = System.currentTimeMillis()) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(ConnectionLogEntry(nowMs, message))
    }

    @Synchronized
    fun snapshot(): List<ConnectionLogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_CAPACITY = 300
    }
}
