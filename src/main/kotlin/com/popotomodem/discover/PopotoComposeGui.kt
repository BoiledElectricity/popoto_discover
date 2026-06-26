package com.popotomodem.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.prefs.Preferences

object PopotoComposeGui {
    fun launch(secretFile: String?, noAuth: Boolean) = application {
        val windowState = rememberWindowState(size = DpSize(1320.dp, 860.dp))
        var shouldExit by remember { mutableStateOf(false) }
        val appIcon = painterResource("icons/popoto-icon.png")

        Window(
            onCloseRequest = { shouldExit = true },
            title = "Popoto Discover",
            state = windowState,
            icon = appIcon,
        ) {
            if (shouldExit) {
                exitApplication()
            }
            App(secretFile, noAuth, onExit = { shouldExit = true })
        }
    }
}

private val PopotoBlue = Color(0xFF2777C3)
private val AcousticBlue = Color(0xFF21608A)
private val DeepNavy = Color(0xFF172130)
private val DeepSea = Color(0xFF282B34)
private val Clamshell = Color(0xFFF2F4F7)
private val Panel = Color(0xFFFFFFFF)
private val PanelAlt = Color(0xFFF8FAFC)
private val Border = Color(0xFFD9E2EC)
private val Muted = Color(0xFF6B7280)
private val TextPrimary = Color(0xFF21364F)
private val Success = Color(0xFF1F9D72)
private val Danger = Color(0xFFC43D3D)
private val UpdateOrange = Color(0xFFF59E0B)
private val UpdateOrangeBg = Color(0xFFFFF4DE)

private data class ComposeSettings(
    val useCustomSecret: Boolean,
    val secretFile: String,
    val timeout: String,
    val interfaceName: String,
    val wicImage: String,
    val preserveSshKeys: Boolean,
) {
    fun save() {
        runCatching {
            prefs().apply {
                putBoolean(KEY_CUSTOM_SECRET, useCustomSecret)
                put(KEY_SECRET_FILE, secretFile)
                put(KEY_TIMEOUT, timeout)
                put(KEY_INTERFACE, interfaceName)
                put(KEY_WIC_IMAGE, wicImage)
                putBoolean(KEY_PRESERVE_SSH_KEYS, preserveSshKeys)
            }
        }
    }

    companion object {
        private const val KEY_CUSTOM_SECRET = "useCustomSecret"
        private const val KEY_SECRET_FILE = "secretFile"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_INTERFACE = "interface"
        private const val KEY_WIC_IMAGE = "wicImage"
        private const val KEY_PRESERVE_SSH_KEYS = "preserveSshKeys"

        fun load(initialSecretFile: String?): ComposeSettings {
            if (!initialSecretFile.isNullOrBlank()) {
                return ComposeSettings(
                    useCustomSecret = true,
                    secretFile = initialSecretFile,
                    timeout = "8.0",
                    interfaceName = "",
                    wicImage = "",
                    preserveSshKeys = false,
                )
            }
            val prefs = prefs()
            return ComposeSettings(
                useCustomSecret = prefs.getBoolean(KEY_CUSTOM_SECRET, false),
                secretFile = prefs.get(KEY_SECRET_FILE, ""),
                timeout = prefs.get(KEY_TIMEOUT, "8.0"),
                interfaceName = prefs.get(KEY_INTERFACE, ""),
                wicImage = prefs.get(KEY_WIC_IMAGE, ""),
                preserveSshKeys = prefs.getBoolean(KEY_PRESERVE_SSH_KEYS, false),
            )
        }

        private fun prefs(): Preferences = Preferences.userRoot().node("/com/popotomodem/discover/gui")
    }
}

private data class LogLine(
    val stamp: String,
    val level: String,
    val message: String,
)

private sealed interface DialogState {
    data class Message(val title: String, val message: String, val isError: Boolean = false) : DialogState
    data class MfgTestResult(
        val result: String,
        val test: String,
        val returnCode: String,
        val sourceIp: String,
        val output: String,
        val truncated: Boolean,
    ) : DialogState
    data class ConfirmFlash(val plans: List<FlashPlan>) : DialogState
    data object AdvancedConnection : DialogState
    data class SetIp(val device: Device, val ip: String, val netmask: String, val gateway: String) : DialogState
    data class SetRtc(val device: Device, val rtc: String) : DialogState
    data class SetParam(val device: Device, val name: String, val value: String) : DialogState
    data class SyncClient(
        val device: Device,
        val host: String,
        val username: String,
        val password: String,
        val port: String,
    ) : DialogState
    data class InstallClient(
        val host: String,
        val username: String,
        val password: String,
        val port: String,
    ) : DialogState
}

private data class MfgDeviceResult(
    val name: String,
    val result: String,
    val detail: String,
)

private data class FlashPlan(
    val device: Device,
    val target: TargetSelector,
    val interfaceName: String,
    val aoeTarget: AoETargetAddress,
    val image: File,
    val bmap: File?,
    val mode: FlashMode,
)

private class FlashRunState(val request: FlashRequest) {
    var status by mutableStateOf("Starting")
    var bytesWritten by mutableStateOf(0L)
    var bytesWrittenText by mutableStateOf<String?>(null)
    var progress by mutableIntStateOf(0)
    var running by mutableStateOf(true)
    var complete by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val lines = mutableStateListOf<String>()
    private var lastWriteStatusMillis = 0L

    fun add(event: FlashEvent) {
        if (isRetryNotice(event)) {
            return
        }
        if (event.totalBytes > 0) {
            progress = FlashWorkflow.progressPercent(event)
            if (event.phase == "write") {
                bytesWritten = event.doneBytes
                bytesWrittenText = writeDetailText(event)
            }
        }
        if (event.message.lineSequence().firstOrNull()?.trim() == "Flash workflow complete") {
            progress = 100
            running = false
            complete = true
            status = "Flash complete"
        }
        val nextStatus = flashStatusText(event)
        if (nextStatus != null && shouldUpdateStatus(event)) {
            status = nextStatus
        }
        lines += stamped(event.message)
    }

    fun addLogLine(message: String) {
        lines += stamped(message)
    }

    private fun shouldUpdateStatus(event: FlashEvent): Boolean {
        if (!isWriteProgress(event)) {
            return true
        }
        val now = System.currentTimeMillis()
        if (progress >= 100 || now - lastWriteStatusMillis >= FLASH_STATUS_UPDATE_INTERVAL_MS) {
            lastWriteStatusMillis = now
            return true
        }
        return false
    }
}

private class BatchFlashRunState(val requests: List<FlashRequest>) {
    val runs = requests.map { FlashRunState(it) }
    val lines = mutableStateListOf<String>()
    var status by mutableStateOf(batchStatusText(requests.size))
    var progress by mutableIntStateOf(0)
    var running by mutableStateOf(true)
    var complete by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun add(request: FlashRequest, event: FlashEvent) {
        val run = runs.firstOrNull { it.request.target.label == request.target.label } ?: return
        run.add(event)
        val prefix = request.initialDevice.text("name") ?: request.initialDevice.deviceIdText() ?: request.target.label
        lines += stamped("[$prefix] ${event.message}")
        progress = if (runs.isEmpty()) 0 else (runs.sumOf { it.progress } / runs.size).coerceIn(0, 100)
        status = if (runs.size == 1) run.status else batchStatusText(runs.size)
    }

    fun addLine(request: FlashRequest, message: String) {
        val run = runs.firstOrNull { it.request.target.label == request.target.label }
        run?.addLogLine(message)
        val prefix = request.initialDevice.text("name") ?: request.initialDevice.deviceIdText() ?: request.target.label
        lines += stamped("[$prefix] $message")
    }

    fun markComplete(rediscovered: List<Device>) {
        progress = 100
        status = "Flash complete: ${rediscovered.size}/${requests.size} unit(s)"
        complete = true
        running = false
        for (run in runs) {
            run.progress = 100
            run.complete = true
            run.running = false
            run.status = "Flash complete"
        }
        lines += stamped("OK: batch flash workflow complete")
    }

    fun markError(message: String) {
        status = "Flash failed"
        error = message
        running = false
        for (run in runs) {
            run.running = false
            if (run.error == null && !run.complete) {
                run.error = message
                run.status = "Flash failed"
            }
        }
        lines += stamped("ERROR: $message")
    }
}

