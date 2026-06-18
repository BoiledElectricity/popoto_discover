package com.popotomodem.discover

fun usableIdentity(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) {
        return null
    }
    if (text.equals("unknown", ignoreCase = true) || text.startsWith("UNKNOWN-", ignoreCase = true)) {
        return null
    }
    return text
}

fun Device.deviceIdText(): String? = usableIdentity(text("device_id")) ?: usableIdentity(text("cpu_uid"))

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
