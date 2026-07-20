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
            marker_5=0
        """.trimIndent()

        val support = ActiveBootloaderSupportInspector.parseProbeOutput(output, "device-2")

        assertEquals("boot0", support.activeSlot)
        assertEquals(
            listOf("PMM AoE flash mode", "discover_reply", "PMM U-Boot"),
            support.missingMarkers,
        )
        assertTrue(!support.hasPmmAoeSupport)
        assertTrue(support.failureText("device-2").contains("Select a current imx-boot"))
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
