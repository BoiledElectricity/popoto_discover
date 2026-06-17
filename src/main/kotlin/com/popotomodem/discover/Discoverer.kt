package com.popotomodem.discover

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class TransportMode {
    AUTO,
    UDP,
    L2,
    ALL;

    companion object {
        fun parse(value: String): TransportMode {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown transport '$value' (expected auto, udp, l2, or all)")
        }
    }
}

data class DiscoveryOptions(
    val timeoutSeconds: Double = Protocol.DEFAULT_TIMEOUT_SECONDS,
    val secret: String? = null,
    val transportMode: TransportMode = TransportMode.AUTO,
    val interfaces: List<String> = emptyList(),
    val retries: Int = 3,
)

data class DiscoveryPath(
    val transport: String,
    val interfaceName: String? = null,
    val sourceIp: String? = null,
    val sourceMac: String? = null,
)

data class Device(
    val fields: MutableMap<String, JsonElement>,
    val paths: MutableList<DiscoveryPath> = mutableListOf(),
) {
    fun text(field: String): String? = fields[field]?.jsonPrimitive?.contentOrNull
}

class Discoverer {
    fun discover(options: DiscoveryOptions): List<Device> {
        val timeoutMillis = max(1, (options.timeoutSeconds * 1000).toInt())
        val retries = max(1, options.retries)
        val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
        val request = Protocol.createDiscoverMessage(nonce, options.secret)

        val useUdp = options.transportMode in setOf(TransportMode.AUTO, TransportMode.UDP, TransportMode.ALL)
        val useL2 = options.transportMode in setOf(TransportMode.AUTO, TransportMode.L2, TransportMode.ALL)

        var udpFailure: String? = null
        val udp = if (useUdp) {
            runCatching { UdpDiscoveryTransport() }
                .onFailure { udpFailure = "UDP unavailable: ${it.message ?: it::class.simpleName}" }
                .getOrNull()
        } else {
            null
        }
        val l2Failures = mutableListOf<String>()
        val l2Transports = if (useL2) openL2Transports(options.interfaces, timeoutMillis, l2Failures) else emptyList()

        if (udp == null && l2Transports.isEmpty()) {
            val reasons = buildList {
                if (useUdp) {
                    add(udpFailure ?: "UDP unavailable")
                } else {
                    add("UDP disabled by transport mode ${options.transportMode.name.lowercase()}")
                }
                if (useL2) {
                    addAll(l2Failures.ifEmpty { listOf("raw Ethernet has no candidate interfaces") })
                } else {
                    add("raw Ethernet disabled by transport mode ${options.transportMode.name.lowercase()}")
                }
            }
            throw RuntimeException("no discovery transports are available: ${reasons.joinToString("; ")}")
        }

        val udpTargets = if (udp != null) UdpDiscoveryTransport.broadcastTargets(options.interfaces.ifEmpty { null }) else emptyList()
        val devices = mutableListOf<Device>()
        val byIdentity = mutableMapOf<Pair<String, String>, Device>()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val intervalMillis = max(200, min(500, timeoutMillis / retries))
        var sendCount = 0
        var nextSend = 0L

        val used = buildList {
            if (udp != null) add("UDP")
            if (l2Transports.isNotEmpty()) add("raw Ethernet")
        }
        println("Sent discovery (nonce=$nonce) over ${used.joinToString(", ")}, waiting for replies...")

        while (System.nanoTime() < deadline) {
            val nowMillis = System.currentTimeMillis()
            if (sendCount < retries && nowMillis >= nextSend) {
                if (udp != null) {
                    runCatching { udp.send(request, udpTargets) }
                }
                for (l2 in l2Transports) {
                    runCatching { l2.sendJson(L2Protocol.broadcastAddress(), request) }
                }
                sendCount++
                nextSend = nowMillis + intervalMillis
            }

            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(0)
            if (remainingMillis <= 0) {
                break
            }

            if (udp != null) {
                val packet = runCatching { udp.receive(min(50, remainingMillis)) }.getOrNull()
                if (packet != null) {
                    acceptReply(packet.message, nonce, options.secret, DiscoveryPath("udp", sourceIp = packet.sourceIp))
                        ?.let { mergeDevice(devices, byIdentity, it) }
                }
            } else {
                Thread.sleep(min(10, remainingMillis).toLong())
            }

            for (l2 in l2Transports) {
                while (true) {
                    val packet = runCatching { l2.receive(1) }.getOrNull() ?: break
                    acceptReply(
                        packet.message,
                        nonce,
                        options.secret,
                        DiscoveryPath(
                            transport = "l2",
                            interfaceName = packet.interfaceName,
                            sourceMac = packet.sourceMac,
                        ),
                    )?.let { mergeDevice(devices, byIdentity, it) }
                }
            }
        }

        udp?.close()
        l2Transports.forEach { it.close() }
        return devices
    }

    private fun openL2Transports(
        interfaces: List<String>,
        timeoutMillis: Int,
        failures: MutableList<String>,
    ): List<RawEthernetTransport> {
        val names = interfaces.ifEmpty { RawEthernetTransport.candidateInterfaces() }
        if (names.isEmpty()) {
            failures += "raw Ethernet has no candidate interfaces"
        }
        return names.mapNotNull { name ->
            runCatching { RawEthernetTransport.open(name, timeoutMillis) }
                .onFailure {
                    val message = "raw Ethernet unavailable on $name: ${it.message ?: it::class.simpleName}"
                    failures += message
                    System.err.println(message)
                }
                .getOrNull()
        }
    }

    private fun acceptReply(
        message: JsonObject,
        nonce: String,
        secret: String?,
        path: DiscoveryPath,
    ): Device? = runCatching {
        if (Protocol.text(message, "cmd") != Protocol.MSG_DISCOVER_REPLY) {
            return@runCatching null
        }
        val replyNonce = Protocol.text(message, "nonce")
        if (!replyNonce.isNullOrEmpty() && replyNonce != nonce) {
            return@runCatching null
        }
        if (!secret.isNullOrEmpty() && !Protocol.verifyAuth(message, secret)) {
            return@runCatching null
        }
        Protocol.validateDiscoverReply(message)

        Device(message.toMutableMap(), mutableListOf(path))
    }.getOrNull()

    private fun mergeDevice(
        devices: MutableList<Device>,
        byIdentity: MutableMap<Pair<String, String>, Device>,
        incoming: Device,
    ) {
        val key = identity(incoming)
        if (key == null) {
            devices += incoming
            return
        }

        val existing = byIdentity[key]
        if (existing == null) {
            byIdentity[key] = incoming
            devices += incoming
            return
        }

        for (path in incoming.paths) {
            if (path !in existing.paths) {
                existing.paths += path
            }
        }
    }

    private fun identity(device: Device): Pair<String, String>? {
        device.deviceIdText()?.let { return "device_id" to it.lowercase() }
        usableIdentity(device.text("serial"))?.let { return "serial" to it.lowercase() }
        for (field in listOf("hostname", "name")) {
            usableIdentity(device.text(field))?.let { return field to it.lowercase() }
        }
        val mac = device.text("mac")?.trim().orEmpty()
        if (mac.isNotEmpty() && mac != "00:00:00:00:00:00") {
            return "mac" to mac.lowercase()
        }
        return null
    }
}
