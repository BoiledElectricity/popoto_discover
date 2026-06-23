package com.popotomodem.discover

import kotlinx.serialization.json.JsonPrimitive

private const val UI_KEY_FIELD = "_ui_key"
private const val MAC_DISPLAY_FIELD = "_mac_display"
private val INVALID_IDENTITY_TEXT = setOf(
    "0",
    "none",
    "null",
    "no_device_id",
    "no device id",
    "0.0.0.0",
    "unknown",
    "unknown element",
)

fun usableIdentity(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) {
        return null
    }
    if (text.all { it == '0' }) {
        return null
    }
    val normalized = text.lowercase()
    if (normalized in INVALID_IDENTITY_TEXT || normalized.startsWith("unknown-")) {
        return null
    }
    return text
}

fun Device.deviceIdText(): String? = usableIdentity(text("device_id")) ?: usableIdentity(text("cpu_uid"))

fun usableMac(value: String?): String? {
    val mac = value?.trim()?.replace('-', ':')?.lowercase().orEmpty()
    if (mac.isEmpty() || mac.equals("unknown", ignoreCase = true)) {
        return null
    }
    if (!Protocol.validateMacAddress(mac) || mac == "00:00:00:00:00:00") {
        return null
    }
    return mac
}

fun Device.macText(): String = usableMac(text("mac")) ?: "unknown"

fun Device.displayMacText(): String = text(MAC_DISPLAY_FIELD) ?: macText()

fun Device.matchesMac(targetMac: String): Boolean {
    val normalized = usableMac(targetMac) ?: return false
    if (usableMac(text("mac"))?.equals(normalized, ignoreCase = true) == true) {
        return true
    }
    return paths.any { path ->
        usableMac(path.sourceMac)?.equals(normalized, ignoreCase = true) == true
    }
}

fun Device.displayNameText(): String = text("name") ?: text("model") ?: "PMM"

fun Device.uiKeyText(): String? = text(UI_KEY_FIELD)

fun Device.sshHostText(): String? =
    paths.firstOrNull { it.transport.equals("udp", ignoreCase = true) }
        ?.sourceIp
        ?.let(::usableIdentity)
        ?: usableIdentity(text("ip"))

fun annotateDiscoveredDevices(devices: List<Device>): List<Device> {
    val macCounts = devices
        .mapNotNull { usableMac(it.text("mac")) }
        .groupingBy { it }
        .eachCount()
    val seenUiKeys = mutableMapOf<String, Int>()

    devices.forEachIndexed { index, device ->
        val uiBase = device.deviceIdText()?.let { "cpu:${it.lowercase()}" }
            ?: fallbackUiKey(device, index)
        val occurrence = (seenUiKeys[uiBase] ?: 0) + 1
        seenUiKeys[uiBase] = occurrence
        device.fields[UI_KEY_FIELD] = JsonPrimitive(
            if (occurrence == 1) uiBase else "$uiBase#$occurrence",
        )

        val mac = usableMac(device.text("mac"))
        device.fields[MAC_DISPLAY_FIELD] = JsonPrimitive(
            when {
                mac == null -> "unknown"
                macCounts.getOrDefault(mac, 0) > 1 -> "$mac (dup)"
                else -> mac
            },
        )
    }
    return devices
}

fun Device.serialText(): String {
    val serial = usableIdentity(text("serial")) ?: return "unknown"
    val identitySource = text("identity_source")?.trim().orEmpty()
    val deviceId = usableIdentity(text("device_id"))
    val cpuUid = usableIdentity(text("cpu_uid"))
    if (identitySource.equals("cpu_uid", ignoreCase = true) &&
        (serial.equals(deviceId, ignoreCase = true) || serial.equals(cpuUid, ignoreCase = true))
    ) {
        return "unknown"
    }
    return serial
}

private fun fallbackUiKey(device: Device, index: Int): String {
    val path = device.paths.firstOrNull()
    return buildList {
        path?.transport?.let { add("transport:$it") }
        path?.interfaceName?.let { add("if:$it") }
        path?.sourceIp?.let { add("srcip:$it") }
        path?.sourceMac?.let { add("srcmac:${it.lowercase()}") }
        usableIdentity(device.text("ip"))?.let { add("ip:$it") }
        usableIdentity(device.text("name"))?.let { add("name:$it") }
        usableIdentity(device.text("model"))?.let { add("model:$it") }
        add("row:$index")
    }.joinToString("|", prefix = "unidentified:")
}
