package com.popotomodem.discover

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import kotlin.math.roundToInt

data class BatchFlashEvent(
    val request: FlashRequest,
    val event: FlashEvent,
)

class BatchFlashWorkflow(
    private val requests: List<FlashRequest>,
    private val onEvent: (BatchFlashEvent) -> Unit,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
) {
    private val commandClient = CommandClient()
    private val preserved = ConcurrentHashMap<String, List<PreservedDeviceFile>>()

    fun run(): List<Device> {
        require(requests.isNotEmpty()) { "no flash targets selected" }
        validateTargets()

        val bmap = prepareArtifacts()
        val contexts = configureAndReboot()
        waitForUbootAoE(contexts)
        flashTargets(contexts, bmap)
        resetTargets(contexts)
        val rediscovered = waitForLinux(contexts)
        restoreAndClear(rediscovered)
        return rediscovered.values.toList()
    }

    private fun validateTargets() {
        val duplicateDeviceIds = requests.groupBy { it.target.label.lowercase() }.filterValues { it.size > 1 }.keys
        if (duplicateDeviceIds.isNotEmpty()) {
            throw IllegalArgumentException("duplicate target identity in selection: ${duplicateDeviceIds.joinToString()}")
        }
        val duplicateAoe = requests.groupBy { it.aoeTarget.label }.filterValues { it.size > 1 }.keys
        if (duplicateAoe.isNotEmpty()) {
            throw IllegalArgumentException("duplicate AoE target address in selection: ${duplicateAoe.joinToString()}")
        }
    }

    private fun prepareArtifacts(): Bmap? {
        val first = requests.first()
        if (!first.image.exists()) {
            throw IllegalArgumentException("image not found: ${first.image}")
        }
        first.bootloaderImage?.let { bootloader ->
            if (!bootloader.isFile) {
                throw IllegalArgumentException("imx-boot image not found: $bootloader")
            }
            requests.forEach { event(it, "Bootloader update requested: ${bootloader.name}") }
            val support = BootloaderImageSupportInspector.inspect(bootloader)
            if (support.hasPmmAoeSupport) {
                requests.forEach { event(it, "Bootloader image includes PMM AoE/discovery support") }
            } else {
                requests.forEach { event(it, "WARNING: ${support.warningText()}") }
            }
        }
        for (request in requests) {
            if (request.image != first.image || request.mode != first.mode || request.bmap != first.bmap) {
                throw IllegalArgumentException("batch flashing requires one shared image and mode")
            }
            if (request.bootloaderImage != first.bootloaderImage) {
                throw IllegalArgumentException("batch flashing requires one shared bootloader image")
            }
        }
        if (first.mode == FlashMode.FULL_IMAGE) {
            requests.forEach { event(it, "Using full-image write mode") }
            return null
        }
        val bmapFile = first.bmap ?: throw IllegalArgumentException("bmap mode requires a bmap file")
        if (!bmapFile.exists()) {
            throw IllegalArgumentException("bmap not found: $bmapFile")
        }
        requests.forEach { event(it, "Parsing bmap: ${bmapFile.name}") }
        return Bmap.parse(bmapFile)
    }

    private fun configureAndReboot(): List<FlashRequest> {
        val alreadyInUbootAoE = requests.filter(::isAlreadyInRequestedUbootAoE)
        alreadyInUbootAoE.forEach { request ->
            if (request.bootloaderImage != null) {
                throw IllegalArgumentException(
                    "Cannot program U-Boot on ${request.target.label}: target is already in U-Boot AoE mode. " +
                        "Bootloader programming requires Linux and uboot-flash.",
                )
            }
            event(request, "Target is already in U-Boot AoE mode on ${request.aoeTarget.label}; resuming at AoE write")
        }

        val needsLinuxSetup = requests - alreadyInUbootAoE.toSet()
        if (needsLinuxSetup.any { it.bootloaderImage != null } && needsLinuxSetup.size > 1) {
            requests.forEach { request ->
                event(request, "Bootloader update requested; preparing targets one at a time before AoE writes")
            }
            needsLinuxSetup.map(::configureAndRebootOne)
        } else {
            parallel(needsLinuxSetup, ::configureAndRebootOne)
        }
        return requests
    }

    private fun configureAndRebootOne(request: FlashRequest): FlashRequest {
        val options = commandOptions(request, timeoutSeconds = 8.0)
        val preserver = DeviceFilePreserver(commandClient, options) { event ->
            onEvent(BatchFlashEvent(request, event))
        }
        event(request, "Preparing ${request.aoeTarget.label} for U-Boot AoE flash mode")
        preserved[key(request)] = preserver.preserve(request.target)
        BootloaderFlasher(commandClient, options, { event ->
            onEvent(BatchFlashEvent(request, event))
        }, sshHost = request.initialDevice.sshHostText()).flashIfRequested(request.target, request.bootloaderImage)

        requireOk(
            request,
            commandClient.shellExec(
                request.target,
                UbootAoeMode.setEnvCommand(request.aoeTarget),
                options,
                timeoutSeconds = 10.0,
            ),
            "set U-Boot AoE flash environment",
        )

        val verify = requireOk(
            request,
            commandClient.shellExec(
                request.target,
                UbootAoeMode.verifyEnvCommand(),
                options,
                timeoutSeconds = 5.0,
            ),
            "verify U-Boot AoE flash environment",
        )
        try {
            UbootAoeMode.verifyEnv(verify, request.aoeTarget)
        } catch (e: RuntimeException) {
            throw RuntimeException("${request.target.label}: ${e.message}")
        }

        event(request, "Rebooting into automatic AoE export")
        requireOk(
            request,
            commandClient.shellExec(
                request.target,
                UbootAoeMode.rebootCommand(),
                options,
                timeoutSeconds = 2.0,
            ),
            "reboot PMM",
        )
        return request
    }

    private fun isAlreadyInRequestedUbootAoE(request: FlashRequest): Boolean {
        val device = request.initialDevice
        if (device.text("uboot") != "1") {
            return false
        }
        if (!FlashWorkflow.matchesTarget(device, request.target)) {
            return false
        }
        val active = device.text("aoe_active") == "1"
        val aoeTarget = device.text("aoe_target")
        if (active && aoeTarget == request.aoeTarget.label) {
            return true
        }
        throw IllegalArgumentException(
            "Target ${request.target.label} is in U-Boot but not exporting ${request.aoeTarget.label} " +
                "(current ${aoeTarget ?: "not exported"}). Reset or start the expected AoE export first.",
        )
    }

    private fun waitForUbootAoE(contexts: List<FlashRequest>) {
        val pending = contexts.associateBy { key(it) }.toMutableMap()
        val deadline = System.nanoTime() + 90_000L * 1_000_000L

        while (pending.isNotEmpty() && System.nanoTime() < deadline) {
            val byInterface = pending.values.groupBy { it.interfaceName }
            for ((interfaceName, requestsOnInterface) in byInterface) {
                val devices = runCatching {
                    Discoverer().discover(
                        DiscoveryOptions(
                            timeoutSeconds = 2.0,
                            secret = requestsOnInterface.first().secret,
                            transportMode = TransportMode.L2,
                            interfaces = listOf(interfaceName),
                            retries = 4,
                        ),
                    )
                }.getOrDefault(emptyList())

                for (request in requestsOnInterface) {
                    val device = devices.firstOrNull {
                        it.text("uboot") == "1" &&
                            FlashWorkflow.matchesTarget(it, request.target)
                    }
                    if (isAoETargetReady(request)) {
                        event(request, "AoE target ${request.aoeTarget.label} is ready")
                        pending.remove(key(request))
                        continue
                    }
                    if (device == null) {
                        continue
                    }
                    val active = device.text("aoe_active") == "1"
                    val label = device.text("aoe_target")
                    if (active && label == request.aoeTarget.label) {
                        event(request, "U-Boot AoE ready on ${request.aoeTarget.label}; fdtfile=${device.text("fdtfile") ?: "unknown"}")
                        pending.remove(key(request))
                    } else {
                        event(request, "Saw U-Boot, waiting for ${request.aoeTarget.label} (current ${label ?: "not exported"})")
                    }
                }
            }
            if (pending.isNotEmpty()) {
                Thread.sleep(500)
            }
        }

        if (pending.isNotEmpty()) {
            throw RuntimeException("timed out waiting for U-Boot AoE target(s): ${pending.values.joinToString { it.target.label }}")
        }
    }

    private fun isAoETargetReady(request: FlashRequest): Boolean {
        return runCatching {
            AoEFlasher.open(
                interfaceName = request.interfaceName,
                major = request.aoeTarget.major,
                minor = request.aoeTarget.minor,
            ).use { aoe ->
                aoe.discover(600)
                aoe.readSectors(0, 1)
            }
            true
        }.getOrDefault(false)
    }

    private fun flashTargets(contexts: List<FlashRequest>, bmap: Bmap?) {
        parallel(contexts) { request ->
            AoEFlasher.open(
                interfaceName = request.interfaceName,
                major = request.aoeTarget.major,
                minor = request.aoeTarget.minor,
            ).use { aoe ->
                event(request, "Discovering AoE target ${request.aoeTarget.label}")
                discoverAoE(request, aoe)
                event(request, "AoE target found; testing LBA0 read")
                aoe.readSectors(0, 1)

                val window = aoe.preferredWindow().coerceAtLeast(AoEFlasher.AOE_DEFAULT_WINDOW)
                when (request.mode) {
                    FlashMode.BMAP -> {
                        val parsed = bmap ?: throw IllegalArgumentException("bmap mode requires a bmap file")
                        event(request, "Writing WIC bmap payload over AoE; window=$window")
                        aoe.writeBmap(request.image, parsed, window) { progress ->
                            if (progress.isRetryNotice()) return@writeBmap
                            val message = progress.message ?: progressText(progress)
                            onEvent(BatchFlashEvent(request, FlashEvent(message, progress.phase, progress.doneBytes, progress.totalBytes)))
                        }
                    }
                    FlashMode.FULL_IMAGE -> {
                        event(request, "Writing full WIC image over AoE; window=$window")
                        aoe.writeFull(request.image, window) { progress ->
                            if (progress.isRetryNotice()) return@writeFull
                            val message = progress.message ?: progressText(progress)
                            onEvent(BatchFlashEvent(request, FlashEvent(message, progress.phase, progress.doneBytes, progress.totalBytes)))
                        }
                    }
                }

                event(request, "Flushing AoE target write cache")
                aoe.flush()
            }
            request
        }
    }

    private fun resetTargets(contexts: List<FlashRequest>) {
        parallel(contexts) { request ->
            event(request, "Resetting U-Boot target over Popoto Discover L2")
            requireOk(
                request,
                commandClient.reboot(
                    request.target,
                    commandOptions(request, timeoutSeconds = 8.0).copy(transportMode = TransportMode.L2),
                ),
                "reset U-Boot target",
                logStdout = false,
            )
            request
        }
    }

    private fun waitForLinux(contexts: List<FlashRequest>): Map<String, Device> {
        val pending = contexts.associateBy { key(it) }.toMutableMap()
        val found = mutableMapOf<String, Device>()
        val deadline = System.nanoTime() + 240_000L * 1_000_000L

        while (pending.isNotEmpty() && System.nanoTime() < deadline) {
            val byInterface = pending.values.groupBy { it.interfaceName }
            for ((interfaceName, requestsOnInterface) in byInterface) {
                val devices = runCatching {
                    Discoverer().discover(
                        DiscoveryOptions(
                            timeoutSeconds = 3.0,
                            secret = requestsOnInterface.first().secret,
                            transportMode = TransportMode.L2,
                            interfaces = listOf(interfaceName),
                            retries = 5,
                        ),
                    )
                }.getOrDefault(emptyList())

                for (request in requestsOnInterface) {
                    val device = devices.firstOrNull {
                        it.text("uboot") != "1" &&
                            FlashWorkflow.matchesTarget(it, request.target)
                    } ?: continue
                    found[key(request)] = device
                    pending.remove(key(request))
                    event(request, "Rediscovered ${device.text("name") ?: device.deviceIdText() ?: request.target.label}")
                }
            }
            if (pending.isNotEmpty()) {
                Thread.sleep(1_000)
            }
        }

        if (pending.isNotEmpty()) {
            throw RuntimeException("timed out waiting for Linux rediscovery: ${pending.values.joinToString { it.target.label }}")
        }
        return found
    }

    private fun restoreAndClear(rediscovered: Map<String, Device>) {
        parallel(requests) { request ->
            val device = rediscovered[key(request)] ?: throw RuntimeException("missing rediscovered device for ${request.target.label}")
            val target = FlashWorkflow.targetFor(device) ?: request.target
            val options = l2CommandOptions(request, timeoutSeconds = 8.0)
            val preserver = DeviceFilePreserver(commandClient, options) { event ->
                onEvent(BatchFlashEvent(request, event))
            }
            preserver.restore(target, preserved[key(request)].orEmpty())

            event(request, "Clearing U-Boot AoE flash environment")
            requireOk(
                request,
                commandClient.shellExec(
                    target,
                    listOf(
                        "fw_setenv pmm_aoe_flash 0",
                        "fw_setenv pmm_aoe_major 0",
                        "fw_setenv pmm_aoe_minor 0",
                        "fw_setenv pmm_eth_console 0",
                    ).joinToString(" && "),
                    options,
                    timeoutSeconds = 10.0,
                ),
                "clear U-Boot AoE flash environment",
            )
            val verify = requireOk(
                request,
                commandClient.shellExec(target, "fw_printenv pmm_aoe_flash", options, timeoutSeconds = 5.0),
                "verify pmm_aoe_flash=0",
            )
            requireStdoutContains(request, verify, "pmm_aoe_flash=0", "verify pmm_aoe_flash=0")
            event(request, "Flash workflow complete")
            request
        }
    }

    private fun discoverAoE(request: FlashRequest, aoe: AoEFlasher) {
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

    private fun commandOptions(request: FlashRequest, timeoutSeconds: Double) = CommandOptions(
        timeoutSeconds = timeoutSeconds,
        secret = request.secret,
        interfaces = listOf(request.interfaceName),
        transportMode = TransportMode.AUTO,
    )

    private fun l2CommandOptions(request: FlashRequest, timeoutSeconds: Double) =
        commandOptions(request, timeoutSeconds).copy(transportMode = TransportMode.L2)

    private fun requireOk(request: FlashRequest, response: CommandResponse?, action: String, logStdout: Boolean = true): CommandResponse {
        if (response == null) {
            throw RuntimeException("No reply while trying to $action on ${request.target.label}")
        }
        if (response.text("status") != "ok") {
            throw RuntimeException("Failed to $action on ${request.target.label}: ${response.text("error") ?: "unknown error"}")
        }
        val stdout = response.text("stdout")?.trim().orEmpty()
        if (logStdout && stdout.isNotEmpty()) {
            event(request, "$action stdout: $stdout")
        }
        return response
    }

    private fun requireStdoutContains(request: FlashRequest, response: CommandResponse, expected: String, action: String) {
        val stdout = response.text("stdout").orEmpty()
        if (!stdout.lineSequence().any { it.trim() == expected }) {
            throw RuntimeException("Failed to $action on ${request.target.label}: expected '$expected', got '${stdout.trim()}'")
        }
    }

    private fun event(request: FlashRequest, message: String) {
        onEvent(BatchFlashEvent(request, FlashEvent(message)))
    }

    private fun progressText(progress: AoEProgress): String {
        return formatFlashProgress(progress)
    }

    private fun AoEProgress.isRetryNotice(): Boolean {
        return message?.startsWith("retrying ") == true
    }

    private fun <T, R> parallel(items: List<T>, block: (T) -> R): List<R> {
        val threads = items.size.coerceIn(1, maxConcurrency.coerceAtLeast(1))
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures = items.map { item -> executor.submit(Callable { block(item) }) }
            return futures.map {
                try {
                    it.get()
                } catch (e: ExecutionException) {
                    throw (e.cause ?: e)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun key(request: FlashRequest): String = request.target.label.lowercase()

    companion object {
        const val DEFAULT_MAX_CONCURRENCY = 10

        fun progressPercent(events: List<FlashEvent>): Int {
            val totals = events.filter { it.totalBytes > 0 }
            if (totals.isEmpty()) {
                return 0
            }
            val done = totals.sumOf { it.doneBytes }
            val total = totals.sumOf { it.totalBytes }
            if (total <= 0) {
                return 0
            }
            return ((done * 100.0 / total).roundToInt()).coerceIn(0, 100)
        }
    }
}
