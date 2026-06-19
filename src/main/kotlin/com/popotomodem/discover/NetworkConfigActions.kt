package com.popotomodem.discover

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
        if (gateway.isNotBlank()) {
            requireCommandOk(
                commandClient.shellExec(
                    target,
                    delayedGatewayCommand(gateway, interfaceName),
                    options,
                    timeoutSeconds = 5.0,
                ),
                "schedule gateway update",
            )
        }
        return PshellTelnetClient.setIp(currentIp, newIp, netmask, interfaceName)
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

    private fun delayedGatewayCommand(gateway: String, interfaceName: String): String {
        requireIpv4Address("gateway", gateway)
        val path = "/etc/network/interfaces"
        val script = """
sleep 10
p=${quoteShellArg(path)}
t=/tmp/interfaces.${'$'}${'$'}
awk -v i=${quoteShellArg(interfaceName)} -v g=${quoteShellArg(gateway)} '
${'$'}1=="iface"&&${'$'}2==i{e=1;s=0;print;next}
e&&${'$'}1=="gateway"{if(!s)print "    gateway " g;s=1;next}
e&&(${'$'}1=="iface"||${'$'}1=="auto"||${'$'}1=="allow-hotplug"){if(!s)print "    gateway " g;e=0}
{print}
END{if(e&&!s)print "    gateway " g}
' "${'$'}p" > "${'$'}t" && mv "${'$'}t" "${'$'}p"
ip route replace default via ${quoteShellArg(gateway)} dev ${quoteShellArg(interfaceName)} 2>/dev/null || route add default gw ${quoteShellArg(gateway)} ${quoteShellArg(interfaceName)} 2>/dev/null || true
        """.trimIndent()
        return "nohup sh -c ${quoteShellArg(script)} >/tmp/popoto-gateway.log 2>&1 &"
    }

    private fun requireIpv4Address(label: String, value: String) {
        val parts = value.split(".")
        if (parts.size != 4 || parts.any { it.toIntOrNull()?.let { octet -> octet !in 0..255 } != false }) {
            throw IllegalArgumentException("invalid $label: $value")
        }
    }

    private fun quoteShellArg(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
