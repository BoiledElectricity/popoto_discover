package com.popotomodem.discover

import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UbootAoeTargetResolverTest {
    @Test
    fun parsesAoETargetLabels() {
        assertEquals(AoETargetAddress(0, 0), AoETargetAddress.parse("e0.0"))
        assertEquals(AoETargetAddress(65534, 254), AoETargetAddress.parse("e65534.254"))
        assertFailsWith<IllegalArgumentException> { AoETargetAddress.parse("e65535.0") }
        assertFailsWith<IllegalArgumentException> { AoETargetAddress.parse("invalid") }
    }

    @Test
    fun identifiesCurrentMismatch() {
        val request = request(currentAoE = "e0.0")

        assertEquals(
            AoETargetAddress.DEFAULT,
            UbootAoeTargetResolver.currentMismatch(request.initialDevice, request.aoeTarget),
        )
    }

    @Test
    fun rejectsMismatchWithoutExplicitAuthorization() {
        val error = assertFailsWith<IllegalArgumentException> {
            UbootAoeTargetResolver.resolve(listOf(request(currentAoE = "e0.0")))
        }

        assertTrue(error.message.orEmpty().contains("--allow-single-target-aoe-fallback"))
    }

    @Test
    fun resolvesAuthorizedSingleTargetAndPinsUbootMac() {
        val resolved = UbootAoeTargetResolver.resolve(
            listOf(request(currentAoE = "e0.0", allowFallback = true)),
        ).single()

        assertEquals(AoETargetAddress.DEFAULT, resolved.aoeTarget)
        assertEquals("02:11:22:33:44:55", resolved.expectedAoeSourceMac)
    }

    @Test
    fun neverAllowsFallbackForMultipleTargets() {
        val error = assertFailsWith<IllegalArgumentException> {
            UbootAoeTargetResolver.resolve(
                listOf(
                    request("fe64bada09122316", "e0.0", true),
                    request("c135bcda09c23b09", "e0.0", true),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("exactly one"))
    }

    @Test
    fun fallbackRequiresL2SourceMac() {
        val request = request(currentAoE = "e0.0", allowFallback = true).copy(
            initialDevice = device("fe64bada09122316", "e0.0", sourceMac = null),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            UbootAoeTargetResolver.resolve(listOf(request))
        }

        assertTrue(error.message.orEmpty().contains("source MAC"))
    }

    @Test
    fun fallbackRequiresACompletionCapability() {
        val request = request(currentAoE = "e0.0", allowFallback = true).copy(
            initialDevice = device(
                "fe64bada09122316",
                "e0.0",
                supportsFinalize = false,
                supportsBootLinux = false,
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            UbootAoeTargetResolver.resolve(listOf(request))
        }

        assertTrue(error.message.orEmpty().contains("finalize_flash or boot_linux"))
    }

    @Test
    fun fallbackAcceptsBootLinuxCompletionCapability() {
        val request = request(currentAoE = "e0.0", allowFallback = true).copy(
            initialDevice = device(
                "fe64bada09122316",
                "e0.0",
                supportsFinalize = false,
                supportsBootLinux = true,
            ),
        )

        val resolved = UbootAoeTargetResolver.resolve(listOf(request)).single()

        assertEquals(AoETargetAddress.DEFAULT, resolved.aoeTarget)
        assertEquals("02:11:22:33:44:55", resolved.expectedAoeSourceMac)
    }

    @Test
    fun linuxDeviceHasNoCurrentMismatch() {
        val request = request(currentAoE = "e0.0").copy(
            initialDevice = device("fe64bada09122316", "e0.0", uboot = false),
        )

        assertNull(UbootAoeTargetResolver.currentMismatch(request.initialDevice, request.aoeTarget))
        assertEquals(request, UbootAoeTargetResolver.resolve(listOf(request)).single())
    }

    private fun request(
        identity: String = "fe64bada09122316",
        currentAoE: String,
        allowFallback: Boolean = false,
    ): FlashRequest {
        val device = device(identity, currentAoE)
        return FlashRequest(
            initialDevice = device,
            target = TargetSelector.parse(identity),
            interfaceName = "enp1s0",
            aoeTarget = AoETargetAddress.fromIdentity(identity),
            image = File("image.wic.lz4"),
            bmap = null,
            mode = FlashMode.FULL_IMAGE,
            bootloaderImage = null,
            secret = null,
            allowSingleTargetAoeFallback = allowFallback,
        )
    }

    private fun device(
        identity: String,
        currentAoE: String,
        sourceMac: String? = "02:11:22:33:44:55",
        uboot: Boolean = true,
        supportsFinalize: Boolean = true,
        supportsBootLinux: Boolean = false,
    ): Device {
        return Device(
            fields = mutableMapOf(
                "device_id" to JsonPrimitive(identity),
                "cpu_uid" to JsonPrimitive(identity),
                "uboot" to JsonPrimitive(if (uboot) "1" else "0"),
                "aoe_active" to JsonPrimitive("1"),
                "aoe_target" to JsonPrimitive(currentAoE),
                "supports_finalize_flash" to JsonPrimitive(if (supportsFinalize) "1" else "0"),
                "supports_boot_linux" to JsonPrimitive(if (supportsBootLinux) "1" else "0"),
            ),
            paths = mutableListOf(
                DiscoveryPath(
                    transport = "l2",
                    interfaceName = "enp1s0",
                    sourceMac = sourceMac,
                ),
            ),
        )
    }
}
