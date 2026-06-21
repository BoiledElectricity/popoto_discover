package com.popoto.core

import com.popoto.core.auth.MessageAuthenticator
import com.popoto.core.auth.SecretValidator
import com.popoto.core.auth.SignatureEngine
import com.popoto.core.auth.HmacSha256SignatureEngine
import com.popoto.core.device.DeviceDirectory
import com.popoto.core.device.HydrophoneDevice
import com.popoto.core.message.DiscoverHydrophoneMessage
import com.popoto.core.message.ProtocolCodec
import com.popoto.core.message.ProtocolEncoder
import com.popoto.core.session.SessionEngine
import com.popoto.core.time.RtcCodec
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun main() {
    verifySecretValidation()
    verifyPureKotlinHmac()
    verifySigningRoundTrip()
    verifyDiscoverReplyMapping()
    verifyDeviceDirectoryMergeAndRtc()
    verifySessionEngineFlow()
    verifyDiscoveryClearsStaleDevices()
    verifyReportedSerialsDoNotDriveIdentity()
    println("shared-core smoke tests passed")
}

private fun verifySecretValidation() {
    check(SecretValidator.isValid("a".repeat(64)))
    check(!SecretValidator.isValid("abc"))
}

private fun verifySigningRoundTrip() {
    val authenticator = MessageAuthenticator(JvmSignatureEngine)
    val encoder = ProtocolEncoder(authenticator)
    val secret = "0123456789abcdef".repeat(4)

    val json = encoder.encode(
        message = DiscoverHydrophoneMessage(nonce = "deadbeef"),
        secret = secret,
    )

    check(ProtocolCodec.command(json) == "discover_hydrophone")
    check(authenticator.verify(json, secret))
}

private fun verifyPureKotlinHmac() {
    val message = """{"cmd": "discover_hydrophone", "nonce": "deadbeef"}"""
    val secret = "0123456789abcdef".repeat(4)
    val expected = JvmSignatureEngine.hmacSha256Hex(message, secret)
    val actual = HmacSha256SignatureEngine().hmacSha256Hex(message, secret)
    check(actual == expected)
}

private fun verifyDiscoverReplyMapping() {
    val reply = ProtocolCodec.parseDiscoverReply(
        """
        {
          "cmd": "discover_reply",
          "nonce": "deadbeef",
          "name": "Alpha",
          "device_id": "8539bcda09622732",
          "cpu_uid": "8539bcda09622732",
          "identity_source": "cpu_uid",
          "model": "P-1",
          "serial": "SN123",
          "ip": "192.168.1.20",
          "mac": "AA:BB:CC:DD:EE:FF",
          "interface": "eth0",
          "configured_mode": "static",
          "active_mode": "static",
          "link_state": "up",
          "topology_hint": "routed",
          "gateway_reachable": true,
          "netmask": "255.255.255.0",
          "gateway": "192.168.1.1",
          "fw": "1.2.3",
          "battery_v": 12.8,
          "sample_rate_hz": 96000,
          "storage_free_gb": 42.0,
          "storage_total_gb": 64.0,
          "recording_state": "idle"
        }
        """.trimIndent()
    )

    val device = reply.toDevice(lastSeenEpochMillis = 5_000L)
    check(device.name == "Alpha")
    check(device.storageUsedGb == 22.0)
    check(device.deviceId == "8539bcda09622732")
    check(device.cpuUid == "8539bcda09622732")
    check(device.uniqueKey == "cpu:8539bcda09622732")
    check(device.configuredMode == "static")
    check(device.activeMode == "static")
    check(device.topologyHint == "routed")
    check(device.gatewayReachable == true)
}

