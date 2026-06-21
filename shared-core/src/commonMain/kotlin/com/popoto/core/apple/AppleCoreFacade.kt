package com.popoto.core.apple

import com.popoto.core.auth.HmacSha256SignatureEngine
import com.popoto.core.auth.MessageAuthenticator
import com.popoto.core.auth.SecretValidator
import com.popoto.core.device.HydrophoneDevice
import com.popoto.core.json.CanonicalJson
import com.popoto.core.json.JsonValue
import com.popoto.core.json.JsonValue.JsonObject
import com.popoto.core.json.SimpleJsonParser
import com.popoto.core.message.DiscoverHydrophoneMessage
import com.popoto.core.message.DiscoverReplyMessage
import com.popoto.core.message.GetRtcMessage
import com.popoto.core.message.GetRtcReplyMessage
import com.popoto.core.message.ProtocolEncoder
import com.popoto.core.message.SetIpMessage
import com.popoto.core.message.SetIpReplyMessage
import com.popoto.core.message.SetParamMessage
import com.popoto.core.message.SetParamReplyMessage
import com.popoto.core.message.SetRtcMessage
import com.popoto.core.message.SetRtcReplyMessage
import com.popoto.core.message.ShellExecReplyMessage
import com.popoto.core.session.CompletedOperation
import com.popoto.core.session.OutboundPacket
import com.popoto.core.session.SessionEngine
import com.popoto.core.session.SessionLog
import com.popoto.core.session.SessionMutation
import com.popoto.core.session.SessionSnapshot

class AppleCoreFacade {
    private val authenticator = MessageAuthenticator(HmacSha256SignatureEngine())
    private val protocolEncoder = ProtocolEncoder(authenticator)
    private val sessionEngine = SessionEngine(protocolEncoder)

    fun validateSecret(secret: String): Boolean = SecretValidator.isValid(secret)

    fun encodeDiscoverRequestJson(nonce: String, secret: String?): String =
        protocolEncoder.encode(DiscoverHydrophoneMessage(nonce = nonce), secret)

    fun encodeSetIpRequestJson(
        nonce: String,
        targetId: String,
        newIp: String,
        netmask: String,
        gateway: String,
        secret: String?,
    ): String = protocolEncoder.encode(
        SetIpMessage(
            nonce = nonce,
            targetId = targetId,
            newIp = newIp,
            netmask = netmask,
            gateway = gateway,
        ),
        secret,
    )

    fun encodeSetRtcRequestJson(
        nonce: String,
        targetId: String,
        rtc: String,
        secret: String?,
    ): String = protocolEncoder.encode(
        SetRtcMessage(
            nonce = nonce,
            targetId = targetId,
            rtc = rtc,
        ),
        secret,
    )

    fun encodeGetRtcRequestJson(
        nonce: String,
        targetId: String,
        secret: String?,
    ): String = protocolEncoder.encode(
        GetRtcMessage(
            nonce = nonce,
            targetId = targetId,
        ),
        secret,
    )

    fun encodeSetParamStringRequestJson(
        nonce: String,
        targetId: String,
        paramName: String,
        paramValue: String,
        secret: String?,
    ): String = encodeSetParamRequestJson(nonce, targetId, paramName, paramValue, secret)

    fun encodeSetParamLongRequestJson(
        nonce: String,
        targetId: String,
        paramName: String,
        paramValue: Long,
        secret: String?,
    ): String = encodeSetParamRequestJson(nonce, targetId, paramName, paramValue, secret)

    fun encodeSetParamDoubleRequestJson(
        nonce: String,
        targetId: String,
        paramName: String,
        paramValue: Double,
        secret: String?,
    ): String = encodeSetParamRequestJson(nonce, targetId, paramName, paramValue, secret)

    fun encodeSetParamBooleanRequestJson(
        nonce: String,
        targetId: String,
        paramName: String,
        paramValue: Boolean,
        secret: String?,
    ): String = encodeSetParamRequestJson(nonce, targetId, paramName, paramValue, secret)

