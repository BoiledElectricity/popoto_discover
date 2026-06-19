package com.popotomodem.discover

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object NetworkConfigActions {
    fun setIp(
        target: TargetSelector,
        currentIp: String,
        newIp: String,
        netmask: String,
        gateway: String,
        options: CommandOptions,
        interfaceName: String = "eth0",
        commandClient: CommandClient = CommandClient(),
    ): CommandResponse {
        val shellOptions = options.copy(
            timeoutSeconds = maxOf(options.timeoutSeconds, 20.0),
            transportMode = if (options.transportMode == TransportMode.UDP) TransportMode.AUTO else options.transportMode,
        )
        val response = requireCommandOk(
            commandClient.shellExec(
                target,
                localPshellSetIpCommand(newIp, netmask, gateway, interfaceName),
                shellOptions,
                timeoutSeconds = 20.0,
            ),
            "set IP through local pshell",
        )
        return CommandResponse(
            sourceIp = response.sourceIp,
            message = JsonObject(
                mapOf(
                    "cmd" to JsonPrimitive(Protocol.MSG_SET_IP_REPLY),
                    "status" to JsonPrimitive("ok"),
                    "ip" to JsonPrimitive(newIp),
                    "pshell" to JsonPrimitive("local"),
                    "previous_ip" to JsonPrimitive(currentIp),
                    "stdout" to JsonPrimitive(response.text("stdout").orEmpty()),
                ),
            ),
        )
    }

    fun requireCommandOk(response: CommandResponse?, action: String): CommandResponse {
        if (response == null) {
            throw IllegalStateException("$action: no reply")
        }
        if (response.text("status") != "ok") {
            val error = response.text("error")
                ?: response.text("stderr")
                ?: response.text("stdout")
                ?: "unknown error"
            throw IllegalStateException("$action: $error")
        }
        return response
    }

    private fun localPshellSetIpCommand(newIp: String, netmask: String, gateway: String, interfaceName: String): String {
        requireIpv4Address("new IP address", newIp)
        requireNetmask(netmask)
        if (gateway.isNotBlank()) {
            requireIpv4Address("gateway", gateway)
        }
        return buildString {
            append("cd /opt/popoto && pshell --unittest True ")
            append(quoteShellArg("setIP $newIp $netmask $interfaceName"))
            if (gateway.isNotBlank()) {
                append(" && ")
                append(gatewayPatchCommand(gateway, interfaceName))
            }
        }
    }

    private fun gatewayPatchCommand(gateway: String, interfaceName: String): String {
        return listOf(
            "sed -i '/^[[:space:]]*gateway[[:space:]]/d' /etc/network/interfaces",
            "sed -i '/^[[:space:]]*netmask[[:space:]]/a\\    gateway $gateway' /etc/network/interfaces",
            "(ip route replace default via ${quoteShellArg(gateway)} dev ${quoteShellArg(interfaceName)} 2>/dev/null || route add default gw ${quoteShellArg(gateway)} ${quoteShellArg(interfaceName)} 2>/dev/null || true)",
        ).joinToString(" && ")
    }

    private fun requireIpv4Address(label: String, value: String) {
        val parts = value.split(".")
        if (parts.size != 4 || parts.any { it.toIntOrNull()?.let { octet -> octet !in 0..255 } != false }) {
            throw IllegalArgumentException("invalid $label: $value")
        }
    }

    private fun requireNetmask(value: String) {
        requireIpv4Address("netmask", value)
        val bits = value.split(".").joinToString("") { it.toInt().toString(2).padStart(8, '0') }
        if (!Regex("^1*0*$").matches(bits)) {
            throw IllegalArgumentException("invalid netmask: $value")
        }
    }

    private fun quoteShellArg(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
