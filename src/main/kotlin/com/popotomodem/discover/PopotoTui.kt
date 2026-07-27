package com.popotomodem.discover

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.jline.utils.Display
import org.jline.utils.InfoCmp.Capability
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

object PopotoTui {
    fun launch(secretFile: String?, noAuth: Boolean) {
        TerminalUi(secretFile, noAuth).run()
    }
}

/**
 * Full-screen, fixed-layout terminal UI built on JLine's [Display]. Unlike a
 * scrolling print loop, the whole screen is diffed and repainted in place, so
 * there is no scrollback spam and panes update live. Prompts/menus/output are
 * rendered as centred modal dialogs overlaid on the dimmed main view.
 */
private class TerminalUi(
    private val secretFile: String?,
    private val noAuth: Boolean,
) {
    private val commandClient = CommandClient()
    private val logLines = ArrayDeque<String>()
    private var devices: List<Device> = emptyList()
    private var selectedKeys = mutableSetOf<String>()
    private var cursor = 0
    private var listOffset = 0
    private var transportMode = TransportMode.ALL
    private var interfaceName: String? = null
    private var timeoutSeconds = 4.0
    private var retries = 5
    private var status = "Ready"
    private var endOfInput = false
    private var terminal: Terminal? = null
    private var display: Display? = null
    private var originalAttributes: Attributes? = null
    private var originalStty: String? = null
    private var rawActive = false
    private var keyMode = false
    private val stateLock = Any()
    private val flashProgress = linkedMapOf<String, FlashLineState>()
    private val secret: String? by lazy {
        if (noAuth) {
            log("WARNING: running without authentication")
            null
        } else {
            SecretProvider.load(secretFile)
        }
    }

    fun run() {
        if (System.console() != null) {
            runKeyMode()
        } else {
            discover()
            runCommandMode()
        }
    }

    // ------------------------------------------------------------------ modes

    private fun runKeyMode() {
        keyMode = true
        terminal = TerminalBuilder.builder()
            .system(true)
            .jna(true)
            .build()
        originalAttributes = terminal?.let { Attributes(it.attributes) }
        enterRawMode()
        enterAlternateScreen()
        display = Display(terminal, true)
        terminal?.puts(Capability.cursor_invisible)
        try {
            discover()
            while (true) {
                render()
                try {
                    when (readKey()) {
                        TuiKey.QUIT -> return
                        TuiKey.UP -> moveCursor(-1)
                        TuiKey.DOWN -> moveCursor(1)
                        TuiKey.SELECT -> toggleSelected()
                        TuiKey.ENTER -> showDeviceMenu()
                        TuiKey.REFRESH -> discover()
                        TuiKey.FLASH -> flashSelected()
                        TuiKey.OPTIONS -> editOptions()
                        TuiKey.HELP -> showHelp()
                        TuiKey.ALL -> selectAll()
                        TuiKey.NONE -> {
                            selectedKeys.clear()
                            status = "Selection cleared"
                        }
                        TuiKey.VERSION -> getVersion()
                        TuiKey.RTC -> syncRtc()
                        TuiKey.INSTALL -> installClient()
                        TuiKey.SYNC -> syncClient()
                        TuiKey.BOOT -> bootLinux()
                        TuiKey.BACK -> Unit
                        TuiKey.UNKNOWN -> Unit
                    }
                } catch (error: Throwable) {
                    status = "ERROR: ${error.message ?: error::class.simpleName}"
                    log(status)
                    showModal("Error", listOf(astr(status, S.danger)), "any key to dismiss")
                }
            }
        } finally {
            terminal?.puts(Capability.cursor_visible)
            exitAlternateScreen()
            leaveRawMode()
            terminal?.close()
            terminal = null
            display = null
            keyMode = false
        }
    }

    private fun runCommandMode() {
        while (true) {
            renderPlain()
            val input = prompt("Command", "").trim()
            if (endOfInput) {
                return
            }
            if (input.isEmpty()) {
                continue
            }
            try {
                if (!handleCommand(input)) {
                    return
                }
            } catch (error: Throwable) {
                status = "ERROR: ${error.message ?: error::class.simpleName}"
                log(status)
                pause()
            }
        }
    }

    private fun handleCommand(input: String): Boolean {
        val parts = input.split(Regex("\\s+")).filter { it.isNotBlank() }
        val command = parts.first().lowercase()
        when (command) {
            "q", "quit", "exit" -> return false
            "?", "h", "help" -> showHelp()
            "r", "refresh", "discover" -> discover()
            "j", "down", "n" -> moveCursor(1)
            "k", "up", "p" -> moveCursor(-1)
            "x", "space", "select" -> toggleSelected()
            "all" -> selectAll()
            "none", "clear" -> {
                selectedKeys.clear()
                status = "Selection cleared"
            }
            "o", "opts", "options" -> editOptions()
            "v", "version" -> getVersion()
            "rtc", "clock" -> syncRtc()
            "ip", "set-ip" -> setIp()
            "shell" -> shell()
            "sync", "sync-client" -> syncClient()
            "install", "install-client" -> installClient()
            "boot", "linux", "boot-linux" -> bootLinux()
            "mfg", "test" -> manufacturingTest()
            "f", "flash" -> flashSelected()
            "enter", "menu" -> showDeviceMenu()
            else -> {
                val index = command.toIntOrNull()
                if (index != null) {
                    jumpTo(index - 1)
                } else {
                    status = "Unknown command '$input' (? for help)"
                    log(status)
                }
            }
        }
        return true
    }

    // ----------------------------------------------------------------- actions

    private fun showDeviceMenu() {
        val device = requireCurrentDevice()
        val title = "Actions · ${device.displayNameText()}"
        val items = buildList {
            add(MenuItem("Read version", "v") { getVersion() })
            add(MenuItem("Sync RTC to host clock", "rtc") { syncRtc() })
            add(MenuItem("Set IP / netmask / gateway", "ip") { setIp() })
            add(MenuItem("Sync Discover client", "sync") { syncClient() })
            add(MenuItem("Run shell command", "shell") { shell() })
            add(MenuItem("Flash selected/current", "flash") { flashSelected() })
            if (device.text("uboot") == "1") {
                add(MenuItem("Boot Linux", "boot") { bootLinux() })
                add(MenuItem("Run manufacturing test", "mfg") { manufacturingTest() })
            }
            add(MenuItem("Back", "esc") {})
        }
        val selected = chooseMenu(title, items)
        if (selected != null && items[selected].shortcut != "esc") {
            items[selected].action()
        }
    }

    private fun discover() {
        status = "Discovering..."
        log("Starting discovery over ${transportMode.name.lowercase()}${interfaceName?.let { " on $it" } ?: ""}")
        if (keyMode) render()
        ensurePacketCaptureAccess(transportMode)
        val found = Discoverer().discover(
            DiscoveryOptions(
                timeoutSeconds = timeoutSeconds,
                secret = secret,
                transportMode = transportMode,
                interfaces = interfaceName?.let(::listOf).orEmpty(),
                retries = retries,
            ),
        )
        devices = annotateDiscoveredDevices(found)
        selectedKeys.retainAll(devices.mapNotNull(::selectionKey).toSet())
        cursor = cursor.coerceIn(0, (devices.size - 1).coerceAtLeast(0))
        status = "Discovered ${devices.size} device(s)"
        log(status)
    }

    private fun getVersion() {
        val device = requireCurrentDevice()
        val response = requireReply(
            commandClient.getVersion(targetFor(device), commandOptions().copy(timeoutSeconds = maxOf(timeoutSeconds, 8.0))),
            "get version",
        )
        status = "Version ${response.text("version") ?: "unknown"} / serial ${response.text("serial") ?: "unknown"}"
        log(status)
        pause()
    }

    private fun syncRtc() {
        val targets = selectedOrCurrentTargets()
        val rtc = hostRtcString()
        for ((device, target) in targets) {
            val response = requireReply(
                commandClient.setRtc(target, rtc, commandOptions()),
                "set RTC on ${device.displayNameText()}",
            )
            log("${deviceLabel(device)} RTC set to $rtc via ${response.sourceIp}")
        }
        status = "RTC synced on ${targets.size} unit(s)"
        pause()
    }

    private fun setIp() {
        val device = requireCurrentDevice()
        val currentIp = device.sshHostText()
            ?: throw IllegalArgumentException("selected device has no reachable IP address")
        val ip = prompt("New IP", device.text("ip").orEmpty()).trim()
        val netmask = prompt("Netmask", "255.255.255.0").trim()
        val gateway = prompt("Gateway", "").trim()
        require(ip.isNotBlank()) { "IP is required" }
        val response = NetworkConfigActions.setIp(targetFor(device), currentIp, ip, netmask, gateway, commandOptions())
        status = "IP set to ${response.text("ip")} (${response.text("verified") ?: "accepted"})"
        log(status)
        pause()
    }

    private fun shell() {
        val device = requireCurrentDevice()
        val command = prompt("Shell command", "").trim()
        require(command.isNotBlank()) { "shell command is required" }
        val response = requireReply(
            commandClient.shellExec(targetFor(device), command, commandOptions(), timeoutSeconds = 10.0),
            "shell command",
        )
        status = "Shell command ${response.text("status") ?: "done"}"
        log(status)
        val out = buildList {
            response.text("stdout")?.takeIf { it.isNotBlank() }?.lines()?.forEach { add(astr(it, S.text)) }
            response.text("stderr")?.takeIf { it.isNotBlank() }?.lines()?.forEach { add(astr(it, S.danger)) }
            if (isEmpty()) add(astr("(no output)", S.faint))
        }
        showOutput("$ $command", out)
    }

    private fun syncClient() {
        val targets = selectedOrCurrentDevices()
        for (device in targets) {
            val host = device.sshHostText()
                ?: throw IllegalArgumentException("${deviceLabel(device)} has no reachable SSH host")
            log("Syncing modem client on ${deviceLabel(device)} at $host")
            val result = ModemClientSync(ModemSshCredentials(host = host), onProgress = ::log).sync()
            log("${deviceLabel(device)} synced; service ${result.serviceStatus}")
        }
        status = "Client synced on ${targets.size} unit(s)"
        pause()
    }

    private fun installClient() {
        val host = prompt("Device host/IP", "").trim()
        require(host.isNotBlank()) { "host/IP is required" }
        val user = prompt("SSH user", "root").trim().ifBlank { "root" }
        val password = prompt("SSH password", "root")
        val port = prompt("SSH port", "22").trim().toInt()
        val result = ModemClientSync(
            ModemSshCredentials(host = host, username = user, password = password, port = port),
            onProgress = ::log,
        ).sync()
        status = "Client installed on ${result.host}; service ${result.serviceStatus}"
        log(status)
        pause()
    }

    private fun bootLinux() {
        val targets = selectedOrCurrentTargets()
        ensurePacketCaptureAccess(TransportMode.L2)
        for ((device, target) in targets) {
            val response = requireReply(
                commandClient.bootLinux(target, commandOptions().copy(transportMode = TransportMode.L2)),
                "boot Linux on ${device.displayNameText()}",
            )
            log("${deviceLabel(device)} boot Linux accepted via ${response.sourceIp}")
        }
        status = "Boot Linux sent to ${targets.size} unit(s)"
        pause()
    }

    private fun manufacturingTest() {
        val device = requireCurrentDevice()
        ensurePacketCaptureAccess(TransportMode.L2)
        val response = requireReply(
            commandClient.runManufacturingTest(targetFor(device), commandOptions().copy(transportMode = TransportMode.L2)),
            "manufacturing test",
        )
        val result = response.text("result")?.uppercase() ?: "UNKNOWN"
        status = "Manufacturing test ${response.text("result") ?: "unknown"}"
        log(status)
        val out = buildList {
            val style = if (result == "PASS") S.ok else S.danger
            add(astr("Result: $result", style.bold()))
            response.text("output")?.takeIf { it.isNotBlank() }?.lines()?.forEach { add(astr(it, S.text)) }
            if (response.text("output_truncated") == "1") {
                add(astr("WARNING: output was truncated by the U-Boot reply.", S.warn))
            }
        }
        showOutput("Manufacturing test", out)
    }

    private fun flashSelected() {
        val selected = selectedOrCurrentDevices()
        require(selected.isNotEmpty()) { "select at least one device" }
        val startDir = File(System.getProperty("user.dir"))
        val image = pickFile(
            title = "Select WIC image",
            start = startDir,
            allowNone = false,
            highlight = { it.endsWith(".lz4") || it.endsWith(".wic") || it.contains(".wic") },
        ) ?: run {
            status = "Flash canceled"
            log(status)
            return
        }
        require(image.isFile) { "image not found: $image" }
        val defaultBmap = FlashWorkflow.defaultBmapFor(image)
        val bmap = pickFile(
            title = "Select bmap (skip = full image)",
            start = image.parentFile ?: startDir,
            allowNone = true,
            preselect = defaultBmap.takeIf { it.isFile },
            highlight = { it.endsWith(".bmap") },
        )
        val fullImage = bmap == null
        val bootloader = pickFile(
            title = "Select imx-boot (optional)",
            start = image.parentFile ?: startDir,
            allowNone = true,
            highlight = { it.contains("imx-boot") || it.endsWith(".bin") },
        )
        val preserveSshKeys = confirm("Preserve /root/.ssh keys", default = false)
        val jobsDefault = min(2, selected.size).coerceAtLeast(1)
        val jobs = prompt("Parallel jobs", jobsDefault.toString()).trim().toInt()
        val requests = selected.map { device ->
            val target = FlashWorkflow.targetFor(device)
                ?: throw IllegalArgumentException("${deviceLabel(device)} has no CPU UID/device ID")
            val iface = FlashWorkflow.bestInterfaceFor(device, interfaceName)
                ?: throw IllegalArgumentException("${deviceLabel(device)} has no Ethernet interface for AoE")
            FlashRequest(
                initialDevice = device,
                target = target,
                interfaceName = iface,
                aoeTarget = AoETargetAddress.forDevice(device),
                image = image,
                bmap = bmap,
                mode = if (fullImage) FlashMode.FULL_IMAGE else FlashMode.BMAP,
                bootloaderImage = bootloader,
                secret = secret,
                preserveSshKeys = preserveSshKeys,
            )
        }

        val summary = buildList {
            add(astr("About to flash ${requests.size} unit(s):", S.warn.bold()))
            for (request in requests) {
                add(astr("  ${request.target.label}  ${request.interfaceName} → ${request.aoeTarget.label}", S.text))
            }
            add(astr("Image:  ${image.absolutePath}", S.muted))
            add(astr("Mode:   ${if (fullImage) "full image" else "bmap payload ${bmap?.absolutePath}"}", S.muted))
            add(astr("U-Boot: ${bootloader?.absolutePath ?: "disabled"}", S.muted))
        }
        if (!confirmBox("Start destructive flash?", summary, default = false)) {
            status = "Flash canceled"
            log(status)
            return
        }
        synchronized(stateLock) {
            flashProgress.clear()
            requests.forEach { request ->
                flashProgress[request.target.label] = FlashLineState(
                    label = request.target.label,
                    message = "Queued",
                )
            }
        }
        renderFlashProgress(requests)

        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<List<Device>> {
            BatchFlashWorkflow(
                requests,
                onEvent = { event ->
                    val text = "[${event.request.target.label}] ${event.event.message}"
                    synchronized(stateLock) {
                        updateFlashProgressLocked(event)
                        status = text
                    }
                    log(text)
                },
                maxConcurrency = jobs,
            ).run()
        }
        val rediscovered = try {
            while (!future.isDone) {
                renderFlashProgress(requests)
                Thread.sleep(100)
            }
            future.get()
        } finally {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
        devices = annotateDiscoveredDevices(rediscovered)
        selectedKeys.clear()
        status = "Flash complete; rediscovered ${rediscovered.size} unit(s)"
        log(status)
        pause()
    }

    private fun updateFlashProgressLocked(event: BatchFlashEvent) {
        val state = flashProgress.getOrPut(event.request.target.label) {
            FlashLineState(label = event.request.target.label)
        }
        state.message = event.event.message
        state.phase = event.event.phase
        if (event.event.totalBytes > 0) {
            state.doneBytes = event.event.doneBytes
            state.totalBytes = event.event.totalBytes
        }
    }

    private fun editOptions() {
        log("Candidate interfaces: ${RawEthernetTransport.candidateInterfaces().joinToString().ifBlank { "none" }}")
        interfaceName = prompt("Interface, blank for auto", interfaceName.orEmpty()).trim().ifBlank { null }
        transportMode = TransportMode.parse(prompt("Transport auto/udp/l2/all", transportMode.name.lowercase()).trim())
        timeoutSeconds = prompt("Discovery timeout seconds", timeoutSeconds.toString()).trim().toDouble()
        retries = prompt("Discovery retries", retries.toString()).trim().toInt().coerceAtLeast(1)
        status = "Options updated"
        log("Options: transport=${transportMode.name.lowercase()} interface=${interfaceName ?: "auto"} timeout=$timeoutSeconds retries=$retries")
    }

    // -------------------------------------------------------------- selection

    private fun selectedOrCurrentDevices(): List<Device> {
        val byKey = devices.associateBy { selectionKey(it) }
        return selectedKeys.mapNotNull(byKey::get).ifEmpty { listOf(requireCurrentDevice()) }
    }

    private fun selectedOrCurrentTargets(): List<Pair<Device, TargetSelector>> =
        selectedOrCurrentDevices().map { it to targetFor(it) }

    private fun requireCurrentDevice(): Device {
        if (devices.isEmpty()) {
            throw IllegalArgumentException("no devices discovered")
        }
        return devices[cursor.coerceIn(0, devices.lastIndex)]
    }

    private fun targetFor(device: Device): TargetSelector {
        return FlashWorkflow.targetFor(device)
            ?: throw IllegalArgumentException("${deviceLabel(device)} has no usable CPU UID/device ID")
    }

    private fun commandOptions(): CommandOptions =
        CommandOptions(
            timeoutSeconds = timeoutSeconds,
            secret = secret,
            interfaces = interfaceName?.let(::listOf).orEmpty(),
            transportMode = transportMode,
        )

    private fun ensurePacketCaptureAccess(mode: TransportMode) {
        if (MacBpfAccess.needsSetupFor(mode)) {
            log("Enabling macOS packet capture access")
            val result = MacBpfAccess.install()
            if (!result.success) {
                throw RuntimeException("macOS L2 capture setup failed: ${result.output.ifBlank { result.exitCode.toString() }}")
            }
        }
        if (WindowsPacketAccess.needsSetupFor(mode)) {
            log("Enabling Windows raw Ethernet access")
            val result = WindowsPacketAccess.install()
            if (!result.success) {
                throw RuntimeException("Windows L2 setup failed: ${result.output.ifBlank { result.exitCode.toString() }}")
            }
            if (result.rebootRequired) {
                log("Windows raw Ethernet setup requires a reboot before L2 works")
            }
        }
    }

    private fun requireReply(response: CommandResponse?, action: String): CommandResponse {
        if (response == null) {
            throw RuntimeException("$action: no reply")
        }
        if (response.text("status") != "ok") {
            throw RuntimeException("$action: ${response.text("error") ?: response.text("stderr") ?: "failed"}")
        }
        return response
    }

    private fun moveCursor(delta: Int) {
        if (devices.isEmpty()) return
        cursor = (cursor + delta).coerceIn(0, devices.lastIndex)
    }

    private fun jumpTo(index: Int) {
        if (devices.isEmpty()) return
        cursor = index.coerceIn(0, devices.lastIndex)
        status = "Selected row ${cursor + 1}"
    }

    private fun toggleSelected() {
        val device = requireCurrentDevice()
        val key = selectionKey(device) ?: return
        if (!selectedKeys.add(key)) {
            selectedKeys.remove(key)
        }
        status = "${selectedKeys.size} selected"
    }

    private fun selectAll() {
        selectedKeys = devices.mapNotNull(::selectionKey).toMutableSet()
        status = "${selectedKeys.size} selected"
    }

    // ------------------------------------------------------ full-screen render

    private fun render() {
        val term = terminal ?: return
        val disp = display ?: return
        val size = term.size
        val width = size.columns.coerceAtLeast(60)
        val height = size.rows.coerceAtLeast(20)
        disp.resize(height, width)
        disp.update(buildScreen(width, height), -1)
    }

    private fun buildScreen(width: Int, height: Int): List<AttributedString> {
        val lines = ArrayList<AttributedString>(height)

        // --- header -------------------------------------------------------
        lines += headerLine(width)
        lines += rule(width, "├", "┤", null)
        lines += boxRow(width, statusLine(width))
        lines += boxRow(width, chipLine())

        // --- compute pane heights ----------------------------------------
        // Fixed rows used outside the two flexible panes:
        //   header(1) hRule(1) status(1) chips(1)
        //   devRule(1) curRule(1) current(3) actRule(1) keys(1) bottom(1)
        val fixed = 12
        val flexible = (height - fixed).coerceAtLeast(4)
        val devRows = (flexible * 3 / 5).coerceAtLeast(2)
        val actRows = (flexible - devRows).coerceAtLeast(1)

        // --- discovered units --------------------------------------------
        ensureCursorVisible(devRows)
        val moreAbove = listOffset > 0
        val moreBelow = listOffset + devRows < devices.size
        val devTitle = "Discovered Units" +
            if (devices.isNotEmpty()) "  ${cursor + 1}/${devices.size}" else ""
        lines += rule(width, "├", "┤", devTitle, scroll = Pair(moreAbove, moreBelow))
        lines += deviceRows(width, devRows)

        // --- current unit ------------------------------------------------
        lines += rule(width, "├", "┤", "Current Unit")
        currentDeviceRows().take(3).let { rows ->
            rows.forEach { lines += boxRow(width, it) }
            repeat(3 - rows.size) { lines += boxRow(width, AttributedString.EMPTY) }
        }

        // --- activity ----------------------------------------------------
        lines += rule(width, "├", "┤", "Activity")
        val recent = recentLogLines(actRows)
        for (i in 0 until actRows) {
            val entry = recent.getOrNull(recent.size - actRows + i)
            lines += if (entry == null) boxRow(width, AttributedString.EMPTY) else boxRow(width, colorLogLine(entry))
        }

        // --- key bar + frame ---------------------------------------------
        lines += boxRow(width, keyBar())
        lines += rule(width, "╰", "╯", null)

        // Pad/trim to exact height so Display paints a clean full screen.
        while (lines.size < height) lines += AttributedString.EMPTY
        return lines.take(height)
    }

    private fun headerLine(width: Int): AttributedString {
        val b = AttributedStringBuilder()
        b.style(S.border).append("╭─ ")
        b.style(S.brand).append("◆ Popoto")
        b.style(S.accent.bold()).append(" Discover")
        b.style(S.muted).append("  terminal control surface")
        val leftLen = b.toAttributedString().columnLength()
        val build = AppBuild.version
        // total = leftLen + 1(sp) + fill + 1(sp) + build + 2(" ╮") == width
        val fill = (width - leftLen - build.length - 4).coerceAtLeast(1)
        b.style(S.border).append(" ").append("─".repeat(fill)).append(" ")
        b.style(S.faint).append(build)
        b.style(S.border).append(" ╮")
        return b.toAttributedString()
    }

    private fun statusLine(width: Int): AttributedString {
        val b = AttributedStringBuilder()
        val (label, style) = statusBadge()
        b.style(style).append(label)
        b.style(S.text).append("  ").append(status)
        return b.toAttributedString()
    }

    private fun chipLine(): AttributedString {
        val b = AttributedStringBuilder()
        fun chip(name: String, value: String) {
            b.style(S.chipName).append(" $name ")
            b.style(S.chipValue).append(" $value ")
            b.style(AttributedStyle.DEFAULT).append(" ")
        }
        chip("transport", transportMode.name.lowercase())
        chip("interface", interfaceName ?: "auto")
        chip("timeout", "${timeoutSeconds}s")
        chip("retries", retries.toString())
        chip("selected", selectedKeys.size.toString())
        return b.toAttributedString()
    }

    private fun ensureCursorVisible(rows: Int) {
        if (cursor < listOffset) listOffset = cursor
        if (cursor >= listOffset + rows) listOffset = cursor - rows + 1
        listOffset = listOffset.coerceIn(0, (devices.size - rows).coerceAtLeast(0))
    }

    private fun deviceRows(width: Int, rows: Int): List<AttributedString> {
        if (devices.isEmpty()) {
            val empty = ArrayList<AttributedString>()
            empty += boxRow(width, astr("No devices discovered — press r to scan again.", S.faint))
            repeat(rows - 1) { empty += boxRow(width, AttributedString.EMPTY) }
            return empty
        }
        val header = "    #  S  STATE    NAME                          ID                IP              VIA"
        val out = ArrayList<AttributedString>(rows + 1)
        out += boxRow(width, astr(header, S.muted))
        for (i in 0 until rows - 1) {
            val index = listOffset + i
            val device = devices.getOrNull(index)
            if (device == null) {
                out += boxRow(width, AttributedString.EMPTY)
                continue
            }
            val key = selectionKey(device)
            val isCurrent = index == cursor
            val isSelected = key != null && key in selectedKeys
            val body = "%2d  %s  %-7s  %-28s  %-16s  %-14s  %s".format(
                index + 1,
                if (isSelected) "✓" else "·",
                deviceStateText(device),
                truncate(device.displayNameText(), 28),
                truncate(device.deviceIdText() ?: "unknown", 16),
                truncate(device.sshHostText() ?: device.text("ip") ?: "unknown", 14),
                truncate(transportText(device), 22),
            )
            val style = when {
                isCurrent -> S.rowCurrent
                isSelected -> S.ok
                device.text("uboot") == "1" -> S.warn
                else -> S.text
            }
            val b = AttributedStringBuilder()
            if (isCurrent) b.style(S.brand2.bold()).append(" ▸ ") else b.style(S.faint).append("   ")
            b.style(style).append(body)
            out += boxRow(width, b.toAttributedString())
        }
        return out
    }

    private fun currentDeviceRows(): List<AttributedString> {
        val device = devices.getOrNull(cursor) ?: return listOf(astr("No unit selected.", S.faint))
        val aoe = AoETargetAddress.forDevice(device)
        fun row(vararg pairs: Pair<String, String>): AttributedString {
            val b = AttributedStringBuilder()
            pairs.forEachIndexed { i, (k, v) ->
                if (i > 0) b.style(AttributedStyle.DEFAULT).append("   ")
                b.style(S.muted).append("$k ")
                b.style(S.text.bold()).append(v)
            }
            return b.toAttributedString()
        }
        return listOf(
            row(
                "name" to device.displayNameText(),
                "model" to (device.text("model") ?: "unknown"),
                "state" to deviceStateText(device),
                "aoe" to aoe.label,
            ),
            row(
                "cpu" to (device.text("cpu_uid") ?: "unknown"),
                "serial" to device.serialText(),
                "mac" to device.displayMacText(),
            ),
            row(
                "ip" to (device.sshHostText() ?: device.text("ip") ?: "unknown"),
                "storage" to "${device.text("storage_free_gb") ?: "?"}/${device.text("storage_total_gb") ?: "?"} GB",
                "battery" to batteryText(device),
                "via" to transportText(device),
            ),
        )
    }

    private fun keyBar(): AttributedString {
        val commands = listOf(
            "↑↓" to "move", "Space" to "select", "⏎" to "actions", "r" to "scan",
            "f" to "flash", "a" to "all", "c" to "clear", "o" to "options", "?" to "help", "q" to "quit",
        )
        val b = AttributedStringBuilder()
        commands.forEachIndexed { i, (k, v) ->
            if (i > 0) b.style(S.faint).append(" · ")
            b.style(S.brand2.bold()).append(k)
            b.style(S.muted).append(" $v")
        }
        return b.toAttributedString()
    }

    // ----------------------------------------------------------- flash screen

    private fun renderFlashProgress(requests: List<FlashRequest>) {
        val disp = display
        val term = terminal
        if (disp == null || term == null) return
        val size = term.size
        val width = size.columns.coerceAtLeast(60)
        val height = size.rows.coerceAtLeast(20)

        val lines = ArrayList<AttributedString>(height)
        val head = AttributedStringBuilder()
        head.style(S.border).append("╭─ ")
        head.style(S.brand).append("◆ Popoto").style(S.accent.bold()).append(" Discover")
        head.style(S.warn).append("   ▸ flash workflow")
        lines += closeFrame(head, width)
        lines += rule(width, "├", "┤", null)
        val (badge, style) = statusBadge()
        val st = AttributedStringBuilder().style(style).append(badge).style(S.text).append("  ").append(status)
        lines += boxRow(width, st.toAttributedString())

        lines += rule(width, "├", "┤", "Targets")
        for (request in requests) {
            val state = synchronized(stateLock) {
                flashProgress[request.target.label]?.copy() ?: FlashLineState(request.target.label)
            }
            val percent = progressPercent(state)
            val barWidth = (width - 48).coerceIn(20, 64)
            val b = AttributedStringBuilder()
            b.style(S.text.bold()).append(truncate(state.label, 16).padEnd(16))
            b.append(" ")
            appendProgressBar(b, state.doneBytes, state.totalBytes, barWidth)
            b.append(" ")
            when {
                percent == null -> b.style(S.faint).append(" --%")
                percent >= 100 -> b.style(S.ok.bold()).append("%3d%%".format(percent))
                else -> b.style(S.brand2).append("%3d%%".format(percent))
            }
            b.style(S.muted).append("  ").append(truncate(state.message, (width - barWidth - 30).coerceAtLeast(16)))
            lines += boxRow(width, b.toAttributedString())
        }

        lines += rule(width, "├", "┤", "Latest Activity")
        val actRows = (height - lines.size - 1).coerceAtLeast(1)
        val recent = recentLogLines(actRows)
        for (i in 0 until actRows) {
            val entry = recent.getOrNull(recent.size - actRows + i)
            lines += if (entry == null) boxRow(width, AttributedString.EMPTY) else boxRow(width, colorLogLine(entry))
        }
        lines += rule(width, "╰", "╯", null)
        while (lines.size < height) lines += AttributedString.EMPTY
        disp.resize(height, width)
        disp.update(lines.take(height), -1)
    }

    // ---------------------------------------------------------------- modals

    private fun showHelp() {
        val rows = listOf(
            "r" to "discover again",
            "↑↓ / j k" to "move current row",
            "Enter" to "open actions for the current unit",
            "Space / x" to "select devices for batch actions",
            "a / c" to "select all / clear selection",
            "1-9" to "jump to row",
            "f" to "guided WIC flash (sibling .wic.bmap by default)",
            "v" to "read version from current device",
            "t" to "set selected/current RTC to host clock",
            "s" to "sync bundled Discover client to Linux device",
            "b" to "tell selected/current U-Boot AoE device to boot Linux",
            "o" to "edit transport/interface/timeout",
            "q" to "back/quit",
        )
        val body = rows.map { (k, v) ->
            AttributedStringBuilder()
                .style(S.brand2.bold()).append(k.padEnd(12))
                .style(S.muted).append(v)
                .toAttributedString()
        }
        showOutput("Keyboard Shortcuts", body)
    }

    private fun chooseMenu(title: String, items: List<MenuItem>): Int? {
        if (!keyMode) {
            // Plain fallback for command mode.
            items.forEachIndexed { i, item -> println("${i + 1}. ${item.label} (${item.shortcut})") }
            val choice = prompt("Choose", "").trim().toIntOrNull() ?: return null
            return (choice - 1).takeIf { it in items.indices }
        }
        var selected = 0
        while (true) {
            val body = items.mapIndexed { index, item ->
                val active = index == selected
                val b = AttributedStringBuilder()
                if (active) b.style(S.brand2.bold()).append("▸ ") else b.style(S.faint).append("  ")
                b.style(if (active) S.text.bold() else S.text).append(item.label.padEnd(30))
                b.style(S.faint).append(item.shortcut)
                b.toAttributedString()
            }
            renderModal(title, body, "↑↓ move · ⏎ select · Esc back")
            when (readKey()) {
                TuiKey.UP -> selected = (selected - 1).coerceAtLeast(0)
                TuiKey.DOWN -> selected = (selected + 1).coerceAtMost(items.lastIndex)
                TuiKey.ENTER -> return selected
                TuiKey.BACK, TuiKey.QUIT -> return null
                else -> Unit
            }
        }
    }

    private fun showOutput(title: String, body: List<AttributedString>) {
        if (!keyMode) {
            println("== $title ==")
            body.forEach { println(it.toString()) }
            pause()
            return
        }
        renderModal(title, body, "any key to dismiss")
        readKey()
    }

    private fun showModal(title: String, body: List<AttributedString>, footer: String) {
        if (!keyMode) {
            println("== $title ==")
            body.forEach { println(it.toString()) }
            return
        }
        renderModal(title, body, footer)
        readKey()
    }

    /** Draws a centred dialog box over the (dimmed) main screen. */
    private fun renderModal(title: String, body: List<AttributedString>, footer: String) {
        val term = terminal ?: return
        val disp = display ?: return
        val size = term.size
        val width = size.columns.coerceAtLeast(60)
        val height = size.rows.coerceAtLeast(20)
        val base = dim(buildScreen(width, height))

        val maxBody = (height - 6).coerceAtLeast(1)
        val shown = if (body.size > maxBody) body.takeLast(maxBody) else body
        val contentWidth = (shown.map { it.columnLength() } + listOf(title.length, footer.length))
            .max().coerceIn(20, width - 8)
        val safeTitle = if (title.length > contentWidth) "…" + title.takeLast(contentWidth - 1) else title
        val boxW = contentWidth + 4
        val boxH = shown.size + 4
        val top = ((height - boxH) / 2).coerceAtLeast(0)
        val left = ((width - boxW) / 2).coerceAtLeast(0)

        val boxLines = ArrayList<AttributedString>(boxH)
        boxLines += modalRule(boxW, "╭", "╮", safeTitle)
        boxLines += modalRow(boxW, AttributedString.EMPTY)
        shown.forEach { boxLines += modalRow(boxW, it) }
        boxLines += modalRow(boxW, astr(footer, S.faint), footerRow = true)
        boxLines += modalRule(boxW, "╰", "╯", null)

        val merged = base.toMutableList()
        boxLines.forEachIndexed { i, boxLine ->
            val r = top + i
            if (r in merged.indices) merged[r] = spliceLine(merged[r], boxLine, left, width)
        }
        disp.resize(height, width)
        disp.update(merged, -1)
    }

    // ----------------------------------------------------------- input / keys

    private fun prompt(label: String, default: String): String {
        if (!keyMode) {
            print(if (default.isBlank()) "$label: " else "$label [$default]: ")
            val value = readLine() ?: run {
                endOfInput = true
                return default
            }
            return value.ifBlank { default }
        }
        return inputModal(label, default)
    }

    private fun inputModal(label: String, default: String): String {
        val reader = terminal?.reader() ?: return default
        val buf = StringBuilder()
        while (true) {
            val field = AttributedStringBuilder()
            field.style(S.text).append(if (buf.isEmpty()) default else buf.toString())
            field.style(S.brand2).append("▏")
            val hint = if (default.isBlank()) "" else "default: $default"
            val body = buildList {
                add(field.toAttributedString())
                if (hint.isNotEmpty()) add(astr(hint, S.faint))
            }
            renderModal(label, body, "⏎ accept · Esc cancel")
            when (val c = reader.read()) {
                -1, 3 -> {
                    endOfInput = c == -1
                    return buf.toString().ifBlank { default }
                }
                10, 13 -> return buf.toString().ifBlank { default }
                27 -> {
                    // swallow a possible arrow sequence, treat bare Esc as cancel
                    reader.read(20L).let { if (it == '['.code) reader.read(20L) }
                    return default
                }
                8, 127 -> if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
                in 32..126 -> buf.append(c.toChar())
                else -> Unit
            }
        }
    }

    /**
     * Interactive, arrow-key file browser. Returns the chosen file, or null if
     * the user cancels (Esc) or picks the "none" entry. Falls back to a plain
     * text prompt when not running on a console.
     */
    private fun pickFile(
        title: String,
        start: File,
        allowNone: Boolean,
        preselect: File? = null,
        highlight: (String) -> Boolean = { false },
    ): File? {
        if (!keyMode) {
            val def = preselect?.absolutePath ?: ""
            val typed = prompt(title, def).trim()
            return typed.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }
        }
        val reader = terminal?.reader() ?: return null
        var dir = (if (start.isDirectory) start else start.parentFile ?: File(System.getProperty("user.dir"))).absoluteFile
        val filter = StringBuilder()
        var sel = 0
        var pendingPreselect = preselect

        while (true) {
            val entries = listEntries(dir, allowNone, filter.toString(), highlight)
            if (pendingPreselect != null) {
                val idx = entries.indexOfFirst { it.file?.absolutePath == pendingPreselect?.absolutePath }
                if (idx >= 0) sel = idx
                pendingPreselect = null
            }
            sel = sel.coerceIn(0, (entries.size - 1).coerceAtLeast(0))

            val height = (terminal?.size?.rows ?: 24).coerceAtLeast(20)
            val visible = (height - 10).coerceIn(4, 28)
            val offset = (if (sel < visible) 0 else sel - visible + 1)
                .coerceIn(0, (entries.size - visible).coerceAtLeast(0))

            val body = ArrayList<AttributedString>()
            body += astr(compactPath(dir, 60), S.muted)
            if (filter.isNotEmpty()) {
                body += AttributedStringBuilder()
                    .style(S.faint).append("filter: ")
                    .style(S.brand2.bold()).append(filter.toString())
                    .toAttributedString()
            }
            if (entries.isEmpty()) {
                body += astr("(no matching entries)", S.faint)
            } else {
                for (i in offset until min(offset + visible, entries.size)) {
                    body += entryLine(entries[i], i == sel)
                }
                if (entries.size > visible) body += astr("  ${sel + 1} / ${entries.size}", S.faint)
            }
            renderModal(title, body, "↑↓ move · ⏎ open/select · ← up dir · type to filter · Esc cancel")

            when (val c = reader.read()) {
                -1 -> {
                    endOfInput = true
                    return null
                }
                3 -> return null
                27 -> {
                    if (reader.read(20L) != '['.code) return null // bare Esc cancels
                    when (reader.read(20L)) {
                        'A'.code -> sel--
                        'B'.code -> sel++
                        'C'.code -> entries.getOrNull(sel)?.takeIf { it.kind == EntryKind.DIR }?.let {
                            dir = it.file!!; sel = 0; filter.clear()
                        }
                        'D'.code -> { dir = dir.parentFile ?: dir; sel = 0; filter.clear() }
                        else -> Unit
                    }
                }
                10, 13 -> {
                    val entry = entries.getOrNull(sel) ?: continue
                    when (entry.kind) {
                        EntryKind.NONE -> return null
                        EntryKind.PARENT -> { dir = dir.parentFile ?: dir; sel = 0; filter.clear() }
                        EntryKind.DIR -> { dir = entry.file!!; sel = 0; filter.clear() }
                        EntryKind.FILE -> return entry.file!!.absoluteFile
                    }
                }
                8, 127 -> {
                    if (filter.isNotEmpty()) filter.deleteCharAt(filter.length - 1) else dir = dir.parentFile ?: dir
                    sel = 0
                }
                in 32..126 -> { filter.append(c.toChar()); sel = 0 }
                else -> Unit
            }
        }
    }

    private fun listEntries(
        dir: File,
        allowNone: Boolean,
        filter: String,
        highlight: (String) -> Boolean,
    ): List<FsEntry> {
        val out = ArrayList<FsEntry>()
        if (allowNone) out += FsEntry(null, EntryKind.NONE, "‹ none / skip ›", false)
        if (dir.parentFile != null) out += FsEntry(dir.parentFile, EntryKind.PARENT, "..", false)
        val children = dir.listFiles()?.toList().orEmpty()
            .filter { !it.isHidden || filter.startsWith(".") }
            .filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
        val (dirs, files) = children.partition { it.isDirectory }
        dirs.sortedBy { it.name.lowercase() }.forEach {
            out += FsEntry(it, EntryKind.DIR, it.name, false)
        }
        files.sortedBy { it.name.lowercase() }.forEach {
            out += FsEntry(it, EntryKind.FILE, it.name, highlight(it.name))
        }
        return out
    }

    private fun entryLine(entry: FsEntry, current: Boolean): AttributedString {
        val b = AttributedStringBuilder()
        b.style(if (current) S.accent.bold() else S.faint).append(if (current) "▸ " else "  ")
        when (entry.kind) {
            EntryKind.NONE -> b.style(if (current) S.text.bold() else S.faint).append(entry.label)
            EntryKind.PARENT -> b.style((if (current) S.title else S.brand2)).append("⮬  ..")
            EntryKind.DIR -> b.style((if (current) S.brand else S.brand2).bold()).append("${entry.label}/")
            EntryKind.FILE -> {
                val style = when {
                    entry.highlighted && current -> S.ok.bold()
                    entry.highlighted -> S.ok
                    current -> S.rowCurrent
                    else -> S.text
                }
                b.style(style).append(entry.label)
                entry.file?.length()?.let { b.style(S.faint).append("   ${humanSize(it)}") }
            }
        }
        return b.toAttributedString()
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    private fun compactPath(dir: File, max: Int): String {
        val path = dir.absolutePath
        if (path.length <= max) return path
        return "…" + path.takeLast(max - 1)
    }

    private fun confirm(label: String, default: Boolean): Boolean = confirmBox(label, emptyList(), default)

    private fun confirmBox(label: String, body: List<AttributedString>, default: Boolean): Boolean {
        if (!keyMode) {
            val suffix = if (default) "Y/n" else "y/N"
            body.forEach { println(it.toString()) }
            val answer = prompt("$label $suffix", "").trim().lowercase()
            if (answer.isBlank()) return default
            return answer in setOf("y", "yes", "true", "1")
        }
        val reader = terminal?.reader() ?: return default
        val footer = if (default) "Y/n · ⏎ yes · Esc no" else "y/N · ⏎ no · Esc cancel"
        while (true) {
            renderModal(label, body.ifEmpty { listOf(astr("Confirm this action?", S.text)) }, footer)
            when (val c = reader.read()) {
                'y'.code, 'Y'.code -> return true
                'n'.code, 'N'.code -> return false
                10, 13 -> return default
                27, 3, -1 -> return false
                else -> Unit
            }
        }
    }

    private fun pause() {
        if (!keyMode) {
            print("Press Enter to continue...")
            readLine()
            return
        }
        showModal("Done", listOf(astr(status, S.text)), "any key to continue")
    }

    private fun readKey(): TuiKey {
        val reader = terminal?.reader() ?: return TuiKey.UNKNOWN
        return when (val code = reader.read()) {
            -1, 3 -> TuiKey.QUIT
            10, 13 -> TuiKey.ENTER
            27 -> readEscapeSequence()
            'q'.code, 'Q'.code -> TuiKey.QUIT
            'j'.code, 'J'.code -> TuiKey.DOWN
            'k'.code, 'K'.code -> TuiKey.UP
            ' '.code, 'x'.code, 'X'.code -> TuiKey.SELECT
            'r'.code, 'R'.code -> TuiKey.REFRESH
            'f'.code, 'F'.code -> TuiKey.FLASH
            'o'.code, 'O'.code -> TuiKey.OPTIONS
            '?'.code, 'h'.code, 'H'.code -> TuiKey.HELP
            'a'.code, 'A'.code -> TuiKey.ALL
            'c'.code, 'C'.code -> TuiKey.NONE
            'v'.code, 'V'.code -> TuiKey.VERSION
            't'.code, 'T'.code -> TuiKey.RTC
            's'.code, 'S'.code -> TuiKey.SYNC
            'i'.code, 'I'.code -> TuiKey.INSTALL
            'b'.code, 'B'.code -> TuiKey.BOOT
            in '1'.code..'9'.code -> {
                jumpTo(code - '1'.code)
                TuiKey.UNKNOWN
            }
            else -> TuiKey.UNKNOWN
        }
    }

    private fun readEscapeSequence(): TuiKey {
        val reader = terminal?.reader() ?: return TuiKey.BACK
        val second = reader.read(35L)
        if (second != '['.code) {
            return TuiKey.BACK
        }
        return when (reader.read(35L)) {
            'A'.code -> TuiKey.UP
            'B'.code -> TuiKey.DOWN
            else -> TuiKey.UNKNOWN
        }
    }

    // -------------------------------------------------------- plain (no tty)

    private fun renderPlain() {
        println()
        println("== Popoto Discover ${AppBuild.version} ==")
        println("status: $status")
        if (devices.isEmpty()) {
            println("No devices discovered.")
        } else {
            devices.forEachIndexed { index, device ->
                val mark = if (index == cursor) ">" else " "
                val sel = if (selectionKey(device) in selectedKeys) "*" else " "
                println(
                    "$mark%2d [%s] %-7s %-24s %s".format(
                        index + 1, sel, deviceStateText(device),
                        device.displayNameText(), device.sshHostText() ?: device.text("ip") ?: "unknown",
                    ),
                )
            }
        }
        println("Commands: r refresh, j/k move, x select, f flash, ? help, q quit")
    }

    // --------------------------------------------------------------- styling

    private fun statusBadge(): Pair<String, AttributedStyle> = when {
        status.startsWith("ERROR", ignoreCase = true) -> " ✕ ERROR " to S.badgeDanger
        status.contains("complete", ignoreCase = true) -> " ✓ OK " to S.badgeOk
        status.contains("discover", ignoreCase = true) -> " ⟳ DISCOVER " to S.badgeInfo
        status.contains("flash", ignoreCase = true) -> " ▸ FLASH " to S.badgeWarn
        else -> " ● READY " to S.badgeReady
    }

    private fun deviceStateText(device: Device): String = when {
        device.text("uboot") == "1" && device.text("aoe_active") == "1" -> "AOE"
        device.text("uboot") == "1" -> "U-BOOT"
        else -> "LINUX"
    }

    private fun transportText(device: Device): String {
        return device.paths.joinToString(",") { path ->
            path.transport.uppercase() + (path.interfaceName?.let { "@$it" } ?: "")
        }.ifBlank { "unknown" }
    }

    private fun batteryText(device: Device): String {
        val raw = device.text("battery_v") ?: return "?"
        val voltage = raw.toDoubleOrNull() ?: return raw
        return if (voltage > 1000.0) "$raw?" else "%.2f V".format(voltage)
    }

    private fun colorLogLine(line: String): AttributedString {
        val stamp = Regex("^(\\[[0-9:]+\\])\\s*").find(line)
        val prefix = stamp?.groupValues?.get(1) ?: ""
        val rest = stamp?.let { line.substring(it.range.last + 1) } ?: line
        val style = when {
            rest.contains("ERROR", ignoreCase = true) -> S.danger
            rest.contains("WARNING", ignoreCase = true) -> S.warn
            rest.contains("complete", ignoreCase = true) -> S.ok
            rest.contains("write:", ignoreCase = true) -> S.brand2
            else -> S.muted
        }
        val b = AttributedStringBuilder()
        if (prefix.isNotEmpty()) b.style(S.faint).append("$prefix ")
        b.style(style).append(rest)
        return b.toAttributedString()
    }

    private fun progressPercent(state: FlashLineState): Int? {
        if (state.totalBytes <= 0L) return null
        return ((state.doneBytes * 100.0) / state.totalBytes).toInt().coerceIn(0, 100)
    }

    private fun appendProgressBar(b: AttributedStringBuilder, done: Long, total: Long, width: Int) {
        b.style(S.faint).append("╶")
        if (total <= 0L) {
            b.style(S.faint).append("░".repeat(width))
            b.style(S.faint).append("╴")
            return
        }
        val ratio = (done.toDouble() / total).coerceIn(0.0, 1.0)
        val exact = ratio * width
        val full = exact.toInt().coerceIn(0, width)
        val eighths = arrayOf("", "▏", "▎", "▍", "▌", "▋", "▊", "▉")
        val partial = if (full < width) eighths[((exact - full) * 8).toInt().coerceIn(0, 7)] else ""
        val emptyCount = (width - full - if (partial.isNotEmpty()) 1 else 0).coerceAtLeast(0)
        val color = when {
            ratio >= 1.0 -> S.ok
            ratio >= 0.66 -> S.brand2
            else -> S.brand
        }
        b.style(color).append("█".repeat(full)).append(partial)
        b.style(S.faint).append("░".repeat(emptyCount)).append("╴")
    }

    // --------------------------------------------------- box / frame helpers

    private fun astr(text: String, style: AttributedStyle): AttributedString = AttributedString(text, style)

    /** A framed content row: `│ content… │` padded to [width]. */
    private fun boxRow(width: Int, content: AttributedString): AttributedString {
        val inner = width - 4
        val b = AttributedStringBuilder()
        b.style(S.border).append("│ ")
        b.append(fit(content, inner))
        b.style(S.border).append(" │")
        return b.toAttributedString()
    }

    private fun rule(
        width: Int,
        left: String,
        right: String,
        title: String?,
        scroll: Pair<Boolean, Boolean>? = null,
    ): AttributedString {
        val b = AttributedStringBuilder()
        b.style(S.border).append(left)
        var used = 1
        if (title != null) {
            b.append("─ ")
            b.style(S.title).append(title)
            b.style(S.border).append(" ")
            used += 3 + title.length
        }
        if (scroll != null) {
            val marker = when {
                scroll.first && scroll.second -> "↕"
                scroll.first -> "↑"
                scroll.second -> "↓"
                else -> null
            }
            if (marker != null) {
                b.append("─ ")
                b.style(S.brand2).append(marker)
                b.style(S.border).append(" ")
                used += 4
            }
        }
        val fill = (width - used - 1).coerceAtLeast(0)
        b.style(S.border).append("─".repeat(fill)).append(right)
        return b.toAttributedString()
    }

    private fun closeFrame(b: AttributedStringBuilder, width: Int): AttributedString {
        val current = b.toAttributedString()
        val fill = (width - current.columnLength() - 1).coerceAtLeast(0)
        val out = AttributedStringBuilder()
        out.append(current)
        out.style(S.border).append("─".repeat(fill)).append("╮")
        return out.toAttributedString()
    }

    private fun modalRule(width: Int, left: String, right: String, title: String?): AttributedString {
        val b = AttributedStringBuilder()
        b.style(S.modalBorder).append(left)
        var used = 1
        if (title != null) {
            b.append("─ ")
            b.style(S.modalTitle).append(title)
            b.style(S.modalBorder).append(" ")
            used += 3 + title.length
        }
        val fill = (width - used - 1).coerceAtLeast(0)
        b.style(S.modalBorder).append("─".repeat(fill)).append(right)
        return b.toAttributedString()
    }

    private fun modalRow(width: Int, content: AttributedString, footerRow: Boolean = false): AttributedString {
        val inner = width - 4
        val b = AttributedStringBuilder()
        b.style(S.modalBorder).append("│ ")
        b.style(S.modalBg)
        b.append(fitBg(content, inner))
        b.style(S.modalBorder).append(" │")
        return b.toAttributedString()
    }

    /** Pad/truncate [s] to exactly [w] columns. */
    private fun fit(s: AttributedString, w: Int): AttributedString {
        if (w <= 0) return AttributedString.EMPTY
        val len = s.columnLength()
        if (len == w) return s
        if (len < w) {
            val b = AttributedStringBuilder()
            b.append(s)
            b.style(AttributedStyle.DEFAULT).append(" ".repeat(w - len))
            return b.toAttributedString()
        }
        val b = AttributedStringBuilder()
        b.append(s.columnSubSequence(0, w - 1))
        b.style(S.faint).append("…")
        return b.toAttributedString()
    }

    /** Like [fit] but the padding inherits the current (background) style. */
    private fun fitBg(s: AttributedString, w: Int): AttributedString {
        if (w <= 0) return AttributedString.EMPTY
        val len = s.columnLength()
        val b = AttributedStringBuilder()
        b.style(S.modalBg)
        if (len <= w) {
            b.append(s)
            b.style(S.modalBg).append(" ".repeat(w - len))
        } else {
            b.append(s.columnSubSequence(0, w - 1))
            b.append("…")
        }
        return b.toAttributedString()
    }

    private fun dim(lines: List<AttributedString>): List<AttributedString> =
        lines.map { astr(it.toString(), S.faint) }

    /** Replace columns [start, start+overlay.width) of [base] with [overlay]. */
    private fun spliceLine(base: AttributedString, overlay: AttributedString, start: Int, width: Int): AttributedString {
        val padded = fit(base, width)
        val end = (start + overlay.columnLength()).coerceAtMost(width)
        val b = AttributedStringBuilder()
        if (start > 0) b.append(padded.columnSubSequence(0, start))
        b.append(overlay)
        if (end < width) b.append(padded.columnSubSequence(end, width))
        return b.toAttributedString()
    }

    // ------------------------------------------------------------- terminal

    private fun log(message: String) {
        val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        synchronized(stateLock) {
            logLines += "[$stamp] $message"
            while (logLines.size > 200) {
                logLines.removeFirst()
            }
        }
    }

    private fun recentLogLines(count: Int): List<String> =
        synchronized(stateLock) { logLines.takeLast(max(count, 0)) }

    private fun enterAlternateScreen() {
        print("[?1049h[H")
        System.out.flush()
    }

    private fun exitAlternateScreen() {
        print("[?1049l")
        System.out.flush()
    }

    private fun enterRawMode() {
        val term = terminal ?: return
        if (!isWindowsHost()) {
            originalStty = runShellCapture("stty -g < /dev/tty")?.trim()?.takeIf { it.isNotBlank() }
            runShellCapture("stty raw -echo min 1 time 0 < /dev/tty")
        }
        term.enterRawMode()
        val attrs = Attributes(term.attributes)
        attrs.setLocalFlag(Attributes.LocalFlag.ECHO, false)
        attrs.setLocalFlag(Attributes.LocalFlag.ICANON, false)
        attrs.setControlChar(Attributes.ControlChar.VMIN, 1)
        attrs.setControlChar(Attributes.ControlChar.VTIME, 0)
        term.setAttributes(attrs)
        rawActive = true
    }

    private fun leaveRawMode() {
        val stty = originalStty
        if (!stty.isNullOrBlank() && !isWindowsHost()) {
            runShellCapture("stty $stty < /dev/tty")
            originalStty = null
        }
        originalAttributes?.let { terminal?.setAttributes(it) }
        rawActive = false
    }

    private fun runShellCapture(command: String): String? {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        }.getOrNull()
    }

    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name").contains("windows", ignoreCase = true)

    private fun selectionKey(device: Device): String? = device.deviceIdText() ?: device.uiKeyText()

    private fun deviceLabel(device: Device): String =
        device.displayNameText() + "-" + (device.deviceIdText() ?: "unknown")

    private fun truncate(value: String, width: Int): String {
        if (value.length <= width) return value
        if (width <= 1) return value.take(width)
        return value.take(width - 1) + "~"
    }
}

