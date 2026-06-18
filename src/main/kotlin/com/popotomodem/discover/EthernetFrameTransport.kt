package com.popotomodem.discover

import java.io.Closeable

class EthernetFrameException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class EthernetFrameTransport private constructor(
    val interfaceName: String,
    val etherType: Int,
    val localMac: ByteArray,
    private val channel: RawFrameChannel,
) : Closeable {
    fun send(frame: ByteArray) {
        channel.send(frame)
    }

    fun receive(timeoutMillis: Int): ByteArray? {
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
            if (frame.size >= ETHERNET_HEADER_LEN && etherType(frame) == etherType) {
                return frame
            }
        }
    }

    override fun close() {
        channel.close()
    }

    companion object {
        const val ETHERNET_HEADER_LEN = 14
        const val ETHERNET_ADDR_LEN = 6
        const val MIN_ETHERNET_FRAME_LEN = 60
        val BROADCAST_MAC = ByteArray(6) { 0xff.toByte() }

        fun open(interfaceName: String, etherType: Int, timeoutMillis: Int): EthernetFrameTransport {
            val channel = RawFrameChannels.open(interfaceName, etherType, timeoutMillis)
            return EthernetFrameTransport(channel.interfaceName, etherType, channel.localMac, channel)
        }

        fun candidateInterfaces(): List<String> = RawFrameChannels.candidateInterfaces()

        fun etherType(frame: ByteArray): Int {
            if (frame.size < ETHERNET_HEADER_LEN) {
                return -1
            }
            return ((frame[12].toInt() and 0xff) shl 8) or (frame[13].toInt() and 0xff)
        }

        fun macToText(mac: ByteArray): String {
            return mac.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
        }

        fun parseMac(text: String): ByteArray {
            val parts = text.split(":")
            require(parts.size == 6) { "invalid MAC address: $text" }
            return parts.map { it.toInt(16).toByte() }.toByteArray()
        }

        fun buildFrame(destination: ByteArray, source: ByteArray, etherType: Int, payload: ByteArray): ByteArray {
            require(destination.size == 6) { "destination MAC must be 6 bytes" }
            require(source.size == 6) { "source MAC must be 6 bytes" }

            val frameLen = maxOf(MIN_ETHERNET_FRAME_LEN, ETHERNET_HEADER_LEN + payload.size)
            val frame = ByteArray(frameLen)
            destination.copyInto(frame, 0)
            source.copyInto(frame, 6)
            frame[12] = ((etherType ushr 8) and 0xff).toByte()
            frame[13] = (etherType and 0xff).toByte()
            payload.copyInto(frame, ETHERNET_HEADER_LEN)
            return frame
        }

    }
}
