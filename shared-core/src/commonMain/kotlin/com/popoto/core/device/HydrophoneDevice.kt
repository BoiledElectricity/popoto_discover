package com.popoto.core.device

import com.popoto.core.time.RtcCodec

data class HydrophoneDevice(
    val localId: String = LocalDeviceIdGenerator.nextId(),
    val name: String? = null,
    val deviceId: String? = null,
    val cpuUid: String? = null,
    val identitySource: String? = null,
    val model: String? = null,
    val serial: String? = null,
    val ipAddress: String? = null,
    val macAddress: String? = null,
    val interfaceName: String? = null,
    val configuredMode: String? = null,
    val activeMode: String? = null,
    val linkState: String? = null,
    val topologyHint: String? = null,
    val gatewayReachable: Boolean? = null,
    val netmask: String? = null,
    val gateway: String? = null,
    val firmwareVersion: String? = null,
    val batteryVoltage: Double? = null,
    val sampleRate: Int? = null,
    val rtc: String? = null,
    val storageUsedGb: Double? = null,
    val storageTotalGb: Double? = null,
    val recordingState: String? = null,
    val lastSeenEpochMillis: Long = 0L,
    val rtcQueryEpochMillis: Long? = null,
) {
    val uniqueKey: String
        get() = stableDiscoveryKey()

    fun targetDeviceId(): String? = meaningfulDeviceId(deviceId) ?: meaningfulDeviceId(cpuUid)

    fun sanitizedForStorage(): HydrophoneDevice {
        val sanitizedDeviceId = meaningfulDeviceId(deviceId)
        val sanitizedCpuUid = meaningfulDeviceId(cpuUid)
        val sanitizedIdentitySource = trimmedNonEmptyValue(identitySource)
        val sanitizedModel = trimmedNonEmptyValue(model)?.takeIf(::isMeaningfulModelValue)
        val sanitizedSerial = reportedSerialValue(serial)
        val sanitizedIpAddress = trimmedNonEmptyValue(ipAddress)?.takeIf(::isMeaningfulIpAddress)
        val sanitizedFirmwareVersion = trimmedNonEmptyValue(firmwareVersion)?.takeIf(::isMeaningfulFirmwareValue)
        val sanitizedMacAddress = trimmedNonEmptyValue(macAddress)
        val sanitizedInterfaceName = trimmedNonEmptyValue(interfaceName)
        val sanitizedConfiguredMode = trimmedNonEmptyValue(configuredMode)
        val sanitizedActiveMode = trimmedNonEmptyValue(activeMode)
        val sanitizedLinkState = trimmedNonEmptyValue(linkState)
        val sanitizedTopologyHint = trimmedNonEmptyValue(topologyHint)
        val sanitizedNetmask = trimmedNonEmptyValue(netmask)
        val sanitizedGateway = trimmedNonEmptyValue(gateway)?.takeIf(::isMeaningfulIpAddress)
        val sanitizedName = trimmedNonEmptyValue(name)?.takeIf(::isMeaningfulNameValue)
            ?: synthesizedName(sanitizedModel, sanitizedSerial)

        return copy(
            name = sanitizedName,
            deviceId = sanitizedDeviceId,
            cpuUid = sanitizedCpuUid,
            identitySource = sanitizedIdentitySource,
            model = sanitizedModel,
            serial = sanitizedSerial,
            ipAddress = sanitizedIpAddress,
            macAddress = sanitizedMacAddress,
            interfaceName = sanitizedInterfaceName,
            configuredMode = sanitizedConfiguredMode,
            activeMode = sanitizedActiveMode,
            linkState = sanitizedLinkState,
            topologyHint = sanitizedTopologyHint,
            netmask = sanitizedNetmask,
            gateway = sanitizedGateway,
            firmwareVersion = sanitizedFirmwareVersion,
        )
    }

    fun needsVersionRefresh(): Boolean {
        return reportedSerialValue(serial) == null || meaningfulFirmwareValue(firmwareVersion) == null
    }

    fun matches(other: HydrophoneDevice): Boolean {
        val strongKeys = strongMatchKeys()
        val otherStrongKeys = other.strongMatchKeys()
        return strongKeys.intersect(otherStrongKeys).isNotEmpty()
    }

    fun mergedWith(incoming: HydrophoneDevice): HydrophoneDevice {
        val mergedModel = mergeStringValue(model, incoming.model, ::isMeaningfulModelValue)
        val mergedSerial = mergeStringValue(serial, incoming.serial, ::isReportedSerialValue)
        val mergedName = mergeName(
            existing = name,
            incoming = incoming.name,
            model = mergedModel,
            serial = mergedSerial,
        )

        return copy(
            name = mergedName,
            deviceId = mergeStringValue(deviceId, incoming.deviceId, ::isMeaningfulDeviceId),
            cpuUid = mergeStringValue(cpuUid, incoming.cpuUid, ::isMeaningfulDeviceId),
            identitySource = mergePlainStringValue(identitySource, incoming.identitySource),
            model = mergedModel,
            serial = mergedSerial,
            ipAddress = mergeStringValue(ipAddress, incoming.ipAddress, ::isMeaningfulIpAddress),
            macAddress = mergeStringValue(macAddress, incoming.macAddress, ::isMeaningfulMacAddress),
            interfaceName = mergePlainStringValue(interfaceName, incoming.interfaceName),
            configuredMode = mergePlainStringValue(configuredMode, incoming.configuredMode),
            activeMode = mergePlainStringValue(activeMode, incoming.activeMode),
            linkState = mergePlainStringValue(linkState, incoming.linkState),
            topologyHint = mergePlainStringValue(topologyHint, incoming.topologyHint),
            gatewayReachable = incoming.gatewayReachable ?: gatewayReachable,
            netmask = mergePlainStringValue(netmask, incoming.netmask),
            gateway = mergeStringValue(gateway, incoming.gateway, ::isMeaningfulIpAddress),
            firmwareVersion = mergeStringValue(firmwareVersion, incoming.firmwareVersion, ::isMeaningfulFirmwareValue),
            batteryVoltage = incoming.batteryVoltage ?: batteryVoltage,
            sampleRate = incoming.sampleRate ?: sampleRate,
            rtc = incoming.rtc ?: rtc,
            storageUsedGb = incoming.storageUsedGb ?: storageUsedGb,
            storageTotalGb = incoming.storageTotalGb ?: storageTotalGb,
            recordingState = incoming.recordingState ?: recordingState,
            lastSeenEpochMillis = maxOf(lastSeenEpochMillis, incoming.lastSeenEpochMillis),
            rtcQueryEpochMillis = incoming.rtcQueryEpochMillis ?: rtcQueryEpochMillis,
        )
    }

    fun interpolatedRtc(nowEpochMillis: Long, rtcCodec: RtcCodec): String? {
        val rtcValue = rtc ?: return null
        val queryEpoch = rtcQueryEpochMillis ?: return rtcValue
        val rtcEpoch = rtcCodec.parseUtcMillis(rtcValue) ?: return rtcValue
        return rtcCodec.formatUtcMillis(rtcEpoch + (nowEpochMillis - queryEpoch))
    }

    fun storagePercentage(): Double? {
        val used = clampedStorageUsedGb() ?: return null
        val total = storageTotalGb ?: return null
        if (total <= 0.0) {
            return null
        }
        return (used / total) * 100.0
    }

    fun storageFreeGb(): Double? {
        val used = clampedStorageUsedGb() ?: return null
        val total = storageTotalGb ?: return null
        if (total < 0.0) {
            return null
        }
        return maxOf(total - used, 0.0)
    }

    fun clampedStorageUsedGb(): Double? {
        val used = storageUsedGb ?: return null
        val total = storageTotalGb ?: return null
        if (total < 0.0) {
            return null
        }
        return used.coerceIn(0.0, total)
    }

    private fun strongMatchKeys(): Set<String> = buildSet {
        meaningfulDeviceId(deviceId)?.let { add("device:$it") }
        meaningfulDeviceId(cpuUid)?.let { add("cpu:$it") }
    }

    private fun fallbackMatchKey(): String? {
        val normalizedName = meaningfulNameValue(name)
        val normalizedModel = meaningfulModelValue(model)
        return if (normalizedName != null && normalizedModel != null) {
            "device:$normalizedModel|$normalizedName"
        } else {
            null
        }
    }

    private fun stableDiscoveryKey(): String {
        meaningfulDeviceId(deviceId)?.let { return "cpu:$it" }
        meaningfulDeviceId(cpuUid)?.let { return "cpu:$it" }
        return "local:$localId"
    }

    private fun mergeName(
        existing: String?,
        incoming: String?,
        model: String?,
        serial: String?,
    ): String? {
        val merged = mergeStringValue(existing, incoming, ::isMeaningfulNameValue)
        if (isMeaningfulNameValue(merged)) {
            return merged?.trim()
        }

        return synthesizedName(model, serial) ?: merged?.trim()
    }

    private fun mergePlainStringValue(existing: String?, incoming: String?): String? {
        return mergeStringValue(existing, incoming, ::isMeaningfulPlainValue)
    }

    private fun synthesizedName(model: String?, serial: String?): String? {
        val modelValue = trimmedNonEmptyValue(model) ?: return null
        val serialValue = trimmedNonEmptyValue(serial) ?: return null
        if (!isMeaningfulModelValue(modelValue) || !isReportedSerialValue(serialValue)) {
            return null
        }

        return "$modelValue-$serialValue"
    }

    private fun mergeStringValue(
        existing: String?,
        incoming: String?,
        isMeaningful: (String?) -> Boolean,
    ): String? {
        val existingValue = trimmedNonEmptyValue(existing)
        val incomingValue = trimmedNonEmptyValue(incoming)

        return when {
            incomingValue == null -> existingValue
            isMeaningful(incomingValue) -> incomingValue
            existingValue != null -> existingValue
            else -> incomingValue
        }
    }

    private fun trimmedNonEmptyValue(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) null else trimmed
    }

    private fun normalizedValue(value: String?): String? {
        return trimmedNonEmptyValue(value)?.lowercase()
    }

    private fun meaningfulNameValue(value: String?): String? {
        val normalized = normalizedValue(value) ?: return null
        return normalized.takeIf(::isMeaningfulNameValue)
    }

    private fun meaningfulModelValue(value: String?): String? {
        val normalized = normalizedValue(value) ?: return null
        return normalized.takeIf(::isMeaningfulModelValue)
    }

    private fun reportedSerialValue(value: String?): String? {
        return trimmedNonEmptyValue(value)
    }

    private fun meaningfulDeviceId(value: String?): String? {
        val normalized = normalizedValue(value) ?: return null
        return normalized.takeIf(::isMeaningfulDeviceId)
    }

    private fun meaningfulIpAddress(value: String?): String? {
        val normalized = normalizedValue(value) ?: return null
        return normalized.takeIf(::isMeaningfulIpAddress)
    }

    private fun meaningfulFirmwareValue(value: String?): String? {
        val normalized = normalizedValue(value) ?: return null
        return normalized.takeIf(::isMeaningfulFirmwareValue)
    }

    private fun meaningfulMacAddress(value: String?): String? {
        val normalized = normalizedValue(value) ?: return null
        val cleaned = normalized.filter { character -> character.isLetterOrDigit() }
        return cleaned.takeIf(::isMeaningfulMacAddress)
    }

    private fun isMeaningfulNameValue(value: String?): Boolean {
        val normalized = normalizedValue(value) ?: return false
        return !isPlaceholderText(normalized) && !normalized.contains("unknown")
    }

    private fun isMeaningfulModelValue(value: String?): Boolean {
        val normalized = normalizedValue(value) ?: return false
        return !isPlaceholderText(normalized)
    }

    private fun isReportedSerialValue(value: String?): Boolean {
        return trimmedNonEmptyValue(value) != null
    }

    private fun isMeaningfulDeviceId(value: String?): Boolean {
        val normalized = normalizedValue(value) ?: return false
        val compact = normalized.filter { character -> character.isLetterOrDigit() }
        val isFactoryPlaceholder = compact.isNotEmpty() &&
            (compact.all { character -> character == '0' } || compact.all { character -> character == 'f' })
        return !isPlaceholderText(normalized) && !normalized.startsWith("unknown") && !isFactoryPlaceholder
    }

    private fun isMeaningfulIpAddress(value: String?): Boolean {
        val normalized = normalizedValue(value) ?: return false
        return normalized != "0.0.0.0" && !isPlaceholderText(normalized)
    }

    private fun isMeaningfulFirmwareValue(value: String?): Boolean {
        val normalized = normalizedValue(value) ?: return false
        return !isPlaceholderText(normalized)
    }

    private fun isMeaningfulPlainValue(value: String?): Boolean {
        return trimmedNonEmptyValue(value) != null
    }

    private fun isMeaningfulMacAddress(value: String?): Boolean {
        val normalized = normalizedValue(value) ?: return false
        val cleaned = normalized.filter { character -> character.isLetterOrDigit() }
        return cleaned.isNotEmpty() && cleaned.any { it != '0' }
    }

    private fun isPlaceholderText(value: String): Boolean {
        return value == "unknown" ||
            value == "n/a" ||
            value == "na" ||
            value == "none" ||
            value == "null" ||
            value == "-" ||
            value == "--" ||
            value == "unavailable"
    }
}

private object LocalDeviceIdGenerator {
    private var counter = 0L

    fun nextId(): String {
        counter += 1
        return counter.toString(16)
    }
}
