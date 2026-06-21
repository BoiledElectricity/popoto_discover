package com.popoto.discover.android.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.popoto.discover.R
import com.popoto.discover.android.core.DiscoveryController
import com.popoto.discover.android.core.HydrophoneDevice
import com.popoto.discover.android.core.SessionSnapshot
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var controller: DiscoveryController
    private lateinit var statusText: TextView
    private lateinit var selectedDeviceText: TextView
    private lateinit var logText: TextView
    private lateinit var secretInput: EditText
    private lateinit var saveSecretButton: Button
    private lateinit var discoverButton: Button
    private lateinit var setIpButton: Button
    private lateinit var setRtcButton: Button
    private lateinit var getRtcButton: Button
    private lateinit var setParamButton: Button
    private lateinit var logToggleButton: Button
    private lateinit var clearLogButton: Button
    private lateinit var deviceList: ListView
    private lateinit var logPanel: View
    private lateinit var emptyDeviceText: TextView
    private lateinit var deviceSectionSubtitle: TextView
    private lateinit var footerSubtitleText: TextView
    private lateinit var deviceCountText: TextView
    private lateinit var selectedMetricText: TextView
    private lateinit var activityMetricText: TextView

    private lateinit var deviceAdapter: DeviceAdapter
    private var selectedDeviceKey: String? = null
    private var showLogs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        controller = DiscoveryController(applicationContext)

        bindViews()
        setupActions()
        observeController()
        prefillSecret()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch { controller.shutdown() }
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        selectedDeviceText = findViewById(R.id.selectedDeviceText)
        logText = findViewById(R.id.logText)
        secretInput = findViewById(R.id.secretInput)
        saveSecretButton = findViewById(R.id.saveSecretButton)
        discoverButton = findViewById(R.id.discoverButton)
        setIpButton = findViewById(R.id.setIpButton)
        setRtcButton = findViewById(R.id.setRtcButton)
        getRtcButton = findViewById(R.id.getRtcButton)
        setParamButton = findViewById(R.id.setParamButton)
        logToggleButton = findViewById(R.id.logToggleButton)
        clearLogButton = findViewById(R.id.clearLogButton)
        deviceList = findViewById(R.id.deviceList)
        logPanel = findViewById(R.id.logPanel)
        emptyDeviceText = findViewById(R.id.emptyDeviceText)
        deviceSectionSubtitle = findViewById(R.id.deviceSectionSubtitle)
        footerSubtitleText = findViewById(R.id.footerSubtitleText)
        deviceCountText = findViewById(R.id.deviceCountText)
        selectedMetricText = findViewById(R.id.selectedMetricText)
        activityMetricText = findViewById(R.id.activityMetricText)

        deviceAdapter = DeviceAdapter(this)
        deviceList.adapter = deviceAdapter
        deviceList.emptyView = emptyDeviceText
        logText.movementMethod = ScrollingMovementMethod.getInstance()
        updateLogVisibility()
    }

    private fun setupActions() {
        saveSecretButton.setOnClickListener {
            controller.saveSecret(secretInput.text.toString())
            Toast.makeText(this, "Secret saved", Toast.LENGTH_SHORT).show()
        }

        logToggleButton.setOnClickListener {
            showLogs = !showLogs
            updateLogVisibility()
        }

        clearLogButton.setOnClickListener {
            lifecycleScope.launch { controller.clearLogs() }
        }

        discoverButton.setOnClickListener {
            lifecycleScope.launch { controller.discover() }
        }

        setIpButton.setOnClickListener {
            if (selectedDeviceKey == null) {
                showToast("No device selected")
                return@setOnClickListener
            }

            showSetIpDialog()
        }

        setRtcButton.setOnClickListener {
            if (selectedDeviceKey == null) {
                showToast("No device selected")
                return@setOnClickListener
            }

            showInputDialog("Set RTC", "RTC") { value ->
                lifecycleScope.launch { controller.setRtc(value) }
            }
        }

        getRtcButton.setOnClickListener {
            if (selectedDeviceKey == null) {
                showToast("No device selected")
                return@setOnClickListener
            }

            lifecycleScope.launch { controller.getRtc() }
        }

        setParamButton.setOnClickListener {
            if (selectedDeviceKey == null) {
                showToast("No device selected")
                return@setOnClickListener
            }

            showSetParamDialog { paramName, paramValue ->
                lifecycleScope.launch { controller.setParam(paramName, paramValue) }
            }
        }

        deviceList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val device = deviceAdapter.getItem(position)
            val nextKey = if (device?.key == selectedDeviceKey) null else device?.key
            selectedDeviceKey = nextKey
            lifecycleScope.launch { controller.selectDevice(nextKey) }
        }
    }

    private fun prefillSecret() {
        secretInput.setText(controller.currentSecret().orEmpty())
    }

    private fun observeController() {
        lifecycleScope.launch {
            controller.state.collect { state ->
                updateState(state)
            }
        }
    }

    private fun updateState(state: SessionSnapshot) {
        statusText.text = statusLabel(state)
        statusText.setBackgroundResource(statusBackground(state))

        val selected = state.selectedDeviceKey?.let { key -> state.devicesByKey[key] }
        selectedDeviceKey = state.selectedDeviceKey

        selectedDeviceText.text = headerSubtitle(selected, state)

        val orderedDevices = state.sortedDeviceKeys.mapNotNull { key ->
            state.devicesByKey[key]
        }
        deviceSectionSubtitle.text = if (orderedDevices.isEmpty()) {
            "No devices are currently visible on the network."
        } else {
            "${orderedDevices.size} device${if (orderedDevices.size == 1) "" else "s"} available."
        }
        deviceAdapter.submitList(orderedDevices)
        deviceAdapter.selectedKey = state.selectedDeviceKey

        logText.text = state.logs.takeLast(20).joinToString("\n") { log ->
            log.message
        }

        footerSubtitleText.text = footerSubtitle(selected, orderedDevices)
        deviceCountText.text = orderedDevices.size.toString()
        selectedMetricText.text = selected?.displayNameText() ?: "None"
        activityMetricText.text = if (state.isDiscovering) "Scanning" else "Ready"

        val isConnected = state.status == "connected"
        setCommandEnabled(discoverButton, isConnected && !state.isDiscovering)
        setCommandEnabled(setIpButton, isConnected && selectedDeviceKey != null)
        setCommandEnabled(setRtcButton, isConnected && selectedDeviceKey != null)
        setCommandEnabled(getRtcButton, isConnected && selectedDeviceKey != null)
        setCommandEnabled(setParamButton, isConnected && selectedDeviceKey != null)
    }

    private fun updateLogVisibility() {
        logPanel.visibility = if (showLogs) View.VISIBLE else View.GONE
        logToggleButton.text = if (showLogs) "Hide" else "Log"
        logToggleButton.setBackgroundResource(
            if (showLogs) R.drawable.bg_command_primary else R.drawable.bg_command_secondary
        )
        logToggleButton.setTextColor(
            ContextCompat.getColor(
                this,
                if (showLogs) android.R.color.white else R.color.popoto_primary,
            )
        )
    }

    private fun setCommandEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.55f
    }

    private fun statusLabel(state: SessionSnapshot): String {
        return when (state.status) {
            "connected" -> if (state.isDiscovering) "Discovering" else "Connected"
            "connecting" -> "Connecting"
            else -> "Offline"
        }
    }

    private fun statusBackground(state: SessionSnapshot): Int {
        return when (state.status) {
            "connected" -> if (state.isDiscovering) {
                R.drawable.bg_status_discovering
            } else {
                R.drawable.bg_status_connected
            }
            else -> R.drawable.bg_status_offline
        }
    }

    private fun headerSubtitle(selected: HydrophoneDevice?, state: SessionSnapshot): String {
        if (selected != null) {
            return "${selected.model.displayValue()} | ${selected.ipAddress.displayValue()} | ${selected.networkSummaryText()}"
        }

        val deviceCount = state.sortedDeviceKeys.size
        return if (deviceCount == 0) {
            "No devices selected"
        } else {
            "$deviceCount device${if (deviceCount == 1) "" else "s"} available"
        }
    }

    private fun footerSubtitle(selected: HydrophoneDevice?, devices: List<HydrophoneDevice>): String {
        if (selected != null) {
            return "Selected device: ${selected.displayNameText()}"
        }

        if (devices.isEmpty()) {
            return "Run a network discovery to locate nearby Popoto hardware."
        }

        return "Choose a device to configure IP, time sync, and runtime parameters."
    }

    private fun showSetIpDialog() {
        val selected = currentSelectedDevice()
        val defaultIp = selected?.ipAddress.validIpv4OrNull() ?: "10.0.0.238"
        val defaultNetmask = selected?.netmask.validIpv4OrNull() ?: "255.255.255.0"
        val defaultGateway = selected?.gateway.validIpv4OrNull() ?: defaultIp.suggestedGateway().orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 10)
        }

        fun fieldLabel(text: String): TextView =
            TextView(this).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.popoto_heading))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 12, 0, 0)
            }

        val summary = TextView(this).apply {
            text = selected?.let { device ->
                "Selected: ${device.displayNameText()}\nCurrent: ${device.ipAddress.displayValue()} | ${device.networkSummaryText()}"
            } ?: "Selected modem"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.popoto_heading))
            textSize = 13f
            setPadding(0, 0, 0, 14)
        }
        val ipInput = EditText(this).apply {
            hint = "New IP"
            setText(defaultIp)
            setPadding(0, 8, 0, 8)
        }
        val netmaskInput = EditText(this).apply {
            hint = "Netmask"
            setText(defaultNetmask)
            setPadding(0, 8, 0, 8)
        }
        val gatewayInput = EditText(this).apply {
            hint = "Gateway"
            setText(defaultGateway)
            setPadding(0, 8, 0, 8)
        }

        root.addView(summary)
        root.addView(fieldLabel("Static IP address"))
        root.addView(ipInput)
        root.addView(fieldLabel("Netmask"))
        root.addView(netmaskInput)
        root.addView(fieldLabel("Gateway"))
        root.addView(gatewayInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Set IP")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val ip = ipInput.text.toString().trim()
            val mask = netmaskInput.text.toString().trim()
            val gateway = gatewayInput.text.toString().trim().ifEmpty { "0.0.0.0" }

            if (!ip.isValidIpv4() || !mask.isValidIpv4() || !gateway.isValidIpv4()) {
                showToast("Enter a valid static IP, netmask, and gateway")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                controller.setIp(
                    newIp = ip,
                    netmask = mask,
                    gateway = gateway,
                )
            }
            dialog.dismiss()
        }
    }

    private fun currentSelectedDevice(): HydrophoneDevice? {
        val key = selectedDeviceKey ?: return null
        return controller.state.value.devicesByKey[key]
    }

    private fun showInputDialog(title: String, hint: String, onSubmit: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(24, 18, 24, 0)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    onSubmit(value)
                } else {
                    showToast("Value required")
                }
            }
            .show()
    }

    private fun showSetParamDialog(onSubmit: (String, String) -> Unit) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 10)
        }

        val nameInput = EditText(this).apply {
            hint = "Parameter name"
            setPadding(0, 8, 0, 8)
        }
        val valueInput = EditText(this).apply {
            hint = "Parameter value"
            setPadding(0, 8, 0, 8)
        }

        root.addView(nameInput)
        root.addView(valueInput)

        AlertDialog.Builder(this)
            .setTitle("Set Param")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val paramName = nameInput.text.toString().trim()
                val paramValue = valueInput.text.toString().trim()

                if (paramName.isNotBlank() && paramValue.isNotBlank()) {
                    onSubmit(paramName, paramValue)
                } else {
                    showToast("Both name and value are required")
                }
            }
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private class DeviceAdapter(context: Context) : ArrayAdapter<HydrophoneDevice>(context, R.layout.item_device) {
    private val items = mutableListOf<HydrophoneDevice>()
    private val inflater = LayoutInflater.from(context)
    var selectedKey: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submitList(newItems: List<HydrophoneDevice>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): HydrophoneDevice? = items[position]

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_device, parent, false).also {
            it.tag = DeviceViewHolder(it)
        }
        val holder = view.tag as DeviceViewHolder

        val device = getItem(position) ?: return view
        val isSelected = device.key == selectedKey

        holder.card.setBackgroundResource(
            if (isSelected) R.drawable.bg_device_card_selected else R.drawable.bg_device_card
        )
        holder.title.text = device.displayNameText()
        holder.subtitle.text = device.model.displayValue()
        holder.selectedChip.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.networkChip.text = device.networkSummaryText()
        holder.networkChip.setTextColor(ContextCompat.getColor(context, device.networkColorRes()))
        holder.ip.text = device.ipAddress.displayValue()
        holder.mac.text = device.macAddress.shortMac()
        holder.serial.text = device.serial.displayValue()
        holder.firmware.text = device.firmwareVersion.shortFirmware()

        val recordingState = device.recordingState?.trim().orEmpty()
        if (recordingState.isBlank()) {
            holder.recordingChip.visibility = View.GONE
        } else {
            holder.recordingChip.visibility = View.VISIBLE
            holder.recordingChip.text = recordingState.replaceFirstChar { it.uppercase() }
            holder.recordingChip.setTextColor(recordingColor(recordingState))
        }

        holder.metrics.text = buildString {
            append("Battery ")
            append(device.batteryVoltage?.let { String.format("%.1f V", it) } ?: "-")
            append("   |   Sample ")
            append(device.sampleRate.formatSampleRate())
            append("\nStorage ")
            append(device.storageText())
            append("   |   RTC ")
            append(device.rtc.displayValue())
        }
        holder.metrics.setTextColor(ContextCompat.getColor(context, R.color.popoto_muted))

        return view
    }

    private fun recordingColor(state: String): Int {
        val color = when (state.lowercase()) {
            "recording", "active" -> R.color.popoto_error
            else -> R.color.popoto_success
        }
        return ContextCompat.getColor(context, color)
    }

    private class DeviceViewHolder(view: View) {
        val card: LinearLayout = view.findViewById(R.id.deviceCard)
        val title: TextView = view.findViewById(R.id.deviceTitle)
        val subtitle: TextView = view.findViewById(R.id.deviceSubtitle)
        val selectedChip: TextView = view.findViewById(R.id.selectedChip)
        val recordingChip: TextView = view.findViewById(R.id.recordingChip)
        val networkChip: TextView = view.findViewById(R.id.networkChip)
        val ip: TextView = view.findViewById(R.id.ipValue)
        val mac: TextView = view.findViewById(R.id.macValue)
        val serial: TextView = view.findViewById(R.id.serialValue)
        val firmware: TextView = view.findViewById(R.id.firmwareValue)
        val metrics: TextView = view.findViewById(R.id.deviceMetrics)
    }
}

