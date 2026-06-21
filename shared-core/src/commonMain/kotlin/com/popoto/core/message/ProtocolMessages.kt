package com.popoto.core.message

import com.popoto.core.device.HydrophoneDevice
import com.popoto.core.json.JsonValue
import com.popoto.core.json.JsonValue.JsonObject

interface WireMessage {
    fun fields(): Map<String, JsonValue>
}

data class DiscoverHydrophoneMessage(
    val nonce: String,
    val replyBroadcast: Boolean = false,
) : WireMessage {
    override fun fields(): Map<String, JsonValue> {
        val fields = mutableMapOf(
            "cmd" to JsonValue.of(ProtocolCommand.DISCOVER_HYDROPHONE.wireValue),
            "nonce" to JsonValue.of(nonce),
        )

        if (replyBroadcast) {
            fields["reply_broadcast"] = JsonValue.of(true)
        }

        return fields
    }
}

data class GetVersionMessage(
    val nonce: String,
    val targetId: String,
) : WireMessage {
    override fun fields(): Map<String, JsonValue> = targetedFields(ProtocolCommand.GET_VERSION, nonce, targetId)
}

data class SetIpMessage(
    val nonce: String,
    val targetId: String,
    val newIp: String,
    val netmask: String,
    val gateway: String,
) : WireMessage {
    override fun fields(): Map<String, JsonValue> = targetedFields(ProtocolCommand.SET_IP, nonce, targetId) + mapOf(
        "new_ip" to JsonValue.of(newIp),
        "netmask" to JsonValue.of(netmask),
        "gateway" to JsonValue.of(gateway),
    )
}

data class SetRtcMessage(
    val nonce: String,
    val targetId: String,
    val rtc: String,
) : WireMessage {
    override fun fields(): Map<String, JsonValue> = targetedFields(ProtocolCommand.SET_RTC, nonce, targetId) + mapOf(
        "rtc" to JsonValue.of(rtc),
    )
}

data class GetRtcMessage(
    val nonce: String,
    val targetId: String,
) : WireMessage {
    override fun fields(): Map<String, JsonValue> = targetedFields(ProtocolCommand.GET_RTC, nonce, targetId)
}

data class SetParamMessage(
    val nonce: String,
    val targetId: String,
    val paramName: String,
    val paramValue: Any?,
) : WireMessage {
    override fun fields(): Map<String, JsonValue> = targetedFields(ProtocolCommand.SET_PARAM, nonce, targetId) + mapOf(
        "param_name" to JsonValue.of(paramName),
        "param_value" to JsonValue.of(paramValue),
    )
}

data class SetUbootEnvMessage(
    val nonce: String,
    val targetId: String,
    val name: String,
    val value: String,
) : WireMessage {
    override fun fields(): Map<String, JsonValue> = targetedFields(ProtocolCommand.SET_UBOOT_ENV, nonce, targetId) + mapOf(
        "name" to JsonValue.of(name),
        "value" to JsonValue.of(value),
    )
}

data class RebootMessage(
    val nonce: String,
    val targetId: String,
) : WireMessage {
    override fun fields(): Map<String, JsonValue> = targetedFields(ProtocolCommand.REBOOT, nonce, targetId)
}

data class ShellExecMessage(
    val nonce: String,
    val targetId: String,
    val command: String,
    val timeoutSeconds: Double,
) : WireMessage {
    override fun fields(): Map<String, JsonValue> = targetedFields(ProtocolCommand.SHELL_EXEC, nonce, targetId) + mapOf(
        "command" to JsonValue.of(command),
        "timeout_seconds" to JsonValue.of(timeoutSeconds),
    )
}

private fun targetedFields(command: ProtocolCommand, nonce: String, targetId: String): Map<String, JsonValue> = mapOf(
    "cmd" to JsonValue.of(command.wireValue),
    "nonce" to JsonValue.of(nonce),
    "target_id" to JsonValue.of(targetId),
    "target_serial" to JsonValue.of(targetId),
)

