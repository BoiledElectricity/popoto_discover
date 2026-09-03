package com.popotomodem.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActiveBootloaderSupportInspectorTest {
    @Test
    fun parsesSupportedBoot1() {
        val output = buildString {
            appendLine("partition_config=0x50")
            appendLine("active_slot=boot1")
            BootloaderImageSupportInspector.requiredMarkers.indices.forEach { appendLine("marker_$it=1") }
        }

        val support = ActiveBootloaderSupportInspector.parseProbeOutput(output, "device-1")

        assertEquals("0x50", support.partitionConfig)
        assertEquals("boot1", support.activeSlot)
        assertTrue(support.hasPmmAoeSupport)
        assertEquals(BootloaderImageSupportInspector.requiredMarkers, support.presentMarkers)
    }

    @Test
    fun reportsEveryMissingCapability() {
        val output = """
            partition_config=0x48
            active_slot=boot0
            marker_0=1
            marker_1=0
            marker_2=1
            marker_3=0
            marker_4=1
            marker_5=1
        """.trimIndent()

        val support = ActiveBootloaderSupportInspector.parseProbeOutput(output, "device-2")

        assertEquals("boot0", support.activeSlot)
        assertEquals(
            listOf("aoe mmc", "aoe_active"),
            support.missingMarkers,
        )
        assertTrue(!support.hasPmmAoeSupport)
        assertTrue(support.failureText("device-2").contains("Select a current imx-boot"))
    }

    @Test
    fun exactActiveSlotVerificationAcceptsMatchingHash() {
        val hash = "a".repeat(64)
        val result = ActiveBootloaderWriteVerifier.parse(
            """
            partition_config=0x48
            active_slot=boot0
            active_sha256=$hash
            """.trimIndent(),
            expectedSize = 1_343_040,
            expectedSha256 = hash.uppercase(),
        )

        assertEquals("boot0", result.activeSlot)
        assertEquals("0x48", result.partitionConfig)
        assertEquals(1_343_040, result.imageSize)
        assertEquals(hash, result.imageSha256)
    }

    @Test
    fun exactActiveSlotVerificationRejectsDifferentBytes() {
        val error = assertFailsWith<RuntimeException> {
            ActiveBootloaderWriteVerifier.parse(
                """
                partition_config=0x48
                active_slot=boot0
                active_sha256=${"b".repeat(64)}
                """.trimIndent(),
                expectedSize = 1_343_040,
                expectedSha256 = "a".repeat(64),
            )
        }

        assertTrue(error.message.orEmpty().contains("does not match the supplied imx-boot"))
    }

    @Test
    fun exactActiveSlotCommandHashesOnlyTheImageLength() {
        val command = ActiveBootloaderWriteVerifier.command(1_343_040)

        assertTrue(command.length <= 1200, "active write verifier command is ${command.length} bytes")
        assertTrue(command.contains("head -c 1343040"))
        assertTrue(command.contains("PARTITION_CONFIG"))
        assertTrue(command.contains("active_sha256"))
    }

    @Test
    fun rejectsUserAreaBootSelection() {
        val error = assertFailsWith<RuntimeException> {
            ActiveBootloaderSupportInspector.parseProbeOutput(
                "probe_error=unsupported_active_partition_0",
                "device-3",
            )
        }

        assertTrue(error.message.orEmpty().contains("not configured to boot from boot0 or boot1"))
    }

    @Test
    fun probeReadsOnlyTheSelectedBootPartition() {
        val command = ActiveBootloaderSupportInspector.probeCommand()

        assertTrue(command.length <= 1200, "probe command is ${command.length} bytes")
        assertTrue(command.contains("PARTITION_CONFIG"))
        assertTrue(command.contains("slot=boot0"))
        assertTrue(command.contains("slot=boot1"))
        assertTrue(command.contains("dev=/dev/mmcblk2\$slot"))
        BootloaderImageSupportInspector.requiredMarkers.forEach { marker ->
            assertTrue(command.contains(marker))
        }
    }

    @Test
    fun aoeCleanupDisarmsEveryEntryPoint() {
        val command = UbootAoeMode.clearFlashEnvCommand()

        assertTrue(command.contains("fw_setenv pmm_aoe_flash 0"))
        assertTrue(command.contains("fw_setenv pmm_aoe_major 0"))
        assertTrue(command.contains("fw_setenv pmm_aoe_minor 0"))
        assertTrue(command.contains("fw_setenv pmm_eth_console 0"))
    }
}
