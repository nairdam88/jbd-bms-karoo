package com.jbdbms.karoo.data

/**
 * Fixed-capacity ring buffer for the in-app protocol console.
 *
 * Unlike rebuilding an immutable list on every BLE packet (`(list + frame)
 * .takeLast(n)`), this mutates a single deque in place so steady-state work is
 * O(1) and no intermediate lists are allocated.
 */
class RawFrameBuffer(private val capacity: Int) {

    private val deque = ArrayDeque<RawFrame>(capacity.coerceAtLeast(1))

    fun add(frame: RawFrame) {
        deque.addLast(frame)
        while (deque.size > capacity) deque.removeFirst()
    }

    fun clear() = deque.clear()

    fun snapshot(): List<RawFrame> = deque.toList()

    val size: Int get() = deque.size
}