data class DiscoverReplyMessage(
    val cmd: String? = null,
    val nonce: String? = null,
    val name: String? = null,
    val deviceId: String? = null,
    val cpuUid: String? = null,
    val identitySource: String? = null,
    val model: String? = null,
    val serial: String? = null,
    val ip: String? = null,
    val mac: String? = null,
    val interfaceName: String? = null,
    val configuredMode: String? = null,
    val activeMode: String? = null,
    val linkState: String? = null,
    val topologyHint: String? = null,
    val gatewayReachable: Boolean? = null,
    val netmask: String? = null,
    val gateway: String? = null,
    val fw: String? = null,
    val batteryV: Double? = null,
    val sampleRateHz: Int? = null,
    val storageFreeGb: Double? = null,
    val storageTotalGb: Double? = null,
    val recordingState: String? = null,
    val auth: String? = null,
) {
    fun toDevice(lastSeenEpochMillis: Long): HydrophoneDevice {
        val storageUsedGb = if (storageFreeGb != null && storageTotalGb != null) {
            storageTotalGb - storageFreeGb
        } else {
            null
        }

        return HydrophoneDevice(
            name = name,
            deviceId = deviceId,
            cpuUid = cpuUid,
            identitySource = identitySource,
            model = model,
            serial = serial,
            ipAddress = ip,
            macAddress = mac,
            interfaceName = interfaceName,
            configuredMode = configuredMode,
            activeMode = activeMode,
            linkState = linkState,
            topologyHint = topologyHint,
            gatewayReachable = gatewayReachable,
            netmask = netmask,
            gateway = gateway,
            firmwareVersion = fw,
            batteryVoltage = batteryV,
            sampleRate = sampleRateHz,
            storageUsedGb = storageUsedGb,
            storageTotalGb = storageTotalGb,
            recordingState = recordingState,
            lastSeenEpochMillis = lastSeenEpochMillis,
        )
    }

    companion object {
        fun fromJsonObject(json: JsonObject): DiscoverReplyMessage = DiscoverReplyMessage(
            cmd = json.string("cmd"),
            nonce = json.string("nonce"),
            name = json.string("name"),
            deviceId = json.string("device_id"),
            cpuUid = json.string("cpu_uid"),
            identitySource = json.string("identity_source"),
            model = json.string("model"),
            serial = json.string("serial"),
            ip = json.string("ip"),
            mac = json.string("mac"),
            interfaceName = json.string("interface"),
            configuredMode = json.string("configured_mode"),
            activeMode = json.string("active_mode"),
            linkState = json.string("link_state"),
            topologyHint = json.string("topology_hint"),
            gatewayReachable = json.boolean("gateway_reachable"),
            netmask = json.string("netmask"),
            gateway = json.string("gateway"),
            fw = json.string("fw"),
            batteryV = json.double("battery_v"),
            sampleRateHz = json.int("sample_rate_hz"),
            storageFreeGb = json.double("storage_free_gb"),
            storageTotalGb = json.double("storage_total_gb"),
            recordingState = json.string("recording_state"),
            auth = json.string("auth"),
        )
    }
}

data class GetVersionReplyMessage(
    val cmd: String? = null,
    val nonce: String? = null,
    val version: String? = null,
    val serial: String? = null,
    val status: String? = null,
    val error: String? = null,
    val auth: String? = null,
) {
    val success: Boolean
        get() = status == "ok"

    val message: String?
        get() = error

    companion object {
        fun fromJsonObject(json: JsonObject): GetVersionReplyMessage = GetVersionReplyMessage(
            cmd = json.string("cmd"),
            nonce = json.string("nonce"),
            version = json.string("version"),
            serial = json.string("serial"),
            status = json.string("status"),
            error = json.string("error"),
            auth = json.string("auth"),
        )
    }
}

data class SetIpReplyMessage(
    val cmd: String? = null,
    val nonce: String? = null,
    val status: String? = null,
    val error: String? = null,
    val auth: String? = null,
) {
    val success: Boolean
        get() = status == "ok"

    val message: String?
        get() = error

    companion object {
        fun fromJsonObject(json: JsonObject): SetIpReplyMessage = SetIpReplyMessage(
            cmd = json.string("cmd"),
            nonce = json.string("nonce"),
            status = json.string("status"),
            error = json.string("error"),
            auth = json.string("auth"),
        )
    }
}

data class SetRtcReplyMessage(
    val cmd: String? = null,
    val nonce: String? = null,
    val status: String? = null,
    val error: String? = null,
    val auth: String? = null,
) {
    val success: Boolean
        get() = status == "ok"

    val message: String?
        get() = error

    companion object {
        fun fromJsonObject(json: JsonObject): SetRtcReplyMessage = SetRtcReplyMessage(
            cmd = json.string("cmd"),
            nonce = json.string("nonce"),
            status = json.string("status"),
            error = json.string("error"),
            auth = json.string("auth"),
        )
    }
}

data class GetRtcReplyMessage(
    val cmd: String? = null,
    val nonce: String? = null,
    val rtc: String? = null,
    val status: String? = null,
    val error: String? = null,
    val auth: String? = null,
) {
    val success: Boolean
        get() = status == "ok"

    val message: String?
        get() = error

    companion object {
        fun fromJsonObject(json: JsonObject): GetRtcReplyMessage = GetRtcReplyMessage(
            cmd = json.string("cmd"),
            nonce = json.string("nonce"),
            rtc = json.string("rtc"),
            status = json.string("status"),
            error = json.string("error"),
            auth = json.string("auth"),
        )
    }
}

data class SetParamReplyMessage(
    val cmd: String? = null,
    val nonce: String? = null,
    val status: String? = null,
    val error: String? = null,
    val auth: String? = null,
) {
    val success: Boolean
        get() = status == "ok"

    val message: String?
        get() = error

    companion object {
        fun fromJsonObject(json: JsonObject): SetParamReplyMessage = SetParamReplyMessage(
            cmd = json.string("cmd"),
            nonce = json.string("nonce"),
            status = json.string("status"),
            error = json.string("error"),
            auth = json.string("auth"),
        )
    }
}

data class ShellExecReplyMessage(
    val cmd: String? = null,
    val nonce: String? = null,
    val status: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
    val auth: String? = null,
) {
    val success: Boolean
        get() = status == "ok" && (exitCode == null || exitCode == 0)

    val message: String?
        get() = error ?: stderr?.takeIf { it.isNotBlank() } ?: stdout

    companion object {
        fun fromJsonObject(json: JsonObject): ShellExecReplyMessage = ShellExecReplyMessage(
            cmd = json.string("cmd"),
            nonce = json.string("nonce"),
            status = json.string("status"),
            stdout = json.string("stdout"),
            stderr = json.string("stderr"),
            exitCode = json.int("exit_code"),
            error = json.string("error"),
            auth = json.string("auth"),
        )
    }
}
