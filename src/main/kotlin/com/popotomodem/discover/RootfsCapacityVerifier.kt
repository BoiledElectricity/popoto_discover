package com.popotomodem.discover

data class RootfsCapacity(
    val diskSectors: Long,
    val partitionStartSectors: Long,
    val partitionSectors: Long,
    val filesystemBlocks: Long,
    val filesystemBlockSize: Long,
) {
    val partitionEndSectors: Long
        get() = partitionStartSectors + partitionSectors

    val filesystemBytes: Long
        get() = Math.multiplyExact(filesystemBlocks, filesystemBlockSize)

    val partitionBytes: Long
        get() = Math.multiplyExact(partitionSectors, 512L)
}

object RootfsCapacityVerifier {
    private const val MAX_UNUSED_TAIL_SECTORS = 2048L
    private const val MIN_FILESYSTEM_PERCENT = 95L

    fun verify(
        commandClient: CommandClient,
        target: TargetSelector,
        options: CommandOptions,
    ): RootfsCapacity {
        val response = commandClient.shellExec(
            target,
            probeCommand(),
            options,
            timeoutSeconds = 10.0,
            repeatRequest = true,
        ) ?: throw RuntimeException("No reply while verifying resized rootfs on ${target.label}")
        if (response.text("status") != "ok") {
            throw RuntimeException(
                "Could not verify resized rootfs on ${target.label}: " +
                    (response.text("error") ?: "unknown device error"),
            )
        }
        return parseAndRequireFull(response.text("stdout").orEmpty(), target.label)
    }

    internal fun probeCommand(): String {
        return "disk=\$(blockdev --getsz /dev/mmcblk2); " +
            "start=\$(cat /sys/class/block/mmcblk2p2/start); " +
            "part=\$(blockdev --getsz /dev/mmcblk2p2); " +
            "set -- \$(stat -f -c '%b %S' /); " +
            "echo disk_sectors=\$disk; echo partition_start=\$start; " +
            "echo partition_sectors=\$part; echo filesystem_blocks=\$1; " +
            "echo filesystem_block_size=\$2"
    }

    internal fun parseAndRequireFull(output: String, targetLabel: String): RootfsCapacity {
        val fields = output.lineSequence()
            .map(String::trim)
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        fun positive(name: String): Long {
            return fields[name]?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw RuntimeException("Rootfs verification for $targetLabel did not report valid $name")
        }

        val capacity = RootfsCapacity(
            diskSectors = positive("disk_sectors"),
            partitionStartSectors = positive("partition_start"),
            partitionSectors = positive("partition_sectors"),
            filesystemBlocks = positive("filesystem_blocks"),
            filesystemBlockSize = positive("filesystem_block_size"),
        )
        val tail = capacity.diskSectors - capacity.partitionEndSectors
        if (tail < 0 || tail > MAX_UNUSED_TAIL_SECTORS) {
            throw RuntimeException(
                "Rootfs finalization failed on $targetLabel: partition 2 ends at sector " +
                    "${capacity.partitionEndSectors}, disk has ${capacity.diskSectors} sectors",
            )
        }
        if (capacity.filesystemBytes * 100L < capacity.partitionBytes * MIN_FILESYSTEM_PERCENT) {
            throw RuntimeException(
                "Rootfs finalization failed on $targetLabel: filesystem is ${capacity.filesystemBytes} bytes " +
                    "but partition 2 is ${capacity.partitionBytes} bytes",
            )
        }
        return capacity
    }
}