private fun verifyDeviceDirectoryMergeAndRtc() {
    val directory = DeviceDirectory()

    val firstDevice = HydrophoneDevice(
        name = "Alpha",
        deviceId = "8539bcda09622732",
        cpuUid = "8539bcda09622732",
        model = "P-1",
        ipAddress = "192.168.1.20",
        macAddress = "AA:BB:CC:DD:EE:FF",
        lastSeenEpochMillis = 1_000L,
    )

    val firstMutation = directory.addOrUpdateDevice(firstDevice, nowEpochMillis = 1_000L)
    check(firstMutation.isNewDevice)
    check(firstMutation.shouldQueryRtc)

    val richerUpdate = HydrophoneDevice(
        name = "P-1-UNKNOWN-AB12CD",
        deviceId = "8539bcda09622732",
        cpuUid = "8539bcda09622732",
        model = "P-1",
        serial = "UNKNOWN-AB12CD",
        ipAddress = "192.168.1.20",
        macAddress = "00:00:00:00:00:00",
        firmwareVersion = "unknown",
        lastSeenEpochMillis = 2_000L,
    )

    val secondMutation = directory.addOrUpdateDevice(richerUpdate, nowEpochMillis = 2_000L)
    check(!secondMutation.isNewDevice)
    check(secondMutation.device.macAddress == "AA:BB:CC:DD:EE:FF")
    check(secondMutation.device.serial == null)
    check(secondMutation.device.firmwareVersion == null)
    check(!secondMutation.shouldQueryVersion)

    val metadataUpdate = directory.mergeDevice(
        preferredKey = secondMutation.device.uniqueKey,
        incoming = HydrophoneDevice(
            deviceId = "8539bcda09622732",
            cpuUid = "8539bcda09622732",
            serial = "SN123",
            firmwareVersion = "1.2.3",
            lastSeenEpochMillis = 3_000L,
        ),
        nowEpochMillis = 3_000L,
    ) ?: error("Metadata merge should succeed")

    check(metadataUpdate.serial == "SN123")
    check(metadataUpdate.firmwareVersion == "1.2.3")
    check(metadataUpdate.name == "Alpha")

    val updated = directory.applyRtc(
        preferredKey = metadataUpdate.uniqueKey,
        device = metadataUpdate,
        rtc = "2026.04.23-12:00:00",
        queryEpochMillis = 10_000L,
    ) ?: error("RTC update should succeed")

    check(updated.interpolatedRtc(15_000L, JvmRtcCodec) == "2026.04.23-12:00:05")
    check(!directory.shouldRefreshRtc(updated, nowEpochMillis = 20_000L))
    check(directory.shouldRefreshRtc(updated, nowEpochMillis = 45_500L))
}

