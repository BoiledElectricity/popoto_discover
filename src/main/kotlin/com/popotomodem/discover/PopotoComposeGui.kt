package com.popotomodem.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

        Window(
            onCloseRequest = { shouldExit = true },
            title = "Popoto Discover",
            state = windowState,
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
private val Warning = Color(0xFFB7791F)
private val Danger = Color(0xFFC43D3D)

private data class ComposeSettings(
    val useCustomSecret: Boolean,
    val secretFile: String,
    val timeout: String,
    val interfaceName: String,
    val transport: String,
    val wicImage: String,
) {
    fun save() {
        runCatching {
            prefs().apply {
                putBoolean(KEY_CUSTOM_SECRET, useCustomSecret)
                put(KEY_SECRET_FILE, secretFile)
                put(KEY_TIMEOUT, timeout)
                put(KEY_INTERFACE, interfaceName)
                put(KEY_TRANSPORT, transport)
                put(KEY_WIC_IMAGE, wicImage)
            }
        }
    }

    companion object {
        private const val KEY_CUSTOM_SECRET = "useCustomSecret"
        private const val KEY_SECRET_FILE = "secretFile"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_INTERFACE = "interface"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_WIC_IMAGE = "wicImage"

        fun load(initialSecretFile: String?): ComposeSettings {
            if (!initialSecretFile.isNullOrBlank()) {
                return ComposeSettings(
                    useCustomSecret = true,
                    secretFile = initialSecretFile,
                    timeout = "8.0",
                    interfaceName = "",
                    transport = "auto",
                    wicImage = "",
                )
            }
            val prefs = prefs()
            return ComposeSettings(
                useCustomSecret = prefs.getBoolean(KEY_CUSTOM_SECRET, false),
                secretFile = prefs.get(KEY_SECRET_FILE, ""),
                timeout = prefs.get(KEY_TIMEOUT, "8.0"),
                interfaceName = prefs.get(KEY_INTERFACE, ""),
                transport = prefs.get(KEY_TRANSPORT, "auto").takeIf { it in setOf("auto", "udp", "l2", "all") } ?: "auto",
                wicImage = prefs.get(KEY_WIC_IMAGE, ""),
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
    data object AdvancedConnection : DialogState
    data class SetIp(val device: Device, val ip: String, val netmask: String, val gateway: String) : DialogState
    data class SetRtc(val device: Device, val rtc: String) : DialogState
    data class SetParam(val device: Device, val name: String, val value: String) : DialogState
    data class ConfirmFlash(val device: Device, val image: File, val bmap: File?, val defaultMode: FlashMode) : DialogState
}

private class FlashRunState(val request: FlashRequest) {
    var status by mutableStateOf("Starting")
    var progress by mutableIntStateOf(0)
    var running by mutableStateOf(true)
    var complete by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val lines = mutableStateListOf<String>()

    fun add(event: FlashEvent) {
        status = event.message.lineSequence().firstOrNull()?.take(160) ?: event.phase
        if (event.totalBytes > 0) {
            progress = FlashWorkflow.progressPercent(event)
        }
        lines += stamped(event.message)
    }
}

@Composable
private fun App(initialSecretFile: String?, noAuth: Boolean, onExit: () -> Unit) {
    val saved = remember { ComposeSettings.load(initialSecretFile) }
    var useCustomSecret by remember { mutableStateOf(saved.useCustomSecret && !noAuth) }
    var secretFile by remember { mutableStateOf(saved.secretFile) }
    var timeout by remember { mutableStateOf(saved.timeout) }
    var interfaceName by remember { mutableStateOf(saved.interfaceName) }
    var interfaceOptions by remember { mutableStateOf(interfaceChoices(saved.interfaceName)) }
    var transport by remember { mutableStateOf(saved.transport) }
    var wicImage by remember { mutableStateOf(saved.wicImage) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var commandRunning by remember { mutableStateOf(false) }
    var hasBpfAccess by remember { mutableStateOf(MacBpfAccess.hasBpfAccess()) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var flashRun by remember { mutableStateOf<FlashRunState?>(null) }
    val logs = remember { mutableStateListOf<LogLine>() }
    val scope = rememberCoroutineScope()

    fun settings() = ComposeSettings(
        useCustomSecret = useCustomSecret,
        secretFile = secretFile,
        timeout = timeout,
        interfaceName = interfaceName,
        transport = transport,
        wicImage = wicImage,
    )

    fun saveSettings() = settings().save()

    fun refreshInterfaces() {
        interfaceOptions = interfaceChoices(interfaceName)
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

    fun selectedDevice(): Device? {
        val key = selectedDeviceId
        return devices.firstOrNull { selectionKey(it) == key }
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

    fun discover() {
        saveSettings()
        val mode = runCatching { TransportMode.parse(transport) }.getOrElse {
            dialog = DialogState.Message("Discovery", it.message ?: "Invalid transport", isError = true)
            return
        }
        if (MacBpfAccess.needsSetupFor(mode)) {
            installBpf(afterSuccess = { discover() })
            return
        }

        scope.launch {
            discovering = true
            log("Starting discovery")
            val previous = selectedDevice()?.deviceIdText()
            val secret = try {
                readSecret()
            } catch (e: IllegalArgumentException) {
                discovering = false
                dialog = DialogState.Message("Authentication", e.message ?: "Authentication setup failed", isError = true)
                return@launch
            }
            val interfaces = interfaceName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            try {
                val found = withContext(Dispatchers.IO) {
                    Discoverer().discover(
                        DiscoveryOptions(
                            timeoutSeconds = timeout.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 8.0,
                            secret = secret,
                            transportMode = mode,
                            interfaces = interfaces,
                            retries = 3,
                        ),
                    )
                }
                devices = found.sortedWith(
                    compareBy<Device> { it.text("model") ?: "" }
                        .thenBy { it.serialText() }
                        .thenBy { it.deviceIdText() ?: "" }
                        .thenBy { it.text("ip") ?: "" },
                )
                selectedDeviceId = previous?.takeIf { id -> devices.any { selectionKey(it) == id } }
                    ?: if (devices.size == 1) selectionKey(devices.first()) else null
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
                    val summary = responseSummary(response)
                    log("$label: $summary", "SUCCESS")
                    dialog = DialogState.Message("Success", summary)
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

    fun startFlash(device: Device, image: File, bmap: File?, mode: FlashMode) {
        if (MacBpfAccess.isMac() && !MacBpfAccess.hasBpfAccess()) {
            installBpf(afterSuccess = { startFlash(device, image, bmap, mode) })
            return
        }
        val target = FlashWorkflow.targetFor(device) ?: run {
            dialog = DialogState.Message("Flash WIC", "Selected device has no usable target identifier.", isError = true)
            return
        }
        val iface = FlashWorkflow.bestInterfaceFor(device, interfaceName) ?: run {
            dialog = DialogState.Message("Flash WIC", "No Ethernet interface is available for flashing.", isError = true)
            return
        }
        val secret = try {
            readSecret()
        } catch (e: IllegalArgumentException) {
            dialog = DialogState.Message("Authentication", e.message ?: "Authentication setup failed", isError = true)
            return
        }
        val request = FlashRequest(
            initialDevice = device,
            target = target,
            interfaceName = iface,
            image = image,
            bmap = bmap,
            mode = mode,
            secret = secret,
        )
        val run = FlashRunState(request)
        flashRun = run
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    EventQueue.invokeLater {
                        run.lines += stamped("Image: ${request.image.absolutePath}")
                        run.lines += stamped("Mode: ${if (request.mode == FlashMode.BMAP) "bmap payload" else "full image"}")
                        request.bmap?.let { run.lines += stamped("Bmap: ${it.absolutePath}") }
                        run.lines += stamped("Interface: ${request.interfaceName}")
                        run.lines += stamped("Target: ${request.target.label}")
                    }
                    val rediscovered = FlashWorkflow(request) { event ->
                        EventQueue.invokeLater { run.add(event) }
                    }.run()
                    EventQueue.invokeLater {
                        run.progress = 100
                        run.status = "Flash complete: ${rediscovered.text("name") ?: rediscovered.deviceIdText() ?: request.target.label}"
                        run.complete = true
                        run.running = false
                        run.lines += stamped("OK: flash workflow complete")
                    }
                } catch (e: Exception) {
                    val message = e.message ?: e::class.simpleName ?: "Unknown error"
                    EventQueue.invokeLater {
                        run.status = "Flash failed"
                        run.error = message
                        run.running = false
                        run.lines += stamped("ERROR: $message")
                    }
                }
            }
        }
    }

    LaunchedEffect(useCustomSecret, secretFile, timeout, interfaceName, transport, wicImage) {
        saveSettings()
    }

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
                    transport = transport,
                    onTransport = { transport = it },
                    discovering = discovering,
                    onDiscover = ::discover,
                    isMac = MacBpfAccess.isMac(),
                    hasBpfAccess = hasBpfAccess,
                    onEnableL2 = { installBpf() },
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
                        selectedDeviceId = selectedDeviceId,
                        onSelected = { selectedDeviceId = selectionKey(it) },
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
                        SelectedUnitCard(
                            modifier = Modifier.fillMaxWidth(),
                            device = selectedDevice(),
                        )
                        FlashImageCard(
                            modifier = Modifier.fillMaxWidth(),
                            wicImage = wicImage,
                            onWicImage = { wicImage = it },
                            enabled = selectedDevice() != null && !commandRunning && !discovering,
                            onFlash = {
                                val device = selectedDevice() ?: return@FlashImageCard
                                val image = File(wicImage)
                                if (!isWicLz4(image) || !image.isFile) {
                                    dialog = DialogState.Message("Flash Image", "Select a readable .wic.lz4 image first.", isError = true)
                                    return@FlashImageCard
                                }
                                val bmap = FlashWorkflow.defaultBmapFor(image).takeIf { it.exists() }
                                dialog = DialogState.ConfirmFlash(
                                    device = device,
                                    image = image,
                                    bmap = bmap,
                                    defaultMode = if (bmap != null) FlashMode.BMAP else FlashMode.FULL_IMAGE,
                                )
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
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd-HH:mm:ss")),
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
        DialogState.AdvancedConnection -> AdvancedConnectionDialog(
            useCustomSecret = useCustomSecret,
            onUseCustomSecret = { useCustomSecret = it && !noAuth },
            secretFile = secretFile,
            onSecretFile = { secretFile = it },
            noAuth = noAuth,
            timeout = timeout,
            onTimeout = { timeout = it },
            onDismiss = { dialog = null },
        )
        is DialogState.SetIp -> SetIpDialog(
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { ip, mask, gateway ->
                dialog = null
                val target = targetFor(state.device)
                runCommand("Setting IP on ${target.label} to $ip") {
                    CommandClient().setIp(target, ip, mask, gateway, commandOptions())
                }
            },
        )
        is DialogState.SetRtc -> SetRtcDialog(
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { rtc ->
                dialog = null
                val target = targetFor(state.device)
                runCommand("Setting RTC on ${target.label} to $rtc") {
                    CommandClient().setRtc(target, rtc, commandOptions())
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
        is DialogState.ConfirmFlash -> ConfirmFlashDialog(
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { mode ->
                dialog = null
                startFlash(state.device, state.image, if (mode == FlashMode.BMAP) state.bmap else null, mode)
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
    transport: String,
    onTransport: (String) -> Unit,
    discovering: Boolean,
    onDiscover: () -> Unit,
    isMac: Boolean,
    hasBpfAccess: Boolean,
    onEnableL2: () -> Unit,
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
            TransportSelector(transport, onTransport)
            Spacer(Modifier.weight(1f))
            if (isMac) {
                SecondaryButton(if (hasBpfAccess) "L2 Enabled" else "Enable L2", enabled = !hasBpfAccess, onClick = onEnableL2)
            }
            SecondaryButton("Advanced", onClick = onAdvanced)
        }
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
    Box {
        Surface(
            modifier = Modifier
                .width(212.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable {
                    onRefresh()
                    expanded = true
                },
            color = Color.White,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Border),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Interface", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    selected.ifBlank { "Auto" },
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option.ifBlank { "Auto detect" }) },
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
private fun TransportSelector(selected: String, onSelected: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Transport", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        for (mode in listOf("auto", "udp", "l2", "all")) {
            val active = selected == mode
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (active) PopotoBlue else Color.White,
                border = BorderStroke(1.dp, if (active) PopotoBlue else Border),
                modifier = Modifier.clickable { onSelected(mode) },
            ) {
                Text(
                    mode,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    color = if (active) Color.White else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SelectedUnitCard(modifier: Modifier, device: Device?) {
    AppCard("Selected Unit", modifier) {
        if (device == null) {
            Text("No unit selected", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Select a discovered row before running commands or flashing.", color = Muted)
            return@AppCard
        }
        Text(device.text("name") ?: device.text("model") ?: "Selected unit", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        InfoLine("Serial", device.serialText())
        InfoLine("Device ID", device.deviceIdText() ?: "--")
        InfoLine("IP", device.text("ip") ?: "--")
        InfoLine("MAC", device.text("mac") ?: "--")
    }
}

@Composable
private fun FlashImageCard(
    modifier: Modifier,
    wicImage: String,
    onWicImage: (String) -> Unit,
    enabled: Boolean,
    onFlash: () -> Unit,
) {
    AppCard("Flash Image", modifier) {
        Text("Persistent WIC image selection", color = Muted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = wicImage,
                onValueChange = onWicImage,
                label = { Text(".wic.lz4 image") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton("Choose") {
                chooseFile("Select PMM WIC LZ4 Image", wicImage, "wic.lz4")?.let { onWicImage(it.absolutePath) }
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Flash WIC", enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = onFlash)
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
    selectedDeviceId: String?,
    onSelected: (Device) -> Unit,
    modifier: Modifier,
) {
    AppCard("Discovered Devices", modifier) {
        if (devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No devices discovered yet", color = Muted, fontSize = 16.sp)
            }
            return@AppCard
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(devices, key = { selectionKey(it) ?: it.hashCode().toString() }) { device ->
                DeviceRow(device, selected = selectionKey(device) == selectedDeviceId, onClick = { onSelected(device) })
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) PopotoBlue else Border
    val background = if (selected) Color(0xFFEAF6FF) else PanelAlt
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
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (selected) PopotoBlue else Success),
            )
            Column(Modifier.weight(1.25f)) {
                Text(device.text("name") ?: device.text("model") ?: "PMM", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(device.deviceIdText() ?: "no device id", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            DetailColumn("Serial", device.serialText(), Modifier.weight(1.0f))
            DetailColumn("Network", "${device.text("ip") ?: "--"}\n${device.text("mac") ?: "--"}", Modifier.weight(1.15f))
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
        Column(Modifier.padding(18.dp)) {
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
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PopotoBlue, contentColor = Color.White),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.width(74.dp))
        Text(value, color = TextPrimary, fontSize = 13.sp, fontFamily = if (label == "Device ID") FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun MessageDialog(state: DialogState.Message, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title, color = if (state.isError) Danger else TextPrimary) },
        text = { Text(state.message) },
        confirmButton = { PrimaryButton("OK", onClick = onDismiss) },
    )
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
private fun ConfirmFlashDialog(state: DialogState.ConfirmFlash, onDismiss: () -> Unit, onConfirm: (FlashMode) -> Unit) {
    var mode by remember { mutableStateOf(state.defaultMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flash PMM eMMC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This will overwrite the PMM eMMC user area.", color = Danger, fontWeight = FontWeight.Bold)
                Text("Device: ${state.device.text("name") ?: state.device.deviceIdText() ?: "selected unit"}")
                Text("Image: ${state.image.name}")
                if (state.bmap != null) {
                    ModeChoice("Use bmap payload", mode == FlashMode.BMAP) { mode = FlashMode.BMAP }
                }
                ModeChoice("Write full image", mode == FlashMode.FULL_IMAGE) { mode = FlashMode.FULL_IMAGE }
            }
        },
        confirmButton = { PrimaryButton("Start Flash") { onConfirm(mode) } },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun ModeChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Color(0xFFEAF6FF) else Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (selected) PopotoBlue else Border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), color = TextPrimary, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
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
    )
}

@Composable
private fun FlashRunWindow(run: FlashRunState, onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        title = "Flash PMM eMMC",
        state = rememberWindowState(size = DpSize(860.dp, 620.dp)),
    ) {
        MaterialTheme(colorScheme = darkColorScheme(primary = PopotoBlue, background = DeepNavy, surface = DeepSea)) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF101827)) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (run.running) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp, color = Color(0xFF77E0BF))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(run.status, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(run.request.image.name, color = Color(0xFFB8C7D9), fontSize = 13.sp)
                        }
                        SecondaryButton("Close", enabled = !run.running, onClick = onClose)
                    }
                    LinearProgressIndicator(
                        progress = { run.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(10.dp)),
                        color = Color(0xFF77E0BF),
                        trackColor = Color(0xFF273246),
                    )
                    Text("${run.progress}%", color = Color(0xFFD9E8FF), fontFamily = FontFamily.Monospace)
                    Surface(Modifier.fillMaxSize(), color = Color(0xFF0D1320), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFF263245))) {
                        LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(run.lines.takeLast(500)) { line ->
                                Text(
                                    line,
                                    color = when {
                                        line.contains("ERROR") -> Color(0xFFFFA8A8)
                                        line.contains("OK:") -> Color(0xFF9AF2CE)
                                        else -> Color(0xFFD9E8FF)
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun targetFor(device: Device): TargetSelector {
    val targetText = device.deviceIdText() ?: usableIdentity(device.text("mac"))
    require(!targetText.isNullOrBlank()) { "Selected device has no usable target identifier." }
    return TargetSelector.parse(targetText)
}

private fun selectionKey(device: Device): String? {
    return device.deviceIdText() ?: usableIdentity(device.text("mac"))
}

private fun responseSummary(response: CommandResponse): String {
    return when (Protocol.text(response.message, "cmd")) {
        Protocol.MSG_SET_IP_REPLY -> "IP set to ${response.text("ip")} (reply from ${response.sourceIp})"
        Protocol.MSG_SET_RTC_REPLY -> "RTC set (reply from ${response.sourceIp})"
        Protocol.MSG_GET_RTC_REPLY -> "RTC: ${response.text("rtc") ?: "Unknown"} (reply from ${response.sourceIp})"
        Protocol.MSG_SET_PARAM_REPLY -> "Parameter set (reply from ${response.sourceIp})"
        Protocol.MSG_GET_VERSION_REPLY -> "Version: ${response.text("version") ?: "Unknown"}; Serial: ${response.text("serial") ?: "unknown"} (reply from ${response.sourceIp})"
        else -> "Command succeeded (reply from ${response.sourceIp})"
    }
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