private const val FLASH_STATUS_UPDATE_INTERVAL_MS = 5_000L

private fun batchStatusText(count: Int): String {
    return if (count == 1) "Starting" else "Flashing $count units"
}

private fun flashStatusText(event: FlashEvent): String? {
    val text = event.message.lineSequence().firstOrNull()?.trim().orEmpty()
    if (isWriteProgress(event)) {
        return "Writing image"
    }
    if (text.isBlank()) {
        return event.phase.takeIf { it.isNotBlank() }
    }
    if (text.startsWith("retrying ")) {
        return null
    }
    if (text.startsWith("write range ") || text.contains("sha256:", ignoreCase = true)) {
        return null
    }
    return text.take(160)
}

private fun isWriteProgress(event: FlashEvent): Boolean {
    val firstLine = event.message.lineSequence().firstOrNull()?.trim().orEmpty()
    return event.totalBytes > 0 && event.phase == "write" && firstLine.startsWith("write:")
}

private fun isRetryNotice(event: FlashEvent): Boolean {
    return event.message.lineSequence().firstOrNull()?.trim()?.startsWith("retrying ") == true
}

private fun writeDetailText(event: FlashEvent): String {
    val firstLine = event.message.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine
        .removePrefix("${event.phase}:")
        .trim()
        .takeIf { it.isNotBlank() }
        ?: "${formatByteCount(event.doneBytes)} written"
}

private fun visibleTargetStatus(run: FlashRunState, multiTarget: Boolean): String {
    if (!multiTarget) {
        return visibleRunStatusText(run)
    }
    return when {
        run.error != null -> "Failed"
        run.complete -> "Complete"
        run.progress >= 100 -> "Finalizing"
        run.progress > 0 -> run.bytesWrittenText?.let { "Writing · $it" } ?: "Writing"
        else -> "Preparing"
    }
}

private fun visibleStatusText(status: String): String {
    val text = status.trim()
    return if (text.startsWith("write:")) "Writing image" else text
}

private fun visibleRunStatusText(run: FlashRunState): String {
    val status = visibleStatusText(run.status)
    return run.bytesWrittenText
        ?.takeIf { status == "Writing image" || status == "Writing" }
        ?.let { "$status · $it" }
        ?: status
}

private fun visibleBatchStatusText(run: BatchFlashRunState): String {
    if (run.runs.size == 1) {
        return visibleRunStatusText(run.runs.first())
    }
    val status = visibleStatusText(run.status)
    val bytesWritten = run.runs.sumOf { it.bytesWritten }
    return if (bytesWritten > 0L && run.running) {
        "$status · ${formatByteCount(bytesWritten)} written"
    } else {
        status
    }
}

private fun formatByteCount(bytes: Long): String {
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return "${"%.1f".format(mib)} MiB"
}

