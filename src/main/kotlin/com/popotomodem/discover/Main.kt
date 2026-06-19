package com.popotomodem.discover

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        PopotoCli().run(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

private class PopotoCli {
    fun run(rawArgs: List<String>) {
        if (rawArgs.isEmpty() || rawArgs.any { it == "-h" || it == "--help" }) {
            usage()
            return
        }

        val args = rawArgs.toMutableList()
        var secretFile: String? = null
        var noAuth = false

        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--secret-file" -> {
                    secretFile = args.removeOptionWithValue(index, "--secret-file")
                    continue
                }
                "--no-auth" -> {
                    args.removeAt(index)
                    noAuth = true
                    continue
                }
            }
            index++
        }

        val command = args.removeFirstOrNull() ?: "discover"
        when (command) {
            "gui" -> PopotoComposeGui.launch(secretFile, noAuth)
            "discover" -> discover(args, secretFile, noAuth)
            "set-ip" -> setIp(args, secretFile, noAuth)
            "set-rtc" -> setRtc(args, secretFile, noAuth)
            "get-rtc" -> getRtc(args, secretFile, noAuth)
            "set-param" -> setParam(args, secretFile, noAuth)
            "get-version" -> getVersion(args, secretFile, noAuth)
            "set-uboot-env" -> setUbootEnv(args, secretFile, noAuth)
            "reboot" -> reboot(args, secretFile, noAuth)
            "shell" -> shell(args, secretFile, noAuth)
            else -> throw IllegalArgumentException("unknown command '$command'")
        }
    }

    private fun discover(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        var transport = TransportMode.AUTO
        val interfaces = mutableListOf<String>()
        var retries = 3

        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--timeout" -> {
                    timeout = args.removeOptionWithValue(index, "--timeout").toDouble()
                    continue
                }
                "--transport" -> {
                    transport = TransportMode.parse(args.removeOptionWithValue(index, "--transport"))
                    continue
                }
                "-i", "--interface" -> {
                    interfaces += args.removeOptionWithValue(index, args[index])
                    continue
                }
                "--retries" -> {
                    retries = args.removeOptionWithValue(index, "--retries").toInt()
                    continue
                }
                else -> throw IllegalArgumentException("unknown discover option '${args[index]}'")
            }
        }

        ensurePacketCaptureAccess(transport)

        val secret = if (noAuth) {
            System.err.println("WARNING: running without authentication is insecure")
            null
        } else {
            SecretProvider.load(secretFile)
        }

        val devices = Discoverer().discover(
            DiscoveryOptions(
                timeoutSeconds = timeout,
                secret = secret,
                transportMode = transport,
                interfaces = interfaces,
                retries = retries,
            ),
        )

        if (devices.isEmpty()) {
            println("No Popoto devices discovered.")
            return
        }

        println()
        println("Discovered ${devices.size} device(s):")
        for (device in devices) {
            printDevice(device)
        }
    }

    private fun setIp(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown set-ip option '$option'")
            }
        }
        requireArgs(args, 4, "set-ip TARGET IP NETMASK GATEWAY [--timeout SECONDS]")
        val target = TargetSelector.parse(args[0])
        val ip = args[1]
        val netmask = args[2]
        val gateway = args[3]
        val options = commandOptions(secretFile, noAuth, timeout, interfaces)
        ensurePacketCaptureAccess(options.transportMode)

        val device = resolveTargetDevice(target, options)
        val currentIp = device.text("ip")
            ?: throw IllegalArgumentException("target ${target.label} did not report an IP address")
        val response = NetworkConfigActions.setIp(target, currentIp, ip, netmask, gateway, options)

        println("IP set successfully to ${response.text("ip")} through pshell at $currentIp")
        if (gateway.isNotBlank()) {
            println("Gateway update scheduled for $gateway")
        }
    }

    private fun setRtc(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown set-rtc option '$option'")
            }
        }
        requireArgs(args, 2, "set-rtc TARGET YYYY.MM.DD-HH:MM:SS [--timeout SECONDS]")
        val target = TargetSelector.parse(args[0])
        val rtc = args[1]
        val response = CommandClient().setRtc(target, rtc, commandOptions(secretFile, noAuth, timeout, interfaces))

        if (response == null) {
            println("No set_rtc_reply received (timeout).")
            exitProcess(1)
        }
        if (response.text("status") == "ok") {
            println("RTC set successfully to $rtc (reply from ${response.sourceIp})")
        } else {
            println("Failed to set RTC: ${response.text("error") ?: "Unknown error"}")
            exitProcess(1)
        }
    }

    private fun getRtc(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown get-rtc option '$option'")
            }
        }
        requireArgs(args, 1, "get-rtc TARGET [--timeout SECONDS]")
        val response = CommandClient().getRtc(
            TargetSelector.parse(args[0]),
            commandOptions(secretFile, noAuth, timeout, interfaces),
        )

        if (response == null) {
            println("No get_rtc_reply received (timeout).")
            exitProcess(1)
        }
        if (response.text("status") == "ok") {
            println("RTC value: ${response.text("rtc") ?: "Unknown"} (reply from ${response.sourceIp})")
        } else {
            println("Failed to get RTC: ${response.text("error") ?: "Unknown error"}")
            exitProcess(1)
        }
    }

    private fun setParam(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown set-param option '$option'")
            }
        }
        requireArgs(args, 3, "set-param TARGET PARAM_NAME PARAM_VALUE [--timeout SECONDS]")
        val target = TargetSelector.parse(args[0])
        val name = args[1]
        val value = args[2]
        val response = CommandClient().setParam(target, name, value, commandOptions(secretFile, noAuth, timeout, interfaces))

        if (response == null) {
            println("No set_param_reply received (timeout).")
            exitProcess(1)
        }
        if (response.text("status") == "ok") {
            println("Parameter $name set successfully to $value (reply from ${response.sourceIp})")
        } else {
            println("Failed to set parameter: ${response.text("error") ?: "Unknown error"}")
            exitProcess(1)
        }
    }

    private fun getVersion(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = 8.0
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown get-version option '$option'")
            }
        }
        requireArgs(args, 1, "get-version TARGET [--timeout SECONDS]")
        val response = CommandClient().getVersion(
            TargetSelector.parse(args[0]),
            commandOptions(secretFile, noAuth, timeout, interfaces),
        )

        if (response == null) {
            println("No get_version_reply received (timeout).")
            exitProcess(1)
        }
        if (response.text("status") == "ok") {
            println(
                "Version: ${response.text("version") ?: "Unknown"} " +
                    "Serial: ${response.text("serial") ?: "unknown"} " +
                    "(reply from ${response.sourceIp})",
            )
        } else {
            println("Failed to get version: ${response.text("error") ?: "Unknown error"}")
            exitProcess(1)
        }
    }

    private fun setUbootEnv(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown set-uboot-env option '$option'")
            }
        }
        requireArgs(args, 3, "set-uboot-env TARGET NAME VALUE [--timeout SECONDS]")
        val response = CommandClient().setUbootEnv(
            TargetSelector.parse(args[0]),
            args[1],
            args[2],
            commandOptions(secretFile, noAuth, timeout, interfaces),
        )

        if (response == null) {
            println("No set_uboot_env_reply received (timeout).")
            exitProcess(1)
        }
        if (response.text("status") == "ok") {
            println("U-Boot environment set successfully (reply from ${response.sourceIp})")
        } else {
            println("Failed to set U-Boot environment: ${response.text("error") ?: "Unknown error"}")
            exitProcess(1)
        }
    }

    private fun reboot(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown reboot option '$option'")
            }
        }
        requireArgs(args, 1, "reboot TARGET [--timeout SECONDS]")
        val response = CommandClient().reboot(
            TargetSelector.parse(args[0]),
            commandOptions(secretFile, noAuth, timeout, interfaces),
        )

        if (response == null) {
            println("No reboot_reply received (timeout).")
            exitProcess(1)
        }
        if (response.text("status") == "ok") {
            println("Reboot accepted (reply from ${response.sourceIp})")
        } else {
            println("Failed to reboot: ${response.text("error") ?: "Unknown error"}")
            exitProcess(1)
        }
    }

    private fun shell(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = 8.0
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown shell option '$option'")
            }
        }
        if (args.size < 2) {
            throw IllegalArgumentException("usage: popoto-discover shell TARGET COMMAND [--timeout SECONDS]")
        }
        val target = TargetSelector.parse(args.removeAt(0))
        val command = args.joinToString(" ")
        val response = CommandClient().shellExec(
            target,
            command,
            commandOptions(secretFile, noAuth, timeout, interfaces),
            timeoutSeconds = timeout,
        )

        if (response == null) {
            println("No shell_exec_reply received (timeout).")
            exitProcess(1)
        }
        response.text("stdout")?.takeIf { it.isNotEmpty() }?.let { print(it) }
        response.text("stderr")?.takeIf { it.isNotEmpty() }?.let { System.err.print(it) }
        if (response.text("status") == "ok") {
            println("\nShell command completed successfully (reply from ${response.sourceIp})")
        } else {
            println("\nShell command failed: ${response.text("error") ?: "Unknown error"}")
            exitProcess(1)
        }
    }

    private fun printDevice(device: Device) {
        val ip = device.text("ip").orEmpty()
        val port = device.text("http_port")?.toIntOrNull() ?: 80
        val url = if (port == 80) "http://$ip/" else "http://$ip:$port/"

        println("----")
        println(" Name:            ${device.text("name")}")
        println(" Model:           ${device.text("model")}")
        println(" Device ID:       ${device.deviceIdText()}")
        println(" CPU UID:         ${device.text("cpu_uid")}")
        println(" Serial:          ${device.serialText()}")
        println(" IP:              $ip")
        println(" MAC:             ${device.text("mac")}")
        println(" mDNS Hostname:   ${device.text("mdns_hostname")}")
        println(" Identity source: ${device.text("identity_source")}")
        println(" FW:              ${device.text("fw")}")
        println(" Battery [V]:     ${device.text("battery_v")}")
        println(" Sample Rate [Hz]:${device.text("sample_rate_hz")}")
        println(" Recording state: ${device.text("recording_state")}")
        println(" Storage Free [G]:${device.text("storage_free_gb")}")
        println(" Storage Total[G]:${device.text("storage_total_gb")}")
        println(" URL:             $url")
        if (device.paths.isNotEmpty()) {
            val pathText = device.paths.joinToString(", ") { path ->
                path.transport + (path.interfaceName?.let { "@$it" } ?: "")
            }
            println(" Discovered via:  $pathText")
        }
    }

    private fun resolveTargetDevice(target: TargetSelector, options: CommandOptions): Device {
        val devices = Discoverer().discover(
            DiscoveryOptions(
                timeoutSeconds = maxOf(options.timeoutSeconds, 5.0),
                secret = options.secret,
                transportMode = options.transportMode,
                interfaces = options.interfaces,
                retries = 5,
            ),
        )
        val matches = devices.filter { matchesTarget(it, target) }
        if (matches.isEmpty()) {
            throw IllegalArgumentException("target ${target.label} was not discovered")
        }
        if (matches.size > 1) {
            throw IllegalArgumentException("target ${target.label} matched ${matches.size} devices")
        }
        return matches.first()
    }

    private fun matchesTarget(device: Device, target: TargetSelector): Boolean {
        target.serial?.let { deviceId ->
            if (device.deviceIdText()?.equals(deviceId, ignoreCase = true) == true) {
                return true
            }
        }
        target.mac?.let { mac ->
            if (usableMac(device.text("mac"))?.equals(mac, ignoreCase = true) == true) {
                return true
            }
        }
        return false
    }

    private fun commandOptions(
        secretFile: String?,
        noAuth: Boolean,
        timeout: Double,
        interfaces: List<String> = emptyList(),
    ): CommandOptions {
        return CommandOptions(timeoutSeconds = timeout, secret = secret(secretFile, noAuth), interfaces = interfaces)
    }

    private fun secret(secretFile: String?, noAuth: Boolean): String? {
        return if (noAuth) {
            System.err.println("WARNING: running without authentication is insecure")
            null
        } else {
            SecretProvider.load(secretFile)
        }
    }

    private fun ensurePacketCaptureAccess(transport: TransportMode) {
        if (MacBpfAccess.needsSetupFor(transport)) {
            println("macOS L2 discovery needs packet capture access. Requesting administrator permission once.")
            val result = MacBpfAccess.install()
            if (result.output.isNotBlank()) {
                println(result.output)
            }
            if (!result.success) {
                val suffix = if (result.output.isBlank()) "" else ": ${result.output}"
                throw RuntimeException("macOS L2 capture setup failed with exit code ${result.exitCode}$suffix")
            }
            println("macOS L2 capture access enabled.")
        }

        if (WindowsPacketAccess.needsSetupFor(transport)) {
            println("Windows L2 discovery needs PMM raw Ethernet driver setup. Requesting administrator permission once.")
            val result = WindowsPacketAccess.install()
            if (result.output.isNotBlank()) {
                println(result.output)
            }
            if (!result.success) {
                val suffix = if (result.output.isBlank()) "" else ": ${result.output}"
                throw RuntimeException("Windows L2 setup failed with exit code ${result.exitCode}$suffix")
            }
            if (result.rebootRequired) {
                println("Windows L2 setup completed and requires a reboot before raw Ethernet is ready.")
            } else {
                println("Windows L2 raw Ethernet access enabled.")
            }
        }
    }

    private fun parseCommonCommandOptions(
        args: MutableList<String>,
        handler: (option: String, value: String) -> Unit,
    ) {
        var index = 0
        while (index < args.size) {
            when (val option = args[index]) {
                "--timeout", "-i", "--interface" -> {
                    val value = args.removeOptionWithValue(index, option)
                    handler(option, value)
                    continue
                }
            }
            index++
        }
    }

    private fun requireArgs(args: List<String>, count: Int, usage: String) {
        if (args.size != count) {
            throw IllegalArgumentException("usage: popoto-discover $usage")
        }
    }

    private fun MutableList<String>.removeOptionWithValue(index: Int, option: String): String {
        if (index + 1 >= size) {
            throw IllegalArgumentException("$option requires a value")
        }
        removeAt(index)
        return removeAt(index)
    }

    private fun usage() {
        println(
            """
            Popoto discovery Kotlin CLI

            Usage:
              popoto-discover [--secret-file PATH] [--no-auth] discover [options]
              popoto-discover [--secret-file PATH] [--no-auth] set-ip TARGET IP NETMASK GATEWAY [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] set-rtc TARGET YYYY.MM.DD-HH:MM:SS [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] get-rtc TARGET [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] set-param TARGET PARAM_NAME PARAM_VALUE [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] get-version TARGET [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] set-uboot-env TARGET NAME VALUE [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] reboot TARGET [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] shell TARGET COMMAND [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] gui

            Authentication uses the built-in Popoto default secret unless --secret-file is provided.

            Discover options:
              --timeout SECONDS       Discovery timeout, default ${Protocol.DEFAULT_TIMEOUT_SECONDS}
              --transport MODE        auto, udp, l2, or all; default auto
              -i, --interface NAME    Interface to probe; may be repeated
              --retries N             Probe bursts during timeout, default 3

            Management command options:
              --timeout SECONDS       Reply timeout, default ${Protocol.DEFAULT_TIMEOUT_SECONDS}
                                      get-version defaults to 8.0 seconds
              -i, --interface NAME    Interface broadcast to use; may be repeated

            TARGET may be a device ID/CPU UID or a MAC address.
            On macOS, raw Ethernet discovery installs one-time BPF device access when needed.
            On Windows, raw Ethernet discovery uses the bundled PMM NDIS driver when it is present in the package.
            """.trimIndent(),
        )
    }
}
