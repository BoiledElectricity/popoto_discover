package com.popotomodem.discover

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class PmmEthConsoleException(message: String) : RuntimeException(message)

class PmmEthConsoleClient private constructor(
    private val transport: EthernetFrameTransport,
    private val ignoredPeers: Set<String>,
    initialPeer: ByteArray? = null,
) : Closeable {
    private var peer: ByteArray? = initialPeer
    private var sequence = 1
    private var lastProbeMillis = 0L

    fun attach(timeoutMillis: Int, onOutput: (String) -> Unit = {}) {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(1) * 1_000_000L
        sendProbe(force = true)
        while (System.nanoTime() < deadline) {
            sendProbe()
            val frame = transport.receive(50)
            val packet = frame?.let { parseFrame(it) } ?: continue
            handlePacket(packet, onOutput)
            if (peer != null) {
                return
            }
        }
        throw PmmEthConsoleException("timed out waiting for PMM U-Boot Ethernet console")
    }

    fun sendCommand(command: String) {
        sendData((command.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
    }

    fun sendCtrlC() {
        sendData(byteArrayOf(0x03))
    }

    fun peerMacText(): String? = peer?.let { EthernetFrameTransport.macToText(it) }

    fun readUntil(
        timeoutMillis: Int,
        onOutput: (String) -> Unit = {},
        predicate: (String) -> Boolean,
    ): String {
        val output = StringBuilder()
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(1) * 1_000_000L
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1)
            val frame = transport.receive(min(50, remaining))
            val packet = frame?.let { parseFrame(it) } ?: continue
            val text = handlePacket(packet, onOutput)
            if (text.isNotEmpty()) {
                output.append(text)
                if (predicate(output.toString())) {
                    return output.toString()
                }
            }
        }
        return output.toString()
    }

    fun waitForAoEExport(timeoutMillis: Int, onOutput: (String) -> Unit = {}) {
        val output = readUntil(timeoutMillis, onOutput) { text ->
            text.contains("AoE: Ctrl-C to stop export") ||
                text.contains("exporting mmc") ||
                text.contains("Unknown command")
        }
        if (output.contains("Unknown command")) {
            throw PmmEthConsoleException("U-Boot rejected aoe command")
        }
    }

    fun resetFromAoE(onOutput: (String) -> Unit = {}) {
        sendCtrlC()
        readUntil(5_000, onOutput) { it.contains("u-boot=>") || it.contains("=>") }
        sendCommand("reset")
    }

    private fun sendProbe(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProbeMillis < DEFAULT_PROBE_INTERVAL_MS) {
            return
        }
        lastProbeMillis = now
        transport.send(frame(EthernetFrameTransport.BROADCAST_MAC, PMMETH_PROBE, ByteArray(0)))
    }

    private fun sendData(data: ByteArray) {
        val destination = peer ?: EthernetFrameTransport.BROADCAST_MAC
        var offset = 0
        while (offset < data.size) {
            val count = min(PMMETH_MAX_PAYLOAD, data.size - offset)
            transport.send(frame(destination, PMMETH_DATA, data.copyOfRange(offset, offset + count)))
            offset += count
        }
    }

    private fun frame(destination: ByteArray, frameType: Int, payload: ByteArray): ByteArray {
        if (payload.size > PMMETH_MAX_PAYLOAD) {
            throw PmmEthConsoleException("console payload too large: ${payload.size}")
        }
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        header.put(PMMETH_MAGIC)
        header.put(PMMETH_VERSION.toByte())
        header.put(frameType.toByte())
        header.putShort(payload.size.toShort())
        header.putInt(nextSequence())
        return EthernetFrameTransport.buildFrame(destination, transport.localMac, ETH_P_PMMETH, header.array() + payload)
    }

    private fun nextSequence(): Int {
        val current = sequence
        sequence++
        if (sequence == 0) {
            sequence = 1
        }
        return current
    }

    private fun parseFrame(frame: ByteArray): ConsolePacket? {
        if (frame.size < EthernetFrameTransport.ETHERNET_HEADER_LEN + 12) {
            return null
        }
        if (EthernetFrameTransport.etherType(frame) != ETH_P_PMMETH) {
            return null
        }
        val payloadOffset = EthernetFrameTransport.ETHERNET_HEADER_LEN
        if (!frame.copyOfRange(payloadOffset, payloadOffset + 4).contentEquals(PMMETH_MAGIC)) {
            return null
        }
        val version = frame[payloadOffset + 4].toInt() and 0xff
        if (version != PMMETH_VERSION) {
            return null
        }
        val frameType = frame[payloadOffset + 5].toInt() and 0xff
        val payloadLen = (((frame[payloadOffset + 6].toInt() and 0xff) shl 8) or
            (frame[payloadOffset + 7].toInt() and 0xff))
        val dataOffset = payloadOffset + 12
        if (payloadLen > frame.size - dataOffset) {
            return null
        }
        return ConsolePacket(
            source = frame.copyOfRange(6, 12),
            frameType = frameType,
            payload = frame.copyOfRange(dataOffset, dataOffset + payloadLen),
        )
    }

    private fun handlePacket(packet: ConsolePacket, onOutput: (String) -> Unit): String {
        if (packet.source.contentEquals(transport.localMac)) {
            return ""
        }
        val sourceText = EthernetFrameTransport.macToText(packet.source)
        if (sourceText in ignoredPeers) {
            return ""
        }
        val currentPeer = peer
        if (currentPeer != null && !packet.source.contentEquals(currentPeer)) {
            return ""
        }
        if (packet.frameType == PMMETH_HELLO || packet.frameType == PMMETH_DATA) {
            if (peer == null) {
                peer = packet.source
            }
            if (packet.payload.isNotEmpty()) {
                val text = packet.payload.toString(Charsets.UTF_8)
                onOutput(text)
                return text
            }
        } else if (packet.frameType == PMMETH_DETACH && currentPeer != null && packet.source.contentEquals(currentPeer)) {
            peer = null
        }
        return ""
    }

    override fun close() {
        transport.close()
    }

    private data class ConsolePacket(
        val source: ByteArray,
        val frameType: Int,
        val payload: ByteArray,
    )

    companion object {
        const val ETH_P_PMMETH = 0x88B5
        private val PMMETH_MAGIC = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte())
        private const val PMMETH_VERSION = 1
        private const val PMMETH_PROBE = 1
        private const val PMMETH_HELLO = 2
        private const val PMMETH_DATA = 3
        private const val PMMETH_DETACH = 4
        private const val PMMETH_MAX_PAYLOAD = 1400
        private const val DEFAULT_PROBE_INTERVAL_MS = 250L

        fun open(
            interfaceName: String,
            timeoutMillis: Int = 250,
            peerMac: String? = null,
            ignoredPeers: Set<String> = emptySet(),
        ): PmmEthConsoleClient {
            return PmmEthConsoleClient(
                transport = EthernetFrameTransport.open(interfaceName, ETH_P_PMMETH, timeoutMillis),
                ignoredPeers = ignoredPeers.map { it.lowercase() }.toSet(),
                initialPeer = peerMac?.let { EthernetFrameTransport.parseMac(it) },
            )
        }
    }
}