@Composable
private fun App(initialSecretFile: String?, noAuth: Boolean, onExit: () -> Unit) {
    val saved = remember { ComposeSettings.load(initialSecretFile) }
    var useCustomSecret by remember { mutableStateOf(saved.useCustomSecret && !noAuth) }
    var secretFile by remember { mutableStateOf(saved.secretFile) }
    var timeout by remember { mutableStateOf(saved.timeout) }
    var interfaceName by remember { mutableStateOf(saved.interfaceName) }
    var interfaceOptions by remember { mutableStateOf(interfaceChoices(saved.interfaceName)) }
    var wicImage by remember { mutableStateOf(saved.wicImage) }
    var preserveSshKeys by remember { mutableStateOf(saved.preserveSshKeys) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var selectedDeviceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var discovering by remember { mutableStateOf(false) }
    var commandRunning by remember { mutableStateOf(false) }
    var hasBpfAccess by remember { mutableStateOf(MacBpfAccess.hasBpfAccess()) }
    var hasWindowsL2 by remember { mutableStateOf(WindowsPacketAccess.hasPacketAccess()) }
    var startupDiscoveryStarted by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var flashRun by remember { mutableStateOf<BatchFlashRunState?>(null) }
    val logs = remember { mutableStateListOf<LogLine>() }
    val scope = rememberCoroutineScope()

    fun settings() = ComposeSettings(
        useCustomSecret = useCustomSecret,
        secretFile = secretFile,
        timeout = timeout,
        interfaceName = interfaceName,
        wicImage = wicImage,
        preserveSshKeys = preserveSshKeys,
    )

    fun saveSettings() = settings().save()

    fun refreshInterfaces() {
        val choices = interfaceChoices(interfaceName)
        val liveInterfaces = choices.filter { it.isNotBlank() }
        interfaceOptions = choices
        if (interfaceName.isNotBlank() && interfaceName !in liveInterfaces) {
            interfaceName = ""
        }
    }

    fun log(message: String, level: String = "INFO") {
        logs += LogLine(
            stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            level = level,
            message = message,
        )
    }

    fun readSecret(): String? {
        return if (noAuth) null else SecretProvider.load(if (useCustomSecret) secretFile else null)
    }

    fun commandOptions(): CommandOptions {
        return CommandOptions(
            timeoutSeconds = timeout.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 8.0,
            secret = readSecret(),
            interfaces = interfaceName.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            transportMode = TransportMode.AUTO,
        )
    }

    fun selectedDevices(): List<Device> {
        return devices.filter { selectionKey(it)?.let(selectedDeviceIds::contains) == true }
    }

    fun selectedDevice(): Device? {
        return selectedDevices().firstOrNull()
            ?: if (devices.size == 1) devices.first() else null
    }

    fun installBpf(afterSuccess: (() -> Unit)? = null) {
        scope.launch {
            commandRunning = true
            log("Requesting macOS L2 capture access")
            val result = withContext(Dispatchers.IO) { MacBpfAccess.install() }
            hasBpfAccess = MacBpfAccess.hasBpfAccess()
            commandRunning = false
            if (result.success) {
                log("macOS L2 capture access enabled", "SUCCESS")
                afterSuccess?.invoke()
            } else {
                val message = result.output.ifBlank { "Installer exited with code ${result.exitCode}" }
                log("L2 capture setup failed: $message", "ERROR")
                dialog = DialogState.Message("L2 Capture Setup Failed", message, isError = true)
            }
        }
    }

    fun installWindowsL2(afterSuccess: (() -> Unit)? = null) {
        scope.launch {
            commandRunning = true
            log("Requesting Windows L2 raw Ethernet setup")
            val result = withContext(Dispatchers.IO) { WindowsPacketAccess.install() }
            hasWindowsL2 = WindowsPacketAccess.hasPacketAccess()
            commandRunning = false
            if (result.success) {
                if (result.rebootRequired) {
                    log("Windows L2 setup installed; reboot Windows before raw Ethernet", "SUCCESS")
                    dialog = DialogState.Message(
                        "Windows L2 Installed",
                        "Windows L2 raw Ethernet setup completed. Reboot Windows before using L2 discovery or flashing.",
                    )
                } else {
                    log("Windows L2 raw Ethernet access enabled", "SUCCESS")
                    afterSuccess?.invoke()
                }
            } else {
                val message = result.output.ifBlank { "Installer exited with code ${result.exitCode}" }
                log("Windows L2 setup failed: $message", "ERROR")
                dialog = DialogState.Message("Windows L2 Setup Failed", message, isError = true)
            }
        }
    }

    fun discover() {
        saveSettings()
        val mode = TransportMode.ALL
        if (MacBpfAccess.needsSetupFor(mode)) {
            installBpf(afterSuccess = { discover() })
            return
        }
        if (WindowsPacketAccess.needsSetupFor(mode)) {
            installWindowsL2(afterSuccess = { discover() })
            return
        }

        scope.launch {
            discovering = true
            log("Starting discovery")
            val secret = try {
                readSecret()
            } catch (e: IllegalArgumentException) {
                discovering = false
                dialog = DialogState.Message("Authentication", e.message ?: "Authentication setup failed", isError = true)
                return@launch
            }
            val interfaces = interfaceName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            try {
                val previous = selectedDeviceIds
                fun applyDiscoveryUpdate(found: List<Device>) {
                    devices = annotateDiscoveredDevices(
                        found.sortedWith(
                            compareBy<Device> { it.text("model") ?: "" }
                                .thenBy { it.serialText() }
                                .thenBy { it.deviceIdText() ?: "" }
                                .thenBy { it.text("ip") ?: "" },
                        ),
                    )
                    val available = devices.mapNotNull(::selectionKey).toSet()
                    val retained = selectedDeviceIds.intersect(available)
                    selectedDeviceIds = retained.ifEmpty {
                        previous.intersect(available).ifEmpty {
                            if (devices.size == 1) setOfNotNull(selectionKey(devices.first())) else emptySet()
                        }
                    }
                }
                val found = withContext(Dispatchers.IO) {
                    Discoverer().discoverStreaming(
                        DiscoveryOptions(
                            timeoutSeconds = timeout.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 8.0,
                            secret = secret,
                            transportMode = mode,
                            interfaces = interfaces,
                            retries = 3,
                        ),
                    ) { snapshot ->
                        EventQueue.invokeLater {
                            applyDiscoveryUpdate(snapshot)
                        }
                    }
                }
                applyDiscoveryUpdate(found)
                log("Discovery complete: ${devices.size} device(s)", "SUCCESS")
            } catch (e: Exception) {
                log("Discovery failed: ${e.message ?: e::class.simpleName}", "ERROR")
                dialog = DialogState.Message("Discovery Failed", e.message ?: "Discovery failed", isError = true)
            } finally {
                discovering = false
            }
        }
    }

    fun runCommand(label: String, block: () -> CommandResponse?) {
        saveSettings()
        scope.launch {
            commandRunning = true
            log(label)
            try {
                val response = withContext(Dispatchers.IO) { block() }
                if (response == null) {
                    log("$label: no reply", "ERROR")
                    dialog = DialogState.Message("No Reply", "No reply received.", isError = true)
                } else if (response.text("status") == "ok") {
                    if (Protocol.text(response.message, "cmd") == Protocol.MSG_MFG_TEST_REPLY) {
                        val result = mfgTestDialogState(response)
                        val level = if (result.result.equals("pass", ignoreCase = true)) "SUCCESS" else "ERROR"
                        log("$label: ${mfgTestHeadline(result)}", level)
                        dialog = result
                    } else {
                        val summary = responseSummary(response)
                        log("$label: $summary", "SUCCESS")
                        dialog = DialogState.Message("Success", summary)
                    }
                } else {
                    val error = response.text("error") ?: "Unknown error"
                    log("$label: $error", "ERROR")
                    dialog = DialogState.Message("Command Failed", error, isError = true)
                }
            } catch (e: Exception) {
                val message = e.message ?: e::class.simpleName ?: "Unknown error"
                log("$label: $message", "ERROR")
                dialog = DialogState.Message("Error", message, isError = true)
            } finally {
                commandRunning = false
            }
        }
    }

    fun bootLinux(device: Device) {
        val target = targetFor(device)
        runCommand("Booting Linux on ${target.label}") {
            CommandClient().bootLinux(target, commandOptions())
        }
    }

    fun runManufacturingTest(device: Device) {
        val target = targetFor(device)
        runCommand("Starting manufacturing test on ${target.label}") {
            CommandClient().runManufacturingTest(target, commandOptions())
        }
    }

    fun waitForUbootAoe(target: TargetSelector, aoeTarget: AoETargetAddress, options: CommandOptions): Device {
        val deadline = System.nanoTime() + 45_000_000_000L
        while (System.nanoTime() < deadline) {
            val devices = Discoverer().discover(
                DiscoveryOptions(
                    timeoutSeconds = 2.0,
                    secret = options.secret,
                    transportMode = TransportMode.L2,
                    interfaces = options.interfaces,
                    retries = 3,
                ),
            )
            devices.firstOrNull {
                    it.text("uboot") == "1" &&
                    it.text("aoe_active") == "1" &&
                    it.text("aoe_target") == aoeTarget.label &&
                    FlashWorkflow.matchesTarget(it, target)
            }?.let { return it }
            Thread.sleep(1_000)
        }
        throw RuntimeException("Timed out waiting for ${target.label} to enter U-Boot AoE mode on ${aoeTarget.label}")
    }

    fun sendToUbootAoe(device: Device) {
        saveSettings()
        val target = targetFor(device)
        val aoeTarget = AoETargetAddress.forDevice(device)
        val options = commandOptions()
        scope.launch {
            commandRunning = true
            log("Sending ${target.label} to U-Boot AoE mode (${aoeTarget.label})")
            try {
                val ubootDevice = withContext(Dispatchers.IO) {
                    val client = CommandClient()
                    val setEnv = client.shellExec(
                        target,
                        UbootAoeMode.setEnvCommand(aoeTarget),
                        options,
                        timeoutSeconds = 10.0,
                    ) ?: throw RuntimeException("No reply while setting U-Boot AoE environment")
                    if (setEnv.text("status") != "ok") {
                        throw RuntimeException(setEnv.text("error") ?: "Failed to set U-Boot AoE environment")
                    }

                    val verify = client.shellExec(
                        target,
                        UbootAoeMode.verifyEnvCommand(),
                        options,
                        timeoutSeconds = 5.0,
                    ) ?: throw RuntimeException("No reply while verifying U-Boot AoE environment")
                    if (verify.text("status") != "ok") {
                        throw RuntimeException(verify.text("error") ?: "Failed to verify U-Boot AoE environment")
                    }
                    UbootAoeMode.verifyEnv(verify, aoeTarget)

                    client.shellExec(
                        target,
                        UbootAoeMode.rebootCommand(),
                        options,
                        timeoutSeconds = 2.0,
                    )
                    waitForUbootAoe(target, aoeTarget, options)
                }
                devices = annotateDiscoveredDevices(
                    devices.filterNot {
                        selectionKey(it) == selectionKey(device) ||
                            it.deviceIdText()?.equals(target.serial.orEmpty(), ignoreCase = true) == true
                    } + ubootDevice,
                )
                selectedDeviceIds = setOfNotNull(selectionKey(ubootDevice))
                val message = "U-Boot AoE is ready on ${aoeTarget.label} for ${target.label}."
                log(message, "SUCCESS")
                dialog = DialogState.Message("U-Boot AoE Ready", message)
            } catch (e: Exception) {
                val message = e.message ?: e::class.simpleName ?: "Unknown error"
                log("Send to U-Boot AoE failed: $message", "ERROR")
                dialog = DialogState.Message("Send to U-Boot AoE Failed", message, isError = true)
            } finally {
                commandRunning = false
            }
        }
    }

    fun syncModemClient(device: Device, host: String, username: String, password: String, port: String) {
        saveSettings()
        val sshPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
            dialog = DialogState.Message("Sync Client", "Enter a valid SSH port.", isError = true)
            return
        }
        scope.launch {
            commandRunning = true
            var refreshAfterSync = false
            log("Syncing Popoto Discover modem client to $host")
            try {
                val result = withContext(Dispatchers.IO) {
                    ModemClientSync(
                        credentials = ModemSshCredentials(
                            host = host,
                            username = username,
                            password = password,
                            port = sshPort,
                        ),
                        onProgress = { message ->
                            EventQueue.invokeLater { log("Client sync: $message") }
                        },
                    ).sync()
                }
                val targetName = device.displayNameText()
                log("Client sync complete on $targetName: ${result.serviceStatus}", "SUCCESS")
                dialog = DialogState.Message(
                    "Client Sync Complete",
                    buildString {
                        append("Updated Popoto Discover modem client on ${result.host}.\n")
                        append("Service: ${result.serviceStatus}")
                        result.backupPath?.let { append("\nBackup: $it") }
                    },
                )
                refreshAfterSync = true
            } catch (e: Exception) {
                val message = e.message ?: e::class.simpleName ?: "Unknown error"
                log("Client sync failed: $message", "ERROR")
                dialog = DialogState.Message("Client Sync Failed", message, isError = true)
            } finally {
                commandRunning = false
                if (refreshAfterSync) {
                    discover()
                }
            }
        }
    }

    fun installModemClient(host: String, username: String, password: String, port: String) {
        saveSettings()
        val sshPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
            dialog = DialogState.Message("Install Client", "Enter a valid SSH port.", isError = true)
            return
        }
        scope.launch {
            commandRunning = true
            var refreshAfterSync = false
            log("Installing Popoto Discover modem client to $host")
            try {
                val result = withContext(Dispatchers.IO) {
                    ModemClientSync(
                        credentials = ModemSshCredentials(
                            host = host,
                            username = username,
                            password = password,
                            port = sshPort,
                        ),
                        onProgress = { message ->
                            EventQueue.invokeLater { log("Client install: $message") }
                        },
                    ).sync()
                }
                log("Client install complete on ${result.host}: ${result.serviceStatus}", "SUCCESS")
                dialog = DialogState.Message(
                    "Client Install Complete",
                    buildString {
                        append("Installed Popoto Discover modem client on ${result.host}.\n")
                        append("Service: ${result.serviceStatus}")
                        result.backupPath?.let { append("\nBackup: $it") }
                    },
                )
                refreshAfterSync = true
            } catch (e: Exception) {
                val message = e.message ?: e::class.simpleName ?: "Unknown error"
                log("Client install failed: $message", "ERROR")
                dialog = DialogState.Message("Client Install Failed", message, isError = true)
            } finally {
                commandRunning = false
                if (refreshAfterSync) {
                    discover()
                }
            }
        }
    }

    fun prepareFlashPlan(device: Device, image: File, bmap: File?, mode: FlashMode): FlashPlan? {
        val target = FlashWorkflow.targetFor(device) ?: run {
            dialog = DialogState.Message("Flash WIC", "Selected device has no usable target identifier.", isError = true)
            return null
        }
        val iface = FlashWorkflow.bestInterfaceFor(device, interfaceName) ?: run {
            dialog = DialogState.Message("Flash WIC", "No Ethernet interface is available for flashing.", isError = true)
            return null
        }
        return FlashPlan(
            device = device,
            target = target,
            interfaceName = iface,
            aoeTarget = AoETargetAddress.forDevice(device),
            image = image,
            bmap = bmap,
            mode = mode,
        )
    }

    fun startFlash(plans: List<FlashPlan>, bootloaderImage: File?) {
        if (plans.isEmpty()) {
            return
        }
        if (MacBpfAccess.isMac() && !MacBpfAccess.hasBpfAccess()) {
            installBpf(afterSuccess = { startFlash(plans, bootloaderImage) })
            return
        }
        if (WindowsPacketAccess.isWindows() && !WindowsPacketAccess.hasPacketAccess()) {
            installWindowsL2(afterSuccess = { startFlash(plans, bootloaderImage) })
            return
        }
        val secret = try {
            readSecret()
        } catch (e: IllegalArgumentException) {
            dialog = DialogState.Message("Authentication", e.message ?: "Authentication setup failed", isError = true)
            return
        }
        val requests = plans.map { plan ->
            FlashRequest(
                initialDevice = plan.device,
                target = plan.target,
                interfaceName = plan.interfaceName,
                aoeTarget = plan.aoeTarget,
                image = plan.image,
                bmap = plan.bmap,
                mode = plan.mode,
                bootloaderImage = bootloaderImage,
                secret = secret,
                preserveSshKeys = preserveSshKeys,
            )
        }
        val run = BatchFlashRunState(requests)
        flashRun = run
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    EventQueue.invokeLater {
                        for (request in requests) {
                            run.addLine(request, "Image: ${request.image.absolutePath}")
                            run.addLine(request, "Mode: ${if (request.mode == FlashMode.BMAP) "bmap payload" else "full image"}")
                            request.bmap?.let { run.addLine(request, "Bmap: ${it.absolutePath}") }
                            if (request.bootloaderImage != null) {
                                run.addLine(request, "U-Boot: ${request.bootloaderImage.absolutePath} -> boot0")
                            } else {
                                run.addLine(request, "U-Boot: disabled")
                            }
                            run.addLine(request, "Preserve .ssh keys: ${if (request.preserveSshKeys) "enabled" else "disabled"}")
                            run.addLine(request, "Interface: ${request.interfaceName}")
                            run.addLine(request, "AoE target: ${request.aoeTarget.label}")
                            run.addLine(request, "Target: ${request.target.label}")
                        }
                    }
                    val rediscovered = BatchFlashWorkflow(requests, onEvent = { event ->
                        EventQueue.invokeLater { run.add(event.request, event.event) }
                    }).run()
                    EventQueue.invokeLater {
                        run.markComplete(rediscovered)
                    }
                } catch (e: Exception) {
                    val message = e.message ?: e::class.simpleName ?: "Unknown error"
                    EventQueue.invokeLater {
                        run.markError(message)
                    }
                }
            }
        }
    }

    LaunchedEffect(useCustomSecret, secretFile, timeout, interfaceName, wicImage, preserveSshKeys) {
        saveSettings()
    }

    LaunchedEffect(Unit) {
        refreshInterfaces()
        if (!startupDiscoveryStarted) {
            startupDiscoveryStarted = true
            discover()
        }
    }

    val flashingDeviceIds = flashRun
        ?.runs
        ?.filter { it.running && it.error == null && !it.complete }
        ?.mapNotNull { selectionKey(it.request.initialDevice) }
        ?.toSet()
        .orEmpty()

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PopotoBlue,
            secondary = AcousticBlue,
            background = Clamshell,
            surface = Panel,
            error = Danger,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = Clamshell) {
            Column(Modifier.fillMaxSize()) {
                AppHeader()
                DiscoveryBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp, 12.dp, 18.dp, 8.dp),
                    deviceCount = devices.size,
                    interfaceName = interfaceName,
                    onInterfaceName = { interfaceName = it },
                    interfaceOptions = interfaceOptions,
                    onRefreshInterfaces = ::refreshInterfaces,
                    discovering = discovering,
                    onDiscover = ::discover,
                    isMac = MacBpfAccess.isMac(),
                    hasBpfAccess = hasBpfAccess,
                    isWindows = WindowsPacketAccess.isWindows(),
                    hasWindowsL2 = hasWindowsL2,
                    onAdvanced = { dialog = DialogState.AdvancedConnection },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(18.dp, 4.dp, 18.dp, 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DeviceList(
                        devices = devices,
                        selectedDeviceIds = selectedDeviceIds,
                        flashingDeviceIds = flashingDeviceIds,
                        onSyncClient = { device ->
                            dialog = DialogState.SyncClient(
                                device = device,
                                host = device.sshHostText().orEmpty(),
                                username = "root",
                                password = "root",
                                port = "22",
                            )
                        },
                        onInstallClient = {
                            dialog = DialogState.InstallClient(
                                host = "",
                                username = "root",
                                password = "root",
                                port = "22",
                            )
                        },
                        onSendToUbootAoe = ::sendToUbootAoe,
                        onBootLinux = ::bootLinux,
                        onRunMfgTest = ::runManufacturingTest,
                        onToggle = { device ->
                            selectionKey(device)?.let { key ->
                                selectedDeviceIds = if (key in selectedDeviceIds) {
                                    selectedDeviceIds - key
                                } else {
                                    selectedDeviceIds + key
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    Column(
                        modifier = Modifier
                            .width(390.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FlashImageCard(
                            modifier = Modifier.fillMaxWidth(),
                            wicImage = wicImage,
                            onWicImage = { wicImage = it },
                            targetCount = selectedDevices().size,
                            enabled = selectedDevices().isNotEmpty() && !commandRunning && !discovering,
                            onFlash = {
                                val selected = selectedDevices()
                                if (selected.isEmpty()) {
                                    return@FlashImageCard
                                }
                                val image = File(wicImage)
                                if (!isWicLz4(image) || !image.isFile) {
                                    dialog = DialogState.Message("Flash Image", "Select a readable .wic.lz4 image first.", isError = true)
                                    return@FlashImageCard
                                }
                                val bmap = FlashWorkflow.defaultBmapFor(image).takeIf { it.exists() }
                                if (bmap == null) {
                                    log("No bmap found beside ${image.name}; running full image write")
                                } else {
                                    log("Using bmap: ${bmap.name}")
                                }
                                val plans = selected.mapNotNull { device ->
                                    prepareFlashPlan(
                                        device = device,
                                        image = image,
                                        bmap = bmap,
                                        mode = if (bmap != null) FlashMode.BMAP else FlashMode.FULL_IMAGE,
                                    )
                                }
                                if (plans.size == selected.size) {
                                    dialog = DialogState.ConfirmFlash(plans)
                                }
                            },
                        )
                    }
                }
                LogPanel(
                    logs = logs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(118.dp)
                        .padding(18.dp, 10.dp, 18.dp, 8.dp),
                )
                BottomCommandBar(
                    selected = selectedDevice(),
                    enabled = selectedDevice() != null && !commandRunning && !discovering,
                    commandRunning = commandRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp, 0.dp, 18.dp, 14.dp),
                    onSetIp = {
                        val device = selectedDevice() ?: return@BottomCommandBar
                        val ip = device.text("ip").orEmpty()
                        dialog = DialogState.SetIp(device, ip, device.text("netmask") ?: "255.255.255.0", defaultGateway(ip))
                    },
                    onSetRtc = {
                        val device = selectedDevice() ?: return@BottomCommandBar
                        dialog = DialogState.SetRtc(
                            device,
                            hostRtcString(),
                        )
                    },
                    onGetRtc = {
                        val device = selectedDevice() ?: return@BottomCommandBar
                        val target = targetFor(device)
                        runCommand("Reading RTC from ${target.label}") {
                            CommandClient().getRtc(target, commandOptions())
                        }
                    },
                    onSetParam = {
                        val device = selectedDevice() ?: return@BottomCommandBar
                        dialog = DialogState.SetParam(device, "TxPowerWatts", "")
                    },
                    onGetVersion = {
                        val device = selectedDevice() ?: return@BottomCommandBar
                        val target = targetFor(device)
                        runCommand("Reading version from ${target.label}") {
                            CommandClient().getVersion(target, commandOptions())
                        }
                    },
                    onClearLog = { logs.clear() },
                )
            }
        }
    }

    when (val state = dialog) {
        null -> Unit
        is DialogState.Message -> MessageDialog(state) { dialog = null }
        is DialogState.MfgTestResult -> MfgTestResultDialog(state) { dialog = null }
        is DialogState.ConfirmFlash -> ConfirmFlashDialog(
            plans = state.plans,
            onDismiss = { dialog = null },
            onConfirm = { bootloaderImage ->
                dialog = null
                startFlash(state.plans, bootloaderImage)
            },
        )
        DialogState.AdvancedConnection -> AdvancedConnectionDialog(
            useCustomSecret = useCustomSecret,
            onUseCustomSecret = { useCustomSecret = it && !noAuth },
            secretFile = secretFile,
            onSecretFile = { secretFile = it },
            noAuth = noAuth,
            timeout = timeout,
            onTimeout = { timeout = it },
            preserveSshKeys = preserveSshKeys,
            onPreserveSshKeys = { preserveSshKeys = it },
            onDismiss = { dialog = null },
        )
        is DialogState.SetIp -> SetIpDialog(
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { ip, mask, gateway ->
                dialog = null
                val target = targetFor(state.device)
                val currentIp = state.device.sshHostText()
                    ?: throw IllegalStateException("Selected device has no reachable IP address for pshell.")
                runCommand("Setting IP through pshell on ${target.label} ($currentIp) to $ip") {
                    NetworkConfigActions.setIp(target, currentIp, ip, mask, gateway, commandOptions())
                }
            },
        )
        is DialogState.SetRtc -> SetRtcDialog(
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { rtc ->
                dialog = null
                val target = targetFor(state.device)
                val resolvedRtc = resolveRtcInput(rtc)
                runCommand("Setting RTC on ${target.label} to $resolvedRtc") {
                    CommandClient().setRtc(target, resolvedRtc, commandOptions())
                }
            },
        )
        is DialogState.SetParam -> SetParamDialog(
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { name, value ->
                dialog = null
                val target = targetFor(state.device)
                runCommand("Setting $name on ${target.label} to $value") {
                    CommandClient().setParam(target, name, value, commandOptions())
                }
            },
        )
        is DialogState.SyncClient -> SyncClientDialog(
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { host, username, password, port ->
                dialog = null
                syncModemClient(state.device, host, username, password, port)
            },
        )
        is DialogState.InstallClient -> InstallClientDialog(
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { host, username, password, port ->
                dialog = null
                installModemClient(host, username, password, port)
            },
        )
    }

    flashRun?.let { run ->
        FlashRunWindow(run, onClose = {
            if (!run.running) {
                flashRun = null
            }
        })
    }
}

@Composable
private fun AppHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(DeepNavy, AcousticBlue)))
            .padding(horizontal = 26.dp, vertical = 16.dp),
    ) {
        Column {
            Text("Popoto Discover", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Discover, manage, and flash PMM modems", color = Color(0xFFD6F5FF), fontSize = 13.sp)
        }
    }
}

