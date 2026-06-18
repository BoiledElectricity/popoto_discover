package com.popotomodem.discover

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProtocolTest {
    @Test
    fun hmacMatchesPythonCanonicalJson() {
        val message = linkedMapOf(
            "cmd" to JsonPrimitive(Protocol.MSG_DISCOVER),
            "nonce" to JsonPrimitive("12345678"),
        )

        assertEquals(
            "{\"cmd\": \"discover_hydrophone\", \"nonce\": \"12345678\"}",
            Protocol.canonicalJson(message),
        )
        assertEquals(
            "6cb0c93f8211935eab5965cd0fc81962cf09148bb8bd52c07e1d1c3e0291099f",
            Protocol.computeMessageAuth(message, "0123456789abcdef"),
        )
    }

    @Test
    fun builtInDefaultSecretAddsAuthentication() {
        val message = Protocol.createDiscoverMessage("12345678", SecretProvider.load(null))

        assertNotNull(message["auth"])
        assertEquals(true, Protocol.verifyAuth(message, SecretProvider.load(null)))
    }

    @Test
    fun verifiesCapturedLinuxL2DiscoveryReply() {
        val message = Protocol.json.parseToJsonElement(
            """
            {
              "cmd": "discover_reply",
              "nonce": "9591c001",
              "model": "pss",
              "serial": "unknown",
              "device_id": "8539bcda09622732",
              "cpu_uid": "8539bcda09622732",
              "ip": "10.0.0.238",
              "mac": "8c:1f:64:71:90:43",
              "fw": "unknown",
              "http_port": 80,
              "name": "pss-8539bcda09622732",
              "hostname": "pss",
              "mdns_hostname": "pss",
              "identity_source": "cpu_uid",
              "netmask": "255.255.255.0",
              "gateway": "",
              "battery_v": 66190.35,
              "sample_rate_hz": 102400,
              "recording_state": "UNIMPLEMENTED",
              "storage_free_gb": 5.51,
              "storage_total_gb": 8.06,
              "auth": "a745116c0c0d1967a036f91a5d6ac784429264272833e92ced06ef0ee79c6e2a"
            }
            """.trimIndent(),
        ).jsonObject

        assertEquals(true, Protocol.verifyAuth(message, SecretProvider.load(null)))
    }

    @Test
    fun l2FrameRoundTripsJsonMessage() {
        val source = L2Protocol.parseMac("02:00:00:00:00:01")
        val destination = L2Protocol.broadcastAddress()
        val message = Protocol.createDiscoverMessage("abcdef12", null)

        val frame = L2Protocol.buildJsonFrame(destination, source, message)
        val parsed = L2Protocol.parseJsonFrame(frame, "eth-test")

        assertNotNull(parsed)
        assertEquals("02:00:00:00:00:01", parsed.sourceMac)
        assertEquals("ff:ff:ff:ff:ff:ff", parsed.destinationMac)
        assertEquals("eth-test", parsed.interfaceName)
        assertEquals(Protocol.MSG_DISCOVER, Protocol.text(parsed.message, "cmd"))
        assertEquals("abcdef12", Protocol.text(parsed.message, "nonce"))
        assertContentEquals(frame, L2Protocol.buildJsonFrame(destination, source, message))
    }

    @Test
    fun setIpMessageIsStaticOnlyAndTargetsSerial() {
        val message = Protocol.createSetIpMessage(
            nonce = "abc12345",
            target = TargetSelector.parse("eba9affefe64bada09122316"),
            newIp = "10.1.0.239",
            netmask = "255.255.255.0",
            gateway = "10.1.0.1",
            secret = null,
        )

        assertEquals(Protocol.MSG_SET_IP, Protocol.text(message, "cmd"))
        assertEquals("abc12345", Protocol.text(message, "nonce"))
        assertEquals("eba9affefe64bada09122316", Protocol.text(message, "target_serial"))
        assertEquals("eba9affefe64bada09122316", Protocol.text(message, "target_id"))
        assertEquals("10.1.0.239", Protocol.text(message, "new_ip"))
        assertEquals("255.255.255.0", Protocol.text(message, "netmask"))
        assertEquals("10.1.0.1", Protocol.text(message, "gateway"))
        assertEquals(
            setOf("cmd", "nonce", "target_serial", "target_id", "new_ip", "netmask", "gateway"),
            message.keys,
        )
    }
}