    fun uniqueKey(deviceJson: String): String? = parseDevice(deviceJson)?.uniqueKey

    fun matchesDevices(lhsDeviceJson: String, rhsDeviceJson: String): Boolean {
        val lhs = parseDevice(lhsDeviceJson) ?: return false
        val rhs = parseDevice(rhsDeviceJson) ?: return false
        return lhs.matches(rhs)
    }

    fun mergeDevices(existingDeviceJson: String, incomingDeviceJson: String): String? {
        val existing = parseDevice(existingDeviceJson) ?: return null
        val incoming = parseDevice(incomingDeviceJson) ?: return null
        return encodeDevice(existing.mergedWith(incoming))
    }

    fun shouldRefreshRtc(deviceJson: String, nowEpochMillis: Long, refreshIntervalMillis: Long): Boolean {
        val device = parseDevice(deviceJson) ?: return false
        if (device.targetDeviceId() == null) {
            return false
        }

        val lastQuery = device.rtcQueryEpochMillis ?: return true
        return nowEpochMillis - lastQuery > refreshIntervalMillis
    }

    fun applyRtc(deviceJson: String, rtc: String, queryEpochMillis: Long): String? {
        val device = parseDevice(deviceJson) ?: return null
        return encodeDevice(device.copy(rtc = rtc, rtcQueryEpochMillis = queryEpochMillis))
    }

    fun parseDiscoverReplyDeviceJson(json: String, lastSeenEpochMillis: Long): String? {
        val reply = runCatching { DiscoverReplyMessage.fromJsonObject(SimpleJsonParser.parseObject(json)) }.getOrNull() ?: return null
        return encodeDevice(reply.toDevice(lastSeenEpochMillis))
    }

    fun parseSetIpReplyJson(json: String): String? {
        val reply = runCatching { SetIpReplyMessage.fromJsonObject(SimpleJsonParser.parseObject(json)) }.getOrNull() ?: return null
        return encodeStatusReply(reply.success, reply.message)
    }

    fun parseSetRtcReplyJson(json: String): String? {
        val reply = runCatching { SetRtcReplyMessage.fromJsonObject(SimpleJsonParser.parseObject(json)) }.getOrNull() ?: return null
        return encodeStatusReply(reply.success, reply.message)
    }

    fun parseGetRtcReplyJson(json: String): String? {
        val reply = runCatching { GetRtcReplyMessage.fromJsonObject(SimpleJsonParser.parseObject(json)) }.getOrNull() ?: return null
        return CanonicalJson.renderCompactObject(
            JsonObject(
                mapOf(
                    "success" to JsonValue.of(reply.success),
                    "rtc" to JsonValue.of(reply.rtc),
                    "message" to JsonValue.of(reply.message),
                )
            )
        )
    }

    fun parseSetParamReplyJson(json: String): String? {
        val reply = runCatching { SetParamReplyMessage.fromJsonObject(SimpleJsonParser.parseObject(json)) }.getOrNull() ?: return null
        return encodeStatusReply(reply.success, reply.message)
    }

    fun parseShellExecReplyJson(json: String): String? {
        val reply = runCatching { ShellExecReplyMessage.fromJsonObject(SimpleJsonParser.parseObject(json)) }.getOrNull() ?: return null
        return encodeStatusReply(reply.success, reply.message)
    }

    fun initializeSessionJson(nowEpochMillis: Long): String = encodeSessionMutation(sessionEngine.initialize(nowEpochMillis))

    fun shutdownSessionJson(): String = encodeSessionMutation(sessionEngine.shutdown())

    fun sessionSnapshotJson(): String = encodeSessionSnapshot(sessionEngine.snapshot())

    fun selectDeviceJson(uniqueKey: String?): String = encodeSessionMutation(sessionEngine.selectDevice(uniqueKey))