@Composable
private fun DiscoveryBar(
    modifier: Modifier,
    deviceCount: Int,
    interfaceName: String,
    onInterfaceName: (String) -> Unit,
    interfaceOptions: List<String>,
    onRefreshInterfaces: () -> Unit,
    discovering: Boolean,
    onDiscover: () -> Unit,
    isMac: Boolean,
    hasBpfAccess: Boolean,
    isWindows: Boolean,
    hasWindowsL2: Boolean,
    onAdvanced: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = Panel,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Border),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(
                if (discovering) "Discovering..." else "Discover Devices",
                enabled = !discovering,
                modifier = Modifier.width(206.dp),
                onClick = onDiscover,
            )
            Surface(
                color = Color(0xFFEAF6FF),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, Color(0xFFCFE9FA)),
            ) {
                Text(
                    "$deviceCount device${if (deviceCount == 1) "" else "s"}",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            InterfaceSelector(
                selected = interfaceName,
                options = interfaceOptions,
                onSelected = onInterfaceName,
                onRefresh = onRefreshInterfaces,
            )
            Spacer(Modifier.weight(1f))
            if (isMac) {
                L2StatusPill(if (hasBpfAccess) "L2 Ready" else "L2 Setup Required", hasBpfAccess)
            }
            if (isWindows) {
                L2StatusPill(if (hasWindowsL2) "L2 Ready" else "L2 Setup Required", hasWindowsL2)
            }
            SecondaryButton("Advanced", onClick = onAdvanced)
        }
    }
}

