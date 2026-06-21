package com.popoto.core.session

import com.popoto.core.device.DeviceDirectory
import com.popoto.core.device.HydrophoneDevice
import com.popoto.core.message.DiscoverHydrophoneMessage
import com.popoto.core.message.DiscoverReplyMessage
import com.popoto.core.message.GetRtcMessage
import com.popoto.core.message.GetRtcReplyMessage
import com.popoto.core.message.GetVersionMessage
import com.popoto.core.message.GetVersionReplyMessage
import com.popoto.core.message.ProtocolCommand
import com.popoto.core.message.ProtocolCodec
import com.popoto.core.message.ProtocolEncoder
import com.popoto.core.message.SetIpMessage
import com.popoto.core.message.SetIpReplyMessage
import com.popoto.core.message.SetParamMessage
import com.popoto.core.message.SetParamReplyMessage
import com.popoto.core.message.SetRtcMessage
import com.popoto.core.message.SetRtcReplyMessage
import com.popoto.core.message.ShellExecMessage
import com.popoto.core.message.ShellExecReplyMessage

data class SessionLog(
    val message: String,
    val level: String,
    val timestampEpochMillis: Long,
)

data class OutboundPacket(
    val nonce: String,
    val payloadJson: String,
    val timeoutMillis: Long,
)

data class CompletedOperation(
    val nonce: String,
    val type: String,
    val success: Boolean,
    val message: String,
    val rtc: String? = null,
    val rediscoverDelayMillis: Long? = null,
)

data class SessionSnapshot(
    val status: String,
    val isDiscovering: Boolean,
    val selectedDeviceKey: String?,
    val devicesByKey: Map<String, HydrophoneDevice>,
    val sortedDeviceKeys: List<String>,
    val logs: List<SessionLog>,
)

data class SessionMutation(
    val snapshot: SessionSnapshot,
    val outboundPackets: List<OutboundPacket> = emptyList(),
    val completedOperations: List<CompletedOperation> = emptyList(),
)

private enum class PendingOperationType(val wireValue: String) {
    DISCOVER("discover"),
    SET_IP("set_ip"),
    SET_RTC("set_rtc"),
    GET_RTC("get_rtc"),
    SET_PARAM("set_param"),
    SHELL_EXEC("shell_exec"),
    AUTO_GET_VERSION("auto_get_version"),
    AUTO_GET_RTC("auto_get_rtc"),
}

private data class PendingOperation(
    val type: PendingOperationType,
    val targetId: String? = null,
    val targetDeviceKey: String? = null,
    val observedDeviceKeys: MutableSet<String> = linkedSetOf(),
)

