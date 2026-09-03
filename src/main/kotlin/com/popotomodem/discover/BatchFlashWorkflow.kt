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
    private val preservationCache = DevicePreservationCache()
    private val finalizationModes = ConcurrentHashMap<String, UbootFinalizationMode>()

    fun run(): List<Device> {
        require(requests.isNotEmpty()) { "no flash targets selected" }
        validateTargets()

        val bmap = prepareArtifacts()
        val contexts = configureAndReboot()
        waitForUbootAoE(contexts)
        flashTargets(contexts, bmap)
        resetTargets(contexts)
        val rediscovered = waitForLinux(contexts)
        val verificationFailure = runCatching { verifyRootfsCapacity(rediscovered) }.exceptionOrNull()
        val restorationFailure = runCatching { restoreAndClear(rediscovered) }.exceptionOrNull()
        if (verificationFailure != null) {
            restorationFailure?.let(verificationFailure::addSuppressed)
            throw verificationFailure
        }
        restorationFailure?.let { throw it }
        requests.forEach { event(it, "Flash workflow complete") }
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
        val resolvedRequests = UbootAoeTargetResolver.resolve(requests)
        resolvedRequests.zip(requests).forEach { (resolved, requested) ->
            if (resolved.aoeTarget != requested.aoeTarget) {
                event(
                    resolved,
                    "Single-target fallback authorized: using current ${resolved.aoeTarget.label} export, " +
                        "pinned to U-Boot MAC ${resolved.expectedAoeSourceMac}",
                )
            }
        }
        val alreadyInUbootAoE = resolvedRequests.filter(::isAlreadyInRequestedUbootAoE)
        alreadyInUbootAoE.forEach { request ->
            if (request.bootloaderImage != null) {
                throw IllegalArgumentException(
                    "Cannot program U-Boot on ${request.target.label}: target is already in U-Boot AoE mode. " +
                        "Bootloader programming requires Linux and uboot-flash.",
                )
            }
            if (loadPendingPreservation(request) == null) {
                event(
                    request,
                    "WARNING: target is already in U-Boot with no preservation snapshot; " +
                        "device-specific files cannot be restored after flashing",
                )
            }
            event(request, "Target is already in U-Boot AoE mode on ${request.aoeTarget.label}; resuming at AoE write")
        }

        val needsLinuxSetup = resolvedRequests - alreadyInUbootAoE.toSet()
        if (needsLinuxSetup.any { it.bootloaderImage != null } && needsLinuxSetup.size > 1) {
            requests.forEach { request ->
                event(request, "Bootloader update requested; preparing targets one at a time before AoE writes")
            }
            needsLinuxSetup.map(::configureAndRebootOne)
        } else {
            parallel(needsLinuxSetup, ::configureAndRebootOne)
        }
        return resolvedRequests
    }

    private fun configureAndRebootOne(request: FlashRequest): FlashRequest {
        val options = commandOptions(request, timeoutSeconds = 8.0)
        val preserver = DeviceFilePreserver(commandClient, options, preserveSshKeys = request.preserveSshKeys) { event ->
            onEvent(BatchFlashEvent(request, event))
        }
        event(request, "Preparing ${request.aoeTarget.label} for U-Boot AoE flash mode")
        val bootloaderFlasher = BootloaderFlasher(commandClient, options, { event ->
            onEvent(BatchFlashEvent(request, event))
        }, sshHost = request.initialDevice.sshHostText())
        bootloaderFlasher.ensureMmcUtils(request.target)
        val programmedBootloader = bootloaderFlasher.flashIfRequested(request.target, request.bootloaderImage)

        if (programmedBootloader == null) {
            event(request, "Checking active eMMC U-Boot before AoE reboot")
            val activeBootloader = try {
                ActiveBootloaderSupportInspector.requireSupported(
                    commandClient,
                    request.target,
                    options,
                )
            } catch (failure: RuntimeException) {
                disarmUnsafeAoeBoot(request, options, failure)
            }
            event(
                request,
                "Active eMMC ${activeBootloader.activeSlot} supports Popoto Discover AoE " +
                    "(PARTITION_CONFIG=${activeBootloader.partitionConfig})",
            )
        } else {
            event(
                request,
                "Supplied imx-boot is byte-for-byte active in ${programmedBootloader.activeSlot}; " +
                    "U-Boot AoE discovery after reboot will verify runtime support",
            )
        }

        val pending = loadPendingPreservation(request)
        if (pending == null) {
            val captured = preserver.preserve(request.target)
            preserved[key(request)] = captured
            val saved = preservationCache.save(request.target.label, captured)
            event(
                request,
                "Saved durable preservation snapshot (${saved.files.size} file(s)) at " +
                    preservationCache.pathFor(request.target.label),
            )
        }

        val setEnvironment = commandClient.shellExec(
            request.target,
            UbootAoeMode.setEnvCommand(request.aoeTarget),
            options,
            timeoutSeconds = 10.0,
        )
        if (setEnvironment == null) {
            event(request, "No direct environment-write acknowledgement; verifying persisted values")
        } else {
            requireOk(request, setEnvironment, "set U-Boot AoE flash environment")
        }

        val verify = requireOk(
            request,
            commandClient.shellExec(
                request.target,
                UbootAoeMode.verifyEnvCommand(),
                options,
                timeoutSeconds = 5.0,
                repeatRequest = true,
            ),
            "verify U-Boot AoE flash environment",
        )
        try {
            UbootAoeMode.verifyEnv(verify, request.aoeTarget)
        } catch (e: RuntimeException) {
            throw RuntimeException("${request.target.label}: ${e.message}")
        }

        event(request, "Rebooting into automatic AoE export")
        val rebootResponse = commandClient.shellExec(
            request.target,
            UbootAoeMode.rebootCommand(),
            options,
            timeoutSeconds = 2.0,
        )
        when {
            rebootResponse == null ->
                event(request, "Reboot command was not acknowledged; verifying the modem's actual boot state")
            rebootResponse.text("status") != "ok" ->
                event(
                    request,
                    "Reboot command reported ${rebootResponse.text("error") ?: "an error"}; " +
                        "verifying the modem's actual boot state",
                )
            else -> event(request, "Reboot command accepted; waiting for U-Boot AoE")
        }
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
        val recovery = pending.keys.associateWith { RebootRecoveryPolicy() }

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
                        FlashWorkflow.matchesTarget(it, request.target)
                    }
                    if (device == null) {
                        continue
                    }
                    if (device.text("uboot") != "1") {
                        recoverIgnoredReboot(request, recovery.getValue(key(request)))
                        continue
                    }
                    val active = device.text("aoe_active") == "1"
                    val label = device.text("aoe_target")
                    if (active && label == request.aoeTarget.label) {
                        val finalizationMode = UbootFinalizationMode.forDevice(device)
                        finalizationModes[key(request)] = finalizationMode
                        event(
                            request,
                            "U-Boot AoE ready on ${request.aoeTarget.label}; " +
                                "fdtfile=${device.text("fdtfile") ?: "unknown"}; " +
                                "completion=${finalizationMode.logLabel}",
                        )
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

    private fun recoverIgnoredReboot(request: FlashRequest, state: RebootRecoveryPolicy) {
        when (state.onLinuxObserved()) {
            RebootRecoveryAction.WAIT -> return
            RebootRecoveryAction.FAIL -> throw RuntimeException(
                "${request.target.label}: reboot did not take effect; the modem still responds from Linux " +
                    "after ${RebootRecoveryPolicy.DEFAULT_MAX_ATTEMPTS} forced reboot attempts",
            )
            RebootRecoveryAction.FORCE_REBOOT -> Unit
        }
        event(
            request,
            "Modem still responds from Linux; forcing reboot " +
                "(${state.attempts}/${RebootRecoveryPolicy.DEFAULT_MAX_ATTEMPTS})",
        )
        val response = commandClient.shellExec(
            request.target,
            UbootAoeMode.forceRebootCommand(),
            l2CommandOptions(request, timeoutSeconds = 3.0),
            timeoutSeconds = 3.0,
        )
        when {
            response == null ->
                event(request, "Forced reboot interrupted its reply; checking for U-Boot AoE")
            response.text("status") == "ok" ->
                event(request, "Forced reboot accepted; checking for U-Boot AoE")
            else ->
                event(
                    request,
                    "Forced reboot reported ${response.text("error") ?: "an error"}; the host will retry if Linux remains up",
                )
        }
    }

    private fun flashTargets(contexts: List<FlashRequest>, bmap: Bmap?) {
        parallel(contexts) { request ->
            AoEFlasher.open(
                interfaceName = request.interfaceName,
                major = request.aoeTarget.major,
                minor = request.aoeTarget.minor,
                expectedTargetMac = request.expectedAoeSourceMac,
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
            val mode = finalizationModes[key(request)]
                ?: throw RuntimeException("${request.target.label}: U-Boot completion capability was not recorded")
            event(request, mode.eventText)
            val options = commandOptions(request, timeoutSeconds = 180.0).copy(transportMode = TransportMode.L2)
            val response = when (mode) {
                UbootFinalizationMode.FINALIZE_FLASH -> commandClient.finalizeFlash(request.target, options)
                UbootFinalizationMode.BOOT_LINUX -> commandClient.bootLinux(request.target, options)
            }
            if (response == null) {
                event(request, "Reset acknowledgement was not received; waiting for Linux rediscovery to confirm completion")
            } else {
                requireOk(request, response, mode.actionText, logStdout = false)
            }
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
            val preserver = DeviceFilePreserver(commandClient, options, preserveSshKeys = request.preserveSshKeys) { event ->
                onEvent(BatchFlashEvent(request, event))
            }
            preserver.restore(target, preserved[key(request)].orEmpty())

            event(request, "Clearing U-Boot AoE flash environment")
            val clearEnvironment = commandClient.shellExec(
                target,
                UbootAoeMode.clearFlashEnvCommand(),
                options,
                timeoutSeconds = 10.0,
            )
            if (clearEnvironment == null) {
                event(request, "No direct environment-clear acknowledgement; verifying persisted values")
            } else {
                requireOk(request, clearEnvironment, "clear U-Boot AoE flash environment")
            }
            val verify = requireOk(
                request,
                commandClient.shellExec(
                    target,
                    "fw_printenv pmm_aoe_flash",
                    options,
                    timeoutSeconds = 5.0,
                    repeatRequest = true,
                ),
                "verify pmm_aoe_flash=0",
            )
            requireStdoutContains(request, verify, "pmm_aoe_flash=0", "verify pmm_aoe_flash=0")
            preservationCache.delete(request.target.label)
            event(request, "Cleared durable preservation snapshot")
            request
        }
    }

    private fun verifyRootfsCapacity(rediscovered: Map<String, Device>) {
        parallel(requests) { request ->
            val device = rediscovered[key(request)]
                ?: throw RuntimeException("missing rediscovered device for ${request.target.label}")
            val target = FlashWorkflow.targetFor(device) ?: request.target
            event(request, "Verifying rootfs occupies partition 2 and the full eMMC")
            val capacity = RootfsCapacityVerifier.verify(
                commandClient,
                target,
                l2CommandOptions(request, timeoutSeconds = 10.0),
            )
            event(
                request,
                "Verified rootfs finalization: partition=${capacity.partitionBytes} bytes, " +
                    "filesystem=${capacity.filesystemBytes} bytes",
            )
            request
        }
    }

    private fun disarmUnsafeAoeBoot(
        request: FlashRequest,
        options: CommandOptions,
        failure: RuntimeException,
    ): Nothing {
        event(request, "U-Boot safety check failed; disarming any pending AoE boot request")
        val cleanup = commandClient.shellExec(
            request.target,
            UbootAoeMode.clearFlashEnvCommand(),
            options,
            timeoutSeconds = 10.0,
        )
        val cleanupError = when {
            cleanup == null -> {
                val verify = commandClient.shellExec(
                    request.target,
                    "fw_printenv pmm_aoe_flash",
                    options,
                    timeoutSeconds = 5.0,
                    repeatRequest = true,
                )
                if (verify?.text("stdout")?.lineSequence()?.any { it.trim() == "pmm_aoe_flash=0" } == true) {
                    ""
                } else {
                    " No reply was received while clearing stale AoE boot variables."
                }
            }
            cleanup.text("status") != "ok" ->
                " Clearing stale AoE boot variables failed: ${cleanup.text("error") ?: "unknown error"}."
            else -> ""
        }
        throw RuntimeException(failure.message.orEmpty() + cleanupError, failure)
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

    private fun loadPendingPreservation(request: FlashRequest): PendingDevicePreservation? {
        val pending = preservationCache.load(request.target.label) ?: return null
        preserved[key(request)] = pending.files
        event(
            request,
            "Using pending preservation snapshot from a prior incomplete flash " +
                "(${pending.files.size} file(s), ${preservationCache.pathFor(request.target.label)})",
        )
        return pending
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

internal enum class UbootFinalizationMode(
    val logLabel: String,
    val eventText: String,
    val actionText: String,
) {
    FINALIZE_FLASH(
        logLabel = "finalize_flash",
        eventText = "Finalizing rootfs and resetting U-Boot target over Popoto Discover L2",
        actionText = "finalize rootfs and reset U-Boot target",
    ),
    BOOT_LINUX(
        logLabel = "boot_linux compatibility path",
        eventText = "Completing flash with legacy U-Boot boot_linux rootfs finalization",
        actionText = "finalize rootfs through boot_linux and reset U-Boot target",
    ),
    ;

    companion object {
        fun forDevice(device: Device): UbootFinalizationMode {
            if (device.text("supports_finalize_flash") == "1") {
                return FINALIZE_FLASH
            }
            require(device.text("supports_boot_linux") == "1") {
                "U-Boot discovery does not advertise finalize_flash or boot_linux completion support"
            }
            return BOOT_LINUX
        }
    }
}
