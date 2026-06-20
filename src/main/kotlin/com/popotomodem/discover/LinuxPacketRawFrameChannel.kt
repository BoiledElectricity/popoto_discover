package com.popotomodem.discover

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.net.NetworkInterface
import kotlin.math.min

internal object LinuxPacketAccess {
    fun isLinux(): Boolean {
        return System.getProperty("os.name").contains("linux", ignoreCase = true)
    }
}

internal class LinuxPacketRawFrameChannel private constructor(
    override val interfaceName: String,
    override val localMac: ByteArray,
    private val etherType: Int,
    private val fd: Int,
) : RawFrameChannel {
    private var debugReads = 0

    override fun send(frame: ByteArray) {
        val deadline = System.nanoTime() + SEND_TIMEOUT_MILLIS * 1_000_000L
        while (true) {
            val written = LinuxLibC.INSTANCE.send(fd, frame, frame.size, 0)
            if (written >= 0) {
                if (written.toInt() != frame.size) {
                    throw EthernetFrameException("AF_PACKET short write on $interfaceName: ${written}/${frame.size} bytes")
                }
                return
            }

            val errno = Native.getLastError()
            if (errno != LinuxLibC.EAGAIN && errno != LinuxLibC.EWOULDBLOCK) {
                throw EthernetFrameException("AF_PACKET send failed on $interfaceName: ${LinuxLibC.errorMessage(errno)}")
            }
            if (System.nanoTime() >= deadline) {
                throw EthernetFrameException(
                    "AF_PACKET send timed out on $interfaceName after ${SEND_TIMEOUT_MILLIS}ms: ${LinuxLibC.errorMessage(errno)}",
                )
            }
            Thread.sleep(1)
        }
    }

    override fun receive(timeoutMillis: Int): ByteArray? {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        val bufferSize = 65536
        val buffer = Memory(bufferSize.toLong())

        while (true) {
            val read = LinuxLibC.INSTANCE.read(fd, buffer, bufferSize)
            if (read >= 0) {
                val frame = buffer.getByteArray(0, read.toInt())
                if (debugReads < 8) {
                    debugReads++
                    L2Debug.log(
                        "AF_PACKET $interfaceName read ${frame.size} bytes ethertype=" +
                            "0x${EthernetFrameTransport.etherType(frame).toString(16)}",
                    )
                }
                if (frame.size >= EthernetFrameTransport.ETHERNET_HEADER_LEN &&
                    EthernetFrameTransport.etherType(frame) == etherType
                ) {
                    L2Debug.log(
                        "AF_PACKET $interfaceName received ${frame.size} bytes " +
                            "${EthernetFrameTransport.macToText(frame.copyOfRange(6, 12))} -> " +
                            EthernetFrameTransport.macToText(frame.copyOfRange(0, 6)),
                    )
                    return frame
                }
                continue
            }

            val errno = Native.getLastError()
            if (errno != LinuxLibC.EAGAIN && errno != LinuxLibC.EWOULDBLOCK) {
                throw EthernetFrameException("AF_PACKET receive failed on $interfaceName: ${LinuxLibC.errorMessage(errno)}")
            }

            if (timeoutMillis <= 0) {
                return null
            }

            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remainingMillis <= 0) {
                return null
            }
            Thread.sleep(min(2, remainingMillis).toLong())
        }
    }

    override fun close() {
        LinuxLibC.INSTANCE.close(fd)
    }

    companion object {
        private const val SEND_TIMEOUT_MILLIS = 2_000L

        fun open(interfaceName: String, sourceMac: ByteArray, etherType: Int): LinuxPacketRawFrameChannel {
            if (!LinuxPacketAccess.isLinux()) {
                throw EthernetFrameException("AF_PACKET is only available on Linux")
            }

            val networkInterface = NetworkInterface.getByName(interfaceName)
                ?: throw EthernetFrameException("network interface not found: $interfaceName")
            val ifIndex = networkInterface.index
            if (ifIndex <= 0) {
                throw EthernetFrameException("could not read interface index for $interfaceName")
            }

            val fd = LinuxLibC.INSTANCE.socket(LinuxLibC.AF_PACKET, LinuxLibC.SOCK_RAW, LinuxLibC.htons(etherType))
            if (fd < 0) {
                throw EthernetFrameException("AF_PACKET socket failed on $interfaceName: ${LinuxLibC.lastErrorMessage()}")
            }

            try {
                val address = SockAddrLl().apply {
                    sll_family = LinuxLibC.AF_PACKET.toShort()
                    sll_protocol = LinuxLibC.htons(etherType).toShort()
                    sll_ifindex = ifIndex
                    sll_halen = EthernetFrameTransport.ETHERNET_ADDR_LEN.toByte()
                    sourceMac.copyInto(sll_addr, endIndex = minOf(sourceMac.size, sll_addr.size))
                }
                address.write()
                if (LinuxLibC.INSTANCE.bind(fd, address.pointer, address.size()) < 0) {
                    throw EthernetFrameException("AF_PACKET bind failed on $interfaceName: ${LinuxLibC.lastErrorMessage()}")
                }

                val flags = LinuxLibC.INSTANCE.fcntl(fd, LinuxLibC.F_GETFL, 0)
                if (flags < 0 ||
                    LinuxLibC.INSTANCE.fcntl(fd, LinuxLibC.F_SETFL, flags or LinuxLibC.O_NONBLOCK) < 0
                ) {
                    throw EthernetFrameException("AF_PACKET nonblocking setup failed on $interfaceName: ${LinuxLibC.lastErrorMessage()}")
                }

                return LinuxPacketRawFrameChannel(interfaceName, sourceMac, etherType, fd)
            } catch (e: Exception) {
                LinuxLibC.INSTANCE.close(fd)
                throw e
            }
        }
    }
}

@Structure.FieldOrder(
    "sll_family",
    "sll_protocol",
    "sll_ifindex",
    "sll_hatype",
    "sll_pkttype",
    "sll_halen",
    "sll_addr",
)
internal class SockAddrLl : Structure() {
    @JvmField var sll_family: Short = 0
    @JvmField var sll_protocol: Short = 0
    @JvmField var sll_ifindex: Int = 0
    @JvmField var sll_hatype: Short = 0
    @JvmField var sll_pkttype: Byte = 0
    @JvmField var sll_halen: Byte = 0
    @JvmField var sll_addr: ByteArray = ByteArray(8)
}

internal interface LinuxLibC : Library {
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun bind(sockfd: Int, addr: Pointer, addrlen: Int): Int
    fun fcntl(fd: Int, cmd: Int, arg: Int): Int
    fun send(sockfd: Int, buf: ByteArray, len: Int, flags: Int): Long
    fun read(fd: Int, buf: Pointer, count: Int): Long
    fun close(fd: Int): Int
    fun strerror(errnum: Int): String?

    companion object {
        const val AF_PACKET = 17
        const val SOCK_RAW = 3
        const val F_GETFL = 3
        const val F_SETFL = 4
        const val O_NONBLOCK = 2048
        const val EAGAIN = 11
        const val EWOULDBLOCK = 11

        val INSTANCE: LinuxLibC = Native.load("c", LinuxLibC::class.java)

        fun htons(value: Int): Int {
            return ((value and 0xff) shl 8) or ((value ushr 8) and 0xff)
        }

        fun lastErrorMessage(): String {
            return errorMessage(Native.getLastError())
        }

        fun errorMessage(errno: Int): String {
            return strerrorSafe(errno) ?: "errno $errno"
        }

        private fun strerrorSafe(errno: Int): String? {
            return runCatching { INSTANCE.strerror(errno) }.getOrNull()
        }
    }
}