private fun HydrophoneDevice.displayNameText(): String =
    name?.trim()?.takeIf { it.isNotBlank() }
        ?: ipAddress?.trim()?.takeIf { it.isNotBlank() }
        ?: key

private fun String?.displayValue(): String =
    this?.trim()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) } ?: "-"

private fun String?.validIpv4OrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() && it != "0.0.0.0" } ?: return null
    return value.takeIf { it.isValidIpv4() }
}

private fun String.isValidIpv4(): Boolean {
    val parts = split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val number = part.toIntOrNull()
        number != null && number in 0..255
    }
}

private fun String.suggestedGateway(): String? {
    val parts = split(".").toMutableList()
    if (parts.size != 4) return null
    parts[3] = "1"
    return parts.joinToString(".")
}

private fun String?.shortMac(): String {
    val value = displayValue()
    if (value == "-") return value
    return if (value.length > 12) value.takeLast(8) else value
}

private fun String?.shortFirmware(): String {
    val value = displayValue()
    if (value == "-") return value
    val plusIndex = value.indexOf('+')
    val clean = if (plusIndex > 0) value.substring(0, plusIndex) else value
    return if (clean.length > 16) clean.take(16) + "..." else clean
}

private fun HydrophoneDevice.networkSummaryText(): String {
    val mode = activeMode.networkModeText() ?: configuredMode.networkModeText()
    val topology = topologyHint.topologyText()
    val parts = listOfNotNull(mode, topology)
    return if (parts.isEmpty()) "Network unknown" else parts.joinToString(" | ")
}

