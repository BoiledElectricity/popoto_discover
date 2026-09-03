package com.popotomodem.discover

import java.io.File

data class BootloaderImageSupport(
    val presentMarkers: List<String>,
    val missingRequiredMarkers: List<String>,
    val missingOptionalMarkers: List<String>,
) {
    val hasPmmAoeSupport: Boolean
        get() = missingRequiredMarkers.isEmpty()

    fun warningText(): String {
        return "This imx-boot image does not appear to include PMM automatic AoE/discovery support. " +
            "Missing discovery marker(s): ${missingRequiredMarkers.joinToString()}. " +
            "If you flash it, Popoto Discover may not be able to enter or discover U-Boot AoE mode."
    }
}

object BootloaderImageSupportInspector {
    internal val requiredMarkers = listOf(
        "pmm_aoe_boot",
        "aoe mmc",
        "discover_reply",
        "aoe_active",
    )

    internal val optionalMarkers = listOf(
        "PMM AoE flash mode",
        "PMM U-Boot",
        "supports_boot_linux",
        "supports_mfg_test",
        "resize_rootfs",
        "supports_finalize_flash",
        "mfg_test_reply",
        "run_mfg_test",
    )

    fun inspect(image: File): BootloaderImageSupport {
        require(image.isFile) { "imx-boot image not found: $image" }
        val text = image.readBytes().toString(Charsets.ISO_8859_1)
        val present = (requiredMarkers + optionalMarkers).filter(text::contains)
        return BootloaderImageSupport(
            presentMarkers = present,
            missingRequiredMarkers = requiredMarkers.filterNot(text::contains),
            missingOptionalMarkers = optionalMarkers.filterNot(text::contains),
        )
    }
}
