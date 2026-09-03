package com.popotomodem.discover

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlashArtifactPlannerTest {
    @Test
    fun autoDetectsSiblingBmap() {
        val dir = Files.createTempDirectory("flash-artifacts")
        try {
            val image = dir.resolve("pmm-image.rootfs.wic.lz4").also { it.writeText("image") }
            dir.resolve("pmm-image.rootfs.wic.bmap").writeText(validBmap())

            val plan = FlashArtifactPlanner.resolve(image.toFile(), null, false, null)

            assertEquals(FlashMode.BMAP, plan.mode)
            assertTrue(plan.bmapWasAutoDetected)
            assertEquals(512, FlashArtifactPlanner.validate(plan)?.mappedBytes)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fallsBackToFullImageWhenSiblingBmapIsAbsent() {
        val dir = Files.createTempDirectory("flash-artifacts")
        try {
            val image = dir.resolve("pmm-image.rootfs.wic.lz4").also { it.writeText("image") }

            val plan = FlashArtifactPlanner.resolve(image.toFile(), null, false, null)

            assertEquals(FlashMode.FULL_IMAGE, plan.mode)
            assertNull(plan.bmap)
            assertNull(FlashArtifactPlanner.validate(plan))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun explicitMissingBmapFailsBeforeDeviceAccess() {
        val dir = Files.createTempDirectory("flash-artifacts")
        try {
            val image = dir.resolve("pmm-image.rootfs.wic.lz4").also { it.writeText("image") }
            val missing = dir.resolve("missing.bmap")

            val error = assertFailsWith<IllegalArgumentException> {
                FlashArtifactPlanner.resolve(image.toFile(), missing.toFile(), false, null)
            }

            assertTrue(error.message.orEmpty().contains("bmap not found"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun validBmap(): String {
        return """
            <?xml version="1.0"?>
            <bmap version="2.0">
              <ImageSize>512</ImageSize>
              <BlockSize>512</BlockSize>
              <ChecksumType>sha256</ChecksumType>
              <BlockMap>
                <Range chksum="${"0".repeat(64)}">0</Range>
              </BlockMap>
            </bmap>
        """.trimIndent()
    }
}
