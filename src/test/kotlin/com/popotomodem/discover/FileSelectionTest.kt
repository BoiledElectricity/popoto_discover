package com.popotomodem.discover

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSelectionTest {
    @Test
    fun filtersImxBootFilesByPrefix() {
        assertTrue(matchesFileSelection("imx-boot", suffix = null, prefix = "imx-boot"))
        assertTrue(matchesFileSelection("imx-boot-pmm-emmc.bin-flash_evk", suffix = null, prefix = "imx-boot"))
        assertTrue(matchesFileSelection("IMX-BOOT-PMM.bin", suffix = null, prefix = "imx-boot"))
        assertFalse(matchesFileSelection("u-boot.bin", suffix = null, prefix = "imx-boot"))
    }

    @Test
    fun combinesPrefixAndSuffixWhenBothAreProvided() {
        assertTrue(matchesFileSelection("imx-boot.wic.lz4", suffix = "wic.lz4", prefix = "imx-boot"))
        assertFalse(matchesFileSelection("image.wic.lz4", suffix = "wic.lz4", prefix = "imx-boot"))
        assertFalse(matchesFileSelection("imx-boot.bin", suffix = "wic.lz4", prefix = "imx-boot"))
    }
}
