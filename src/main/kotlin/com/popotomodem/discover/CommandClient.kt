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

    private fun sendRequest(
        request: JsonObject,
        nonce: String,
        expectedReplyCommand: String,
        options: CommandOptions,
    ): CommandResponse? {
        val timeoutMillis = (options.timeoutSeconds * 1000).toInt().coerceAtLeast(1)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L

        UdpDiscoveryTransport().use { udp ->
            udp.send(request, UdpDiscoveryTransport.broadcastTargets(options.interfaces.ifEmpty { null }))

            while (System.nanoTime() < deadline) {
                val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1)
                val packet = udp.receive(min(100, remainingMillis)) ?: continue
                val message = packet.message

                if (Protocol.text(message, "cmd") != expectedReplyCommand) {
                    continue
                }
                if (Protocol.text(message, "nonce") != nonce) {
                    continue
                }
                if (!options.secret.isNullOrEmpty() && !Protocol.verifyAuth(message, options.secret)) {
                    continue
                }

                try {
                    Protocol.validateStatusReply(message, expectedReplyCommand)
                } catch (_: ProtocolException) {
                    continue
                }

                return CommandResponse(packet.sourceIp, message)
            }
        }

        return null
    }

    private fun parseParamValue(value: String): JsonPrimitive {
        value.toIntOrNull()?.let { return JsonPrimitive(it) }
        value.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        throw ProtocolException("parameter value must be a number: $value")
    }

    private fun nonce(): String = UUID.randomUUID().toString().replace("-", "").take(8)
}
