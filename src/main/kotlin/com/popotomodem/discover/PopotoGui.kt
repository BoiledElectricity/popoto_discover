package com.popotomodem.discover

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Taskbar
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.WindowConstants
import javax.swing.table.DefaultTableModel

class PopotoGui private constructor(
    initialSecretFile: String?,
    initialNoAuth: Boolean,
) : JFrame("Popoto Discovery") {
    private val savedState = GuiState.load(initialSecretFile)
    private val customSecretCheck = JCheckBox("Custom secret file", savedState.useCustomSecret)
    private val secretFileField = JTextField(savedState.secretFile, 28)
    private val browseSecretButton = JButton("Browse")
    private val noAuth = initialNoAuth
    private val timeoutField = JTextField(savedState.timeout, 6)
    private val interfaceField = JTextField(savedState.interfaceName, 10)
    private val transportBox = JComboBox(arrayOf("auto", "udp", "l2", "all")).also {
        it.selectedItem = savedState.transport
    }
    private val discoverButton = JButton("Discover Devices")
    private val enableL2Button = JButton("Enable L2 Capture")
    private val setIpButton = JButton("Set IP Address")
    private val setRtcButton = JButton("Set RTC")
    private val getRtcButton = JButton("Get RTC")
    private val setParamButton = JButton("Set Parameter")
    private val getVersionButton = JButton("Get Version")
    private val flashButton = JButton("Flash WIC")
    private val clearLogButton = JButton("Clear Log")
    private val tableModel = DeviceTableModel()
    private val table = JTable(tableModel)
    private val logArea = JTextArea()
    private var devices: List<Device> = emptyList()
    private var bpfSetupPromptShown = false

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        minimumSize = Dimension(1050, 680)
        layout = BorderLayout(8, 8)
        add(settingsPanel(), BorderLayout.NORTH)
        add(centerPanel(), BorderLayout.CENTER)
        add(actionsPanel(), BorderLayout.SOUTH)
        setPopotoIcon()
        configureSavedState()
        configureTable()
        configureActions()
        updateSecretControls()
        updateBpfControls()
        setActionButtonsEnabled(false)
        pack()
        setLocationRelativeTo(null)
        SwingUtilities.invokeLater { maybeOfferBpfSetup() }
    }

    private fun settingsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Connection")
        val c = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        c.gridx = 0
        c.gridy = 0
        panel.add(JLabel("Authentication:"), c)

        c.gridx = 1
        c.weightx = 1.0
        panel.add(JLabel("Built-in Popoto default"), c)

        c.gridx = 2
        c.weightx = 0.0
        panel.add(customSecretCheck, c)

        c.gridx = 3
        c.weightx = 1.0
        panel.add(secretFileField, c)

        c.gridx = 4
        c.weightx = 0.0
        panel.add(browseSecretButton, c)

        c.gridx = 0
        c.gridy = 1
        panel.add(JLabel("Timeout:"), c)

        c.gridx = 1
        c.weightx = 0.0
        panel.add(timeoutField, c)

        c.gridx = 2
        panel.add(JLabel("Transport:"), c)

        c.gridx = 3
        panel.add(transportBox, c)

        c.gridx = 4
        panel.add(JLabel("Interface:"), c)

        c.gridx = 5
        c.weightx = 0.3
        panel.add(interfaceField, c)

        c.gridx = 6
        c.weightx = 0.0
        panel.add(discoverButton, c)

        c.gridx = 7
        panel.add(enableL2Button, c)

        return panel
    }

    private fun centerPanel(): JSplitPane {
        logArea.isEditable = false
        logArea.rows = 9
        val tablePane = JScrollPane(table)
        val logPane = JScrollPane(logArea)
        return JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePane, logPane).apply {
            resizeWeight = 0.74
            border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
        }
    }

    private fun actionsPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
            add(setIpButton)
            add(setRtcButton)
            add(getRtcButton)
            add(setParamButton)
            add(getVersionButton)
            add(flashButton)
            add(clearLogButton)
        }
    }

    private fun configureTable() {
        table.selectionModel.addListSelectionListener {
            setActionButtonsEnabled(selectedDevice() != null)
        }
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        val widths = intArrayOf(180, 110, 210, 110, 135, 110, 80, 95, 90, 95, 120)
        for (index in widths.indices) {
            table.columnModel.getColumn(index).preferredWidth = widths[index]
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) {
                    selectedDevice()?.text("ip")?.takeIf { it.isNotBlank() }?.let {
                        runCatching {
                            java.awt.Desktop.getDesktop().browse(java.net.URI("http://$it/"))
                        }.onFailure { error ->
                            log("Could not open browser: ${error.message}", "ERROR")
                        }
                    }
                }
            }
        })
    }

    private fun configureActions() {
        customSecretCheck.addActionListener {
            updateSecretControls()
            saveGuiState()
        }
        browseSecretButton.addActionListener { browseSecretFile() }
        transportBox.addActionListener {
            updateBpfControls()
            saveGuiState()
        }
        enableL2Button.addActionListener { installBpfAccess() }
        discoverButton.addActionListener { discoverDevices() }
        setIpButton.addActionListener { setIpAddress() }
        setRtcButton.addActionListener { setRtc() }
        getRtcButton.addActionListener { getRtc() }
        setParamButton.addActionListener { setParam() }
        getVersionButton.addActionListener { getVersion() }
        flashButton.addActionListener { flashWic() }
        clearLogButton.addActionListener { logArea.text = "" }
    }

    private fun discoverDevices() {
        saveGuiState()
        val transport = TransportMode.parse(transportBox.selectedItem.toString())
        if (MacBpfAccess.needsSetupFor(transport)) {
            log("L2 capture is not enabled on this Mac")
            installBpfAccess(afterSuccess = { discoverDevices() })
            return
        }

        discoverButton.isEnabled = false
        setActionButtonsEnabled(false)
        log("Starting discovery")
        val timeout = readTimeout()
        val secret = try {
            readSecret()
        } catch (e: IllegalArgumentException) {
            JOptionPane.showMessageDialog(this, e.message, "Authentication", JOptionPane.ERROR_MESSAGE)
            return finishDiscoveryFailure("Authentication setup failed")
        }
        val interfaces = interfaceField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        object : SwingWorker<List<Device>, Unit>() {
            override fun doInBackground(): List<Device> {
                return Discoverer().discover(
                    DiscoveryOptions(
                        timeoutSeconds = timeout,
                        secret = secret,
                        transportMode = transport,
                        interfaces = interfaces,
                        retries = 3,
                    ),
                )
            }

            override fun done() {
                discoverButton.isEnabled = true
                try {
                    devices = get()
                    tableModel.setDevices(devices)
                    log("Discovery complete: ${devices.size} device(s)")
                    setActionButtonsEnabled(selectedDevice() != null)
                } catch (e: Exception) {
                    log("Discovery failed: ${e.cause?.message ?: e.message}", "ERROR")
                }
            }
        }.execute()
    }

    private fun setIpAddress() {
        val device = selectedDevice() ?: return
        val target = targetFor(device)
        val currentIp = device.text("ip").orEmpty()
        val netmask = device.text("netmask") ?: "255.255.255.0"
        val gateway = device.text("gateway") ?: defaultGateway(currentIp)
        val fields = listOf(
            "IP address" to JTextField(currentIp, 16),
            "Netmask" to JTextField(netmask, 16),
            "Gateway" to JTextField(gateway, 16),
        )
        if (!showForm("Set IP Address - ${target.label}", fields)) {
            return
        }

        val ip = fields[0].second.text.trim()
        val mask = fields[1].second.text.trim()
        val gw = fields[2].second.text.trim()
        runCommand("Setting IP on ${target.label} to $ip") {
            CommandClient().setIp(target, ip, mask, gw, commandOptions())
        }
    }

    private fun setRtc() {
        val device = selectedDevice() ?: return
        val target = targetFor(device)
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd-HH:mm:ss"))
        val fields = listOf("RTC" to JTextField(now, 18))
        if (!showForm("Set RTC - ${target.label}", fields)) {
            return
        }
        val rtc = fields[0].second.text.trim()
        runCommand("Setting RTC on ${target.label} to $rtc") {
            CommandClient().setRtc(target, rtc, commandOptions())
        }
    }

    private fun getRtc() {
        val target = targetFor(selectedDevice() ?: return)
        runCommand("Reading RTC from ${target.label}") {
            CommandClient().getRtc(target, commandOptions())
        }
    }

    private fun setParam() {
        val target = targetFor(selectedDevice() ?: return)
        val fields = listOf(
            "Parameter" to JTextField("TxPowerWatts", 18),
            "Value" to JTextField("", 18),
        )
        if (!showForm("Set Parameter - ${target.label}", fields)) {
            return
        }
        val name = fields[0].second.text.trim()
        val value = fields[1].second.text.trim()
        runCommand("Setting $name on ${target.label} to $value") {
            CommandClient().setParam(target, name, value, commandOptions())
        }
    }

    private fun getVersion() {
        val target = targetFor(selectedDevice() ?: return)
        runCommand("Reading version from ${target.label}") {
            CommandClient().getVersion(target, commandOptions())
        }
    }

    private fun flashWic() {
        val device = selectedDevice() ?: return
        if (MacBpfAccess.isMac() && !MacBpfAccess.hasBpfAccess()) {
            installBpfAccess(afterSuccess = { flashWic() })
            return
        }

        val target = FlashWorkflow.targetFor(device) ?: run {
            JOptionPane.showMessageDialog(this, "Selected device has no usable target identifier.", "Flash WIC", JOptionPane.ERROR_MESSAGE)
            return
        }
        val interfaceName = FlashWorkflow.bestInterfaceFor(device, interfaceField.text) ?: run {
            JOptionPane.showMessageDialog(this, "No Ethernet interface is available for flashing.", "Flash WIC", JOptionPane.ERROR_MESSAGE)
            return
        }
        val secret = try {
            readSecret()
        } catch (e: IllegalArgumentException) {
            JOptionPane.showMessageDialog(this, e.message, "Authentication", JOptionPane.ERROR_MESSAGE)
            return
        }

        val chooser = JFileChooser().apply {
            dialogTitle = "Select PMM WIC LZ4 Image"
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return
        }
        val image = chooser.selectedFile
        val defaultBmap = FlashWorkflow.defaultBmapFor(image)
        val modeChoice = if (defaultBmap.exists()) {
            val options = arrayOf("Use bmap", "Write full image", "Cancel")
            JOptionPane.showOptionDialog(
                this,
                "Matching bmap found:\n${defaultBmap.absolutePath}\n\nUse bmap for the normal fast mapped write, or write the full image.",
                "Flash WIC",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0],
            )
        } else {
            val options = arrayOf("Write full image", "Cancel")
            val selected = JOptionPane.showOptionDialog(
                this,
                "Matching bmap not found:\n${defaultBmap.absolutePath}\n\nThe full decompressed image can still be written.",
                "Flash WIC",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0],
            )
            if (selected == 0) 1 else -1
        }
        val flashMode = when (modeChoice) {
            0 -> FlashMode.BMAP
            1 -> FlashMode.FULL_IMAGE
            else -> return
        }
        val bmap = if (flashMode == FlashMode.BMAP) defaultBmap else null

        val confirm = JOptionPane.showConfirmDialog(
            this,
            "This will overwrite the PMM eMMC user area.\n\nDevice: ${device.text("name") ?: target.label}\nInterface: $interfaceName\nImage: ${image.name}\nMode: ${if (flashMode == FlashMode.BMAP) "bmap payload" else "full image"}\n\nContinue?",
            "Flash WIC",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (confirm != JOptionPane.YES_OPTION) {
            return
        }

        FlashWindow.launch(
            FlashRequest(
                initialDevice = device,
                target = target,
                interfaceName = interfaceName,
                image = image,
                bmap = bmap,
                mode = flashMode,
                secret = secret,
            ),
        )
    }

    private fun runCommand(label: String, command: () -> CommandResponse?) {
        saveGuiState()
        setActionButtonsEnabled(false)
        log(label)
        object : SwingWorker<CommandResponse?, Unit>() {
            override fun doInBackground(): CommandResponse? = command()
            override fun done() {
                setActionButtonsEnabled(selectedDevice() != null)
                try {
                    val response = get()
                    if (response == null) {
                        log("$label: no reply", "ERROR")
                        JOptionPane.showMessageDialog(this@PopotoGui, "No reply received.", "No Reply", JOptionPane.WARNING_MESSAGE)
                    } else if (response.text("status") == "ok") {
                        log("$label: ok from ${response.sourceIp}", "SUCCESS")
                        JOptionPane.showMessageDialog(this@PopotoGui, responseSummary(response), "Success", JOptionPane.INFORMATION_MESSAGE)
                    } else {
                        val error = response.text("error") ?: "Unknown error"
                        log("$label: $error", "ERROR")
                        JOptionPane.showMessageDialog(this@PopotoGui, error, "Failed", JOptionPane.ERROR_MESSAGE)
                    }
                } catch (e: Exception) {
                    val message = e.cause?.message ?: e.message ?: "Unknown error"
                    log("$label: $message", "ERROR")
                    JOptionPane.showMessageDialog(this@PopotoGui, message, "Error", JOptionPane.ERROR_MESSAGE)
                }
            }
        }.execute()
    }

    private fun responseSummary(response: CommandResponse): String {
        return when (Protocol.text(response.message, "cmd")) {
            Protocol.MSG_SET_IP_REPLY -> "IP set to ${response.text("ip")}.\nReply from ${response.sourceIp}"
            Protocol.MSG_SET_RTC_REPLY -> "RTC set.\nReply from ${response.sourceIp}"
            Protocol.MSG_GET_RTC_REPLY -> "RTC: ${response.text("rtc") ?: "Unknown"}\nReply from ${response.sourceIp}"
            Protocol.MSG_SET_PARAM_REPLY -> "Parameter set.\nReply from ${response.sourceIp}"
            Protocol.MSG_GET_VERSION_REPLY -> {
                "Version: ${response.text("version") ?: "Unknown"}\n" +
                    "Serial: ${response.text("serial") ?: "Unknown"}\n" +
                    "Reply from ${response.sourceIp}"
            }
            else -> "Command succeeded.\nReply from ${response.sourceIp}"
        }
    }

    private fun selectedDevice(): Device? {
        val row = table.selectedRow
        if (row < 0) {
            return null
        }
        val modelRow = table.convertRowIndexToModel(row)
        return devices.getOrNull(modelRow)
    }

    private fun targetFor(device: Device): TargetSelector {
        val targetText = listOf("device_id", "serial", "mac")
            .firstNotNullOfOrNull { field -> device.text(field)?.takeIf { it.isNotBlank() && !it.equals("unknown", true) } }
            ?: throw IllegalArgumentException("selected device has no usable target identifier")
        return TargetSelector.parse(targetText)
    }

    private fun commandOptions(): CommandOptions {
        val interfaces = interfaceField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return CommandOptions(readTimeout(), readSecret(), interfaces)
    }

    private fun readTimeout(): Double = timeoutField.text.trim().toDoubleOrNull()?.coerceAtLeast(0.1) ?: 5.0

    private fun readSecret(): String? {
        if (noAuth) {
            return null
        }
        if (!customSecretCheck.isSelected) {
            return SecretProvider.load(null)
        }
        val path = secretFileField.text.trim()
        if (path.isEmpty()) {
            throw IllegalArgumentException("Custom secret file is enabled but no file is selected.")
        }
        return SecretProvider.load(path)
    }

    private fun finishDiscoveryFailure(message: String) {
        discoverButton.isEnabled = true
        log(message, "ERROR")
    }

    private fun maybeOfferBpfSetup() {
        if (bpfSetupPromptShown || !MacBpfAccess.needsSetupFor(TransportMode.AUTO)) {
            updateBpfControls()
            return
        }
        bpfSetupPromptShown = true
        log("L2 capture is not enabled on this Mac")
        installBpfAccess()
    }

    private fun installBpfAccess(afterSuccess: (() -> Unit)? = null) {
        if (!MacBpfAccess.isMac()) {
            return
        }
        if (MacBpfAccess.hasBpfAccess()) {
            updateBpfControls()
            log("L2 capture is already enabled")
            afterSuccess?.invoke()
            return
        }

        val answer = JOptionPane.showConfirmDialog(
            this,
            "Enable L2 packet capture for Popoto discovery?\n\nThis installs a small LaunchDaemon like Wireshark's ChmodBPF setup and requires administrator permission once.",
            "Enable L2 Capture",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE,
        )
        if (answer != JOptionPane.YES_OPTION) {
            log("L2 capture setup canceled")
            updateBpfControls()
            if (afterSuccess != null) {
                finishDiscoveryFailure("L2 capture setup canceled")
            }
            return
        }

        enableL2Button.isEnabled = false
        discoverButton.isEnabled = false
        log("Installing one-time macOS BPF access")

        object : SwingWorker<MacBpfAccess.InstallResult, Unit>() {
            override fun doInBackground(): MacBpfAccess.InstallResult = MacBpfAccess.install()

            override fun done() {
                discoverButton.isEnabled = true
                updateBpfControls()
                try {
                    val result = get()
                    if (result.success) {
                        log("L2 capture enabled")
                        JOptionPane.showMessageDialog(
                            this@PopotoGui,
                            "L2 packet capture is enabled for this Mac.",
                            "L2 Capture Enabled",
                            JOptionPane.INFORMATION_MESSAGE,
                        )
                        afterSuccess?.invoke()
                    } else {
                        val message = result.output.ifBlank { "Installer exited with code ${result.exitCode}" }
                        log("L2 capture setup failed: $message", "ERROR")
                        JOptionPane.showMessageDialog(this@PopotoGui, message, "L2 Capture Setup Failed", JOptionPane.ERROR_MESSAGE)
                        if (afterSuccess != null) {
                            finishDiscoveryFailure("L2 capture setup failed")
                        }
                    }
                } catch (e: Exception) {
                    val message = e.cause?.message ?: e.message ?: "Unknown error"
                    log("L2 capture setup failed: $message", "ERROR")
                    JOptionPane.showMessageDialog(this@PopotoGui, message, "L2 Capture Setup Failed", JOptionPane.ERROR_MESSAGE)
                    if (afterSuccess != null) {
                        finishDiscoveryFailure("L2 capture setup failed")
                    }
                }
            }
        }.execute()
    }

    private fun updateBpfControls() {
        if (!MacBpfAccess.isMac()) {
            enableL2Button.isVisible = false
            return
        }

        enableL2Button.isVisible = true
        val hasAccess = MacBpfAccess.hasBpfAccess()
        enableL2Button.text = if (hasAccess) "L2 Enabled" else "Enable L2 Capture"
        enableL2Button.isEnabled = !hasAccess
        enableL2Button.toolTipText = if (hasAccess) {
            "macOS BPF capture access is already enabled."
        } else {
            "Install one-time macOS BPF permissions for L2 discovery."
        }
    }

    private fun browseSecretFile() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            secretFileField.text = chooser.selectedFile.absolutePath
            saveGuiState()
        }
    }

    private fun updateSecretControls() {
        val enabled = customSecretCheck.isSelected && !noAuth
        secretFileField.isEnabled = enabled
        browseSecretButton.isEnabled = enabled
    }

    private fun setPopotoIcon() {
        val icon = runCatching {
            val resource = javaClass.getResource("/icons/popoto-icon.png") ?: return
            ImageIO.read(resource)
        }.getOrNull() ?: return

        iconImage = icon
        runCatching {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar.getTaskbar().iconImage = icon
            }
        }
    }

    private fun configureSavedState() {
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                saveGuiState()
            }

            override fun windowClosed(event: WindowEvent) {
                saveGuiState()
            }
        })
    }

    private fun saveGuiState() {
        GuiState(
            useCustomSecret = customSecretCheck.isSelected,
            secretFile = secretFileField.text.trim(),
            timeout = timeoutField.text.trim().ifEmpty { "8.0" },
            interfaceName = interfaceField.text.trim(),
            transport = transportBox.selectedItem?.toString() ?: "auto",
        ).save()
    }

    private fun showForm(title: String, fields: List<Pair<String, JTextField>>): Boolean {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
        }
        for ((index, pair) in fields.withIndex()) {
            c.gridx = 0
            c.gridy = index
            c.weightx = 0.0
            panel.add(JLabel(pair.first), c)
            c.gridx = 1
            c.weightx = 1.0
            panel.add(pair.second, c)
        }
        return JOptionPane.showConfirmDialog(this, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION
    }

    private fun defaultGateway(ip: String): String {
        val parts = ip.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.1" else ""
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        setIpButton.isEnabled = enabled
        setRtcButton.isEnabled = enabled
        getRtcButton.isEnabled = enabled
        setParamButton.isEnabled = enabled
        getVersionButton.isEnabled = enabled
        flashButton.isEnabled = enabled
    }

    private fun log(message: String, level: String = "INFO") {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logArea.append("[$stamp] [$level] $message\n")
        logArea.caretPosition = logArea.document.length
    }

    private class DeviceTableModel : DefaultTableModel(
        arrayOf("Name", "Model", "Serial", "IP", "MAC", "FW", "Battery", "Sample Rate", "RTC", "Storage", "Via"),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false

        fun setDevices(devices: List<Device>) {
            rowCount = 0
            for (device in devices.sortedBy { it.text("ip") ?: "" }) {
                addRow(
                    arrayOf(
                        device.text("name"),
                        device.text("model"),
                        device.text("serial"),
                        device.text("ip"),
                        device.text("mac"),
                        device.text("fw"),
                        device.text("battery_v"),
                        device.text("sample_rate_hz"),
                        "",
                        storageText(device),
                        viaText(device),
                    ),
                )
            }
        }

        private fun storageText(device: Device): String {
            val free = device.text("storage_free_gb")
            val total = device.text("storage_total_gb")
            return if (!free.isNullOrBlank() && !total.isNullOrBlank()) "$free / $total GB" else ""
        }

        private fun viaText(device: Device): String {
            return device.paths.joinToString(", ") { path ->
                path.transport + (path.interfaceName?.let { "@$it" } ?: "")
            }
        }
    }

    companion object {
        fun launch(secretFile: String?, noAuth: Boolean) {
            SwingUtilities.invokeLater {
                PopotoGui(secretFile, noAuth).isVisible = true
            }
        }
    }

    private data class GuiState(
        val useCustomSecret: Boolean,
        val secretFile: String,
        val timeout: String,
        val interfaceName: String,
        val transport: String,
    ) {
        fun save() {
            runCatching {
                val prefs = preferences()
                prefs.put(KEY_BUILD_ID, AppBuild.id)
                prefs.putBoolean(KEY_USE_CUSTOM_SECRET, useCustomSecret)
                prefs.put(KEY_SECRET_FILE, secretFile)
                prefs.put(KEY_TIMEOUT, timeout)
                prefs.put(KEY_INTERFACE, interfaceName)
                prefs.put(KEY_TRANSPORT, transport)
            }
        }

        companion object {
            private const val KEY_BUILD_ID = "buildId"
            private const val KEY_USE_CUSTOM_SECRET = "useCustomSecret"
            private const val KEY_SECRET_FILE = "secretFile"
            private const val KEY_TIMEOUT = "timeout"
            private const val KEY_INTERFACE = "interface"
            private const val KEY_TRANSPORT = "transport"

            fun load(initialSecretFile: String?): GuiState {
                if (!initialSecretFile.isNullOrBlank()) {
                    return default().copy(useCustomSecret = true, secretFile = initialSecretFile)
                }

                val prefs = preferences()
                if (prefs.get(KEY_BUILD_ID, "") != AppBuild.id) {
                    runCatching { prefs.clear() }
                    return default()
                }

                return GuiState(
                    useCustomSecret = prefs.getBoolean(KEY_USE_CUSTOM_SECRET, false),
                    secretFile = prefs.get(KEY_SECRET_FILE, ""),
                    timeout = prefs.get(KEY_TIMEOUT, "8.0"),
                    interfaceName = prefs.get(KEY_INTERFACE, ""),
                    transport = prefs.get(KEY_TRANSPORT, "auto").takeIf { it in setOf("auto", "udp", "l2", "all") } ?: "auto",
                )
            }

            private fun default(): GuiState {
                return GuiState(
                    useCustomSecret = false,
                    secretFile = "",
                    timeout = "8.0",
                    interfaceName = "",
                    transport = "auto",
                )
            }

            private fun preferences(): Preferences {
                return Preferences.userNodeForPackage(PopotoGui::class.java).node("gui")
            }
        }
    }
}
