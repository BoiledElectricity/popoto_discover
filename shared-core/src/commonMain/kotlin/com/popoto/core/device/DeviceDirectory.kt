package com.popoto.core.device

data class DeviceMutation(
    val device: HydrophoneDevice,
    val isNewDevice: Boolean,
    val shouldQueryRtc: Boolean,
    val shouldQueryVersion: Boolean,
)

class DeviceDirectory {
    private val devices = linkedMapOf<String, HydrophoneDevice>()
    private var selectedDevice: HydrophoneDevice? = null

    fun devicesByKey(): Map<String, HydrophoneDevice> = devices.toMap()

    fun selectedDevice(): HydrophoneDevice? = selectedDevice

    fun sortedDevices(): List<HydrophoneDevice> {
        return devices.values.sortedWith { lhs, rhs ->
            val lhsName = lhs.name?.lowercase()
                ?: lhs.model?.lowercase()
                ?: lhs.ipAddress?.lowercase()
                ?: lhs.uniqueKey
            val rhsName = rhs.name?.lowercase()
                ?: rhs.model?.lowercase()
                ?: rhs.ipAddress?.lowercase()
                ?: rhs.uniqueKey

            when {
                lhsName < rhsName -> -1
                lhsName > rhsName -> 1
                lhs.uniqueKey < rhs.uniqueKey -> -1
                lhs.uniqueKey > rhs.uniqueKey -> 1
                else -> 0
            }
        }
    }

    fun selectDevice(device: HydrophoneDevice?) {
        selectedDevice = device
    }

    fun selectDeviceByKey(uniqueKey: String?) {
        selectedDevice = uniqueKey?.let { key -> devices[key] }
    }

    fun clear() {
        devices.clear()
        selectedDevice = null
    }

    fun addOrUpdateDevice(device: HydrophoneDevice, nowEpochMillis: Long): DeviceMutation {
        val sanitizedDevice = device.sanitizedForStorage()
        val incomingKey = sanitizedDevice.uniqueKey
        val matchedKey = if (devices.containsKey(incomingKey)) incomingKey else findMatchingKey(sanitizedDevice)

        if (matchedKey != null) {
            val existing = devices.getValue(matchedKey)
            val merged = existing.mergedWith(sanitizedDevice)
            val finalKey = merged.uniqueKey

            if (matchedKey != finalKey) {
                devices.remove(matchedKey)
            }

            devices[finalKey] = merged

            if (selectedDevice?.matches(merged) == true) {
                selectedDevice = merged
            }

            return DeviceMutation(
                device = merged,
                isNewDevice = false,
                shouldQueryRtc = shouldRefreshRtc(merged, nowEpochMillis),
                shouldQueryVersion = merged.needsVersionRefresh() && existing.needsVersionRefresh(),
            )
        }

        devices[incomingKey] = sanitizedDevice
        return DeviceMutation(
            device = sanitizedDevice,
            isNewDevice = true,
            shouldQueryRtc = sanitizedDevice.targetDeviceId() != null,
            shouldQueryVersion = sanitizedDevice.needsVersionRefresh(),
        )
    }

    fun mergeDevice(
        preferredKey: String?,
        incoming: HydrophoneDevice,
        nowEpochMillis: Long,
    ): HydrophoneDevice? {
        val sanitizedIncoming = incoming.sanitizedForStorage()
        val incomingKey = sanitizedIncoming.uniqueKey
        val key = when {
            preferredKey != null && devices.containsKey(preferredKey) -> preferredKey
            devices.containsKey(incomingKey) -> incomingKey
            else -> findMatchingKey(sanitizedIncoming)
        } ?: return null

        val existing = devices.getValue(key)
        val merged = existing.mergedWith(
            sanitizedIncoming.copy(lastSeenEpochMillis = maxOf(existing.lastSeenEpochMillis, nowEpochMillis))
        )
        val finalKey = merged.uniqueKey

        if (key != finalKey) {
            devices.remove(key)
        }

        devices[finalKey] = merged

        if (selectedDevice?.uniqueKey == key || selectedDevice?.matches(merged) == true) {
            selectedDevice = merged
        }

        return merged
    }

    fun applyRtc(device: HydrophoneDevice, rtc: String, queryEpochMillis: Long): HydrophoneDevice? {
        return applyRtc(
            preferredKey = null,
            device = device,
            rtc = rtc,
            queryEpochMillis = queryEpochMillis,
        )
    }

    fun applyRtc(
        preferredKey: String?,
        device: HydrophoneDevice,
        rtc: String,
        queryEpochMillis: Long,
    ): HydrophoneDevice? {
        val originalKey = device.uniqueKey
        val key = when {
            preferredKey != null && devices.containsKey(preferredKey) -> preferredKey
            devices.containsKey(originalKey) -> originalKey
            else -> findMatchingKey(device)
        } ?: return null
        val updated = devices.getValue(key).copy(rtc = rtc, rtcQueryEpochMillis = queryEpochMillis)
        val finalKey = updated.uniqueKey

        if (key != finalKey) {
            devices.remove(key)
        }

        devices[finalKey] = updated

        if (selectedDevice?.matches(updated) == true) {
            selectedDevice = updated
        }

        return updated
    }

    fun shouldRefreshRtc(
        device: HydrophoneDevice,
        nowEpochMillis: Long,
        refreshIntervalMillis: Long = 30_000L,
    ): Boolean {
        if (device.targetDeviceId() == null) {
            return false
        }

        val lastQuery = device.rtcQueryEpochMillis ?: return true
        return nowEpochMillis - lastQuery > refreshIntervalMillis
    }

    private fun findMatchingKey(device: HydrophoneDevice): String? {
        return devices.entries.firstOrNull { (_, existing) -> existing.matches(device) }?.key
    }
}
