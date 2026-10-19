package com.jbdbms.karoo.protocol

/**
 * Encoder helpers for the JBD / XiaoXiang / Overkill Solar BMS UART protocol.
 *
 * Wire format (transparent UART bridged over BLE):
 *
 *   Request : DD A5 <reg> <len> <data…>      <crc_hi crc_lo> 77
 *   Response: DD <reg> <status> <len> <data…> <crc_hi crc_lo> 77
 *   Write   : DD 5A <reg> <len> <data…>      <crc_hi crc_lo> 77
 *
 * Checksum = 0x10000 - sum(covered bytes), transmitted big-endian.
 *   - Request/Write: covers register + length + data bytes (NOT the 0xA5/0x5A command byte).
 *   - Response:       covers length + data bytes only.
 */
object JbdCodec {

    const val HEADER: Int = 0xDD
    const val TAIL: Int = 0x77
    const val CMD_READ: Int = 0xA5
    const val CMD_WRITE: Int = 0x5A
    const val STATUS_OK: Int = 0x00
    const val STATUS_ERROR: Int = 0x80

    // Registers
    const val REG_BASIC_INFO: Int = 0x03
    const val REG_CELL_VOLTAGES: Int = 0x04
    const val REG_DEVICE_NAME: Int = 0x05

    /**
     * 16-bit checksum for a request/write frame.
     * Covers the register byte, length byte and data bytes — the command byte is excluded.
     */
    fun requestChecksum(register: Int, data: ByteArray): Int {
        var sum = register and 0xFF
        sum += data.size and 0xFF
        for (b in data) sum += b.toInt() and 0xFF
        return (0x10000 - sum) and 0xFFFF
    }

    /** 16-bit checksum for a response frame: covers length + data bytes only. */
    fun responseChecksum(data: ByteArray): Int {
        var sum = data.size and 0xFF
        for (b in data) sum += b.toInt() and 0xFF
        return (0x10000 - sum) and 0xFFFF
    }

    /** Builds a read request: `DD A5 <reg> 00 <crc> 77`. */
    fun buildRead(register: Int): ByteArray = build(CMD_READ, register, ByteArray(0))

    /**
     * Builds a write request: `DD 5A <reg> <len> <data…> <crc> 77`.
     * NOTE: this app is read-only and never calls this in the UI. Kept for completeness.
     */
    fun buildWrite(register: Int, data: ByteArray): ByteArray = build(CMD_WRITE, register, data)

    private fun build(command: Int, register: Int, data: ByteArray): ByteArray {
        val crc = requestChecksum(register, data)
        val out = ByteArray(data.size + 7)
        out[0] = HEADER.toByte()
        out[1] = command.toByte()
        out[2] = register.toByte()
        out[3] = data.size.toByte()
        System.arraycopy(data, 0, out, 4, data.size)
        out[4 + data.size] = ((crc shr 8) and 0xFF).toByte()
        out[5 + data.size] = (crc and 0xFF).toByte()
        out[6 + data.size] = TAIL.toByte()
        return out
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
