package com.popotomodem.discover

import kotlinx.serialization.json.JsonObject
import java.io.Closeable

class RawEthernetException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class RawEthernetTransport private constructor(
    val interfaceName: String,
    private val channel: RawFrameChannel,
) : Closeable {
    fun sendJson(destinationMac: ByteArray, message: JsonObject) {
        channel.send(L2Protocol.buildJsonFrame(destinationMac, channel.localMac, message))
    }

    fun receive(timeoutMillis: Int): L2Packet? {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (true) {
            val remainingMillis = if (timeoutMillis <= 0) {
                0
            } else {
                (((deadline - System.nanoTime()).coerceAtLeast(0) + 999_999L) / 1_000_000L).toInt()
            }
            if (timeoutMillis > 0 && remainingMillis <= 0) {
                return null
            }
            val frame = channel.receive(remainingMillis.coerceAtLeast(0)) ?: return null
            L2Protocol.parseJsonFrame(frame, interfaceName)?.let {
                L2Debug.log("parsed ${Protocol.text(it.message, "cmd")} from ${it.sourceMac} on $interfaceName")
                return it
            }
        }
    }

    override fun close() {
        channel.close()
    }

    companion object {
        fun candidateInterfaces(): List<String> = RawFrameChannels.candidateInterfaces()

        fun open(interfaceName: String, timeoutMillis: Int): RawEthernetTransport {
            val channel = try {
                RawFrameChannels.open(interfaceName, L2Protocol.ETHER_TYPE, timeoutMillis)
            } catch (e: EthernetFrameException) {
                throw RawEthernetException(e.message ?: "raw Ethernet unavailable on $interfaceName", e)
            }
            return RawEthernetTransport(channel.interfaceName, channel)
        }
    }
}
