package com.popotomodem.discover

import org.pcap4j.core.BpfProgram.BpfCompileMode
import org.pcap4j.core.PcapHandle
import org.pcap4j.core.PcapNativeException
import org.pcap4j.core.PcapNetworkInterface
import org.pcap4j.core.Pcaps
import java.io.Closeable
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.TimeoutException

class EthernetFrameException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class EthernetFrameTransport private constructor(
    val interfaceName: String,
    val etherType: Int,
    val localMac: ByteArray,
    private val handle: PcapHandle,
) : Closeable {
    fun send(frame: ByteArray) {
        handle.sendPacket(frame)
    }

    fun receive(timeoutMillis: Int): ByteArray? {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (true) {
            try {
                val packet = handle.nextPacketEx
                val frame = packet.rawData
                if (frame.size >= ETHERNET_HEADER_LEN && etherType(frame) == etherType) {
                    return frame
                }
            } catch (_: TimeoutException) {
                if (timeoutMillis <= 0 || System.nanoTime() >= deadline) {
                    return null
                }
            }

            if (timeoutMillis <= 0 || System.nanoTime() >= deadline) {
                return null
            }
        }
    }

    override fun close() {
        handle.close()
    }

    companion object {
        const val ETHERNET_HEADER_LEN = 14
        const val MIN_ETHERNET_FRAME_LEN = 60
        val BROADCAST_MAC = ByteArray(6) { 0xff.toByte() }

        fun open(interfaceName: String, etherType: Int, timeoutMillis: Int): EthernetFrameTransport {
            val sourceMac = NetworkInterface.getByName(interfaceName)?.hardwareAddress
                ?: throw EthernetFrameException("could not read MAC address for $interfaceName")
            val pcapInterface = runCatching { findPcapInterface(interfaceName, sourceMac) }
                .getOrElse {
                    throw EthernetFrameException(CaptureDiagnostics.rawEthernetFailure(interfaceName, it.message), it)
                }
                ?: throw EthernetFrameException(CaptureDiagnostics.rawEthernetFailure(interfaceName, "pcap interface not found"))

            try {
                val handle = pcapInterface.openLive(
                    65536,
                    PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                    timeoutMillis.coerceIn(1, 50),
                )
                handle.setBlockingMode(PcapHandle.BlockingMode.NONBLOCKING)
                handle.setFilter("ether proto 0x${etherType.toString(16)}", BpfCompileMode.OPTIMIZE)
                return EthernetFrameTransport(interfaceName, etherType, sourceMac, handle)
            } catch (e: PcapNativeException) {
                throw EthernetFrameException(CaptureDiagnostics.rawEthernetFailure(interfaceName, e.message), e)
            }
        }

        fun candidateInterfaces(): List<String> = RawEthernetTransport.candidateInterfaces()

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

        private fun findPcapInterface(interfaceName: String, sourceMac: ByteArray): PcapNetworkInterface? {
            val wanted = interfaceName.lowercase(Locale.US)
            val devices = Pcaps.findAllDevs()
            return devices.firstOrNull { dev ->
                dev.getLinkLayerAddresses().any { it.address.contentEquals(sourceMac) }
            } ?: devices.firstOrNull { dev ->
                dev.name.lowercase(Locale.US) == wanted ||
                    dev.description?.lowercase(Locale.US)?.contains(wanted) == true
            }
        }
    }
}