/** Tokyo Night-inspired palette tuned for dark and purple terminal backgrounds. */
private object S {
    private fun fg(n: Int) = AttributedStyle.DEFAULT.foreground(n)
    private fun on(fg: Int, bg: Int) = AttributedStyle.DEFAULT.foreground(fg).background(bg)

    val text = AttributedStyle.DEFAULT       // terminal default foreground; this is real white in user themes
    val muted = AttributedStyle.DEFAULT      // keep labels readable instead of theme-remapped blue
    val faint = AttributedStyle.DEFAULT      // fallback/empty text should stay readable
    val border = fg(69)         // quiet blue frame
    val title = fg(117).bold()  // sky-blue section labels
    val brand = fg(123).bold()  // bright, readable cyan
    val brand2 = fg(111).bold() // active blue-cyan
    val accent = fg(180).bold() // warm sand/gold accent
    val ok = fg(150).bold()     // soft green
    val warn = fg(179).bold()   // warm amber
    val danger = fg(210).bold() // soft red

    val chipName = fg(117).bold()
    val chipValue = AttributedStyle.DEFAULT.bold()
    val rowCurrent = AttributedStyle.DEFAULT.background(24).bold()  // selected row only gets a background

    val badgeReady = fg(117).bold()
    val badgeInfo = fg(111).bold()
    val badgeOk = fg(150).bold()
    val badgeWarn = fg(179).bold()
    val badgeDanger = fg(210).bold()

    val modalBorder = fg(117).bold()
    val modalTitle = fg(180).bold()
    val modalBg = AttributedStyle.DEFAULT.background(234)
}

private data class MenuItem(
    val label: String,
    val shortcut: String,
    val action: () -> Unit,
)

private enum class EntryKind { NONE, PARENT, DIR, FILE }

private data class FsEntry(
    val file: File?,
    val kind: EntryKind,
    val label: String,
    val highlighted: Boolean,
)

private data class FlashLineState(
    val label: String,
    var message: String = "Waiting",
    var phase: String = "status",
    var doneBytes: Long = 0L,
    var totalBytes: Long = 0L,
)

private enum class TuiKey {
    UP,
    DOWN,
    ENTER,
    SELECT,
    REFRESH,
    FLASH,
    OPTIONS,
    HELP,
    ALL,
    NONE,
    VERSION,
    RTC,
    INSTALL,
    SYNC,
    BOOT,
    BACK,
    QUIT,
    UNKNOWN,
}
