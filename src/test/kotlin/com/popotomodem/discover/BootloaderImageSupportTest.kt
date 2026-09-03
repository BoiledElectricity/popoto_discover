package com.popotomodem.discover

import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootloaderImageSupportTest {
    @Test
    fun acceptsAutomaticAoeImageWithoutConsoleOrDecorativeMarkers() {
        val path = Files.createTempFile("imx-boot-supported", ".bin")
        try {
            path.writeBytes(
                (
                    "pmm_aoe_boot\u0000aoe mmc\u0000discover_reply\u0000aoe_active"
                    ).toByteArray(),
            )

            val result = BootloaderImageSupportInspector.inspect(path.toFile())

            assertTrue(result.hasPmmAoeSupport)
            assertEquals(emptyList(), result.missingRequiredMarkers)
            assertTrue("PMM U-Boot" in result.missingOptionalMarkers)
            assertTrue("PMM AoE flash mode" in result.missingOptionalMarkers)
            assertTrue("supports_finalize_flash" in result.missingOptionalMarkers)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectsImageMissingAutomaticAoeEntryPoint() {
        val path = Files.createTempFile("imx-boot-unsupported", ".bin")
        try {
            path.writeBytes(
                (
                    "aoe mmc\u0000discover_reply\u0000aoe_active\u0000resize_rootfs"
                    ).toByteArray(),
            )

            val result = BootloaderImageSupportInspector.inspect(path.toFile())

            assertFalse(result.hasPmmAoeSupport)
            assertEquals(listOf("pmm_aoe_boot"), result.missingRequiredMarkers)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun acceptsLegacyDiscoveryImageWithoutExplicitFinalizeCommand() {
        val path = Files.createTempFile("imx-boot-legacy-discovery", ".bin")
        try {
            path.writeBytes(
                (
                    "pmm_aoe_boot\u0000aoe mmc\u0000discover_reply\u0000aoe_active\u0000" +
                        "resize_rootfs\u0000supports_boot_linux"
                    ).toByteArray(),
            )

            val result = BootloaderImageSupportInspector.inspect(path.toFile())

            assertTrue(result.hasPmmAoeSupport)
            assertEquals(emptyList(), result.missingRequiredMarkers)
            assertTrue("supports_finalize_flash" in result.missingOptionalMarkers)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