    fun clearLogsJson(): String = encodeSessionMutation(sessionEngine.clearLogs())

    fun startDiscoveryJson(
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): String = encodeSessionMutation(
        sessionEngine.startDiscovery(
            timeoutMillis = timeoutMillis,
            secret = secret,
            nowEpochMillis = nowEpochMillis,
        )
    )

    fun startSetIpForSelectedDeviceJson(
        newIp: String,
        netmask: String,
        gateway: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): String = encodeSessionMutation(
        sessionEngine.startSetIpForSelectedDevice(
            newIp = newIp,
            netmask = netmask,
            gateway = gateway,
            timeoutMillis = timeoutMillis,
            secret = secret,
            nowEpochMillis = nowEpochMillis,
        )
    )

    fun startSetRtcForSelectedDeviceJson(
        rtc: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): String = encodeSessionMutation(
        sessionEngine.startSetRtcForSelectedDevice(
            rtc = rtc,
            timeoutMillis = timeoutMillis,
            secret = secret,
            nowEpochMillis = nowEpochMillis,
        )
    )

    fun startGetRtcForSelectedDeviceJson(
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): String = encodeSessionMutation(
        sessionEngine.startGetRtcForSelectedDevice(
            timeoutMillis = timeoutMillis,
            secret = secret,
            nowEpochMillis = nowEpochMillis,
        )
    )

    fun startSetParamStringForSelectedDeviceJson(
        paramName: String,
        paramValue: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): String = encodeSessionMutation(
        sessionEngine.startSetParamForSelectedDevice(
            paramName = paramName,
            paramValue = paramValue,
            timeoutMillis = timeoutMillis,
            secret = secret,
            nowEpochMillis = nowEpochMillis,
        )
    )

    fun startSetParamLongForSelectedDeviceJson(
        paramName: String,
        paramValue: Long,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): String = encodeSessionMutation(
        sessionEngine.startSetParamForSelectedDevice(
            paramName = paramName,
            paramValue = paramValue,
            timeoutMillis = timeoutMillis,
            secret = secret,
            nowEpochMillis = nowEpochMillis,
        )
    )

    fun startSetParamDoubleForSelectedDeviceJson(
        paramName: String,
        paramValue: Double,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): String = encodeSessionMutation(
        sessionEngine.startSetParamForSelectedDevice(
            paramName = paramName,
            paramValue = paramValue,
            timeoutMillis = timeoutMillis,
            secret = secret,
            nowEpochMillis = nowEpochMillis,
        )
    )

    fun startSetParamBooleanForSelectedDeviceJson(
        paramName: String,
        paramValue: Boolean,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): String = encodeSessionMutation(
        sessionEngine.startSetParamForSelectedDevice(
            paramName = paramName,
            paramValue = paramValue,
            timeoutMillis = timeoutMillis,
            secret = secret,
            nowEpochMillis = nowEpochMillis,
        )
    )

    fun startShellExecForSelectedDeviceJson(
        command: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): String = encodeSessionMutation(
        sessionEngine.startShellExecForSelectedDevice(
            command = command,
            timeoutMillis = timeoutMillis,
            secret = secret,
            nowEpochMillis = nowEpochMillis,
        )
    )

    fun handleIncomingPacketJson(
        json: String,
        secret: String?,
        receivedAtEpochMillis: Long,
    ): String? = sessionEngine.handleIncomingPacket(
        json = json,
        secret = secret,
        receivedAtEpochMillis = receivedAtEpochMillis,
    )?.let(::encodeSessionMutation)

    fun handleTimeoutJson(
        nonce: String,
        nowEpochMillis: Long,
    ): String? = sessionEngine.handleTimeout(
        nonce = nonce,
        nowEpochMillis = nowEpochMillis,
    )?.let(::encodeSessionMutation)

    private fun parseObject(json: String): JsonObject? = runCatching { SimpleJsonParser.parseObject(json) }.getOrNull()