private fun HydrophoneDevice.networkColorRes(): Int {
    return when {
        hasNetworkWarning() -> R.color.popoto_warning
        networkSummaryText() == "Network unknown" -> R.color.popoto_muted
        else -> R.color.popoto_success
    }
}

private fun HydrophoneDevice.hasNetworkWarning(): Boolean {
    val active = activeMode.normalizedToken()
    val link = linkState.normalizedToken()
    val topology = topologyHint.normalizedToken()

    if (active == "fallback" || active == "fallback_static") return true
    if (link == "down" || topology == "no_link" || topology == "gateway_unreachable") return true

    return gatewayReachable == false && gateway.displayValue() != "-"
}

private fun String?.networkModeText(): String? {
    val normalized = normalizedToken() ?: return null
    return when (normalized) {
        "static" -> "Static"
        "fallback", "fallback_static" -> "Fallback Static"
        else -> humanizedToken()
    }
}

private fun String?.topologyText(): String? {
    val normalized = normalizedToken() ?: return null
    return when (normalized) {
        "routed" -> "Routed"
        "direct", "direct_or_isolated" -> "Direct"
        "gateway_unreachable" -> "Gateway Issue"
        "no_link" -> "No Link"
        else -> humanizedToken()
    }
}

private fun String?.humanizedToken(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return value
        .replace("-", "_")
        .split("_")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { character -> character.uppercase() }
        }
        .takeIf { it.isNotBlank() }
}

private fun String?.normalizedToken(): String? =
    this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

private fun Int?.formatSampleRate(): String {
    val rate = this ?: return "-"
    return if (rate >= 1000) {
        String.format("%.1f kHz", rate / 1000.0)
    } else {
        "$rate Hz"
    }
}

private fun HydrophoneDevice.storageText(): String {
    val used = storageUsedGb
    val total = storageTotalGb
    return if (used != null && total != null) {
        String.format("%.1f / %.1f GB", used.coerceIn(0.0, total), total)
    } else {
        "-"
    }
}
