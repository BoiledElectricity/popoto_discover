package com.popotomodem.discover

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class PshellTelnetClient private constructor(
    private val host: String,
    private val socket: Socket,
) : Closeable {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    fun setIp(newIp: String, netmask: String, interfaceName: String = "eth0"): String {
        requireIpv4("new IP address", newIp)
        requireNetmask(netmask)

        readUntil(timeoutMillis = 8_000, quietTimeoutMillis = 8_000) {
            it.contains("Popoto->") || it.contains("(DISCONNECTED)Popoto->")
        }

        val command = "setIP $newIp $netmask $interfaceName"
        writeLine(command)
        val transcript = readUntil(timeoutMillis = 8_000, quietTimeoutMillis = 1_000) {
            it.contains("IP address on $interfaceName:") || it.contains("Popoto->")
        }

        val errorLine = transcript.lineSequence().firstOrNull { line ->
            line.contains("Usage:", ignoreCase = true) ||
                line.contains("Please enter", ignoreCase = true) ||
                line.contains("Failed to set IP", ignoreCase = true) ||
                line.contains("No such interface", ignoreCase = true)
        }
        if (errorLine != null) {
            throw RuntimeException("pshell setIP failed: ${errorLine.trim()}")
        }

        return transcript.trim().ifBlank { "pshell accepted setIP; telnet session closed after IP change" }
    }

    private fun writeLine(line: String) {
        output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readUntil(
        timeoutMillis: Int,
        quietTimeoutMillis: Int,
        done: (String) -> Boolean,
    ): String {
        val out = StringBuilder()
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(1) * 1_000_000L
        var lastByteAt = System.nanoTime()

        while (System.nanoTime() < deadline) {
            if (done(out.toString())) {
                return out.toString()
            }
            if (out.isNotEmpty() && System.nanoTime() - lastByteAt > quietTimeoutMillis.coerceAtLeast(1) * 1_000_000L) {
                return out.toString()
            }

            socket.soTimeout = 100
            try {
                val b = input.read()
                if (b < 0) {
                    return out.toString()
                }
                lastByteAt = System.nanoTime()
                handleByte(b, out)
            } catch (_: SocketTimeoutException) {
                // Keep waiting until either the prompt arrives or the quiet/deadline timer fires.
            }
        }

        return out.toString()
    }

    private fun handleByte(byte: Int, out: StringBuilder) {
        if (byte != IAC) {
            out.append(byte.toChar())
            return
        }

        val command = input.read()
        if (command < 0) {
            return
        }
        if (command == IAC) {
            out.append(IAC.toChar())
            return
        }
        if (command == DO || command == DONT || command == WILL || command == WONT) {
            val option = input.read()
            if (option >= 0) {
                val response = if (command == DO) WONT else if (command == WILL) DONT else null
                if (response != null) {
                    output.write(byteArrayOf(IAC.toByte(), response.toByte(), option.toByte()))
                    output.flush()
                }
            }
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        private const val IAC = 255
        private const val DO = 253
        private const val DONT = 254
        private const val WILL = 251
        private const val WONT = 252

        fun open(host: String, timeoutMillis: Int = 5_000): PshellTelnetClient {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, 23), timeoutMillis.coerceAtLeast(1))
            return PshellTelnetClient(host, socket)
        }

        fun setIp(host: String, newIp: String, netmask: String, interfaceName: String = "eth0"): CommandResponse {
            val transcript = open(host).use { it.setIp(newIp, netmask, interfaceName) }
            return CommandResponse(
                sourceIp = host,
                message = JsonObject(
                    mapOf(
                        "cmd" to JsonPrimitive(Protocol.MSG_SET_IP_REPLY),
                        "status" to JsonPrimitive("ok"),
                        "ip" to JsonPrimitive(newIp),
                        "pshell" to JsonPrimitive("1"),
                        "stdout" to JsonPrimitive(transcript),
                    ),
                ),
            )
        }

        private fun requireIpv4(label: String, value: String) {
            val parts = value.split(".")
            if (parts.size != 4 || parts.any { it.toIntOrNull()?.let { octet -> octet !in 0..255 } != false }) {
                throw IllegalArgumentException("invalid $label: $value")
            }
        }

        private fun requireNetmask(value: String) {
            requireIpv4("netmask", value)
            val bits = value.split(".").joinToString("") { it.toInt().toString(2).padStart(8, '0') }
            if (!Regex("^1*0*$").matches(bits)) {
                throw IllegalArgumentException("invalid netmask: $value")
            }
        }
    }
}
