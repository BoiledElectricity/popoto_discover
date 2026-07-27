package com.popotomodem.discover

data class ActiveBootloaderSupport(
    val partitionConfig: String,
    val activeSlot: String,
    val presentMarkers: List<String>,
    val missingMarkers: List<String>,
) {
    val hasPmmAoeSupport: Boolean
        get() = missingMarkers.isEmpty()

    fun failureText(targetLabel: String): String {
        return "Safety check blocked reboot of $targetLabel: active eMMC $activeSlot " +
            "does not contain the required Popoto Discover AoE U-Boot support " +
            "(missing: ${missingMarkers.joinToString()}). Select a current imx-boot " +
            "built from uboot-imx and enable Program U-Boot before flashing."
    }
}

object ActiveBootloaderSupportInspector {
    private const val EMMC = "/dev/mmcblk2"

    fun inspect(
        commandClient: CommandClient,
        target: TargetSelector,
        options: CommandOptions,
    ): ActiveBootloaderSupport {
        val response = commandClient.shellExec(
            target,
            probeCommand(),
            options,
            timeoutSeconds = 15.0,
        ) ?: throw RuntimeException(
            "No reply while checking the active eMMC U-Boot on ${target.label}; " +
                "refusing to reboot into AoE mode.",
        )

        if (response.text("status") != "ok") {
            throw RuntimeException(
                "Could not inspect the active eMMC U-Boot on ${target.label}: " +
                    "${response.text("error") ?: "unknown device error"}. Refusing to reboot into AoE mode.",
            )
        }

        return parseProbeOutput(response.text("stdout").orEmpty(), target.label)
    }

    fun requireSupported(
        commandClient: CommandClient,
        target: TargetSelector,
        options: CommandOptions,
    ): ActiveBootloaderSupport {
        val support = inspect(commandClient, target, options)
        if (!support.hasPmmAoeSupport) {
            throw RuntimeException(support.failureText(target.label))
        }
        return support
    }

    internal fun probeCommand(): String {
        val markerChecks = BootloaderImageSupportInspector.requiredMarkers.mapIndexed { index, marker ->
            "if LC_ALL=C grep -aFqm1 ${shellQuote(marker)} \"\$dev\"; then echo marker_$index=1; else echo marker_$index=0; fi"
        }
        return buildString {
            append("cfg=\$(mmc extcsd read $EMMC 2>/dev/null | grep -i PARTITION_CONFIG | grep -o '0x[0-9a-fA-F]*' | head -1); ")
            append("if [ -z \"\$cfg\" ]; then echo probe_error=partition_config_unavailable; exit 0; fi; ")
            append("dec=\$(printf '%d' \"\$cfg\" 2>/dev/null); ")
            append("if [ -z \"\$dec\" ]; then echo probe_error=partition_config_invalid; exit 0; fi; ")
            append("active=\$(( (dec >> 3) & 7 )); ")
            append("case \"\$active\" in 1) slot=boot0;; 2) slot=boot1;; *) echo probe_error=unsupported_active_partition_\$active; exit 0;; esac; ")
            append("dev=${EMMC}\$slot; ")
            append("if [ ! -b \"\$dev\" ]; then echo probe_error=active_boot_device_missing; exit 0; fi; ")
            append("echo partition_config=\$cfg; echo active_slot=\$slot; ")
            append(markerChecks.joinToString("; "))
        }
    }

    internal fun parseProbeOutput(output: String, targetLabel: String): ActiveBootloaderSupport {
        val fields = output.lineSequence()
            .map(String::trim)
            .filter { it.contains('=') }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

        fields["probe_error"]?.let { error ->
            val detail = when {
                error == "partition_config_unavailable" -> "could not read eMMC PARTITION_CONFIG"
                error == "partition_config_invalid" -> "eMMC PARTITION_CONFIG was invalid"
                error.startsWith("unsupported_active_partition_") ->
                    "eMMC is not configured to boot from boot0 or boot1 (${error.removePrefix("unsupported_active_partition_")})"
                error == "active_boot_device_missing" -> "the active eMMC boot device is missing"
                else -> error.replace('_', ' ')
            }
            throw RuntimeException(
                "Safety check blocked reboot of $targetLabel: $detail. " +
                    "Refusing to enter AoE mode until the active U-Boot can be verified.",
            )
        }

        val partitionConfig = fields["partition_config"]
            ?: throw RuntimeException("Safety check blocked reboot of $targetLabel: active eMMC PARTITION_CONFIG was not reported.")
        val activeSlot = fields["active_slot"]
            ?.takeIf { it == "boot0" || it == "boot1" }
            ?: throw RuntimeException("Safety check blocked reboot of $targetLabel: active eMMC boot slot was not reported.")

        val present = mutableListOf<String>()
        val missing = mutableListOf<String>()
        BootloaderImageSupportInspector.requiredMarkers.forEachIndexed { index, marker ->
            if (fields["marker_$index"] == "1") present += marker else missing += marker
        }
        return ActiveBootloaderSupport(partitionConfig, activeSlot, present, missing)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
