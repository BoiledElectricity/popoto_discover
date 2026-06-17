package com.popotomodem.discover

import java.io.File
import kotlin.math.roundToInt

data class FlashEvent(
    val message: String,
    val phase: String = "status",
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
)

enum class FlashMode {
    BMAP,
    FULL_IMAGE,
}

data class FlashRequest(
    val initialDevice: Device,
    val target: TargetSelector,
    val interfaceName: String,
    val image: File,
    val bmap: File?,
    val mode: FlashMode,
    val secret: String?,
)

class FlashWorkflow(
    private val request: FlashRequest,
    private val onEvent: (FlashEvent) -> Unit,
) {
    fun run(): Device {
        val bmap = prepareArtifacts()
        val commandClient = CommandClient()
        val commandOptions = CommandOptions(
            timeoutSeconds = 5.0,
            secret = request.secret,
            interfaces = listOf(request.interfaceName),
            transportMode = TransportMode.AUTO,
        )

        event("Setting pmm_eth_console=1 with fw_setenv")
        requireOk(
            commandClient.shellExec(request.target, "fw_setenv pmm_eth_console 1", commandOptions, timeoutSeconds = 8.0),
            "set pmm_eth_console=1",
        )
        val enabled = requireOk(
            commandClient.shellExec(request.target, "fw_printenv pmm_eth_console", commandOptions, timeoutSeconds = 5.0),
            "verify pmm_eth_console=1",
        )
        requireStdoutContains(enabled, "pmm_eth_console=1", "verify pmm_eth_console=1")

        PmmEthConsoleClient.open(request.interfaceName).use { console ->
            event("Ethernet console listener ready on ${request.interfaceName}")
            event("Rebooting PMM into U-Boot Ethernet console")
            requireOk(
                commandClient.shellExec(
                    request.target,
                    "(sleep 0.5; sync; /sbin/reboot || reboot) >/dev/null 2>&1 &",
                    commandOptions,
                    timeoutSeconds = 2.0,
                ),
                "reboot PMM",
            )

            event("Catching U-Boot Ethernet console on ${request.interfaceName}")
            console.attach(45_000) { output -> consoleOutput(output) }

            event("Starting U-Boot AoE export: aoe mmc 2")
            console.sendCommand("aoe mmc 2")
            console.waitForAoEExport(10_000) { output -> consoleOutput(output) }

            AoEFlasher.open(request.interfaceName).use { aoe ->
                event("Discovering AoE target e0.0")
                discoverAoE(aoe)
                event("AoE target found; testing LBA0 read")
                aoe.readSectors(0, 1)

                val window = aoe.preferredWindow().coerceAtLeast(AoEFlasher.AOE_DEFAULT_WINDOW)
                when (request.mode) {
                    FlashMode.BMAP -> {
                        val parsedBmap = bmap ?: throw IllegalArgumentException("bmap mode requires a bmap file")
                        event("Writing WIC bmap payload over AoE; window=$window")
                        aoe.writeBmap(request.image, parsedBmap, window) { progress ->
                            val message = progress.message ?: progressText(progress)
                            onEvent(FlashEvent(message, progress.phase, progress.doneBytes, progress.totalBytes))
                        }
                    }
                    FlashMode.FULL_IMAGE -> {
                        event("Writing full WIC image over AoE; window=$window")
                        aoe.writeFull(request.image, window) { progress ->
                            val message = progress.message ?: progressText(progress)
                            onEvent(FlashEvent(message, progress.phase, progress.doneBytes, progress.totalBytes))
                        }
                    }
                }

                event("Flushing AoE target write cache")
                aoe.flush()
            }

            event("Resetting PMM from U-Boot")
            console.resetFromAoE { output -> consoleOutput(output) }
        }

        event("Waiting for PMM to boot and rediscover")
        val rediscovered = waitForRediscovery()

        val clearTarget = targetFor(rediscovered) ?: request.target
        event("Clearing pmm_eth_console=0 with fw_setenv")
        requireOk(
            commandClient.shellExec(clearTarget, "fw_setenv pmm_eth_console 0", commandOptions, timeoutSeconds = 8.0),
            "clear pmm_eth_console=0",
        )
        val disabled = requireOk(
            commandClient.shellExec(clearTarget, "fw_printenv pmm_eth_console", commandOptions, timeoutSeconds = 5.0),
            "verify pmm_eth_console=0",
        )
        requireStdoutContains(disabled, "pmm_eth_console=0", "verify pmm_eth_console=0")

        event("Flash workflow complete")
        return rediscovered
    }

    private fun prepareArtifacts(): Bmap? {
        if (!request.image.exists()) {
            throw IllegalArgumentException("image not found: ${request.image}")
        }
        if (request.mode == FlashMode.FULL_IMAGE) {
            event("Using full-image write mode")
            return null
        }
        val bmap = request.bmap ?: throw IllegalArgumentException("bmap mode requires a bmap file")
        if (!bmap.exists()) {
            throw IllegalArgumentException("bmap not found: $bmap")
        }
        event("Parsing bmap: ${bmap.name}")
        return Bmap.parse(bmap)
    }

    private fun discoverAoE(aoe: AoEFlasher) {
        val deadline = System.nanoTime() + 30_000L * 1_000_000L
        var lastError: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                aoe.discover(2_000)
                return
            } catch (e: Throwable) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw AoEException(lastError?.message ?: "timed out discovering AoE target e0.0")
    }

    private fun waitForRediscovery(): Device {
        val deadline = System.nanoTime() + 180_000L * 1_000_000L
        while (System.nanoTime() < deadline) {
            val devices = runCatching {
                Discoverer().discover(
                    DiscoveryOptions(
                        timeoutSeconds = 3.0,
                        secret = request.secret,
                        transportMode = TransportMode.AUTO,
                        interfaces = listOf(request.interfaceName),
                        retries = 5,
                    ),
                )
            }.getOrDefault(emptyList())
            val match = devices.firstOrNull { matchesTarget(it, request.target) }
            if (match != null) {
                event("Rediscovered ${match.text("name") ?: match.text("serial") ?: request.target.label}")
                return match
            }
            Thread.sleep(1_000)
        }
        throw RuntimeException("timed out waiting for PMM rediscovery after flash")
    }

    private fun requireOk(response: CommandResponse?, action: String): CommandResponse {
        if (response == null) {
            throw RuntimeException("No reply while trying to $action. The PMM discovery service may need the SENG-982 shell_exec update.")
        }
        if (response.text("status") != "ok") {
            throw RuntimeException("Failed to $action: ${response.text("error") ?: "unknown error"}")
        }
        val stdout = response.text("stdout")?.trim().orEmpty()
        if (stdout.isNotEmpty()) {
            event("$action stdout: $stdout")
        }
        return response
    }

    private fun requireStdoutContains(response: CommandResponse, expected: String, action: String) {
        val stdout = response.text("stdout").orEmpty()
        if (!stdout.lineSequence().any { it.trim() == expected }) {
            throw RuntimeException("Failed to $action: expected '$expected', got '${stdout.trim()}'")
        }
    }

    private fun event(message: String) {
        onEvent(FlashEvent(message))
    }

    private fun consoleOutput(output: String) {
        val clean = output.replace("\r", "").trim()
        if (clean.isNotEmpty()) {
            onEvent(FlashEvent(clean, "console"))
        }
    }

    private fun progressText(progress: AoEProgress): String {
        if (progress.totalBytes <= 0) {
            return progress.phase
        }
        val pct = progress.doneBytes * 100.0 / progress.totalBytes
        val mib = progress.doneBytes / (1024.0 * 1024.0)
        return "${progress.phase}: ${"%.1f".format(pct)}%  ${"%.1f".format(mib)} MiB"
    }

    companion object {
        fun defaultBmapFor(image: File): File {
            return if (image.name.endsWith(".wic.lz4")) {
                File(image.parentFile, image.name.removeSuffix(".lz4") + ".bmap")
            } else {
                File(image.parentFile, image.name + ".bmap")
            }
        }

        fun bestInterfaceFor(device: Device, preferred: String?): String? {
            preferred?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            device.paths.firstOrNull { it.transport == "l2" && !it.interfaceName.isNullOrBlank() }?.interfaceName?.let {
                return it
            }
            return EthernetFrameTransport.candidateInterfaces().firstOrNull()
        }

        fun targetFor(device: Device): TargetSelector? {
            val targetText = listOf("device_id", "serial", "mac")
                .firstNotNullOfOrNull { field ->
                    device.text(field)?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
                }
                ?: return null
            return TargetSelector.parse(targetText)
        }

        fun matchesTarget(device: Device, target: TargetSelector): Boolean {
            target.serial?.let { serial ->
                for (field in listOf("device_id", "serial")) {
                    if (device.text(field)?.equals(serial, ignoreCase = true) == true) {
                        return true
                    }
                }
            }
            target.mac?.let { mac ->
                if (device.text("mac")?.equals(mac, ignoreCase = true) == true) {
                    return true
                }
            }
            return false
        }

        fun progressPercent(event: FlashEvent): Int {
            if (event.totalBytes <= 0) {
                return 0
            }
            return ((event.doneBytes * 100.0 / event.totalBytes).roundToInt()).coerceIn(0, 100)
        }
    }
}
