package com.popotomodem.discover

import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

data class UdpPacket(
    val sourceIp: String,
    val message: JsonObject,
)

class UdpDiscoveryTransport : Closeable {
    private val socket = DatagramSocket(null)

    init {
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(0))
    }

    fun send(message: JsonObject, targets: Collection<InetSocketAddress>) {
        val data = Protocol.encodeCompact(message)
        for (target in targets) {
            val packet = DatagramPacket(data, data.size, target.address, target.port)
            socket.send(packet)
        }
    }

    fun receive(timeoutMillis: Int): UdpPacket? {
        socket.soTimeout = timeoutMillis.coerceAtLeast(1)
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            val data = packet.data.copyOf(packet.length)
            UdpPacket(packet.address.hostAddress, Protocol.parseMessage(data))
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        fun broadcastTargets(interfaces: List<String>?): List<InetSocketAddress> {
            val targets = linkedSetOf(InetSocketAddress(InetAddress.getByName(Protocol.BROADCAST_ADDRESS), Protocol.DISCOVERY_PORT))
            val wanted = interfaces?.toSet().orEmpty()

            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (wanted.isNotEmpty() && nif.name !in wanted) {
                        continue
                    }
                    if (!nif.isUp || nif.isLoopback) {
                        continue
                    }
                    for (address in nif.interfaceAddresses) {
                        val broadcast = address.broadcast ?: continue
                        targets += InetSocketAddress(broadcast, Protocol.DISCOVERY_PORT)
                    }
                }
            } catch (_: SocketException) {
                // Keep the global broadcast target. Interface-specific broadcasts are best effort.
            }

            return targets.toList()
        }
    }
}