private fun verifySessionEngineFlow() {
    val secret = "0123456789abcdef".repeat(4)
    val engine = SessionEngine(ProtocolEncoder(MessageAuthenticator(JvmSignatureEngine)))

    val initialMutation = engine.initialize(nowEpochMillis = 1_000L)
    check(initialMutation.snapshot.status == "connected")

    val discoveryMutation = engine.startDiscovery(
        timeoutMillis = 5_000L,
        secret = secret,
        nowEpochMillis = 2_000L,
    )
    check(discoveryMutation.snapshot.isDiscovering)
    check(discoveryMutation.outboundPackets.size == 1)

    val discoveryNonce = discoveryMutation.outboundPackets.single().nonce
    val discoverReplyMutation = engine.handleIncomingPacket(
        json = """
        {
          "cmd": "discover_reply",
          "nonce": "$discoveryNonce",
          "name": "P-1-UNKNOWN-AB12CD",
          "device_id": "8539bcda09622732",
          "cpu_uid": "8539bcda09622732",
          "identity_source": "cpu_uid",
          "model": "P-1",
          "serial": "UNKNOWN-AB12CD",
          "ip": "192.168.1.20",
          "mac": "AA:BB:CC:DD:EE:FF",
          "fw": "unknown",
          "battery_v": 12.8,
          "sample_rate_hz": 96000,
          "storage_free_gb": 42.0,
          "storage_total_gb": 64.0,
          "recording_state": "idle"
        }
        """.trimIndent(),
        secret = secret,
        receivedAtEpochMillis = 2_500L,
    ) ?: error("Discover reply should produce a mutation")

    check(discoverReplyMutation.snapshot.devicesByKey.size == 1)
    check(discoverReplyMutation.outboundPackets.size == 2)

    val autoVersionNonce = discoverReplyMutation.outboundPackets.first { packet ->
        ProtocolCodec.command(packet.payloadJson) == "get_version"
    }.nonce
    val autoRtcNonce = discoverReplyMutation.outboundPackets.first { packet ->
        ProtocolCodec.command(packet.payloadJson) == "get_rtc"
    }.nonce

    val autoVersionMutation = engine.handleIncomingPacket(
        json = """
        {
          "cmd": "get_version_reply",
          "nonce": "$autoVersionNonce",
          "status": "ok",
          "version": "1.2.3",
          "serial": "SN123"
        }
        """.trimIndent(),
        secret = secret,
        receivedAtEpochMillis = 2_750L,
    ) ?: error("Auto version reply should produce a mutation")

    val autoRtcMutation = engine.handleIncomingPacket(
        json = """
        {
          "cmd": "get_rtc_reply",
          "nonce": "$autoRtcNonce",
          "rtc": "2026.04.23-12:00:00",
          "status": "ok"
        }
        """.trimIndent(),
        secret = secret,
        receivedAtEpochMillis = 3_000L,
    ) ?: error("Auto RTC reply should produce a mutation")

    val versionedDevice = autoVersionMutation.snapshot.devicesByKey.values.single()
    check(versionedDevice.serial == "SN123")
    check(versionedDevice.firmwareVersion == "1.2.3")

    val discoveredDevice = autoRtcMutation.snapshot.devicesByKey.values.single()
    check(discoveredDevice.rtc == "2026.04.23-12:00:00")

    val discoveryTimeoutMutation = engine.handleTimeout(
        nonce = discoveryNonce,
        nowEpochMillis = 7_000L,
    ) ?: error("Discovery timeout should complete the discovery operation")

    check(!discoveryTimeoutMutation.snapshot.isDiscovering)
    check(discoveryTimeoutMutation.completedOperations.single().type == "discover")
    check(discoveryTimeoutMutation.completedOperations.single().success)

    val selectedKey = discoveryTimeoutMutation.snapshot.sortedDeviceKeys.single()
    val selectionMutation = engine.selectDevice(selectedKey)
    check(selectionMutation.snapshot.selectedDeviceKey == selectedKey)

    val explicitRtcMutation = engine.startGetRtcForSelectedDevice(
        timeoutMillis = 2_000L,
        secret = secret,
        nowEpochMillis = 8_000L,
    )
    val explicitRtcNonce = explicitRtcMutation.outboundPackets.single().nonce

    val explicitRtcReplyMutation = engine.handleIncomingPacket(
        json = """
        {
          "cmd": "get_rtc_reply",
          "nonce": "$explicitRtcNonce",
          "rtc": "2026.04.23-12:34:56",
          "status": "ok"
        }
        """.trimIndent(),
        secret = secret,
        receivedAtEpochMillis = 8_500L,
    ) ?: error("Explicit RTC reply should complete the RTC operation")

    check(explicitRtcReplyMutation.completedOperations.single().rtc == "2026.04.23-12:34:56")

    val setIpMutation = engine.startSetIpForSelectedDevice(
        newIp = "192.168.1.30",
        netmask = "255.255.255.0",
        gateway = "192.168.1.1",
        timeoutMillis = 2_000L,
        secret = secret,
        nowEpochMillis = 9_000L,
    )
    val setIpNonce = setIpMutation.outboundPackets.single().nonce

    val setIpReplyMutation = engine.handleIncomingPacket(
        json = """
        {
          "cmd": "set_ip_reply",
          "nonce": "$setIpNonce",
          "status": "ok"
        }
        """.trimIndent(),
        secret = secret,
        receivedAtEpochMillis = 9_500L,
    ) ?: error("Set IP reply should complete the set_ip operation")

    check(setIpReplyMutation.completedOperations.single().rediscoverDelayMillis == 1_500L)
    check(setIpReplyMutation.snapshot.devicesByKey.isEmpty())
}

