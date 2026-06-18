package com.popotomodem.discover

import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class L2Packet(
    val sourceMac: String,
    val destinationMac: String,
    val message: JsonObject,
    val interfaceName: String,
)

object L2Protocol {
    const val ETHER_TYPE = 0x88B6
    private const val ETHERNET_HEADER_LEN = 14
    private const val MIN_ETHERNET_FRAME_LEN = 60
    private const val MAX_JSON_LEN = 1400
    private val broadcastMac = ByteArray(6) { 0xff.toByte() }
    private val magic = byteArrayOf('P'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte())

    fun broadcastAddress(): ByteArray = broadcastMac.copyOf()

    fun buildJsonFrame(destinationMac: ByteArray, sourceMac: ByteArray, message: JsonObject): ByteArray {
        require(destinationMac.size == 6) { "destination MAC must be 6 bytes" }
        require(sourceMac.size == 6) { "source MAC must be 6 bytes" }

        val json = Protocol.encodeCompact(message)
        if (json.size > MAX_JSON_LEN) {
            throw IllegalArgumentException("L2 discovery message too large: ${json.size} bytes")
        }

        val frameLen = maxOf(MIN_ETHERNET_FRAME_LEN, ETHERNET_HEADER_LEN + 8 + json.size)
        val frame = ByteBuffer.allocate(frameLen).order(ByteOrder.BIG_ENDIAN)
        frame.put(destinationMac)
        frame.put(sourceMac)
        frame.putShort(ETHER_TYPE.toShort())
        frame.put(magic)
        frame.put(1.toByte())
        frame.put(1.toByte())
        frame.putShort(json.size.toShort())
        frame.put(json)
        return frame.array()
    }

    fun parseJsonFrame(frame: ByteArray, interfaceName: String): L2Packet? {
        if (frame.size < ETHERNET_HEADER_LEN + 8) {
            return null
        }

        val etherType = ((frame[12].toInt() and 0xff) shl 8) or (frame[13].toInt() and 0xff)
        if (etherType != ETHER_TYPE) {
            return null
        }

        val payload = ByteBuffer.wrap(frame, ETHERNET_HEADER_LEN, frame.size - ETHERNET_HEADER_LEN)
            .order(ByteOrder.BIG_ENDIAN)
        val gotMagic = ByteArray(4)
        payload.get(gotMagic)
        if (!gotMagic.contentEquals(magic)) {
            return null
        }

        val version = payload.get().toInt() and 0xff
        val payloadType = payload.get().toInt() and 0xff
        val jsonLen = payload.short.toInt() and 0xffff
        if (version != 1 || payloadType != 1 || jsonLen > payload.remaining()) {
            return null
        }

        val jsonBytes = ByteArray(jsonLen)
        payload.get(jsonBytes)

        return try {
            L2Packet(
                sourceMac = macToText(frame.copyOfRange(6, 12)),
                destinationMac = macToText(frame.copyOfRange(0, 6)),
                message = Protocol.parseMessage(jsonBytes),
                interfaceName = interfaceName,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun macToText(mac: ByteArray): String {
        return mac.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
    }

    fun parseMac(text: String): ByteArray {
        val parts = text.lowercase(Locale.US).split(":")
        require(parts.size == 6) { "invalid MAC address: $text" }
        return parts.map { it.toInt(16).toByte() }.toByteArray()
    }
}