    private fun encodeSetParamRequestJson(
        nonce: String,
        targetId: String,
        paramName: String,
        paramValue: Any,
        secret: String?,
    ): String = protocolEncoder.encode(
        SetParamMessage(
            nonce = nonce,
            targetId = targetId,
            paramName = paramName,
            paramValue = paramValue,
        ),
        secret,
    )

    private fun parseDevice(json: String): HydrophoneDevice? {
        val objectValue = parseObject(json) ?: return null

        return HydrophoneDevice(
            localId = objectValue.string("localId") ?: "ios-bridge",
            name = objectValue.string("name"),
            deviceId = objectValue.string("deviceId"),
            cpuUid = objectValue.string("cpuUid"),
            identitySource = objectValue.string("identitySource"),
            model = objectValue.string("model"),
            serial = objectValue.string("serial"),
            ipAddress = objectValue.string("ipAddress"),
            macAddress = objectValue.string("macAddress"),
            interfaceName = objectValue.string("interfaceName"),
            configuredMode = objectValue.string("configuredMode"),
            activeMode = objectValue.string("activeMode"),
            linkState = objectValue.string("linkState"),
            topologyHint = objectValue.string("topologyHint"),
            gatewayReachable = objectValue.boolean("gatewayReachable"),
            netmask = objectValue.string("netmask"),
            gateway = objectValue.string("gateway"),
            firmwareVersion = objectValue.string("firmwareVersion"),
            batteryVoltage = objectValue.double("batteryVoltage"),
            sampleRate = objectValue.int("sampleRate"),
            rtc = objectValue.string("rtc"),
            storageUsedGb = objectValue.double("storageUsedGb"),
            storageTotalGb = objectValue.double("storageTotalGb"),
            recordingState = objectValue.string("recordingState"),
            lastSeenEpochMillis = objectValue.long("lastSeenEpochMillis") ?: 0L,
            rtcQueryEpochMillis = objectValue.long("rtcQueryEpochMillis"),
        )
    }

    private fun encodeDevice(device: HydrophoneDevice): String {
        return CanonicalJson.renderCompactObject(
            JsonObject(
                mapOf(
                    "localId" to JsonValue.of(device.localId),
                    "name" to JsonValue.of(device.name),
                    "deviceId" to JsonValue.of(device.deviceId),
                    "cpuUid" to JsonValue.of(device.cpuUid),
                    "identitySource" to JsonValue.of(device.identitySource),
                    "model" to JsonValue.of(device.model),
                    "serial" to JsonValue.of(device.serial),
                    "ipAddress" to JsonValue.of(device.ipAddress),
                    "macAddress" to JsonValue.of(device.macAddress),
                    "interfaceName" to JsonValue.of(device.interfaceName),
                    "configuredMode" to JsonValue.of(device.configuredMode),
                    "activeMode" to JsonValue.of(device.activeMode),
                    "linkState" to JsonValue.of(device.linkState),
                    "topologyHint" to JsonValue.of(device.topologyHint),
                    "gatewayReachable" to JsonValue.of(device.gatewayReachable),
                    "netmask" to JsonValue.of(device.netmask),
                    "gateway" to JsonValue.of(device.gateway),
                    "firmwareVersion" to JsonValue.of(device.firmwareVersion),
                    "batteryVoltage" to JsonValue.of(device.batteryVoltage),
                    "sampleRate" to JsonValue.of(device.sampleRate),
                    "rtc" to JsonValue.of(device.rtc),
                    "storageUsedGb" to JsonValue.of(device.storageUsedGb),
                    "storageTotalGb" to JsonValue.of(device.storageTotalGb),
                    "recordingState" to JsonValue.of(device.recordingState),
                    "lastSeenEpochMillis" to JsonValue.of(device.lastSeenEpochMillis),
                    "rtcQueryEpochMillis" to JsonValue.of(device.rtcQueryEpochMillis),
                )
            )
        )
    }

