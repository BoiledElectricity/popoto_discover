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
            "gui" -> PopotoGui.launch(secretFile, noAuth)
            "discover" -> discover(args, secretFile, noAuth)
            "set-ip" -> setIp(args, secretFile, noAuth)
            "set-rtc" -> setRtc(args, secretFile, noAuth)
            "get-rtc" -> getRtc(args, secretFile, noAuth)
            "set-param" -> setParam(args, secretFile, noAuth)
            "get-version" -> getVersion(args, secretFile, noAuth)
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
        val response = CommandClient().setIp(
            target = target,
            newIp = ip,
            netmask = netmask,
            gateway = gateway,
            options = commandOptions(secretFile, noAuth, timeout, interfaces),
        )

        if (response == null) {
            println("No set_ip_reply received (timeout).")
            exitProcess(1)
        }
        if (response.text("status") == "ok") {
            println("IP set successfully to ${response.text("ip")} (reply from ${response.sourceIp})")
        } else {
            println("Failed to set IP: ${response.text("error") ?: "Unknown error"}")
            exitProcess(1)
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
                    "Serial: ${response.text("serial") ?: "Unknown"} " +
                    "(reply from ${response.sourceIp})",
            )
        } else {
            println("Failed to get version: ${response.text("error") ?: "Unknown error"}")
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
        println(" Serial:          ${device.text("serial")}")
        println(" Device ID:       ${device.text("device_id")}")
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

            TARGET may be a real device ID/serial or a MAC address.
            Raw Ethernet discovery uses libpcap/Npcap and usually needs elevated privileges.
            """.trimIndent(),
        )
    }
}
