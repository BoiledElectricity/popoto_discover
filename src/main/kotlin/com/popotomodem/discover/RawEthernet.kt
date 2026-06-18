package com.popotomodem.discover

import kotlinx.serialization.json.JsonObject
import org.pcap4j.core.BpfProgram.BpfCompileMode
import org.pcap4j.core.PcapHandle
import org.pcap4j.core.PcapNativeException
import org.pcap4j.core.PcapNetworkInterface
import org.pcap4j.core.Pcaps
import java.io.Closeable
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.TimeoutException

class RawEthernetException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class RawEthernetTransport private constructor(
    val interfaceName: String,
    private val sourceMac: ByteArray,
    private val handle: PcapHandle,
) : Closeable {
    fun sendJson(destinationMac: ByteArray, message: JsonObject) {
        handle.sendPacket(L2Protocol.buildJsonFrame(destinationMac, sourceMac, message))
    }

    fun receive(timeoutMillis: Int): L2Packet? {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (true) {
            try {
                val packet = handle.nextPacketEx
                val parsed = L2Protocol.parseJsonFrame(packet.rawData, interfaceName)
                if (parsed != null) {
                    return parsed
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
        fun candidateInterfaces(): List<String> {
            return NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { nif ->
                    val name = nif.name ?: return@filter false
                    nif.isUp &&
                        !nif.isLoopback &&
                        !nif.isVirtual &&
                        nif.hardwareAddress?.size == 6 &&
                        !name.startsWith("br-") &&
                        !name.startsWith("docker") &&
                        !name.startsWith("veth") &&
                        !name.startsWith("virbr") &&
                        !name.startsWith("tailscale") &&
                        !name.startsWith("utun") &&
                        !name.startsWith("awdl") &&
                        !name.startsWith("llw") &&
                        !name.startsWith("bridge") &&
                        !name.startsWith("gif") &&
                        !name.startsWith("stf")
                }
                .map { it.name }
                .toList()
        }

        fun open(interfaceName: String, timeoutMillis: Int): RawEthernetTransport {
            val sourceMac = NetworkInterface.getByName(interfaceName)?.hardwareAddress
                ?: throw RawEthernetException("could not read MAC address for $interfaceName")
            val pcapInterface = runCatching { findPcapInterface(interfaceName, sourceMac) }
                .getOrElse {
                    throw RawEthernetException(CaptureDiagnostics.rawEthernetFailure(interfaceName, it.message), it)
                }
                ?: throw RawEthernetException(CaptureDiagnostics.rawEthernetFailure(interfaceName, "pcap interface not found"))

            try {
                val handle = pcapInterface.openLive(
                    65536,
                    PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                    timeoutMillis.coerceIn(1, 10),
                )
                handle.setBlockingMode(PcapHandle.BlockingMode.NONBLOCKING)
                handle.setFilter("ether proto 0x${L2Protocol.ETHER_TYPE.toString(16)}", BpfCompileMode.OPTIMIZE)
                return RawEthernetTransport(interfaceName, sourceMac, handle)
            } catch (e: PcapNativeException) {
                throw RawEthernetException(CaptureDiagnostics.rawEthernetFailure(interfaceName, e.message), e)
            }
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
