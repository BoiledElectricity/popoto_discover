package com.popotomodem.discover

object UbootAoeTargetResolver {
    fun currentMismatch(device: Device, expected: AoETargetAddress): AoETargetAddress? {
        if (device.text("uboot") != "1" || device.text("aoe_active") != "1") {
            return null
        }
        val current = device.text("aoe_target")?.let { label ->
            runCatching { AoETargetAddress.parse(label) }.getOrNull()
        } ?: return null
        return current.takeIf { it != expected }
    }

    fun resolve(requests: List<FlashRequest>): List<FlashRequest> {
        return requests.map { request -> resolveOne(request, requests.size) }
    }

    private fun resolveOne(request: FlashRequest, requestCount: Int): FlashRequest {
        val device = request.initialDevice
        if (device.text("uboot") != "1") {
            return request
        }
        require(FlashWorkflow.matchesTarget(device, request.target)) {
            "Discovered U-Boot identity does not match selected target ${request.target.label}"
        }

        val active = device.text("aoe_active") == "1"
        val currentLabel = device.text("aoe_target")
        if (!active || currentLabel.isNullOrBlank()) {
            throw IllegalArgumentException(
                "Target ${request.target.label} is in U-Boot but is not exporting an AoE block device. " +
                    "Run aoe mmc 2, or boot Linux and start the normal automatic workflow.",
            )
        }

        val currentTarget = runCatching { AoETargetAddress.parse(currentLabel) }.getOrElse {
            throw IllegalArgumentException(
                "Target ${request.target.label} reported invalid AoE target '$currentLabel'.",
            )
        }
        if (currentTarget == request.aoeTarget) {
            return request
        }

        if (!request.allowSingleTargetAoeFallback) {
            throw IllegalArgumentException(
                "Target ${request.target.label} is in U-Boot exporting ${currentTarget.label}, not the expected " +
                    "${request.aoeTarget.label}. For one physically verified board, confirm the single-target " +
                    "fallback in the app or pass --allow-single-target-aoe-fallback.",
            )
        }
        require(requestCount == 1) {
            "The current AoE target fallback is restricted to exactly one selected board"
        }
        runCatching { UbootFinalizationMode.forDevice(device) }.getOrElse {
            throw IllegalArgumentException(
                "Cannot safely use ${currentTarget.label} for ${request.target.label}: this U-Boot does not " +
                    "advertise finalize_flash or boot_linux completion support. Program a current imx-boot first.",
            )
        }

        val sourceMac = l2SourceMac(device, request.interfaceName)
            ?: throw IllegalArgumentException(
                "Cannot safely use ${currentTarget.label} for ${request.target.label}: its U-Boot L2 source MAC " +
                    "was not reported on ${request.interfaceName}.",
            )

        return request.copy(
            aoeTarget = currentTarget,
            expectedAoeSourceMac = sourceMac,
        )
    }

    private fun l2SourceMac(device: Device, interfaceName: String): String? {
        val preferred = device.paths.firstOrNull { path ->
            path.transport.equals("l2", ignoreCase = true) && path.interfaceName == interfaceName
        }
        return usableMac(preferred?.sourceMac)
            ?: device.paths.asSequence()
                .filter { it.transport.equals("l2", ignoreCase = true) }
                .mapNotNull { usableMac(it.sourceMac) }
                .firstOrNull()
    }
}
