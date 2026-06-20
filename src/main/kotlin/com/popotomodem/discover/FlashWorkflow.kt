package com.popotomodem.discover

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
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
    val aoeTarget: AoETargetAddress,
    val image: File,
    val bmap: File?,
    val mode: FlashMode,
    val bootloaderImage: File?,
    val secret: String?,
)

class FlashWorkflow(
    private val request: FlashRequest,
    private val onEvent: (FlashEvent) -> Unit,
) {
    private data class PreservedFile(
        val path: String,
        val bytes: ByteArray,
        val mode: String?,
        val owner: String?,
        val group: String?,
    )

    fun run(): Device {
        val bmap = prepareArtifacts()
        val commandClient = CommandClient()
        val commandOptions = CommandOptions(
            timeoutSeconds = 5.0,
            secret = request.secret,
            interfaces = listOf(request.interfaceName),
            transportMode = TransportMode.AUTO,
        )
        val preservedFiles = preserveDeviceFiles(commandClient, request.target, commandOptions)

        BootloaderFlasher(
            commandClient,
            commandOptions,
            onEvent,
            sshHost = request.initialDevice.text("ip"),
        ).flashIfRequested(request.target, request.bootloaderImage)

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
            console.peerMacText()?.let { event("U-Boot Ethernet console peer: $it") }

            readUbootFdtfile(console)

            val aoeCommand = "aoe mmc 2 ${request.aoeTarget.commandSuffix()}"
            event("Starting U-Boot AoE export: $aoeCommand (${request.aoeTarget.label})")
            console.sendCommand(aoeCommand)
            console.waitForAoEExport(10_000) { output -> consoleOutput(output) }

            AoEFlasher.open(
                interfaceName = request.interfaceName,
                major = request.aoeTarget.major,
                minor = request.aoeTarget.minor,
            ).use { aoe ->
                event("Discovering AoE target ${request.aoeTarget.label}")
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
        restoreDeviceFiles(commandClient, clearTarget, commandOptions, preservedFiles)

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
        request.bootloaderImage?.let { bootloader ->
            if (!bootloader.isFile) {
                throw IllegalArgumentException("imx-boot image not found: $bootloader")
            }
            event("Bootloader update requested: ${bootloader.name}")
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
        throw AoEException(lastError?.message ?: "timed out discovering AoE target ${request.aoeTarget.label}")
    }

    private fun readUbootFdtfile(console: PmmEthConsoleClient) {
        event("Reading U-Boot fdtfile")
        console.sendCommand("printenv fdtfile")
        val output = console.readUntil(3_000) { text ->
            text.contains("u-boot=>") || text.contains("=>")
        }
        val fdtfile = Regex("""(?m)\bfdtfile=([^\s\r\n]+)""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
        event("U-Boot fdtfile: ${fdtfile ?: "unknown"}")
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
                event("Rediscovered ${match.text("name") ?: match.deviceIdText() ?: request.target.label}")
                return match
            }
            Thread.sleep(1_000)
        }
        throw RuntimeException("timed out waiting for PMM rediscovery after flash")
    }

    private fun preserveDeviceFiles(
        commandClient: CommandClient,
        target: TargetSelector,
        options: CommandOptions,
    ): List<PreservedFile> {
        event("Checking device identity/license files to preserve")
        return PRESERVED_DEVICE_FILES.mapNotNull { path ->
            readDeviceFile(commandClient, target, options, path)
        }.also { preserved ->
            event("Preserved ${preserved.size}/${PRESERVED_DEVICE_FILES.size} device file(s)")
        }
    }

    private fun readDeviceFile(
        commandClient: CommandClient,
        target: TargetSelector,
        options: CommandOptions,
        path: String,
    ): PreservedFile? {
        val quoted = shellQuote(path)
        val metadata = requireOk(
            commandClient.shellExec(
                target,
                "if [ -f $quoted ]; then stat -c '%s %a %U %G' -- $quoted; else echo MISSING; fi",
                options,
                timeoutSeconds = 5.0,
            ),
            "inspect $path",
            logStdout = false,
        ).text("stdout")?.trim().orEmpty().lineSequence().lastOrNull()?.trim().orEmpty()

        if (metadata == "MISSING") {
            event("Preserve skipped, missing: $path")
            return null
        }

        val parts = metadata.split(Regex("\\s+"), limit = 4)
        val size = parts.getOrNull(0)?.toLongOrNull()
            ?: throw RuntimeException("Unable to inspect $path: '$metadata'")
        if (size > MAX_PRESERVED_FILE_BYTES) {
            throw RuntimeException("Refusing to preserve $path: $size bytes exceeds limit $MAX_PRESERVED_FILE_BYTES")
        }
        val mode = parts.getOrNull(1)
        val owner = parts.getOrNull(2)
        val group = parts.getOrNull(3)

        val out = ByteArrayOutputStream(size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var offset = 0L
        while (offset < size) {
            val response = requireOk(
                commandClient.shellExec(
                    target,
                    "dd if=$quoted bs=$PRESERVE_CHUNK_BYTES skip=$offset iflag=skip_bytes,count_bytes count=$PRESERVE_CHUNK_BYTES 2>/dev/null | base64 -w0",
                    options,
                    timeoutSeconds = 5.0,
                ),
                "read $path at $offset",
                logStdout = false,
            )
            val encoded = response.text("stdout")?.trim().orEmpty()
            if (encoded.contains("[truncated]")) {
                throw RuntimeException("Failed to read $path: shell output was truncated")
            }
            val chunk = if (encoded.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(encoded)
            out.write(chunk)
            offset += chunk.size
            if (chunk.isEmpty()) {
                throw RuntimeException("Failed to read $path: empty chunk at offset $offset")
            }
        }
        val bytes = out.toByteArray()
        if (bytes.size.toLong() != size) {
            throw RuntimeException("Failed to read $path: expected $size bytes, got ${bytes.size}")
        }
        event("Preserved $path (${bytes.size} bytes)")
        return PreservedFile(path, bytes, mode, owner, group)
    }

    private fun restoreDeviceFiles(
        commandClient: CommandClient,
        target: TargetSelector,
        options: CommandOptions,
        preservedFiles: List<PreservedFile>,
    ) {
        if (preservedFiles.isEmpty()) {
            event("No device identity/license files to restore")
            return
        }

        event("Restoring ${preservedFiles.size} device identity/license file(s)")
        for (file in preservedFiles) {
            restoreDeviceFile(commandClient, target, options, file)
        }
        requireOk(
            commandClient.shellExec(target, "sync", options, timeoutSeconds = 5.0),
            "sync restored device files",
        )
    }

    private fun restoreDeviceFile(
        commandClient: CommandClient,
        target: TargetSelector,
        options: CommandOptions,
        file: PreservedFile,
    ) {
        val quoted = shellQuote(file.path)
        requireOk(
            commandClient.shellExec(
                target,
                "mkdir -p -- ${shellQuote(File(file.path).parent ?: "/")} && : > $quoted",
                options,
                timeoutSeconds = 5.0,
            ),
            "prepare ${file.path}",
        )

        var offset = 0
        while (offset < file.bytes.size) {
            val end = (offset + RESTORE_CHUNK_BYTES).coerceAtMost(file.bytes.size)
            val encoded = Base64.getEncoder().encodeToString(file.bytes.copyOfRange(offset, end))
            requireOk(
                commandClient.shellExec(
                    target,
                    "python3 -c ${shellQuote("import base64,sys;p=sys.argv[1];o=int(sys.argv[2]);d=base64.b64decode(sys.argv[3]);f=open(p,'r+b');f.seek(o);f.write(d);f.close()")} $quoted $offset ${shellQuote(encoded)}",
                    options,
                    timeoutSeconds = 5.0,
                ),
                "restore ${file.path} at $offset",
                logStdout = false,
            )
            offset = end
        }

        file.mode?.takeIf { it.matches(Regex("[0-7]{3,4}")) }?.let { mode ->
            requireOk(
                commandClient.shellExec(target, "chmod $mode -- $quoted", options, timeoutSeconds = 5.0),
                "chmod ${file.path}",
            )
        }
        if (!file.owner.isNullOrBlank() && !file.group.isNullOrBlank()) {
            requireOk(
                commandClient.shellExec(
                    target,
                    "chown ${shellQuote(file.owner)}:${shellQuote(file.group)} -- $quoted",
                    options,
                    timeoutSeconds = 5.0,
                ),
                "chown ${file.path}",
            )
        }
        event("Restored ${file.path} (${file.bytes.size} bytes)")
    }

    private fun requireOk(response: CommandResponse?, action: String, logStdout: Boolean = true): CommandResponse {
        if (response == null) {
            throw RuntimeException("No reply while trying to $action. The PMM discovery service may need the SENG-982 shell_exec update.")
        }
        if (response.text("status") != "ok") {
            throw RuntimeException("Failed to $action: ${response.text("error") ?: "unknown error"}")
        }
        val stdout = response.text("stdout")?.trim().orEmpty()
        if (logStdout && stdout.isNotEmpty()) {
            event("$action stdout: $stdout")
        }
        return response
    }

    private fun requireStdoutContains(response: CommandResponse, expected: String, action: String) {
        val stdout = response.text("stdout").orEmpty()
        if (!stdout.lineSequence().any { it.trim().contains(expected) }) {
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
        val mib = progress.doneBytes / (1024.0 * 1024.0)
        return "${progress.phase}: ${"%.1f".format(mib)} MiB written"
    }

    companion object {
        private val PRESERVED_DEVICE_FILES = listOf(
            "/etc/PopotoSerialNumber.txt",
            "/opt/popoto/config.json",
            "/opt/popoto/license.json",
        )
        private const val PRESERVE_CHUNK_BYTES = 240
        private const val RESTORE_CHUNK_BYTES = 512
        private const val MAX_PRESERVED_FILE_BYTES = 1_048_576L

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
            val targetText = device.deviceIdText() ?: return null
            return TargetSelector.parse(targetText)
        }

        fun matchesTarget(device: Device, target: TargetSelector): Boolean {
            target.serial?.let { deviceId ->
                if (device.deviceIdText()?.equals(deviceId, ignoreCase = true) == true) {
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
