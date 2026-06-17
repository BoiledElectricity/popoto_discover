package com.popotomodem.discover

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
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
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.UIManager
import javax.swing.WindowConstants
import javax.swing.border.LineBorder
import javax.swing.plaf.basic.BasicButtonUI
import javax.swing.table.DefaultTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.filechooser.FileFilter

private object PopotoTheme {
    val PopotoBlue = Color(0x27, 0x77, 0xc3)
    val AcousticBlue = Color(0x21, 0x60, 0x8a)
    val PopotoDarkBlue = Color(0x23, 0x4c, 0x6f)
    val ClamshellWhite = Color(0xf2, 0xf4, 0xf7)
    val DolphinGrey = Color(0xa1, 0xa1, 0xa1)
    val MantaGrey = Color(0x72, 0x72, 0x72)
    val TransducerGrey = Color(0x43, 0x43, 0x43)
    val DeepseaNavy = Color(0x28, 0x2b, 0x34)
    val Border = Color(0xd9, 0xe2, 0xec)
    val RowAlt = Color(0xf8, 0xfa, 0xfc)
    val Selection = Color(0xd6, 0xf5, 0xff)
    val SoftBlue = Color(0xed, 0xf8, 0xfc)
    val Disabled = Color(0xec, 0xf0, 0xf4)
    val Mono = Font(Font.MONOSPACED, Font.PLAIN, 12)
    val Base = Font("SansSerif", Font.PLAIN, 13)
    val BaseBold = Font("SansSerif", Font.BOLD, 13)
    val Heading = Font("SansSerif", Font.BOLD, 18)
}

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
    private val imageField = JTextField(savedState.wicImage, 34)
    private val browseImageButton = JButton("Choose WIC")
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
    private val selectedNameLabel = JLabel("No unit selected")
    private val selectedSerialLabel = JLabel("Serial: --")
    private val selectedDeviceIdLabel = JLabel("Device ID: --")
    private val selectedNetworkLabel = JLabel("IP: --")
    private var devices: List<Device> = emptyList()
    private var bpfSetupPromptShown = false

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        minimumSize = Dimension(1180, 760)
        contentPane.background = PopotoTheme.ClamshellWhite
        layout = BorderLayout(0, 0)
        add(topPanel(), BorderLayout.NORTH)
        add(centerPanel(), BorderLayout.CENTER)
        add(actionsPanel(), BorderLayout.SOUTH)
        setPopotoIcon()
        applyTheme()
        configureSavedState()
        configureTable()
        configureActions()
        updateSecretControls()
        updateBpfControls()
        updateSelectedDevice()
        setActionButtonsEnabled(false)
        pack()
        setLocationRelativeTo(null)
        SwingUtilities.invokeLater { maybeOfferBpfSetup() }
    }

    private fun topPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            background = PopotoTheme.ClamshellWhite
            add(appHeader(), BorderLayout.NORTH)
            add(
                JPanel(GridBagLayout()).apply {
                    background = PopotoTheme.ClamshellWhite
                    border = BorderFactory.createEmptyBorder(14, 16, 10, 16)
                    val c = GridBagConstraints().apply {
                        gridy = 0
                        fill = GridBagConstraints.BOTH
                        insets = Insets(0, 0, 0, 12)
                        weighty = 1.0
                    }

                    c.gridx = 0
                    c.weightx = 0.42
                    add(settingsPanel(), c)

                    c.gridx = 1
                    c.weightx = 0.32
                    add(selectedUnitPanel(), c)

                    c.gridx = 2
                    c.weightx = 0.26
                    c.insets = Insets(0, 0, 0, 0)
                    add(imagePanel(), c)
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun appHeader(): JPanel {
        return GradientPanel(PopotoTheme.PopotoDarkBlue, PopotoTheme.AcousticBlue).apply {
            layout = BorderLayout(16, 0)
            border = BorderFactory.createEmptyBorder(18, 22, 18, 22)

            val title = JLabel("Popoto Discover").apply {
                foreground = Color.WHITE
                font = PopotoTheme.Heading.deriveFont(22f)
            }
            val subtitle = JLabel("Discover, manage, and flash PMM modems").apply {
                foreground = Color(0xd6, 0xf5, 0xff)
                font = PopotoTheme.Base
            }
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(title)
                    add(subtitle)
                },
                BorderLayout.WEST,
            )
        }
    }

    private fun settingsPanel(): JPanel {
        val panel = cardPanel("Connection")
        val c = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        c.gridx = 0
        c.gridy = 1
        panel.add(JLabel("Authentication:"), c)

        c.gridx = 1
        c.weightx = 1.0
        c.gridwidth = 2
        panel.add(JLabel("Built-in Popoto default"), c)

        c.gridx = 3
        c.gridwidth = 1
        c.weightx = 0.0
        panel.add(customSecretCheck, c)

        c.gridx = 4
        c.weightx = 1.0
        panel.add(secretFileField, c)

        c.gridx = 5
        c.weightx = 0.0
        panel.add(browseSecretButton, c)

        c.gridx = 0
        c.gridy = 2
        c.gridwidth = 1
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

        c.gridx = 0
        c.gridy = 3
        c.gridwidth = 2
        c.weightx = 0.0
        panel.add(discoverButton, c)

        c.gridx = 2
        c.gridwidth = 2
        panel.add(enableL2Button, c)

        return panel
    }

    private fun selectedUnitPanel(): JPanel {
        return cardPanel("Selected Unit").apply {
            val c = GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(3, 6, 3, 6)
            }

            c.gridy = 1
            selectedNameLabel.font = PopotoTheme.Heading
            selectedNameLabel.foreground = PopotoTheme.PopotoDarkBlue
            add(selectedNameLabel, c)

            c.gridy = 2
            add(selectedSerialLabel, c)

            c.gridy = 3
            add(selectedDeviceIdLabel, c)

            c.gridy = 4
            add(selectedNetworkLabel, c)
        }
    }

    private fun imagePanel(): JPanel {
        return cardPanel("Flash Image").apply {
            val c = GridBagConstraints().apply {
                insets = Insets(4, 6, 4, 6)
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                weightx = 1.0
            }

            c.gridx = 0
            c.gridy = 1
            c.gridwidth = 2
            val hint = JLabel("Persistent WIC image selection").apply {
                foreground = PopotoTheme.MantaGrey
                font = PopotoTheme.Base
            }
            add(hint, c)

            c.gridy = 2
            c.gridwidth = 1
            imageField.toolTipText = "Selected .wic.lz4 image used by Flash WIC."
            add(imageField, c)

            c.gridx = 1
            c.weightx = 0.0
            add(browseImageButton, c)
        }
    }

    private fun centerPanel(): JSplitPane {
        logArea.isEditable = false
        logArea.rows = 9
        logArea.font = PopotoTheme.Mono
        logArea.background = Color(0x0d, 0x13, 0x20)
        logArea.foreground = Color(0xd9, 0xe8, 0xff)
        logArea.caretColor = Color.WHITE
        val tablePane = JScrollPane(table).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = Color.WHITE
        }
        val tableContainer = RoundedPanel(BorderLayout(), Color.WHITE, PopotoTheme.Border).apply {
            border = BorderFactory.createEmptyBorder(1, 1, 1, 1)
            add(tablePane, BorderLayout.CENTER)
        }
        val logPane = JScrollPane(logArea).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = logArea.background
        }
        val logContainer = RoundedPanel(BorderLayout(), Color(0x0d, 0x13, 0x20), Color(0x1f, 0x2a, 0x3a)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(logPane, BorderLayout.CENTER)
        }
        return JSplitPane(JSplitPane.VERTICAL_SPLIT, tableContainer, logContainer).apply {
            resizeWeight = 0.74
            border = BorderFactory.createEmptyBorder(0, 16, 0, 16)
            background = PopotoTheme.ClamshellWhite
            dividerSize = 8
        }
    }

    private fun actionsPanel(): JPanel {
        val bar = RoundedPanel(FlowLayout(FlowLayout.LEFT, 8, 8), Color.WHITE, PopotoTheme.Border).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(setIpButton)
            add(setRtcButton)
            add(getRtcButton)
            add(setParamButton)
            add(getVersionButton)
            add(flashButton)
            add(clearLogButton)
        }
        return JPanel(BorderLayout()).apply {
            background = PopotoTheme.ClamshellWhite
            border = BorderFactory.createEmptyBorder(2, 16, 14, 16)
            add(bar, BorderLayout.CENTER)
        }
    }

    private fun configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowHeight = 30
        table.gridColor = PopotoTheme.Border
        table.showVerticalLines = false
        table.showHorizontalLines = true
        table.background = Color.WHITE
        table.foreground = PopotoTheme.TransducerGrey
        table.selectionBackground = PopotoTheme.Selection
        table.selectionForeground = PopotoTheme.DeepseaNavy
        table.tableHeader.background = PopotoTheme.PopotoDarkBlue
        table.tableHeader.foreground = Color.WHITE
        table.tableHeader.font = PopotoTheme.BaseBold
        table.autoCreateRowSorter = true
        table.setDefaultRenderer(Object::class.java, DeviceTableCellRenderer())
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateSelectedDevice()
                setActionButtonsEnabled(selectedDevice() != null)
            }
        }
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        val widths = intArrayOf(190, 90, 180, 190, 105, 135, 120, 85, 105, 80, 120, 130)
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
        browseImageButton.addActionListener { browseWicImage() }
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
        val previousDeviceId = selectedDevice()?.deviceIdText()
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
                    restoreSelection(previousDeviceId)
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

        val image = selectedWicImageOrPrompt() ?: return
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
                    "Serial: ${response.text("serial") ?: "unknown"}\n" +
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
        return tableModel.deviceAt(modelRow)
    }

    private fun restoreSelection(previousDeviceId: String?) {
        val modelRow = previousDeviceId?.let { tableModel.rowForDeviceId(it) }
            ?: if (tableModel.rowCount == 1) 0 else -1
        if (modelRow >= 0) {
            val viewRow = table.convertRowIndexToView(modelRow)
            if (viewRow >= 0) {
                table.setRowSelectionInterval(viewRow, viewRow)
                table.scrollRectToVisible(table.getCellRect(viewRow, 0, true))
            }
        } else {
            table.clearSelection()
        }
        updateSelectedDevice()
    }

    private fun updateSelectedDevice() {
        val device = selectedDevice()
        if (device == null) {
            selectedNameLabel.text = "No unit selected"
            selectedSerialLabel.text = "Serial: --"
            selectedDeviceIdLabel.text = "Device ID: --"
            selectedNetworkLabel.text = "IP: --"
            return
        }

        selectedNameLabel.text = device.text("name") ?: device.text("model") ?: "Selected unit"
        selectedSerialLabel.text = "Serial: ${device.serialText()}"
        selectedDeviceIdLabel.text = "Device ID: ${device.deviceIdText() ?: "--"}"
        selectedNetworkLabel.text = "IP: ${device.text("ip") ?: "--"}   MAC: ${device.text("mac") ?: "--"}"
    }

    private fun targetFor(device: Device): TargetSelector {
        val targetText = device.deviceIdText()
            ?: usableIdentity(device.text("mac"))
            ?: throw IllegalArgumentException("selected device has no usable target identifier")
        return TargetSelector.parse(targetText)
    }

    private fun commandOptions(): CommandOptions {
        val interfaces = interfaceField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return CommandOptions(readTimeout(), readSecret(), interfaces)
    }

    private fun selectedWicImageOrPrompt(): File? {
        val current = imageField.text.trim()
        if (current.isNotEmpty()) {
            val file = File(current)
            if (isWicLz4(file) && file.isFile) {
                return file
            }
            JOptionPane.showMessageDialog(
                this,
                "Selected image is not a readable .wic.lz4 file:\n$current",
                "Flash Image",
                JOptionPane.WARNING_MESSAGE,
            )
        }
        return browseWicImage()
    }

    private fun browseWicImage(): File? {
        val chooser = JFileChooser(defaultWicDirectory()).apply {
            dialogTitle = "Select PMM WIC LZ4 Image"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = WicLz4FileFilter
            imageField.text.trim().takeIf { it.isNotEmpty() }?.let {
                val current = File(it)
                selectedFile = current
                current.parentFile?.takeIf { parent -> parent.isDirectory }?.let { parent ->
                    currentDirectory = parent
                }
            }
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null
        }
        val image = chooser.selectedFile
        if (!isWicLz4(image)) {
            JOptionPane.showMessageDialog(this, "Select a .wic.lz4 image.", "Flash Image", JOptionPane.ERROR_MESSAGE)
            return null
        }
        imageField.text = image.absolutePath
        saveGuiState()
        return image
    }

    private fun defaultWicDirectory(): File {
        val downloads = File(System.getProperty("user.home"), "Downloads")
        return if (downloads.isDirectory) downloads else File(System.getProperty("user.home"))
    }

    private fun isWicLz4(file: File): Boolean = file.name.endsWith(".wic.lz4", ignoreCase = true)

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

    private fun applyTheme() {
        UIManager.put("ToolTip.font", PopotoTheme.Base)
        listOf(
            customSecretCheck,
            timeoutField,
            interfaceField,
            imageField,
            secretFileField,
            selectedSerialLabel,
            selectedDeviceIdLabel,
            selectedNetworkLabel,
        ).forEach { component ->
            component.font = PopotoTheme.Base
            component.foreground = PopotoTheme.TransducerGrey
        }
        listOf(timeoutField, interfaceField, imageField, secretFileField).forEach(::styleTextField)
        transportBox.font = PopotoTheme.Base
        transportBox.background = Color.WHITE
        transportBox.foreground = PopotoTheme.TransducerGrey
        transportBox.border = BorderFactory.createCompoundBorder(
            LineBorder(PopotoTheme.Border, 1, true),
            BorderFactory.createEmptyBorder(4, 6, 4, 6),
        )
        customSecretCheck.isOpaque = false
        stylePrimaryButton(discoverButton)
        stylePrimaryButton(flashButton)
        stylePrimaryButton(browseImageButton)
        styleSecondaryButton(enableL2Button)
        styleSecondaryButton(browseSecretButton)
        listOf(setIpButton, setRtcButton, getRtcButton, setParamButton, getVersionButton, clearLogButton).forEach(::styleSecondaryButton)
    }

    private fun cardPanel(title: String): JPanel {
        return RoundedPanel(GridBagLayout(), Color.WHITE, PopotoTheme.Border).apply {
            border = BorderFactory.createEmptyBorder(14, 14, 14, 14)
            val titleLabel = JLabel(title).apply {
                font = PopotoTheme.BaseBold.deriveFont(15f)
                foreground = PopotoTheme.PopotoDarkBlue
            }
            val c = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                gridwidth = GridBagConstraints.REMAINDER
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 6, 8, 6)
                weightx = 1.0
            }
            add(titleLabel, c)
        }
    }

    private fun styleTextField(field: JTextField) {
        field.background = Color.WHITE
        field.foreground = PopotoTheme.TransducerGrey
        field.border = BorderFactory.createCompoundBorder(
            LineBorder(PopotoTheme.Border, 1, true),
            BorderFactory.createEmptyBorder(5, 8, 5, 8),
        )
    }

    private fun stylePrimaryButton(button: JButton) {
        styleButton(button, PopotoTheme.PopotoBlue, Color.WHITE)
    }

    private fun styleSecondaryButton(button: JButton) {
        styleButton(button, Color.WHITE, PopotoTheme.TransducerGrey)
    }

    private fun styleButton(button: JButton, background: Color, foreground: Color) {
        button.font = PopotoTheme.BaseBold
        button.background = background
        button.foreground = foreground
        button.ui = RoundedButtonUi(background, foreground, if (background == Color.WHITE) PopotoTheme.Border else background)
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isFocusPainted = false
        button.isRolloverEnabled = true
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.border = BorderFactory.createEmptyBorder(8, 14, 8, 14)
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
            wicImage = imageField.text.trim(),
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
        arrayOf("Name", "Model", "Device ID", "Serial", "IP", "MAC", "FW", "Battery", "Sample Rate", "RTC", "Storage", "Via"),
        0,
    ) {
        private val rowDevices = mutableListOf<Device>()

        override fun isCellEditable(row: Int, column: Int): Boolean = false

        fun setDevices(devices: List<Device>) {
            rowCount = 0
            rowDevices.clear()
            val sorted = devices.sortedWith(
                compareBy<Device> { it.text("model") ?: "" }
                    .thenBy { it.serialText() }
                    .thenBy { it.deviceIdText() ?: "" }
                    .thenBy { it.text("ip") ?: "" },
            )
            for (device in sorted) {
                rowDevices += device
                addRow(
                    arrayOf(
                        device.text("name"),
                        device.text("model"),
                        device.deviceIdText(),
                        device.serialText(),
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

        fun deviceAt(modelRow: Int): Device? = rowDevices.getOrNull(modelRow)

        fun rowForDeviceId(deviceId: String): Int {
            return rowDevices.indexOfFirst { it.deviceIdText()?.equals(deviceId, ignoreCase = true) == true }
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

    private class DeviceTableCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
            font = PopotoTheme.Base
            horizontalAlignment = if (column in setOf(7, 8, 9)) SwingConstants.RIGHT else SwingConstants.LEFT
            if (isSelected) {
                component.background = PopotoTheme.Selection
                component.foreground = PopotoTheme.DeepseaNavy
                font = PopotoTheme.BaseBold
            } else {
                component.background = if (row % 2 == 0) Color.WHITE else PopotoTheme.RowAlt
                component.foreground = PopotoTheme.TransducerGrey
            }
            return component
        }
    }

    private object WicLz4FileFilter : FileFilter() {
        override fun accept(file: File): Boolean = file.isDirectory || file.name.endsWith(".wic.lz4", ignoreCase = true)
        override fun getDescription(): String = "PMM WIC LZ4 images (*.wic.lz4)"
    }

    private class RoundedPanel(
        layout: LayoutManager,
        private val fill: Color,
        private val stroke: Color,
        private val radius: Int = 8,
    ) : JPanel(layout) {
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g2 = graphics.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, radius.toFloat(), radius.toFloat())
            g2.color = fill
            g2.fill(shape)
            g2.color = stroke
            g2.draw(shape)
            g2.dispose()
        }

        init {
            isOpaque = false
        }
    }

    private class RoundedButtonUi(
        private val fill: Color,
        private val foreground: Color,
        private val stroke: Color,
        private val radius: Int = 8,
    ) : BasicButtonUI() {
        override fun installUI(component: JComponent) {
            super.installUI(component)
            val button = component as? AbstractButton ?: return
            button.isOpaque = false
            button.isContentAreaFilled = false
            button.foreground = foreground
        }

        override fun paint(graphics: Graphics, component: JComponent) {
            val button = component as AbstractButton
            val g2 = graphics.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, component.width - 1f, component.height - 1f, radius.toFloat(), radius.toFloat())
            g2.color = buttonFill(button)
            g2.fill(shape)
            g2.color = if (button.isEnabled) stroke else PopotoTheme.Border
            g2.draw(shape)
            g2.dispose()
            super.paint(graphics, component)
        }

        private fun buttonFill(button: AbstractButton): Color {
            if (!button.isEnabled) {
                return PopotoTheme.Disabled
            }
            if (button.model.isPressed) {
                return blend(fill, PopotoTheme.DeepseaNavy, 0.18f)
            }
            if (button.model.isRollover) {
                return if (fill == Color.WHITE) PopotoTheme.SoftBlue else blend(fill, Color.WHITE, 0.12f)
            }
            return fill
        }

        private fun blend(left: Color, right: Color, rightWeight: Float): Color {
            val leftWeight = 1f - rightWeight
            return Color(
                (left.red * leftWeight + right.red * rightWeight).toInt().coerceIn(0, 255),
                (left.green * leftWeight + right.green * rightWeight).toInt().coerceIn(0, 255),
                (left.blue * leftWeight + right.blue * rightWeight).toInt().coerceIn(0, 255),
            )
        }
    }

    private class GradientPanel(
        private val start: Color,
        private val end: Color,
    ) : JPanel() {
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g2 = graphics as Graphics2D
            g2.paint = GradientPaint(0f, 0f, start, width.toFloat(), height.toFloat(), end)
            g2.fillRect(0, 0, width, height)
        }

        init {
            isOpaque = false
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
        val wicImage: String,
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
                prefs.put(KEY_WIC_IMAGE, wicImage)
            }
        }

        companion object {
            private const val KEY_BUILD_ID = "buildId"
            private const val KEY_USE_CUSTOM_SECRET = "useCustomSecret"
            private const val KEY_SECRET_FILE = "secretFile"
            private const val KEY_TIMEOUT = "timeout"
            private const val KEY_INTERFACE = "interface"
            private const val KEY_TRANSPORT = "transport"
            private const val KEY_WIC_IMAGE = "wicImage"

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
                    wicImage = prefs.get(KEY_WIC_IMAGE, ""),
                )
            }

            private fun default(): GuiState {
                return GuiState(
                    useCustomSecret = false,
                    secretFile = "",
                    timeout = "8.0",
                    interfaceName = "",
                    transport = "auto",
                    wicImage = "",
                )
            }

            private fun preferences(): Preferences {
                return Preferences.userNodeForPackage(PopotoGui::class.java).node("gui")
            }
        }
    }
}