@Composable
private fun L2StatusPill(text: String, ready: Boolean) {
    Surface(
        color = if (ready) Color(0xFFE7F8EF) else Color(0xFFFFF2D9),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (ready) Color(0xFFBDEBD3) else Color(0xFFF4CF8A)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            color = if (ready) Color(0xFF16734F) else Color(0xFF8A5A00),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InterfaceSelector(
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val liveCount = options.count { it.isNotBlank() }
    val selectedText = if (selected.isBlank()) {
        if (liveCount == 0) "Auto" else "Auto ($liveCount up)"
    } else {
        selected
    }
    Box {
        OutlinedButton(
            onClick = {
                onRefresh()
                expanded = true
            },
            modifier = Modifier.width(236.dp).height(48.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Border),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel, contentColor = TextPrimary),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 5.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text("Interface", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        selectedText,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("v", color = PopotoBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.width(236.dp).background(Panel)) {
            for (option in options) {
                val active = selected == option
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (active) PopotoBlue else Color.Transparent),
                            )
                            Text(
                                option.ifBlank { if (liveCount == 0) "Auto detect" else "Auto detect ($liveCount up)" },
                                color = TextPrimary,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FlashImageCard(
    modifier: Modifier,
    wicImage: String,
    onWicImage: (String) -> Unit,
    targetCount: Int,
    enabled: Boolean,
    onFlash: () -> Unit,
) {
    AppCard("Flash Image", modifier) {
        Text("Persistent WIC image selection", color = Muted)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = wicImage,
                onValueChange = onWicImage,
                label = { Text(".wic.lz4 image") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton("Choose", modifier = Modifier.width(112.dp)) {
                chooseFile("Select PMM WIC LZ4 Image", wicImage, "wic.lz4")?.let { onWicImage(it.absolutePath) }
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            if (targetCount <= 1) "Flash WIC" else "Flash $targetCount Units",
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            onClick = onFlash,
        )
    }
}

@Composable
private fun BottomCommandBar(
    selected: Device?,
    enabled: Boolean,
    commandRunning: Boolean,
    modifier: Modifier,
    onSetIp: () -> Unit,
    onSetRtc: () -> Unit,
    onGetRtc: () -> Unit,
    onSetParam: () -> Unit,
    onGetVersion: () -> Unit,
    onClearLog: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = Panel,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Border),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton("Set IP Address", enabled = enabled, onClick = onSetIp)
            SecondaryButton("Set RTC", enabled = enabled, onClick = onSetRtc)
            SecondaryButton("Get RTC", enabled = enabled, onClick = onGetRtc)
            SecondaryButton("Set Parameter", enabled = enabled, onClick = onSetParam)
            SecondaryButton("Get Version", enabled = enabled, onClick = onGetVersion)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    commandRunning -> "Command running..."
                    selected != null -> "Target: ${selected.text("name") ?: selected.deviceIdText() ?: "selected unit"}"
                    else -> "No target selected"
                },
                color = Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SecondaryButton("Clear Log", enabled = true, onClick = onClearLog)
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<Device>,
    selectedDeviceIds: Set<String>,
    flashingDeviceIds: Set<String>,
    onSyncClient: (Device) -> Unit,
    onInstallClient: () -> Unit,
    onSendToUbootAoe: (Device) -> Unit,
    onBootLinux: (Device) -> Unit,
    onRunMfgTest: (Device) -> Unit,
    onToggle: (Device) -> Unit,
    modifier: Modifier,
) {
    AppCard("Discovered Devices (${devices.size})", modifier) {
        ContextMenuArea(
            items = {
                listOf(
                    ContextMenuItem("Install Discover to device") {
                        onInstallClient()
                    },
                )
            },
        ) {
            if (devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No devices discovered yet", color = Muted, fontSize = 16.sp)
                }
            } else {
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(end = 16.dp, bottom = 112.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(devices, key = { selectionKey(it) ?: System.identityHashCode(it).toString() }) { device ->
                            ContextMenuArea(
                                items = {
                                    buildList {
                                        if (device.text("uboot") != "1") {
                                            add(
                                                ContextMenuItem("Sync Popoto Discover client") {
                                                    onSyncClient(device)
                                                },
                                            )
                                            add(
                                                ContextMenuItem("Send to U-Boot AoE") {
                                                    onSendToUbootAoe(device)
                                                },
                                            )
                                        }
                                        if (device.supportsBootLinuxAction()) {
                                            add(
                                                ContextMenuItem("Boot Linux") {
                                                    onBootLinux(device)
                                                },
                                            )
                                        }
                                        if (device.supportsManufacturingTestAction()) {
                                            add(
                                                ContextMenuItem("Start Manufacturing Test") {
                                                    onRunMfgTest(device)
                                                },
                                            )
                                        }
                                    }
                                },
                            ) {
                                DeviceRow(
                                    device,
                                    selected = selectionKey(device)?.let(selectedDeviceIds::contains) == true,
                                    flashing = selectionKey(device)?.let(flashingDeviceIds::contains) == true,
                                    onClick = { onToggle(device) },
                                )
                            }
                        }
                        item(key = "device-list-context-spacer") {
                            Spacer(Modifier.fillMaxWidth().height(96.dp))
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, selected: Boolean, flashing: Boolean, onClick: () -> Unit) {
    val borderColor = when {
        flashing -> UpdateOrange
        selected -> PopotoBlue
        else -> Border
    }
    val background = when {
        flashing -> UpdateOrangeBg
        selected -> Color(0xFFEAF6FF)
        else -> PanelAlt
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = background,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            flashing -> UpdateOrange
                            selected -> PopotoBlue
                            else -> Border
                        },
                    ),
            )
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Column(Modifier.weight(1.25f)) {
                Text(device.displayNameText(), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(device.deviceIdText() ?: "no device id", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            DetailColumn("Serial", device.serialText(), Modifier.weight(1.0f))
            DetailColumn("Network", "${device.text("ip") ?: "--"}\n${device.displayMacText()}", Modifier.weight(1.15f))
            DetailColumn("FW", device.text("fw") ?: "unknown", Modifier.weight(0.85f))
            DetailColumn("Battery", device.text("battery_v")?.let { "$it V" } ?: "--", Modifier.weight(0.65f))
            DetailColumn("Storage", storageText(device), Modifier.weight(0.9f))
            DetailColumn("Via", viaText(device), Modifier.weight(0.95f))
        }
    }
}

@Composable
private fun DetailColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = TextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LogPanel(logs: List<LogLine>, modifier: Modifier) {
    Surface(modifier, color = DeepSea, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color(0xFF1F2A3A))) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(logs.takeLast(300)) { line ->
                Text(
                    "[${line.stamp}] [${line.level}] ${line.message}",
                    color = when (line.level) {
                        "ERROR" -> Color(0xFFFFA8A8)
                        "SUCCESS" -> Color(0xFF9AF2CE)
                        else -> Color(0xFFD9E8FF)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun AppCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp).widthIn(min = 124.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PopotoBlue, contentColor = Color.White),
    ) {
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp).widthIn(min = 104.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MessageDialog(state: DialogState.Message, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title, color = if (state.isError) Danger else TextPrimary) },
        text = {
            Text(
                state.message,
                color = TextPrimary,
                fontFamily = if (state.message.contains('\n')) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { PrimaryButton("OK", onClick = onDismiss) },
        containerColor = Panel,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun MfgTestResultDialog(state: DialogState.MfgTestResult, onDismiss: () -> Unit) {
    val passed = state.result.equals("pass", ignoreCase = true)
    val cleanedOutput = state.output.ifBlank { "No manufacturing test output was returned." }
    val deviceResults = parseMfgDeviceResults(cleanedOutput)
    val statusColor = if (passed) Color(0xFF16734F) else Danger
    val statusBg = if (passed) Color(0xFFE7F8EF) else Color(0xFFFFE8E8)
    val statusBorder = if (passed) Color(0xFFBDEBD3) else Color(0xFFF2B7B7)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (passed) "Manufacturing Test Passed" else "Manufacturing Test Failed",
                color = statusColor,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 560.dp, max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = statusBg,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, statusBorder),
                    ) {
                        Text(
                            state.result.uppercase(),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            color = statusColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text("${state.test} · return code ${state.returnCode}", color = TextPrimary, fontSize = 13.sp)
                }
                Text("Reply from ${state.sourceIp}", color = Muted, fontSize = 12.sp)
                if (deviceResults.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        deviceResults.forEach { row ->
                            MfgDeviceResultRow(row)
                        }
                    }
                }
                if (state.truncated) {
                    Text("Output was truncated by the U-Boot reply.", color = Danger, fontSize = 13.sp)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 290.dp),
                    color = DeepSea,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF1F2A3A)),
                ) {
                    Text(
                        cleanedOutput,
                        color = Color(0xFFD9E8FF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = { PrimaryButton("OK", onClick = onDismiss) },
        containerColor = Panel,
        titleContentColor = statusColor,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun MfgDeviceResultRow(row: MfgDeviceResult) {
    val result = row.result.uppercase()
    val passed = result == "PASS"
    val skipped = result == "SKIP"
    val color = when {
        passed -> Color(0xFF16734F)
        skipped -> Color(0xFF8A5A00)
        else -> Danger
    }
    val bg = when {
        passed -> Color(0xFFE7F8EF)
        skipped -> Color(0xFFFFF2D9)
        else -> Color(0xFFFFE8E8)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PanelAlt,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (row.detail.isNotBlank()) {
                Text(row.detail, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(color = bg, shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.35f))) {
                Text(
                    result,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ConfirmFlashDialog(plans: List<FlashPlan>, onDismiss: () -> Unit, onConfirm: (File?) -> Unit) {
    val first = plans.first()
    var programUboot by remember { mutableStateOf(false) }
    var imxBootPath by remember { mutableStateOf("") }
    var bootloaderSupport by remember { mutableStateOf<BootloaderImageSupport?>(null) }
    var unsafeBootloaderConfirmed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun chooseImxBoot() {
        chooseFile("Select imx-boot Image", imxBootPath, null)?.let {
            imxBootPath = it.absolutePath
            programUboot = true
            val supportResult = runCatching { BootloaderImageSupportInspector.inspect(it) }
            bootloaderSupport = supportResult.getOrNull()
            unsafeBootloaderConfirmed = false
            error = supportResult.exceptionOrNull()?.let { failure ->
                "Could not inspect imx-boot image: ${failure.message ?: failure::class.simpleName}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm eMMC Flash", color = Danger) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "This will overwrite the eMMC user area on ${plans.size} selected modem${if (plans.size == 1) "" else "s"}.",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ConfirmLine("Image", first.image.absolutePath)
                    ConfirmLine(
                        "Mode",
                        if (first.mode == FlashMode.BMAP) "bmap payload" else "full image write",
                    )
                    ConfirmLine("Bmap", first.bmap?.absolutePath ?: "none; full image will be written")
                    ConfirmLine("Destination", "mmc 2 user area")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = programUboot,
                        onCheckedChange = { checked ->
                            if (checked) {
                                chooseImxBoot()
                            } else {
                                programUboot = false
                                bootloaderSupport = null
                                unsafeBootloaderConfirmed = false
                                error = null
                            }
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Program U-Boot boot0", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            imxBootPath.ifBlank { "No imx-boot selected" },
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SecondaryButton("Choose", onClick = { chooseImxBoot() })
                }
                if (programUboot && imxBootPath.isNotBlank()) {
                    BootloaderSupportNotice(bootloaderSupport)
                    if (bootloaderSupport?.hasPmmAoeSupport == false && unsafeBootloaderConfirmed) {
                        Text(
                            "Click Flash Anyway to continue with this bootloader image.",
                            color = Danger,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    "Power loss or selecting the wrong unit can leave the modem unbootable.",
                    color = Danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            val unsafeBootloader = programUboot && bootloaderSupport?.hasPmmAoeSupport == false
            PrimaryButton(if (unsafeBootloader && unsafeBootloaderConfirmed) "Flash Anyway" else "Flash eMMC") {
                val bootloader = if (programUboot) {
                    val selected = File(imxBootPath)
                    if (!selected.isFile) {
                        error = "Select a readable imx-boot image or uncheck Program U-Boot."
                        return@PrimaryButton
                    }
                    val support = bootloaderSupport ?: runCatching {
                        BootloaderImageSupportInspector.inspect(selected)
                    }.getOrElse {
                        error = "Could not inspect imx-boot image: ${it.message ?: it::class.simpleName}"
                        return@PrimaryButton
                    }
                    bootloaderSupport = support
                    if (!support.hasPmmAoeSupport && !unsafeBootloaderConfirmed) {
                        unsafeBootloaderConfirmed = true
                        error = support.warningText()
                        return@PrimaryButton
                    }
                    selected
                } else {
                    null
                }
                onConfirm(bootloader)
            }
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) },
        containerColor = Panel,
        titleContentColor = Danger,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun BootloaderSupportNotice(support: BootloaderImageSupport?) {
    val supported = support?.hasPmmAoeSupport
    val color = when (supported) {
        true -> Success
        false -> Danger
        null -> Muted
    }
    val background = when (supported) {
        true -> Color(0xFFE7F8EF)
        false -> Color(0xFFFFE8E8)
        null -> PanelAlt
    }
    val message = when (supported) {
        true -> "Selected imx-boot includes PMM AoE/discovery support."
        false -> support.warningText()
        null -> "Selected imx-boot has not been inspected yet."
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            color = color,
            fontSize = 13.sp,
            fontWeight = if (supported == false) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ConfirmLine(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(88.dp))
        Text(
            value,
            color = TextPrimary,
            fontSize = 12.sp,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AdvancedConnectionDialog(
    useCustomSecret: Boolean,
    onUseCustomSecret: (Boolean) -> Unit,
    secretFile: String,
    onSecretFile: (String) -> Unit,
    noAuth: Boolean,
    timeout: String,
    onTimeout: (String) -> Unit,
    preserveSshKeys: Boolean,
    onPreserveSshKeys: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Advanced Connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "These settings are normally left at their defaults.",
                    color = Muted,
                    fontSize = 13.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = useCustomSecret, onCheckedChange = onUseCustomSecret, enabled = !noAuth)
                    Column {
                        Text("Custom secret", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (noAuth) "Authentication is disabled for this launch." else "Off uses the built-in Popoto default.",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = preserveSshKeys, onCheckedChange = onPreserveSshKeys)
                    Column {
                        Text("Preserve .ssh keys", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Keep root SSH keys and authorized_keys when flashing.",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = secretFile,
                        onValueChange = onSecretFile,
                        enabled = useCustomSecret && !noAuth,
                        label = { Text("Secret file") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton("Browse", enabled = !noAuth) {
                        chooseFile("Select Popoto Secret", secretFile, null)?.let { onSecretFile(it.absolutePath) }
                        if (!noAuth) onUseCustomSecret(true)
                    }
                }
                OutlinedTextField(
                    value = timeout,
                    onValueChange = onTimeout,
                    label = { Text("Timeout seconds") },
                    singleLine = true,
                    modifier = Modifier.width(180.dp),
                )
            }
        },
        confirmButton = { PrimaryButton("Done", onClick = onDismiss) },
        containerColor = Panel,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun SetIpDialog(state: DialogState.SetIp, onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var ip by remember { mutableStateOf(state.ip) }
    var netmask by remember { mutableStateOf(state.netmask) }
    var gateway by remember { mutableStateOf(state.gateway) }
    FormDialog(
        title = "Set IP Address",
        onDismiss = onDismiss,
        onConfirm = { onConfirm(ip.trim(), netmask.trim(), gateway.trim()) },
    ) {
        OutlinedTextField(ip, { ip = it }, label = { Text("IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(netmask, { netmask = it }, label = { Text("Netmask") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(gateway, { gateway = it }, label = { Text("Gateway") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SetRtcDialog(state: DialogState.SetRtc, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var rtc by remember { mutableStateOf(state.rtc) }
    FormDialog("Set RTC", onDismiss, { onConfirm(rtc.trim()) }) {
        OutlinedTextField(rtc, { rtc = it }, label = { Text("RTC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Use Host Clock") {
                rtc = hostRtcString()
            }
        }
    }
}

@Composable
private fun SetParamDialog(state: DialogState.SetParam, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf(state.name) }
    var value by remember { mutableStateOf(state.value) }
    FormDialog("Set Parameter", onDismiss, { onConfirm(name.trim(), value.trim()) }) {
        OutlinedTextField(name, { name = it }, label = { Text("Parameter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value, { value = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SyncClientDialog(
    state: DialogState.SyncClient,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    var host by remember { mutableStateOf(state.host) }
    var username by remember { mutableStateOf(state.username) }
    var password by remember { mutableStateOf(state.password) }
    var port by remember { mutableStateOf(state.port) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync Modem Client", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This replaces the Popoto Discover client on the selected modem with the client bundled in this app and restarts popoto-discover.service.",
                    color = Muted,
                    fontSize = 13.sp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConfirmLine("Device", state.device.displayNameText())
                    ConfirmLine("Device ID", state.device.deviceIdText() ?: "unknown", monospace = true)
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        error = null
                    },
                    label = { Text("SSH host/IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            error = null
                        },
                        label = { Text("User") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it
                            error = null
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.width(112.dp),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            PrimaryButton("Sync Client") {
                when {
                    host.isBlank() -> error = "Enter the modem SSH host/IP."
                    username.isBlank() -> error = "Enter the SSH username."
                    port.toIntOrNull()?.takeIf { it in 1..65535 } == null -> error = "Enter a valid SSH port."
                    else -> onConfirm(host.trim(), username.trim(), password, port.trim())
                }
            }
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) },
        containerColor = Panel,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun InstallClientDialog(
    state: DialogState.InstallClient,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    var host by remember { mutableStateOf(state.host) }
    var username by remember { mutableStateOf(state.username) }
    var password by remember { mutableStateOf(state.password) }
    var port by remember { mutableStateOf(state.port) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install Discover to Device", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Use this when a modem is reachable over SSH but does not already appear in discovery. This installs the bundled Popoto Discover client and restarts popoto-discover.service.",
                    color = Muted,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        error = null
                    },
                    label = { Text("SSH host/IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            error = null
                        },
                        label = { Text("User") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it
                            error = null
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.width(112.dp),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            PrimaryButton("Install Discover") {
                when {
                    host.isBlank() -> error = "Enter the modem SSH host/IP."
                    username.isBlank() -> error = "Enter the SSH username."
                    port.toIntOrNull()?.takeIf { it in 1..65535 } == null -> error = "Enter a valid SSH port."
                    else -> onConfirm(host.trim(), username.trim(), password, port.trim())
                }
            }
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) },
        containerColor = Panel,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        },
        confirmButton = { PrimaryButton("Apply", onClick = onConfirm) },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) },
        containerColor = Panel,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun FlashRunWindow(run: BatchFlashRunState, onClose: () -> Unit) {
    var logExpanded by remember { mutableStateOf(false) }
    val visibleLogs = run.lines.takeLast(500)
    val logState = rememberLazyListState()

    LaunchedEffect(logExpanded, visibleLogs.size) {
        if (logExpanded && visibleLogs.isNotEmpty()) {
            logState.scrollToItem(visibleLogs.lastIndex)
        }
    }

    Window(
        onCloseRequest = onClose,
        title = "Flash PMM eMMC",
        state = rememberWindowState(size = DpSize(860.dp, 560.dp)),
        icon = painterResource("icons/popoto-icon.png"),
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = PopotoBlue,
                secondary = AcousticBlue,
                background = Clamshell,
                surface = Panel,
                error = Danger,
            ),
        ) {
            Surface(Modifier.fillMaxSize(), color = Clamshell) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        color = Panel,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Border),
                        shadowElevation = 2.dp,
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Targets", color = TextPrimary, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${run.requests.size} unit${if (run.requests.size == 1) "" else "s"} · ${run.requests.first().image.name}",
                                        color = Muted,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                SecondaryButton("Close", enabled = !run.running, onClick = onClose)
                            }
                            val multiTarget = run.runs.size > 1
                            for (boardRun in run.runs) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        boardRun.error != null -> Danger
                                                        boardRun.complete -> Success
                                                        boardRun.running -> UpdateOrange
                                                        else -> PopotoBlue
                                                    },
                                                ),
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                boardRun.request.initialDevice.displayNameText(),
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                "${boardRun.request.aoeTarget.label} · ${visibleTargetStatus(boardRun, multiTarget)}",
                                                color = Muted,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            "${boardRun.progress}%",
                                            color = TextPrimary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { boardRun.progress / 100f },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                                        color = when {
                                            boardRun.error != null -> Danger
                                            boardRun.complete -> Success
                                            else -> PopotoBlue
                                        },
                                        trackColor = Color(0xFFE8EEF5),
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Flash Details", color = TextPrimary, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (logExpanded) {
                                            "Showing ${visibleLogs.size} latest message${if (visibleLogs.size == 1) "" else "s"}"
                                        } else {
                                            "Detailed write and retry messages are hidden"
                                        },
                                        color = Muted,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                SecondaryButton(
                                    if (logExpanded) "Hide Log" else "Show Log",
                                    modifier = Modifier.width(116.dp),
                                    onClick = { logExpanded = !logExpanded },
                                )
                            }
                            if (logExpanded) {
                                Surface(
                                    Modifier.fillMaxWidth().height(190.dp),
                                    color = DeepSea,
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.dp, Color(0xFF1F2A3A)),
                                ) {
                                    LazyColumn(
                                        state = logState,
                                        modifier = Modifier.fillMaxSize().padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        items(visibleLogs) { line ->
                                            Text(
                                                line,
                                                color = when {
                                                    line.contains("ERROR") -> Color(0xFFFFA8A8)
                                                    line.contains("OK:") -> Color(0xFF9AF2CE)
                                                    else -> Color(0xFFD9E8FF)
                                                },
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun targetFor(device: Device): TargetSelector {
    val targetText = device.deviceIdText()
    require(!targetText.isNullOrBlank()) { "Selected device has no usable target identifier." }
    return TargetSelector.parse(targetText)
}

private fun selectionKey(device: Device): String? {
    return device.deviceIdText() ?: device.uiKeyText()
}

private fun mfgTestDialogState(response: CommandResponse): DialogState.MfgTestResult {
    return DialogState.MfgTestResult(
        result = response.text("result") ?: "unknown",
        test = response.text("test") ?: "manufacturing test",
        returnCode = response.text("returncode") ?: "unknown",
        sourceIp = response.sourceIp,
        output = cleanMfgOutput(response.text("output").orEmpty()),
        truncated = response.text("output_truncated") == "1",
    )
}

private fun mfgTestHeadline(state: DialogState.MfgTestResult): String {
    return "${state.test} ${state.result.uppercase()} (return code ${state.returnCode}, reply from ${state.sourceIp})"
}

private fun responseSummary(response: CommandResponse): String {
    return when (Protocol.text(response.message, "cmd")) {
        Protocol.MSG_SET_IP_REPLY -> {
            val ip = response.text("ip")
            when (response.text("verified")) {
                "rediscovery" -> "IP set to $ip (verified by rediscovery)"
                "scheduled" -> "IP change to $ip scheduled on the modem"
                else -> "IP set to $ip (reply from ${response.sourceIp})"
            }
        }
        Protocol.MSG_SET_RTC_REPLY -> "RTC set (reply from ${response.sourceIp})"
        Protocol.MSG_GET_RTC_REPLY -> "RTC: ${response.text("rtc") ?: "Unknown"} (reply from ${response.sourceIp})"
        Protocol.MSG_SET_PARAM_REPLY -> "Parameter set (reply from ${response.sourceIp})"
        Protocol.MSG_GET_VERSION_REPLY -> "Version: ${response.text("version") ?: "Unknown"}; Serial: ${response.text("serial") ?: "unknown"} (reply from ${response.sourceIp})"
        Protocol.MSG_BOOT_LINUX_REPLY -> "Boot Linux accepted; AoE mode was cleared and the unit is resetting (reply from ${response.sourceIp})"
        Protocol.MSG_MFG_TEST_REPLY -> {
            mfgTestHeadline(mfgTestDialogState(response))
        }
        else -> "Command succeeded (reply from ${response.sourceIp})"
    }
}

private fun cleanMfgOutput(output: String): String {
    return output
        .replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"), "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .joinToString("\n") { it.trimEnd() }
        .trim()
}

private fun parseMfgDeviceResults(output: String): List<MfgDeviceResult> {
    val rowPattern = Regex("^\\s*([A-Za-z0-9_.-]+)\\s+(PASS|FAIL|SKIP)\\s*(.*)$", RegexOption.IGNORE_CASE)
    return output.lineSequence().mapNotNull { line ->
        val match = rowPattern.matchEntire(line) ?: return@mapNotNull null
        MfgDeviceResult(
            name = match.groupValues[1],
            result = match.groupValues[2].uppercase(),
            detail = match.groupValues[3].trim(),
        )
    }.toList()
}

private fun Device.supportsBootLinuxAction(): Boolean {
    return text("uboot") == "1" && text("supports_boot_linux") == "1"
}

private fun Device.supportsManufacturingTestAction(): Boolean {
    return text("uboot") == "1" && text("supports_mfg_test") == "1"
}

private fun storageText(device: Device): String {
    val free = device.text("storage_free_gb")
    val total = device.text("storage_total_gb")
    return if (!free.isNullOrBlank() && !total.isNullOrBlank()) "$free / $total GB" else "--"
}

private fun viaText(device: Device): String {
    return device.paths.joinToString(", ") { path ->
        path.transport + (path.interfaceName?.let { "@$it" } ?: "")
    }.ifBlank { "--" }
}

private fun defaultGateway(ip: String): String {
    val parts = ip.split(".")
    return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.1" else ""
}

private fun chooseFile(title: String, current: String, suffix: String?): File? {
    val currentFile = current.takeIf { it.isNotBlank() }?.let(::File)
    val startDir = currentFile?.parentFile?.takeIf { it.isDirectory }
        ?: File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }
        ?: File(System.getProperty("user.home"))
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        directory = startDir.absolutePath
        if (suffix != null) {
            filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".$suffix", ignoreCase = true) }
        }
        file = currentFile?.name
    }
    dialog.isVisible = true
    val selected = dialog.file ?: return null
    val file = File(dialog.directory, selected)
    return if (suffix == null || file.name.endsWith(".$suffix", ignoreCase = true)) file else null
}

private fun interfaceChoices(current: String): List<String> {
    val discovered = runCatching { RawEthernetTransport.candidateInterfaces() }
        .getOrDefault(emptyList())
    return buildList {
        add("")
        addAll(discovered)
        current.trim().takeIf { it.isNotEmpty() && it !in discovered }?.let { add(it) }
    }.distinct()
}

private fun isWicLz4(file: File): Boolean = file.name.endsWith(".wic.lz4", ignoreCase = true)

private fun stamped(message: String): String {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    return "[$stamp] $message"
}