    private fun encodeStatusReply(success: Boolean, message: String?): String {
        return CanonicalJson.renderCompactObject(
            JsonObject(
                mapOf(
                    "success" to JsonValue.of(success),
                    "message" to JsonValue.of(message),
                )
            )
        )
    }

    private fun encodeSessionMutation(mutation: SessionMutation): String {
        return CanonicalJson.renderCompactObject(
            JsonObject(
                mapOf(
                    "snapshot" to encodeSessionSnapshotValue(mutation.snapshot),
                    "outboundPackets" to JsonValue.of(
                        mutation.outboundPackets.map(::encodeOutboundPacketMap)
                    ),
                    "completedOperations" to JsonValue.of(
                        mutation.completedOperations.map(::encodeCompletedOperationMap)
                    ),
                )
            )
        )
    }

    private fun encodeSessionSnapshot(snapshot: SessionSnapshot): String {
        return CanonicalJson.renderCompactObject(encodeSessionSnapshotValue(snapshot))
    }

    private fun encodeSessionSnapshotValue(snapshot: SessionSnapshot): JsonObject {
        return JsonObject(
            mapOf(
                "status" to JsonValue.of(snapshot.status),
                "isDiscovering" to JsonValue.of(snapshot.isDiscovering),
                "selectedDeviceKey" to JsonValue.of(snapshot.selectedDeviceKey),
                "devicesByKey" to JsonValue.of(
                    snapshot.devicesByKey.mapValues { (_, device) -> encodeDeviceMap(device) }
                ),
                "sortedDeviceKeys" to JsonValue.of(snapshot.sortedDeviceKeys),
                "logs" to JsonValue.of(snapshot.logs.map(::encodeSessionLogMap)),
            )
        )
    }

    private fun encodeOutboundPacketMap(packet: OutboundPacket): Map<String, Any?> {
        return mapOf(
            "nonce" to packet.nonce,
            "payloadJson" to packet.payloadJson,
            "timeoutMillis" to packet.timeoutMillis,
        )
    }

    private fun encodeCompletedOperationMap(operation: CompletedOperation): Map<String, Any?> {
        return mapOf(
            "nonce" to operation.nonce,
            "type" to operation.type,
            "success" to operation.success,
            "message" to operation.message,
            "rtc" to operation.rtc,
            "rediscoverDelayMillis" to operation.rediscoverDelayMillis,
        )
    }

    private fun encodeSessionLogMap(log: SessionLog): Map<String, Any?> {
        return mapOf(
            "message" to log.message,
            "level" to log.level,
            "timestampEpochMillis" to log.timestampEpochMillis,
        )
    }

    private fun encodeDeviceMap(device: HydrophoneDevice): Map<String, Any?> {
        return mapOf(
            "localId" to device.localId,
            "name" to device.name,
            "deviceId" to device.deviceId,
            "cpuUid" to device.cpuUid,
            "identitySource" to device.identitySource,
            "model" to device.model,
            "serial" to device.serial,
            "ipAddress" to device.ipAddress,
            "macAddress" to device.macAddress,
            "interfaceName" to device.interfaceName,
            "configuredMode" to device.configuredMode,
            "activeMode" to device.activeMode,
            "linkState" to device.linkState,
            "topologyHint" to device.topologyHint,
            "gatewayReachable" to device.gatewayReachable,
            "netmask" to device.netmask,
            "gateway" to device.gateway,
            "firmwareVersion" to device.firmwareVersion,
            "batteryVoltage" to device.batteryVoltage,
            "sampleRate" to device.sampleRate,
            "rtc" to device.rtc,
            "storageUsedGb" to device.storageUsedGb,
            "storageTotalGb" to device.storageTotalGb,
            "recordingState" to device.recordingState,
            "lastSeenEpochMillis" to device.lastSeenEpochMillis,
            "rtcQueryEpochMillis" to device.rtcQueryEpochMillis,
        )
    }
}
