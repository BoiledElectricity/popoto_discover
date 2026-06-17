package com.popotomodem.discover

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.math.min

data class CommandOptions(
    val timeoutSeconds: Double = Protocol.DEFAULT_TIMEOUT_SECONDS,
    val secret: String? = null,
    val interfaces: List<String> = emptyList(),
    val transportMode: TransportMode = TransportMode.AUTO,
)

data class CommandResponse(
    val sourceIp: String,
    val message: JsonObject,
) {
    fun text(field: String): String? = message[field]?.jsonPrimitive?.contentOrNull
}

class CommandClient {
    fun setIp(
        target: TargetSelector,
        newIp: String,
        netmask: String,
        gateway: String,
        options: CommandOptions,
    ): CommandResponse? {
        val nonce = nonce()
        val request = Protocol.createSetIpMessage(
            nonce = nonce,
            target = target,
            newIp = newIp,
            netmask = netmask,
            gateway = gateway,
            secret = options.secret,
        )
        return sendRequest(request, nonce, Protocol.MSG_SET_IP_REPLY, options)
    }

    fun setRtc(target: TargetSelector, rtc: String, options: CommandOptions): CommandResponse? {
        val nonce = nonce()
        val request = Protocol.createSetRtcMessage(nonce, target, rtc, options.secret)
        return sendRequest(request, nonce, Protocol.MSG_SET_RTC_REPLY, options)
    }

    fun getRtc(target: TargetSelector, options: CommandOptions): CommandResponse? {
        val nonce = nonce()
        val request = Protocol.createGetRtcMessage(nonce, target, options.secret)
        return sendRequest(request, nonce, Protocol.MSG_GET_RTC_REPLY, options)
    }

    fun setParam(target: TargetSelector, name: String, value: String, options: CommandOptions): CommandResponse? {
        val nonce = nonce()
        val request = Protocol.createSetParamMessage(nonce, target, name, parseParamValue(value), options.secret)
        return sendRequest(request, nonce, Protocol.MSG_SET_PARAM_REPLY, options)
    }

    fun getVersion(target: TargetSelector, options: CommandOptions): CommandResponse? {
        val nonce = nonce()
        val request = Protocol.createGetVersionMessage(nonce, target, options.secret)
        return sendRequest(request, nonce, Protocol.MSG_GET_VERSION_REPLY, options)
    }

    fun setUbootEnv(target: TargetSelector, name: String, value: String, options: CommandOptions): CommandResponse? {
        val nonce = nonce()
        val request = Protocol.createSetUbootEnvMessage(nonce, target, name, value, options.secret)
        return sendRequest(request, nonce, Protocol.MSG_SET_UBOOT_ENV_REPLY, options)
    }

    fun reboot(target: TargetSelector, options: CommandOptions): CommandResponse? {
        val nonce = nonce()
        val request = Protocol.createRebootMessage(nonce, target, options.secret)
        return sendRequest(request, nonce, Protocol.MSG_REBOOT_REPLY, options)
    }

    private fun sendRequest(
        request: JsonObject,
        nonce: String,
        expectedReplyCommand: String,
        options: CommandOptions,
    ): CommandResponse? {
        val timeoutMillis = (options.timeoutSeconds * 1000).toInt().coerceAtLeast(1)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val useUdp = options.transportMode in setOf(TransportMode.AUTO, TransportMode.UDP, TransportMode.ALL)
        val useL2 = options.transportMode in setOf(TransportMode.AUTO, TransportMode.L2, TransportMode.ALL)
        val l2Transports = if (useL2) openL2Transports(options.interfaces, timeoutMillis) else emptyList()
        val udp = if (useUdp) runCatching { UdpDiscoveryTransport() }.getOrNull() else null
        var nextSend = 0L

        if (udp == null && l2Transports.isEmpty()) {
            return null
        }

        try {
            while (System.nanoTime() < deadline) {
                val nowMillis = System.currentTimeMillis()
                if (nowMillis >= nextSend) {
                    udp?.send(request, UdpDiscoveryTransport.broadcastTargets(options.interfaces.ifEmpty { null }))
                    for (l2 in l2Transports) {
                        runCatching { l2.sendJson(L2Protocol.broadcastAddress(), request) }
                    }
                    nextSend = nowMillis + 250
                }

                val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1)
                if (udp != null) {
                    val packet = udp.receive(min(50, remainingMillis))
                    if (packet != null) {
                        acceptReply(packet.message, nonce, expectedReplyCommand, options)
                            ?.let { return CommandResponse(packet.sourceIp, it) }
                    }
                } else {
                    Thread.sleep(min(10, remainingMillis).toLong())
                }

                for (l2 in l2Transports) {
                    while (true) {
                        val packet = runCatching { l2.receive(1) }.getOrNull() ?: break
                        acceptReply(packet.message, nonce, expectedReplyCommand, options)
                            ?.let { return CommandResponse("l2@${packet.interfaceName}", it) }
                    }
                }
            }
        } finally {
            udp?.close()
            l2Transports.forEach { it.close() }
        }

        return null
    }

    private fun acceptReply(
        message: JsonObject,
        nonce: String,
        expectedReplyCommand: String,
        options: CommandOptions,
    ): JsonObject? {
        if (Protocol.text(message, "cmd") != expectedReplyCommand) {
            return null
        }
        if (Protocol.text(message, "nonce") != nonce) {
            return null
        }
        if (!options.secret.isNullOrEmpty() && !Protocol.verifyAuth(message, options.secret)) {
            return null
        }
        return try {
            Protocol.validateStatusReply(message, expectedReplyCommand)
            message
        } catch (_: ProtocolException) {
            null
        }
    }

    private fun openL2Transports(interfaces: List<String>, timeoutMillis: Int): List<RawEthernetTransport> {
        val names = interfaces.ifEmpty { RawEthernetTransport.candidateInterfaces() }
        return names.mapNotNull { name ->
            runCatching { RawEthernetTransport.open(name, timeoutMillis) }.getOrNull()
        }
    }

    private fun parseParamValue(value: String): JsonPrimitive {
        value.toIntOrNull()?.let { return JsonPrimitive(it) }
        value.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        throw ProtocolException("parameter value must be a number: $value")
    }

    private fun nonce(): String = UUID.randomUUID().toString().replace("-", "").take(8)
}
