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
            timeoutSeconds = 3.0,
            transportMode = if (options.transportMode == TransportMode.UDP) TransportMode.AUTO else options.transportMode,
        )
        val response = try {
            requireCommandOk(
                commandClient.shellExec(
                    target,
                    scheduledPshellSetIpCommand(newIp, netmask, gateway, interfaceName),
                    shellOptions,
                    timeoutSeconds = 1.0,
                ),
                "schedule set IP through local pshell",
            )
        } catch (error: IllegalStateException) {
            setIpWithLegacyFallback(target, newIp, netmask, gateway, shellOptions, commandClient, error)
        }
        val verified = response.text("verified").orEmpty().ifBlank {
            if (response.text("stdout").orEmpty().contains("POPOTO_SETIP_SCHEDULED")) {
                "scheduled"
            } else {
                ""
            }
        }
        val finalResponse = if (verified == "scheduled") {
            confirmScheduledIpApplied(target, newIp, shellOptions) ?: response
        } else {
            response
        }
        val finalVerified = finalResponse.text("verified").orEmpty().ifBlank { verified }
        return CommandResponse(
            sourceIp = finalResponse.sourceIp,
            message = JsonObject(
                mapOf(
                    "cmd" to JsonPrimitive(Protocol.MSG_SET_IP_REPLY),
                    "status" to JsonPrimitive("ok"),
                    "ip" to JsonPrimitive(newIp),
                    "pshell" to JsonPrimitive("local"),
                    "previous_ip" to JsonPrimitive(currentIp),
                    "verified" to JsonPrimitive(finalVerified),
                    "stdout" to JsonPrimitive(finalResponse.text("stdout").orEmpty()),
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

    private fun setIpWithLegacyFallback(
        target: TargetSelector,
        newIp: String,
        netmask: String,
        gateway: String,
        options: CommandOptions,
        commandClient: CommandClient,
        originalError: IllegalStateException,
    ): CommandResponse {
        val legacyOptions = options.copy(timeoutSeconds = maxOf(options.timeoutSeconds, 10.0))
        val legacyResponse = runCatching {
            commandClient.setIp(target, newIp, netmask, gateway, legacyOptions)
        }.getOrNull()
        if (legacyResponse?.text("status") == "ok") {
            return CommandResponse(
                sourceIp = legacyResponse.sourceIp,
                message = JsonObject(
                    legacyResponse.message + ("verified" to JsonPrimitive("legacy-reply")),
                ),
            )
        }
        return verifyIpApplied(target, newIp, legacyOptions, originalError)
    }

    private fun verifyIpApplied(
        target: TargetSelector,
        newIp: String,
        options: CommandOptions,
        originalError: IllegalStateException,
    ): CommandResponse {
        val verified = runCatching {
            Discoverer().discover(
                DiscoveryOptions(
                    timeoutSeconds = maxOf(options.timeoutSeconds, 12.0),
                    secret = options.secret,
                    transportMode = TransportMode.ALL,
                    interfaces = options.interfaces,
                    retries = 8,
                ),
            ).firstOrNull { device ->
                targetMatches(device, target) &&
                    (device.text("ip") == newIp || device.sshHostText() == newIp)
            }
        }.getOrNull()

        if (verified != null) {
            return CommandResponse(
                sourceIp = verified.sshHostText() ?: newIp,
                message = JsonObject(
                    mapOf(
                        "cmd" to JsonPrimitive(Protocol.MSG_SET_IP_REPLY),
                        "status" to JsonPrimitive("ok"),
                        "ip" to JsonPrimitive(newIp),
                        "verified" to JsonPrimitive("rediscovery"),
                        "stdout" to JsonPrimitive("No direct setIP reply; rediscovered target at $newIp."),
                    ),
                ),
            )
        }

        throw originalError
    }

    private fun confirmScheduledIpApplied(
        target: TargetSelector,
        newIp: String,
        options: CommandOptions,
    ): CommandResponse? {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            val verified = runCatching {
                Discoverer().discover(
                    DiscoveryOptions(
                        timeoutSeconds = 0.75,
                        secret = options.secret,
                        transportMode = TransportMode.ALL,
                        interfaces = options.interfaces,
                        retries = 2,
                    ),
                ).firstOrNull { device ->
                    targetMatches(device, target) &&
                        (device.text("ip") == newIp || device.sshHostText() == newIp)
                }
            }.getOrNull()

            if (verified != null) {
                return CommandResponse(
                    sourceIp = verified.sshHostText() ?: newIp,
                    message = JsonObject(
                        mapOf(
                            "cmd" to JsonPrimitive(Protocol.MSG_SET_IP_REPLY),
                            "status" to JsonPrimitive("ok"),
                            "ip" to JsonPrimitive(newIp),
                            "verified" to JsonPrimitive("rediscovery"),
                            "stdout" to JsonPrimitive("Scheduled setIP applied and was rediscovered at $newIp."),
                        ),
                    ),
                )
            }
            Thread.sleep(150)
        }
        return null
    }

    private fun targetMatches(device: Device, target: TargetSelector): Boolean {
        target.serial?.let { id ->
            if (device.deviceIdText()?.equals(id, ignoreCase = true) == true) {
                return true
            }
        }
        target.mac?.let { mac ->
            if (device.matchesMac(mac)) {
                return true
            }
        }
        return false
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

    private fun scheduledPshellSetIpCommand(newIp: String, netmask: String, gateway: String, interfaceName: String): String {
        val applyCommand = localPshellSetIpCommand(newIp, netmask, gateway, interfaceName)
        return buildString {
            append("command -v pshell >/dev/null 2>&1 || { echo 'pshell not found' >&2; exit 127; }; ")
            append("(")
            append("sleep 0.35; ")
            append(applyCommand)
            append(") >/var/log/popoto-discover-setip.log 2>&1 & ")
            append("echo POPOTO_SETIP_SCHEDULED")
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