class SessionEngine(
    private val protocolEncoder: ProtocolEncoder,
) {
    private val directory = DeviceDirectory()
    private val logs = mutableListOf<SessionLog>()
    private val pendingOperations = linkedMapOf<String, PendingOperation>()

    private var status = "disconnected"
    private var isDiscovering = false
    private var nonceCounter = 0L

    fun initialize(nowEpochMillis: Long): SessionMutation {
        status = "connected"
        addLog("Service initialized", level = "success", timestampEpochMillis = nowEpochMillis)
        return mutation()
    }

    fun shutdown(): SessionMutation {
        status = "disconnected"
        isDiscovering = false
        pendingOperations.clear()
        return mutation()
    }

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        status = status,
        isDiscovering = isDiscovering,
        selectedDeviceKey = directory.selectedDevice()?.uniqueKey,
        devicesByKey = directory.devicesByKey(),
        sortedDeviceKeys = directory.sortedDevices().map { device -> device.uniqueKey },
        logs = logs.toList(),
    )

    fun selectDevice(uniqueKey: String?): SessionMutation {
        directory.selectDeviceByKey(uniqueKey)
        return mutation()
    }

    fun clearLogs(): SessionMutation {
        logs.clear()
        return mutation()
    }

    fun startDiscovery(
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
        replyBroadcast: Boolean = false,
    ): SessionMutation {
        if (status != "connected") {
            addLog("Service is not connected", level = "warning", timestampEpochMillis = nowEpochMillis)
            return mutation(
                completedOperations = listOf(
                    CompletedOperation(
                        nonce = "",
                        type = PendingOperationType.DISCOVER.wireValue,
                        success = false,
                        message = "Service is not connected",
                    )
                )
            )
        }

        if (isDiscovering) {
            addLog("Discovery already in progress", level = "warning", timestampEpochMillis = nowEpochMillis)
            return mutation(
                completedOperations = listOf(
                    CompletedOperation(
                        nonce = "",
                        type = PendingOperationType.DISCOVER.wireValue,
                        success = false,
                        message = "Discovery already in progress",
                    )
                )
            )
        }

        clearPreviousDiscoveryResults()

        val nonce = nextNonce(nowEpochMillis)
        val payloadJson = protocolEncoder.encode(
            message = DiscoverHydrophoneMessage(
                nonce = nonce,
                replyBroadcast = replyBroadcast,
            ),
            secret = secret,
        )

        pendingOperations[nonce] = PendingOperation(type = PendingOperationType.DISCOVER)
        isDiscovering = true
        addLog("Starting device discovery...", level = "info", timestampEpochMillis = nowEpochMillis)
        addLog("Broadcasting device discovery...", level = "info", timestampEpochMillis = nowEpochMillis)

        return mutation(
            outboundPackets = listOf(
                OutboundPacket(
                    nonce = nonce,
                    payloadJson = payloadJson,
                    timeoutMillis = timeoutMillis,
                )
            )
        )
    }

    fun startSetIpForSelectedDevice(
        newIp: String,
        netmask: String,
        gateway: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): SessionMutation {
        val selectedDevice = directory.selectedDevice()
            ?: return immediateFailure(PendingOperationType.SET_IP, "No device selected")
        val targetId = selectedDevice.targetDeviceId()
            ?: return immediateFailure(PendingOperationType.SET_IP, "No device selected")

        val nonce = nextNonce(nowEpochMillis)
        val payloadJson = protocolEncoder.encode(
            message = SetIpMessage(
                nonce = nonce,
                targetId = targetId,
                newIp = newIp,
                netmask = netmask,
                gateway = gateway,
            ),
            secret = secret,
        )

        pendingOperations[nonce] = PendingOperation(
            type = PendingOperationType.SET_IP,
            targetId = targetId,
            targetDeviceKey = selectedDevice.uniqueKey,
        )
        addLog("Setting IP for $targetId...", level = "info", timestampEpochMillis = nowEpochMillis)

        return mutation(
            outboundPackets = listOf(
                OutboundPacket(
                    nonce = nonce,
                    payloadJson = payloadJson,
                    timeoutMillis = timeoutMillis,
                )
            )
        )
    }

    fun startSetRtcForSelectedDevice(
        rtc: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): SessionMutation {
        val selectedDevice = directory.selectedDevice()
            ?: return immediateFailure(PendingOperationType.SET_RTC, "No device selected")
        val targetId = selectedDevice.targetDeviceId()
            ?: return immediateFailure(PendingOperationType.SET_RTC, "No device selected")

        val nonce = nextNonce(nowEpochMillis)
        val payloadJson = protocolEncoder.encode(
            message = SetRtcMessage(
                nonce = nonce,
                targetId = targetId,
                rtc = rtc,
            ),
            secret = secret,
        )

        pendingOperations[nonce] = PendingOperation(
            type = PendingOperationType.SET_RTC,
            targetId = targetId,
            targetDeviceKey = selectedDevice.uniqueKey,
        )
        addLog("Setting RTC for $targetId...", level = "info", timestampEpochMillis = nowEpochMillis)

        return mutation(
            outboundPackets = listOf(
                OutboundPacket(
                    nonce = nonce,
                    payloadJson = payloadJson,
                    timeoutMillis = timeoutMillis,
                )
            )
        )
    }

    fun startGetRtcForSelectedDevice(
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): SessionMutation {
        val selectedDevice = directory.selectedDevice()
            ?: return immediateFailure(PendingOperationType.GET_RTC, "No device selected")
        val targetId = selectedDevice.targetDeviceId()
            ?: return immediateFailure(PendingOperationType.GET_RTC, "No device selected")

        val nonce = nextNonce(nowEpochMillis)
        val payloadJson = protocolEncoder.encode(
            message = GetRtcMessage(
                nonce = nonce,
                targetId = targetId,
            ),
            secret = secret,
        )

        pendingOperations[nonce] = PendingOperation(
            type = PendingOperationType.GET_RTC,
            targetId = targetId,
            targetDeviceKey = selectedDevice.uniqueKey,
        )
        addLog("Getting RTC for $targetId...", level = "info", timestampEpochMillis = nowEpochMillis)

        return mutation(
            outboundPackets = listOf(
                OutboundPacket(
                    nonce = nonce,
                    payloadJson = payloadJson,
                    timeoutMillis = timeoutMillis,
                )
            )
        )
    }

    fun startSetParamForSelectedDevice(
        paramName: String,
        paramValue: Any?,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): SessionMutation {
        val selectedDevice = directory.selectedDevice()
            ?: return immediateFailure(PendingOperationType.SET_PARAM, "No device selected")
        val targetId = selectedDevice.targetDeviceId()
            ?: return immediateFailure(PendingOperationType.SET_PARAM, "No device selected")

        val nonce = nextNonce(nowEpochMillis)
        val payloadJson = protocolEncoder.encode(
            message = SetParamMessage(
                nonce = nonce,
                targetId = targetId,
                paramName = paramName,
                paramValue = paramValue,
            ),
            secret = secret,
        )

        pendingOperations[nonce] = PendingOperation(
            type = PendingOperationType.SET_PARAM,
            targetId = targetId,
            targetDeviceKey = selectedDevice.uniqueKey,
        )
        addLog("Setting parameter $paramName...", level = "info", timestampEpochMillis = nowEpochMillis)

        return mutation(
            outboundPackets = listOf(
                OutboundPacket(
                    nonce = nonce,
                    payloadJson = payloadJson,
                    timeoutMillis = timeoutMillis,
                )
            )
        )
    }

    fun startShellExecForSelectedDevice(
        command: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): SessionMutation {
        val selectedDevice = directory.selectedDevice()
            ?: return immediateFailure(PendingOperationType.SHELL_EXEC, "No device selected")
        val targetId = selectedDevice.targetDeviceId()
            ?: return immediateFailure(PendingOperationType.SHELL_EXEC, "Selected device has no CPU UID")

        val nonce = nextNonce(nowEpochMillis)
        val payloadJson = protocolEncoder.encode(
            message = ShellExecMessage(
                nonce = nonce,
                targetId = targetId,
                command = command,
                timeoutSeconds = (timeoutMillis / 1000.0).coerceIn(1.0, 60.0),
            ),
            secret = secret,
        )

        pendingOperations[nonce] = PendingOperation(
            type = PendingOperationType.SHELL_EXEC,
            targetId = targetId,
            targetDeviceKey = selectedDevice.uniqueKey,
        )
        addLog("Running remote command...", level = "info", timestampEpochMillis = nowEpochMillis)

        return mutation(
            outboundPackets = listOf(
                OutboundPacket(
                    nonce = nonce,
                    payloadJson = payloadJson,
                    timeoutMillis = timeoutMillis,
                )
            )
        )
    }

    fun handleIncomingPacket(
        json: String,
        secret: String?,
        receivedAtEpochMillis: Long,
    ): SessionMutation? {
        val payload = runCatching { ProtocolCodec.parseJsonObject(json) }.getOrNull() ?: return null
        val nonce = payload.string("nonce") ?: return null
        val pending = pendingOperations[nonce] ?: return null
        val command = payload.string("cmd") ?: return null

        return when (pending.type) {
            PendingOperationType.DISCOVER -> handleDiscoverReply(
                pending = pending,
                payload = payload,
                command = command,
                secret = secret,
                receivedAtEpochMillis = receivedAtEpochMillis,
            )
            PendingOperationType.SET_IP -> handleSetIpReply(
                nonce = nonce,
                payload = payload,
                command = command,
                receivedAtEpochMillis = receivedAtEpochMillis,
            )
            PendingOperationType.SET_RTC -> handleSetRtcReply(
                nonce = nonce,
                payload = payload,
                command = command,
                receivedAtEpochMillis = receivedAtEpochMillis,
            )
            PendingOperationType.GET_RTC,
            PendingOperationType.AUTO_GET_RTC
            -> handleGetRtcReply(
                nonce = nonce,
                pending = pending,
                payload = payload,
                command = command,
                receivedAtEpochMillis = receivedAtEpochMillis,
            )
            PendingOperationType.AUTO_GET_VERSION -> handleGetVersionReply(
                nonce = nonce,
                pending = pending,
                payload = payload,
                command = command,
                receivedAtEpochMillis = receivedAtEpochMillis,
            )
            PendingOperationType.SET_PARAM -> handleSetParamReply(
                nonce = nonce,
                payload = payload,
                command = command,
                receivedAtEpochMillis = receivedAtEpochMillis,
            )
            PendingOperationType.SHELL_EXEC -> handleShellExecReply(
                nonce = nonce,
                payload = payload,
                command = command,
                receivedAtEpochMillis = receivedAtEpochMillis,
            )
        }
    }

    fun handleTimeout(
        nonce: String,
        nowEpochMillis: Long,
    ): SessionMutation? {
        val pending = pendingOperations.remove(nonce) ?: return null

        return when (pending.type) {
            PendingOperationType.DISCOVER -> {
                isDiscovering = false
                val foundCount = pending.observedDeviceKeys.size
                val message = "Discovery complete. Found $foundCount device(s)"
                addLog(message, level = "success", timestampEpochMillis = nowEpochMillis)
                mutation(
                    completedOperations = listOf(
                        CompletedOperation(
                            nonce = nonce,
                            type = pending.type.wireValue,
                            success = true,
                            message = message,
                        )
                    )
                )
            }
            PendingOperationType.AUTO_GET_VERSION -> mutation()
            PendingOperationType.AUTO_GET_RTC -> mutation()
            PendingOperationType.SET_IP,
            PendingOperationType.SET_RTC,
            PendingOperationType.GET_RTC,
            PendingOperationType.SET_PARAM,
            PendingOperationType.SHELL_EXEC
            -> {
                val message = "Request timed out"
                addLog(message, level = "error", timestampEpochMillis = nowEpochMillis)
                mutation(
                    completedOperations = listOf(
                        CompletedOperation(
                            nonce = nonce,
                            type = pending.type.wireValue,
                            success = false,
                            message = message,
                        )
                    )
                )
            }
        }
    }

    private fun handleDiscoverReply(
        pending: PendingOperation,
        payload: com.popoto.core.json.JsonValue.JsonObject,
        command: String,
        secret: String?,
        receivedAtEpochMillis: Long,
    ): SessionMutation? {
        if (command != ProtocolCommand.DISCOVER_REPLY.wireValue) {
            return null
        }

        val reply = runCatching { DiscoverReplyMessage.fromJsonObject(payload) }.getOrNull() ?: return null
        val deviceMutation = directory.addOrUpdateDevice(reply.toDevice(receivedAtEpochMillis), receivedAtEpochMillis)
        pending.observedDeviceKeys += deviceMutation.device.uniqueKey

        val outboundPackets = mutableListOf<OutboundPacket>()

        if (deviceMutation.isNewDevice) {
            addLog(
                "Discovered device: ${displayName(deviceMutation.device)}",
                level = "success",
                timestampEpochMillis = receivedAtEpochMillis,
            )
        }

        if (deviceMutation.shouldQueryRtc) {
            val targetId = deviceMutation.device.targetDeviceId()
            if (targetId != null) {
                outboundPackets += enqueueAutoGetRtc(
                    targetId = targetId,
                    targetDeviceKey = deviceMutation.device.uniqueKey,
                    secret = secret,
                    nowEpochMillis = receivedAtEpochMillis,
                )
            }
        }

        if (deviceMutation.shouldQueryVersion) {
            val targetId = deviceMutation.device.targetDeviceId()
            if (targetId != null) {
                outboundPackets += enqueueAutoGetVersion(
                    targetId = targetId,
                    targetDeviceKey = deviceMutation.device.uniqueKey,
                    secret = secret,
                    nowEpochMillis = receivedAtEpochMillis,
                )
            }
        }

        return mutation(outboundPackets = outboundPackets)
    }

    private fun handleSetIpReply(
        nonce: String,
        payload: com.popoto.core.json.JsonValue.JsonObject,
        command: String,
        receivedAtEpochMillis: Long,
    ): SessionMutation? {
        if (command != ProtocolCommand.SET_IP_REPLY.wireValue) {
            return null
        }

        val reply = runCatching { SetIpReplyMessage.fromJsonObject(payload) }.getOrNull() ?: return null
        pendingOperations.remove(nonce)

        val message = reply.message ?: if (reply.success) "IP set successfully" else "Failed"
        addLog(message, level = if (reply.success) "success" else "error", timestampEpochMillis = receivedAtEpochMillis)

        val completedOperations = mutableListOf(
            CompletedOperation(
                nonce = nonce,
                type = PendingOperationType.SET_IP.wireValue,
                success = reply.success,
                message = message,
                rediscoverDelayMillis = if (reply.success) 1_500L else null,
            )
        )

        if (reply.success) {
            directory.clear()
            addLog("Refreshing device list...", level = "info", timestampEpochMillis = receivedAtEpochMillis)
        }

        return mutation(completedOperations = completedOperations)
    }

    private fun handleSetRtcReply(
        nonce: String,
        payload: com.popoto.core.json.JsonValue.JsonObject,
        command: String,
        receivedAtEpochMillis: Long,
    ): SessionMutation? {
        if (command != ProtocolCommand.SET_RTC_REPLY.wireValue) {
            return null
        }

        val reply = runCatching { SetRtcReplyMessage.fromJsonObject(payload) }.getOrNull() ?: return null
        pendingOperations.remove(nonce)

        val message = reply.message ?: if (reply.success) "RTC set successfully" else "Failed"
        addLog(message, level = if (reply.success) "success" else "error", timestampEpochMillis = receivedAtEpochMillis)

        return mutation(
            completedOperations = listOf(
                CompletedOperation(
                    nonce = nonce,
                    type = PendingOperationType.SET_RTC.wireValue,
                    success = reply.success,
                    message = message,
                )
            )
        )
    }

    private fun handleGetRtcReply(
        nonce: String,
        pending: PendingOperation,
        payload: com.popoto.core.json.JsonValue.JsonObject,
        command: String,
        receivedAtEpochMillis: Long,
    ): SessionMutation? {
        if (command != ProtocolCommand.GET_RTC_REPLY.wireValue) {
            return null
        }

        val reply = runCatching { GetRtcReplyMessage.fromJsonObject(payload) }.getOrNull() ?: return null
        pendingOperations.remove(nonce)

        val rtc = reply.rtc
        if (reply.success && rtc != null) {
            pending.targetId?.let { targetId ->
                directory.applyRtc(
                    preferredKey = pending.targetDeviceKey,
                    device = HydrophoneDevice(deviceId = targetId, cpuUid = targetId),
                    rtc = rtc,
                    queryEpochMillis = receivedAtEpochMillis,
                )
            }
        }

        if (pending.type == PendingOperationType.AUTO_GET_RTC) {
            return mutation()
        }

        val message = if (reply.success) {
            "RTC: ${rtc ?: "unknown"}"
        } else {
            reply.message ?: "Failed"
        }

        addLog(message, level = if (reply.success) "success" else "error", timestampEpochMillis = receivedAtEpochMillis)

        return mutation(
            completedOperations = listOf(
                CompletedOperation(
                    nonce = nonce,
                    type = PendingOperationType.GET_RTC.wireValue,
                    success = reply.success,
                    message = message,
                    rtc = rtc,
                )
            )
        )
    }

    private fun handleGetVersionReply(
        nonce: String,
        pending: PendingOperation,
        payload: com.popoto.core.json.JsonValue.JsonObject,
        command: String,
        receivedAtEpochMillis: Long,
    ): SessionMutation? {
        if (command != ProtocolCommand.GET_VERSION_REPLY.wireValue) {
            return null
        }

        val reply = runCatching { GetVersionReplyMessage.fromJsonObject(payload) }.getOrNull() ?: return null
        pendingOperations.remove(nonce)

        if (reply.success) {
            directory.mergeDevice(
                preferredKey = pending.targetDeviceKey,
                incoming = HydrophoneDevice(
                    deviceId = pending.targetId,
                    cpuUid = pending.targetId,
                    serial = reply.serial,
                    firmwareVersion = reply.version,
                    lastSeenEpochMillis = receivedAtEpochMillis,
                ),
                nowEpochMillis = receivedAtEpochMillis,
            )
        }

        return mutation()
    }

    private fun handleSetParamReply(
        nonce: String,
        payload: com.popoto.core.json.JsonValue.JsonObject,
        command: String,
        receivedAtEpochMillis: Long,
    ): SessionMutation? {
        if (command != ProtocolCommand.SET_PARAM_REPLY.wireValue) {
            return null
        }

        val reply = runCatching { SetParamReplyMessage.fromJsonObject(payload) }.getOrNull() ?: return null
        pendingOperations.remove(nonce)

        val message = reply.message ?: if (reply.success) "Parameter set" else "Failed"
        addLog(message, level = if (reply.success) "success" else "error", timestampEpochMillis = receivedAtEpochMillis)

        return mutation(
            completedOperations = listOf(
                CompletedOperation(
                    nonce = nonce,
                    type = PendingOperationType.SET_PARAM.wireValue,
                    success = reply.success,
                    message = message,
                )
            )
        )
    }

    private fun handleShellExecReply(
        nonce: String,
        payload: com.popoto.core.json.JsonValue.JsonObject,
        command: String,
        receivedAtEpochMillis: Long,
    ): SessionMutation? {
        if (command != ProtocolCommand.SHELL_EXEC_REPLY.wireValue) {
            return null
        }

        val reply = runCatching { ShellExecReplyMessage.fromJsonObject(payload) }.getOrNull() ?: return null
        pendingOperations.remove(nonce)

        val message = reply.message ?: if (reply.success) "Remote command complete" else "Remote command failed"
        addLog(message, level = if (reply.success) "success" else "error", timestampEpochMillis = receivedAtEpochMillis)

        return mutation(
            completedOperations = listOf(
                CompletedOperation(
                    nonce = nonce,
                    type = PendingOperationType.SHELL_EXEC.wireValue,
                    success = reply.success,
                    message = message,
                )
            )
        )
    }

    private fun enqueueAutoGetRtc(
        targetId: String,
        targetDeviceKey: String,
        secret: String?,
        nowEpochMillis: Long,
    ): OutboundPacket {
        val nonce = nextNonce(nowEpochMillis)
        val payloadJson = protocolEncoder.encode(
            message = GetRtcMessage(
                nonce = nonce,
                targetId = targetId,
            ),
            secret = secret,
        )

        pendingOperations[nonce] = PendingOperation(
            type = PendingOperationType.AUTO_GET_RTC,
            targetId = targetId,
            targetDeviceKey = targetDeviceKey,
        )

        return OutboundPacket(
            nonce = nonce,
            payloadJson = payloadJson,
            timeoutMillis = 2_000L,
        )
    }

    private fun enqueueAutoGetVersion(
        targetId: String,
        targetDeviceKey: String,
        secret: String?,
        nowEpochMillis: Long,
    ): OutboundPacket {
        val nonce = nextNonce(nowEpochMillis)
        val payloadJson = protocolEncoder.encode(
            message = GetVersionMessage(
                nonce = nonce,
                targetId = targetId,
            ),
            secret = secret,
        )

        pendingOperations[nonce] = PendingOperation(
            type = PendingOperationType.AUTO_GET_VERSION,
            targetId = targetId,
            targetDeviceKey = targetDeviceKey,
        )

        return OutboundPacket(
            nonce = nonce,
            payloadJson = payloadJson,
            timeoutMillis = 2_000L,
        )
    }

    private fun immediateFailure(
        type: PendingOperationType,
        message: String,
    ): SessionMutation {
        return mutation(
            completedOperations = listOf(
                CompletedOperation(
                    nonce = "",
                    type = type.wireValue,
                    success = false,
                    message = message,
                )
            )
        )
    }

    private fun addLog(
        message: String,
        level: String,
        timestampEpochMillis: Long,
    ) {
        logs.add(
            0,
            SessionLog(
                message = message,
                level = level,
                timestampEpochMillis = timestampEpochMillis,
            )
        )

        while (logs.size > 100) {
            logs.removeAt(logs.lastIndex)
        }
    }

    private fun displayName(device: HydrophoneDevice): String {
        return device.name
            ?: device.ipAddress
            ?: device.uniqueKey
    }

    private fun clearPreviousDiscoveryResults() {
        directory.clear()

        pendingOperations
            .filterValues { pending ->
                pending.type == PendingOperationType.AUTO_GET_RTC ||
                    pending.type == PendingOperationType.AUTO_GET_VERSION
            }
            .keys
            .toList()
            .forEach { nonce -> pendingOperations.remove(nonce) }
    }

    private fun mutation(
        outboundPackets: List<OutboundPacket> = emptyList(),
        completedOperations: List<CompletedOperation> = emptyList(),
    ): SessionMutation {
        return SessionMutation(
            snapshot = snapshot(),
            outboundPackets = outboundPackets,
            completedOperations = completedOperations,
        )
    }

    private fun nextNonce(nowEpochMillis: Long): String {
        nonceCounter += 1
        return "${nowEpochMillis.toString(16)}-${nonceCounter.toString(16).padStart(6, '0')}"
    }
}
