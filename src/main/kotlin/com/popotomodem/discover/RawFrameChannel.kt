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

internal interface RawFrameChannel : Closeable {
    val interfaceName: String
    val localMac: ByteArray

    fun send(frame: ByteArray)
    fun receive(timeoutMillis: Int): ByteArray?
}

internal object RawFrameChannels {
    fun open(interfaceName: String, etherType: Int, timeoutMillis: Int): RawFrameChannel {
        val sourceMac = NetworkInterface.getByName(interfaceName)?.hardwareAddress
            ?: throw EthernetFrameException("could not read MAC address for $interfaceName")

        var windowsDriverFailure: Throwable? = null
        if (WindowsPmmNdisAccess.isWindows() && WindowsPmmNdisAccess.hasDriver()) {
            runCatching {
                return WindowsPmmNdisFrameChannel.open(interfaceName, sourceMac, etherType)
            }.onFailure {
                windowsDriverFailure = it
            }
        }

        val pcapInterface = runCatching { findPcapInterface(interfaceName, sourceMac) }
            .getOrElse {
                val detail = buildFailureDetail(interfaceName, it, windowsDriverFailure)
                throw EthernetFrameException(detail, it)
            }
            ?: throw EthernetFrameException(
                buildFailureDetail(interfaceName, RuntimeException("pcap interface not found"), windowsDriverFailure),
            )

        try {
            val handle = pcapInterface.openLive(
                65536,
                PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                timeoutMillis.coerceIn(1, 50),
            )
            handle.setBlockingMode(PcapHandle.BlockingMode.NONBLOCKING)
            handle.setFilter("ether proto 0x${etherType.toString(16)}", BpfCompileMode.OPTIMIZE)
            return PcapRawFrameChannel(interfaceName, sourceMac, etherType, handle)
        } catch (e: PcapNativeException) {
            throw EthernetFrameException(buildFailureDetail(interfaceName, e, windowsDriverFailure), e)
        }
    }

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

    private fun buildFailureDetail(interfaceName: String, pcapFailure: Throwable, windowsDriverFailure: Throwable?): String {
        val pcapDetail = CaptureDiagnostics.rawEthernetFailure(interfaceName, pcapFailure.message)
        return if (windowsDriverFailure == null) {
            pcapDetail
        } else {
            "PMM NDIS driver unavailable on $interfaceName: ${windowsDriverFailure.message}; $pcapDetail"
        }
    }
}

private class PcapRawFrameChannel(
    override val interfaceName: String,
    override val localMac: ByteArray,
    private val etherType: Int,
    private val handle: PcapHandle,
) : RawFrameChannel {
    override fun send(frame: ByteArray) {
        handle.sendPacket(frame)
    }

    override fun receive(timeoutMillis: Int): ByteArray? {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (true) {
            try {
                val frame = handle.nextPacketEx.rawData
                if (frame.size >= EthernetFrameTransport.ETHERNET_HEADER_LEN &&
                    EthernetFrameTransport.etherType(frame) == etherType
                ) {
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
}