private fun verifyDiscoveryClearsStaleDevices() {
    val secret = "0123456789abcdef".repeat(4)
    val engine = SessionEngine(ProtocolEncoder(MessageAuthenticator(JvmSignatureEngine)))

    engine.initialize(nowEpochMillis = 1_000L)

    val firstDiscovery = engine.startDiscovery(
        timeoutMillis = 5_000L,
        secret = secret,
        nowEpochMillis = 2_000L,
    )
    val firstNonce = firstDiscovery.outboundPackets.single().nonce

    val firstReply = engine.handleIncomingPacket(
        json = """
        {
          "cmd": "discover_reply",
          "nonce": "$firstNonce",
          "name": "Stale",
          "model": "P-1",
          "serial": "SN123",
          "ip": "192.168.1.20",
          "mac": "AA:BB:CC:DD:EE:FF",
          "fw": "1.2.3"
        }
        """.trimIndent(),
        secret = secret,
        receivedAtEpochMillis = 2_500L,
    ) ?: error("First discovery reply should produce a mutation")

    check(firstReply.snapshot.devicesByKey.size == 1)
    engine.handleTimeout(firstNonce, nowEpochMillis = 7_000L)
    engine.selectDevice(firstReply.snapshot.sortedDeviceKeys.single())

    val secondDiscovery = engine.startDiscovery(
        timeoutMillis = 5_000L,
        secret = secret,
        nowEpochMillis = 8_000L,
    )

    check(secondDiscovery.snapshot.devicesByKey.isEmpty())
    check(secondDiscovery.snapshot.selectedDeviceKey == null)
}

private fun verifyReportedSerialsDoNotDriveIdentity() {
    val secret = "0123456789abcdef".repeat(4)
    val engine = SessionEngine(ProtocolEncoder(MessageAuthenticator(JvmSignatureEngine)))

    engine.initialize(nowEpochMillis = 1_000L)

    val discovery = engine.startDiscovery(
        timeoutMillis = 5_000L,
        secret = secret,
        nowEpochMillis = 2_000L,
    )
    val nonce = discovery.outboundPackets.single().nonce

    val firstReply = engine.handleIncomingPacket(
        json = """
        {
          "cmd": "discover_reply",
          "nonce": "$nonce",
          "name": "pmm5544-FFFFFFFFFFFFFFFFFFFFF",
          "device_id": "c135bcda09c23b09",
          "cpu_uid": "c135bcda09c23b09",
          "identity_source": "cpu_uid",
          "model": "pmm5544",
          "serial": "FFFFFFFFFFFFFFFFFFFFF",
          "ip": "10.1.0.124",
          "mac": "3e:57:0c:99:ff:ae",
          "fw": "5.0.0-alpha+local"
        }
        """.trimIndent(),
        secret = secret,
        receivedAtEpochMillis = 2_500L,
    ) ?: error("First reported serial reply should produce a mutation")

    val secondReply = engine.handleIncomingPacket(
        json = """
        {
          "cmd": "discover_reply",
          "nonce": "$nonce",
          "name": "pmm6081-boo-boo Kaka",
          "device_id": "fe64bada09122316",
          "cpu_uid": "fe64bada09122316",
          "identity_source": "cpu_uid",
          "model": "pmm6081",
          "serial": "boo-boo Kaka",
          "ip": "10.1.0.15",
          "mac": "0a:8d:af:7e:3c:86",
          "fw": "5.0.0-alpha+local"
        }
        """.trimIndent(),
        secret = secret,
        receivedAtEpochMillis = 2_600L,
    ) ?: error("Second reported serial reply should produce a mutation")

    check(firstReply.snapshot.devicesByKey.size == 1)
    check(secondReply.snapshot.devicesByKey.size == 2)
    check(secondReply.snapshot.devicesByKey.keys.contains("cpu:c135bcda09c23b09"))
    check(secondReply.snapshot.devicesByKey.keys.contains("cpu:fe64bada09122316"))
    check(secondReply.snapshot.devicesByKey["cpu:c135bcda09c23b09"]?.serial == "FFFFFFFFFFFFFFFFFFFFF")
    check(secondReply.snapshot.devicesByKey["cpu:fe64bada09122316"]?.serial == "boo-boo Kaka")
}

private object JvmSignatureEngine : SignatureEngine {
    override fun hmacSha256Hex(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

private object JvmRtcCodec : RtcCodec {
    private val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd-HH:mm:ss")

    override fun parseUtcMillis(value: String): Long? {
        return runCatching {
            LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    override fun formatUtcMillis(epochMillis: Long): String {
        return formatter.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC))
    }
}
