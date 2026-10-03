package com.popotomodem.discover

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    configureApplicationIdentity()
    try {
        if (WindowsSeLowAccess.relaunchGuiIfNeeded(args)) return
        PopotoCli().run(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

private fun configureApplicationIdentity() {
    System.setProperty("apple.awt.application.name", AppBuild.appName)
    System.setProperty("com.apple.mrj.application.apple.menu.about.name", AppBuild.appName)
    System.setProperty("sun.awt.X11.XWMClass", AppBuild.appName)
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
            "version", "--version" -> version()
            "gui" -> PopotoComposeGui.launch(secretFile, noAuth)
            "tui" -> PopotoTui.launch(secretFile, noAuth)
            "discover" -> discover(args, secretFile, noAuth)
            "set-ip" -> setIp(args, secretFile, noAuth)
            "set-rtc" -> setRtc(args, secretFile, noAuth)
            "get-rtc" -> getRtc(args, secretFile, noAuth)
            "set-param" -> setParam(args, secretFile, noAuth)
            "get-version" -> getVersion(args, secretFile, noAuth)
            "set-uboot-env" -> setUbootEnv(args, secretFile, noAuth)
            "reboot" -> reboot(args, secretFile, noAuth)
            "uboot-boot-linux" -> ubootBootLinux(args, secretFile, noAuth)
            "uboot-mfg-test" -> ubootMfgTest(args, secretFile, noAuth)
            "shell" -> shell(args, secretFile, noAuth)
            "sync-client" -> syncClient(args, secretFile, noAuth)
            "install-client", "install-discover" -> installClient(args)
            "check-bootloader" -> checkBootloader(args)
            "check-active-bootloader" -> checkActiveBootloader(args, secretFile, noAuth)
            "flash" -> flash(args, secretFile, noAuth)
            else -> throw IllegalArgumentException("unknown command '$command'")
        }
    }

    private fun version() {
        println("${AppBuild.appName} ${AppBuild.version}")
        println("package: ${AppBuild.packageVersion}")
        println("branch:  ${AppBuild.gitBranch}")
        println("commit:  ${AppBuild.gitCommit}${if (AppBuild.gitDirty) " (dirty)" else ""}")
        println("built:   ${AppBuild.buildTime}")
        if (AppBuild.releaseHighlights.isNotEmpty()) {
            println("includes:")
            AppBuild.releaseHighlights.forEach { println("  - $it") }
        }
    }

    private fun checkBootloader(args: MutableList<String>) {
        requireArgs(args, 1, "check-bootloader IMX_BOOT")
        val image = File(args[0]).absoluteFile
        val support = BootloaderImageSupportInspector.inspect(image)
        println("Bootloader image: ${image.absolutePath}")
        println("PMM AoE/discovery support: ${if (support.hasPmmAoeSupport) "present" else "missing"}")
        println("Present markers: ${support.presentMarkers.joinToString().ifBlank { "none" }}")
        println("Missing required markers: ${support.missingRequiredMarkers.joinToString().ifBlank { "none" }}")
        println("Missing optional markers: ${support.missingOptionalMarkers.joinToString().ifBlank { "none" }}")
        if (!support.hasPmmAoeSupport) {
            System.err.println("WARNING: ${support.warningText()}")
            exitProcess(3)
        }
    }

    private fun checkActiveBootloader(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = 15.0
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown check-active-bootloader option '$option'")
            }
        }
        requireArgs(args, 1, "check-active-bootloader TARGET [-i IFACE] [--timeout SECONDS]")
        if (!timeout.isFinite() || timeout <= 0.0) {
            throw IllegalArgumentException("--timeout must be greater than 0")
        }
        val options = commandOptions(secretFile, noAuth, timeout, interfaces)
        ensurePacketCaptureAccess(options.transportMode)
        val target = TargetSelector.parse(args[0])
        val commandClient = CommandClient()
        val device = resolveTargetDevice(target, options)
        BootloaderFlasher(
            commandClient = commandClient,
            options = options,
            onEvent = { event -> println(event.message) },
            sshHost = device.sshHostText(),
        ).ensureMmcUtils(target)
        val support = ActiveBootloaderSupportInspector.inspect(
            commandClient = commandClient,
            target = target,
            options = options,
        )

        println("Target: ${target.label}")
        println("Active slot: ${support.activeSlot}")
        println("PARTITION_CONFIG: ${support.partitionConfig}")
        println("PMM automatic AoE support: ${if (support.hasPmmAoeSupport) "present" else "missing"}")
        println("Present markers: ${support.presentMarkers.joinToString().ifBlank { "none" }}")
        println("Missing required markers: ${support.missingMarkers.joinToString().ifBlank { "none" }}")
        if (!support.hasPmmAoeSupport) {
            System.err.println("WARNING: ${support.failureText(target.label)}")
            exitProcess(3)
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
        val currentIp = device.sshHostText()
            ?: throw IllegalArgumentException("target ${target.label} did not provide a reachable IP address")
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
        requireArgs(args, 2, "set-rtc TARGET YYYY.MM.DD-HH:MM:SS|now [--timeout SECONDS]")
        val target = TargetSelector.parse(args[0])
        val rtc = resolveRtcInput(args[1])
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

    private fun ubootBootLinux(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = 5.0
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown uboot-boot-linux option '$option'")
            }
        }
        requireArgs(args, 1, "uboot-boot-linux TARGET [--timeout SECONDS]")
        ensurePacketCaptureAccess(TransportMode.L2)
        val response = CommandClient().bootLinux(
            TargetSelector.parse(args[0]),
            commandOptions(secretFile, noAuth, timeout, interfaces).copy(transportMode = TransportMode.L2),
        )

        if (response == null) {
            println("No boot_linux_reply received (timeout).")
            exitProcess(1)
        }
        if (response.text("status") == "ok") {
            println("Boot Linux accepted; AoE mode cleared and unit is resetting (reply from ${response.sourceIp})")
        } else {
            println("Failed to boot Linux: ${response.text("error") ?: "Unknown error"}")
            exitProcess(1)
        }
    }

    private fun ubootMfgTest(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = 30.0
        val interfaces = mutableListOf<String>()
        parseCommonCommandOptions(args) { option, value ->
            when (option) {
                "--timeout" -> timeout = value.toDouble()
                "-i", "--interface" -> interfaces += value
                else -> throw IllegalArgumentException("unknown uboot-mfg-test option '$option'")
            }
        }
        requireArgs(args, 1, "uboot-mfg-test TARGET [--timeout SECONDS]")
        ensurePacketCaptureAccess(TransportMode.L2)
        val response = CommandClient().runManufacturingTest(
            TargetSelector.parse(args[0]),
            commandOptions(secretFile, noAuth, timeout, interfaces).copy(transportMode = TransportMode.L2),
        )

        if (response == null) {
            println("No mfg_test_reply received (timeout).")
            exitProcess(1)
        }
        val result = response.text("result") ?: "unknown"
        val returnCode = response.text("returncode") ?: "unknown"
        println("Manufacturing test result: ${result.uppercase()} (return code $returnCode, reply from ${response.sourceIp})")
        response.text("output")?.takeIf { it.isNotBlank() }?.let { output ->
            println()
            print(output)
            if (!output.endsWith("\n")) {
                println()
            }
        }
        if (response.text("output_truncated") == "1") {
            println("WARNING: manufacturing test output was truncated by the U-Boot reply.")
        }
        if (result != "pass") {
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

    private fun syncClient(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        val interfaces = mutableListOf<String>()
        var host: String? = null
        var username = "root"
        var password = "root"
        var port = 22

        var index = 0
        while (index < args.size) {
            when (val option = args[index]) {
                "--timeout" -> {
                    timeout = args.removeOptionWithValue(index, option).toDouble()
                    continue
                }
                "-i", "--interface" -> {
                    interfaces += args.removeOptionWithValue(index, option)
                    continue
                }
                "--host" -> {
                    host = args.removeOptionWithValue(index, option)
                    continue
                }
                "--user" -> {
                    username = args.removeOptionWithValue(index, option)
                    continue
                }
                "--password" -> {
                    password = args.removeOptionWithValue(index, option)
                    continue
                }
                "--port" -> {
                    port = args.removeOptionWithValue(index, option).toInt()
                    continue
                }
            }
            index++
        }

        if (port !in 1..65535) {
            throw IllegalArgumentException("--port must be between 1 and 65535")
        }

        val target = args.firstOrNull()?.let(TargetSelector::parse)
        if (args.size > 1 || (host == null && target == null)) {
            throw IllegalArgumentException(
                "usage: popoto-discover sync-client [TARGET] [--host HOST] [--user USER] [--password PASS] [--port PORT]",
            )
        }

        val resolvedHost = host ?: run {
            val options = commandOptions(secretFile, noAuth, timeout, interfaces)
            ensurePacketCaptureAccess(options.transportMode)
            val device = resolveTargetDevice(target!!, options)
            val ip = device.sshHostText()
                ?: throw IllegalArgumentException("target ${target.label} did not provide a reachable IP address for SSH")
            println("Resolved ${target.label} to $ip")
            ip
        }

        val result = ModemClientSync(
            credentials = ModemSshCredentials(
                host = resolvedHost,
                username = username,
                password = password,
                port = port,
            ),
            onProgress = { println(it) },
        ).sync()

        println("Popoto Discover modem client synced on ${result.host}")
        println("Service status: ${result.serviceStatus}")
        result.backupPath?.let { println("Backup: $it") }
    }

    private fun installClient(args: MutableList<String>) {
        var host: String? = null
        var username = "root"
        var password = "root"
        var port = 22

        var index = 0
        while (index < args.size) {
            when (val option = args[index]) {
                "--host" -> {
                    host = args.removeOptionWithValue(index, option)
                    continue
                }
                "--user" -> {
                    username = args.removeOptionWithValue(index, option)
                    continue
                }
                "--password" -> {
                    password = args.removeOptionWithValue(index, option)
                    continue
                }
                "--port" -> {
                    port = args.removeOptionWithValue(index, option).toInt()
                    continue
                }
            }
            index++
        }

        if (port !in 1..65535) {
            throw IllegalArgumentException("--port must be between 1 and 65535")
        }

        val positionalHost = args.firstOrNull()
        if (args.size > 1 || (host != null && positionalHost != null)) {
            throw IllegalArgumentException(
                "usage: popoto-discover install-client HOST [--user USER] [--password PASS] [--port PORT]",
            )
        }
        host = host ?: positionalHost
        if (host.isNullOrBlank()) {
            throw IllegalArgumentException(
                "usage: popoto-discover install-client HOST [--user USER] [--password PASS] [--port PORT]",
            )
        }

        println("Installing Popoto Discover modem client on $host")
        val result = ModemClientSync(
            credentials = ModemSshCredentials(
                host = host,
                username = username,
                password = password,
                port = port,
            ),
            onProgress = { println(it) },
        ).sync()

        println("Popoto Discover modem client installed on ${result.host}")
        println("Service status: ${result.serviceStatus}")
        result.backupPath?.let { println("Backup: $it") }
    }

    private fun flash(args: MutableList<String>, secretFile: String?, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        var interfaceName: String? = null
        var bmapFile: File? = null
        var fullImage = false
        var bootloaderImage: File? = null
        var maxConcurrency = BatchFlashWorkflow.DEFAULT_MAX_CONCURRENCY
        var allowUnsupportedBootloader = false
        var allowSingleTargetAoeFallback = false
        var dryRun = false
        var jsonOutput = false

        var index = 0
        while (index < args.size) {
            when (val option = args[index]) {
                "--timeout" -> {
                    val value = args.removeOptionWithValue(index, option)
                    timeout = value.toDoubleOrNull()
                        ?: throw IllegalArgumentException("--timeout must be a number, got '$value'")
                    continue
                }
                "-i", "--interface" -> {
                    interfaceName = args.removeOptionWithValue(index, option)
                    continue
                }
                "--bmap" -> {
                    bmapFile = File(args.removeOptionWithValue(index, option)).absoluteFile
                    continue
                }
                "--full" -> {
                    args.removeAt(index)
                    fullImage = true
                    continue
                }
                "--allow-unsupported-bootloader" -> {
                    args.removeAt(index)
                    allowUnsupportedBootloader = true
                    continue
                }
                "--allow-single-target-aoe-fallback" -> {
                    args.removeAt(index)
                    allowSingleTargetAoeFallback = true
                    continue
                }
                "--dry-run" -> {
                    args.removeAt(index)
                    dryRun = true
                    continue
                }
                "--json" -> {
                    args.removeAt(index)
                    jsonOutput = true
                    continue
                }
                "--bootloader" -> {
                    bootloaderImage = File(args.removeOptionWithValue(index, option)).absoluteFile
                    continue
                }
                "--jobs" -> {
                    val value = args.removeOptionWithValue(index, option)
                    maxConcurrency = value.toIntOrNull()
                        ?: throw IllegalArgumentException("--jobs must be an integer, got '$value'")
                    continue
                }
                else -> {
                    if (option.startsWith("-")) {
                        throw IllegalArgumentException("unknown flash option '$option'")
                    }
                }
            }
            index++
        }

        if (args.size < 2) {
            throw IllegalArgumentException(
                "usage: popoto-discover flash TARGET [TARGET ...] IMAGE [--bmap PATH|--full] [--bootloader IMX_BOOT] [-i IFACE] [--jobs N]",
            )
        }
        if (fullImage && bmapFile != null) {
            throw IllegalArgumentException("--full and --bmap cannot be used together")
        }
        if (maxConcurrency < 1) {
            throw IllegalArgumentException("--jobs must be at least 1")
        }
        if (!timeout.isFinite() || timeout <= 0.0) {
            throw IllegalArgumentException("--timeout must be greater than 0")
        }
        if (allowUnsupportedBootloader && bootloaderImage == null) {
            throw IllegalArgumentException("--allow-unsupported-bootloader requires --bootloader PATH")
        }

        val image = File(args.removeAt(args.lastIndex)).absoluteFile
        val targets = args.map { TargetSelector.parse(it) }
        if (allowSingleTargetAoeFallback && targets.size != 1) {
            throw IllegalArgumentException("--allow-single-target-aoe-fallback requires exactly one TARGET")
        }
        val artifactPlan = FlashArtifactPlanner.resolve(
            image = image,
            requestedBmap = bmapFile,
            forceFullImage = fullImage,
            bootloader = bootloaderImage,
            allowUnsupportedBootloader = allowUnsupportedBootloader,
        )
        val parsedBmap = FlashArtifactPlanner.validate(artifactPlan)
        val output = FlashCliOutput(jsonOutput)
        output.artifacts(artifactPlan, parsedBmap)
        if (dryRun) {
            output.dryRun(targets)
            return
        }

        val options = commandOptions(secretFile, noAuth, timeout, interfaceName?.let(::listOf).orEmpty())
        ensurePacketCaptureAccess(options.transportMode)

        val requests = targets.map { target ->
            val device = resolveTargetDevice(target, options)
            val iface = FlashWorkflow.bestInterfaceFor(device, interfaceName)
                ?: throw IllegalArgumentException("no Ethernet interface is available for ${target.label}")
            FlashRequest(
                initialDevice = device,
                target = FlashWorkflow.targetFor(device) ?: target,
                interfaceName = iface,
                aoeTarget = AoETargetAddress.forDevice(device),
                image = artifactPlan.image,
                bmap = artifactPlan.bmap,
                mode = artifactPlan.mode,
                bootloaderImage = artifactPlan.bootloader,
                secret = options.secret,
                allowSingleTargetAoeFallback = allowSingleTargetAoeFallback,
            )
        }

        for (request in requests) {
            output.target(request)
        }

        val rediscovered = BatchFlashWorkflow(
            requests,
            onEvent = { event ->
                output.event(event)
            },
            maxConcurrency = maxConcurrency,
        ).run()

        output.complete(rediscovered)
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
        device.sshHostText()
            ?.takeIf { it != ip }
            ?.let { println(" SSH Host:        $it") }
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
            if (device.matchesMac(mac)) {
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
            println("Setting up Windows raw Ethernet access.")
            val result = WindowsPacketAccess.install()
            if (result.output.isNotBlank()) {
                println(result.output)
            }
            if (!result.success) {
                val suffix = if (result.output.isBlank()) "" else ": ${result.output}"
                throw RuntimeException("Windows L2 setup failed with exit code ${result.exitCode}$suffix")
            }
            if (result.rebootRequired) {
                throw IllegalStateException("Restart Windows to finish Ethernet driver setup, then run the command again.")
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
              popoto-discover [--secret-file PATH] [--no-auth] set-rtc TARGET YYYY.MM.DD-HH:MM:SS|now [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] get-rtc TARGET [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] set-param TARGET PARAM_NAME PARAM_VALUE [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] get-version TARGET [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] set-uboot-env TARGET NAME VALUE [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] reboot TARGET [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] uboot-boot-linux TARGET [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] uboot-mfg-test TARGET [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] shell TARGET COMMAND [--timeout SECONDS]
              popoto-discover [--secret-file PATH] [--no-auth] sync-client [TARGET] [options]
              popoto-discover install-client HOST [options]
              popoto-discover check-bootloader IMX_BOOT
              popoto-discover [--secret-file PATH] [--no-auth] check-active-bootloader TARGET [options]
              popoto-discover [--secret-file PATH] [--no-auth] flash TARGET [TARGET ...] IMAGE [options]
              popoto-discover [--secret-file PATH] [--no-auth] gui
              popoto-discover [--secret-file PATH] [--no-auth] tui
              popoto-discover version

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

            U-Boot command options:
              uboot-boot-linux clears AoE flash mode and resets the unit so Linux boots.
              uboot-mfg-test runs the U-Boot i2c manufacturing test and exits nonzero on FAIL.
              Both commands require raw Ethernet/L2 access.

            Sync client options:
              --host HOST             SSH host/IP to update; otherwise discovered from TARGET
              --user USER             SSH username, default root
              --password PASS         SSH password, default root
              --port PORT             SSH port, default 22

            Install client:
              install-client installs the bundled modem-side Popoto Discover client over SSH
              to a board that does not already respond to discovery. Use the board IP/hostname.

            Flash options:
              --bmap PATH             Write only mapped WIC payload ranges from this .bmap
              --full                  Write the full WIC image instead of bmap payload ranges
              --bootloader PATH       Flash imx-boot to eMMC boot0 before writing the WIC
              --allow-unsupported-bootloader
                                      Override a failed local AoE capability check
              --allow-single-target-aoe-fallback
                                      For exactly one selected board, use its currently active mismatched
                                      U-Boot AoE export after pinning it to the discovered L2 source MAC
              -i, --interface NAME    Ethernet interface to use for L2 discovery and AoE
              --jobs N                Concurrent target flashes, default ${BatchFlashWorkflow.DEFAULT_MAX_CONCURRENCY}
              --dry-run               Validate local artifacts and print the plan without device access
              --json                  Emit newline-delimited JSON on stdout for automation
                                      Without --bmap or --full, a sibling .wic.bmap is used when present;
                                      otherwise the complete image is written.

            Bootloader check:
              check-bootloader exits nonzero when imx-boot lacks automatic AoE/discovery support.
              check-active-bootloader inspects the active eMMC boot slot without rebooting the target.

            TARGET may be a device ID/CPU UID or a MAC address.
            On macOS, raw Ethernet discovery installs one-time BPF device access when needed.
            On Windows, raw Ethernet discovery uses the bundled PMM NDIS driver when it is present in the package.
            """.trimIndent(),
        )
    }
}

private class FlashCliOutput(
    private val json: Boolean,
) {
    fun artifacts(plan: FlashArtifactPlan, bmap: Bmap?) {
        val mode = if (plan.mode == FlashMode.BMAP) "bmap" else "full"
        if (json) {
            emit(
                "artifacts",
                "image" to plan.image.absolutePath,
                "mode" to mode,
                "bmap" to plan.bmap?.absolutePath,
                "bmap_auto_detected" to plan.bmapWasAutoDetected,
                "mapped_bytes" to bmap?.mappedBytes,
                "image_bytes" to bmap?.imageSize,
                "bootloader" to plan.bootloader?.absolutePath,
                "bootloader_supported" to plan.bootloaderSupport?.hasPmmAoeSupport,
            )
            return
        }

        println("Artifacts:")
        println("  Image:      ${plan.image.absolutePath}")
        println("  Mode:       ${if (plan.mode == FlashMode.BMAP) "bmap payload" else "full image"}")
        plan.bmap?.let {
            val source = if (plan.bmapWasAutoDetected) " (auto-detected)" else ""
            println("  Bmap:       ${it.absolutePath}$source")
            if (bmap != null) {
                println("  Payload:    ${bmap.mappedBytes} mapped bytes of ${bmap.imageSize}")
            }
        }
        println("  Bootloader: ${plan.bootloader?.absolutePath ?: "unchanged"}")
        plan.bootloaderSupport?.let {
            println("  U-Boot AoE: ${if (it.hasPmmAoeSupport) "supported" else "override requested"}")
        }
    }

    fun dryRun(targets: List<TargetSelector>) {
        if (json) {
            emit(
                "dry_run",
                "status" to "ok",
                "targets" to targets.joinToString(",") { it.label },
                "device_accessed" to false,
            )
        } else {
            println("Dry run OK: local artifacts are valid; no device was contacted.")
            println("Targets: ${targets.joinToString { it.label }}")
        }
    }

    fun target(request: FlashRequest) {
        if (json) {
            emit(
                "target",
                "target" to request.target.label,
                "interface" to request.interfaceName,
                "aoe_target" to request.aoeTarget.label,
                "single_target_aoe_fallback" to request.allowSingleTargetAoeFallback,
            )
        } else {
            println("[${request.target.label}] Interface: ${request.interfaceName}")
            println("[${request.target.label}] AoE target: ${request.aoeTarget.label}")
            if (request.allowSingleTargetAoeFallback) {
                println("[${request.target.label}] Single-target current AoE fallback: authorized")
            }
        }
    }

    fun event(batch: BatchFlashEvent) {
        val event = batch.event
        if (json) {
            emit(
                "event",
                "target" to batch.request.target.label,
                "phase" to event.phase,
                "message" to event.message,
                "done_bytes" to event.doneBytes,
                "total_bytes" to event.totalBytes,
            )
        } else {
            println("[${batch.request.target.label}] ${event.message}")
        }
    }

    fun complete(devices: List<Device>) {
        if (json) {
            devices.forEach { device ->
                emit(
                    "device",
                    "device_id" to device.deviceIdText(),
                    "name" to device.text("name"),
                    "ip" to device.text("ip"),
                    "fw" to device.text("fw"),
                )
            }
            emit("summary", "status" to "ok", "rediscovered_devices" to devices.size)
        } else {
            println()
            println("Flash complete. Rediscovered ${devices.size} device(s):")
            devices.forEach { device ->
                println(
                    "  ${device.deviceIdText() ?: "unknown"}  " +
                        "${device.text("name") ?: "unknown"}  ${device.text("ip") ?: "no IP"}",
                )
            }
        }
    }

    private fun emit(type: String, vararg values: Pair<String, Any?>) {
        val fields = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type))
        values.forEach { (key, value) ->
            fields[key] = when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }
        println(JsonObject(fields))
    }
}
