package com.popotomodem.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RootfsCapacityVerifierTest {
    @Test
    fun acceptsFilesystemAndPartitionSpanningEmmc() {
        val result = RootfsCapacityVerifier.parseAndRequireFull(
            """
            disk_sectors=61120512
            partition_start=2752512
            partition_sectors=58368000
            filesystem_blocks=7047003
            filesystem_block_size=4096
            """.trimIndent(),
            "device-1",
        )

        assertEquals(61120512, result.partitionEndSectors)
        assertEquals(28864524288, result.filesystemBytes)
    }

    @Test
    fun rejectsImageSizedPartitionOnLargerEmmc() {
        val error = assertFailsWith<RuntimeException> {
            RootfsCapacityVerifier.parseAndRequireFull(
                """
                disk_sectors=61120512
                partition_start=2752512
                partition_sectors=8315976
                filesystem_blocks=1039497
                filesystem_block_size=4096
                """.trimIndent(),
                "device-2",
            )
        }

        assertTrue(error.message.orEmpty().contains("partition 2 ends"))
    }

    @Test
    fun rejectsSmallFilesystemInsideFullPartition() {
        val error = assertFailsWith<RuntimeException> {
            RootfsCapacityVerifier.parseAndRequireFull(
                """
                disk_sectors=61120512
                partition_start=2752512
                partition_sectors=58368000
                filesystem_blocks=1039497
                filesystem_block_size=4096
                """.trimIndent(),
                "device-3",
            )
        }

        assertTrue(error.message.orEmpty().contains("filesystem is"))
    }
}
