package com.jbdbms.karoo.ble

/**
 * Best-effort implementation of the JBD BLE authentication handshake
 * (header FF AA) used by newer, password-protected modules.
 *
 * The exact semantics of this handshake are only loosely documented (see the
 * open_battery protocol notes). It is therefore only attempted as a *fallback*
 * when a plain 0xDD read is answered with nothing, and every packet is logged to
 * the raw console so the user can see exactly what was exchanged.
 *
 * Sequence:
 *   1. App key        cmd 0x15, data "000000"        (0x00 = OK, password required)
 *   2. Request random cmd 0x17                        (BMS returns one random byte)
 *   3. Password       cmd 0x18, XOR(password, MAC, random)
 *   4. Request root   cmd 0x17
 *   5. Root password  cmd 0x1D
 */
class JbdAuth(
    private val send: (ByteArray, String) -> Unit,
) {
    private enum class Stage { APP_KEY, REQUEST_RANDOM, PASSWORD, REQUEST_ROOT, ROOT_PASSWORD, DONE }

    private var stage = Stage.APP_KEY
    private var random: Int = 0
    private val password = "000000".toByteArray(Charsets.US_ASCII)

    fun start(mac: String) {
        this.mac = mac
        stage = Stage.APP_KEY
        send(packet(0x15, password), "auth: app key")
    }

    private var mac: String = ""

    /** Feed an FF/AA response. Returns true when the handshake is complete. */
    fun onPacket(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        val cmd = bytes[2].toInt() and 0xFF
        when (stage) {
            Stage.APP_KEY -> {
                stage = Stage.REQUEST_RANDOM
                send(packet(0x17, ByteArray(0)), "auth: request random")
            }
            Stage.REQUEST_RANDOM -> {
                // Response payload carries the random byte.
                random = if (bytes.size >= 5) bytes[4].toInt() and 0xFF else bytes.getOrElse(4) { 0 }.toInt()
                stage = Stage.PASSWORD
                send(packet(0x18, xorPassword()), "auth: password")
            }
            Stage.PASSWORD -> {
                stage = Stage.REQUEST_ROOT
                send(packet(0x17, ByteArray(0)), "auth: request root random")
            }
            Stage.REQUEST_ROOT -> {
                random = if (bytes.size >= 5) bytes[4].toInt() and 0xFF else 0
                stage = Stage.ROOT_PASSWORD
                send(packet(0x1D, xorPassword()), "auth: root password")
            }
            Stage.ROOT_PASSWORD -> {
                stage = Stage.DONE
                return true
            }
            Stage.DONE -> return true
        }
        return false
    }

    private fun xorPassword(): ByteArray {
        val macBytes = mac.filter { it.isLetterOrDigit() }.map { it.code.toByte() }.toByteArray()
        return ByteArray(password.size) { i ->
            val m = macBytes.getOrElse(i % macBytes.size.coerceAtLeast(1)) { 0 }
            (password[i].toInt() xor m.toInt() xor random).toByte()
        }
    }

    /** Builds `FF AA <cmd> <len> <data…> <checksum>`, checksum = sum(cmd+len+data)&0xFF. */
    private fun packet(cmd: Int, data: ByteArray): ByteArray {
        val out = ByteArray(4 + data.size + 1)
        out[0] = 0xFF.toByte()
        out[1] = 0xAA.toByte()
        out[2] = cmd.toByte()
        out[3] = data.size.toByte()
        System.arraycopy(data, 0, out, 4, data.size)
        var sum = cmd + data.size
        for (b in data) sum += b.toInt() and 0xFF
        out[out.size - 1] = (sum and 0xFF).toByte()
        return out
    }
}
