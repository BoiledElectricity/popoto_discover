package com.popotomodem.discover

import java.io.File

data class FlashArtifactPlan(
    val image: File,
    val mode: FlashMode,
    val bmap: File?,
    val bmapWasAutoDetected: Boolean,
    val bootloader: File?,
    val bootloaderSupport: BootloaderImageSupport?,
)

object FlashArtifactPlanner {
    fun resolve(
        image: File,
        requestedBmap: File?,
        forceFullImage: Boolean,
        bootloader: File?,
        allowUnsupportedBootloader: Boolean = false,
    ): FlashArtifactPlan {
        require(image.isFile) { "WIC image not found: $image" }
        require(image.canRead()) { "WIC image is not readable: $image" }
        require(!(forceFullImage && requestedBmap != null)) { "--full and --bmap cannot be used together" }

        val detectedBmap = if (!forceFullImage && requestedBmap == null) {
            FlashWorkflow.defaultBmapFor(image).takeIf(File::isFile)
        } else {
            null
        }
        val bmap = requestedBmap ?: detectedBmap
        if (requestedBmap != null) {
            require(requestedBmap.isFile) { "bmap not found: $requestedBmap" }
            require(requestedBmap.canRead()) { "bmap is not readable: $requestedBmap" }
        }

        val support = bootloader?.let { file ->
            require(file.isFile) { "imx-boot image not found: $file" }
            require(file.canRead()) { "imx-boot image is not readable: $file" }
            require(file.length() > 0) { "imx-boot image is empty: $file" }
            BootloaderImageSupportInspector.inspect(file).also {
                if (!it.hasPmmAoeSupport && !allowUnsupportedBootloader) {
                    throw IllegalArgumentException(
                        "${it.warningText()} Use --allow-unsupported-bootloader only for an intentional recovery operation.",
                    )
                }
            }
        }

        return FlashArtifactPlan(
            image = image,
            mode = if (forceFullImage || bmap == null) FlashMode.FULL_IMAGE else FlashMode.BMAP,
            bmap = bmap,
            bmapWasAutoDetected = detectedBmap != null,
            bootloader = bootloader,
            bootloaderSupport = support,
        )
    }

    fun validate(plan: FlashArtifactPlan): Bmap? {
        return when (plan.mode) {
            FlashMode.FULL_IMAGE -> null
            FlashMode.BMAP -> Bmap.parse(
                plan.bmap ?: throw IllegalArgumentException("bmap mode requires a bmap file"),
            )
        }
    }
}
