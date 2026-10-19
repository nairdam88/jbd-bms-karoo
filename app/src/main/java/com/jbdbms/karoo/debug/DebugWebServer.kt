package com.jbdbms.karoo.debug

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tiny read-only HTTP endpoint that serves the debug report over the local
 * network, so the log can be copied from a phone without ADB or a cloud
 * account. Binds to an ephemeral port and requires a random path token.
 */
class DebugWebServer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val token = UUID.randomUUID().toString().replace("-", "").take(10)

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    @Volatile
    private var content: (() -> String)? = null

    /** Starts serving and returns the URL, or null if the port could not open. */
    fun start(provider: () -> String): String? {
        stop()
        return try {
            val socket = ServerSocket(0)
            serverSocket = socket
            content = provider
            val url = "http://${localIpv4() ?: "127.0.0.1"}:${socket.localPort}/$token"
            job = scope.launch { serve(socket) }
            url
        } catch (_: Exception) {
            stop()
            null
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        content = null
    }

    private fun serve(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            try {
                client.soTimeout = 5_000
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine()
                if (requestLine != null) {
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                    }
                    val path = requestLine.split(" ").getOrNull(1) ?: "/"
                    val authorized = path == "/$token"
                    val body = if (authorized) {
                        content?.invoke() ?: ""
                    } else {
                        "JBD BMS debug endpoint. Open the URL shown on the Karoo.\n"
                    }
                    val status = if (authorized) "200 OK" else "404 Not Found"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val header = buildString {
                        append("HTTP/1.1 $status\r\n")
                        append("Content-Type: text/plain; charset=utf-8\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    val out = client.getOutputStream()
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.write(bytes)
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
