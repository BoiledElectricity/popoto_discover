package com.popotomodem.discover

object UbootAoeMode {
    const val BOOTCMD = "run pmm_aoe_boot;run distro_bootcmd;run bsp_bootcmd"
    const val BOOT_SCRIPT =
        "if test ${'$'}{pmm_aoe_flash} = 1; then echo PMM AoE flash mode; aoe mmc ${'$'}{emmc_dev} ${'$'}{pmm_aoe_major} ${'$'}{pmm_aoe_minor}; setenv pmm_resize_rootfs_done 0; if resize_rootfs ${'$'}{emmc_dev} 2; then setenv pmm_resize_rootfs_done 1; saveenv; fi; fi"

    fun setEnvCommand(aoeTarget: AoETargetAddress): String {
        return listOf(
            "fw_setenv pmm_eth_console 0",
            "fw_setenv bootcmd ${shellQuote(BOOTCMD)}",
            "fw_setenv pmm_aoe_boot ${shellQuote(BOOT_SCRIPT)}",
            "fw_setenv pmm_aoe_flash 1",
            "fw_setenv pmm_aoe_major ${aoeTarget.major}",
            "fw_setenv pmm_aoe_minor ${aoeTarget.minor}",
        ).joinToString(" && ")
    }

    fun verifyEnvCommand(): String {
        return "fw_printenv bootcmd; fw_printenv pmm_aoe_boot; fw_printenv pmm_aoe_flash; fw_printenv pmm_aoe_major; fw_printenv pmm_aoe_minor"
    }

    fun rebootCommand(): String {
        return "(sleep 0.5; sync; /sbin/reboot || reboot) >/dev/null 2>&1 &"
    }

    fun verifyEnv(response: CommandResponse, aoeTarget: AoETargetAddress) {
        val stdout = response.text("stdout").orEmpty()
        requireContains(stdout, "bootcmd=$BOOTCMD", "bootcmd")
        requireContains(stdout, "pmm_aoe_boot=$BOOT_SCRIPT", "pmm_aoe_boot")
        requireContains(stdout, "pmm_aoe_flash=1", "pmm_aoe_flash")
        requireContains(stdout, "pmm_aoe_major=${aoeTarget.major}", "pmm_aoe_major")
        requireContains(stdout, "pmm_aoe_minor=${aoeTarget.minor}", "pmm_aoe_minor")
    }

    private fun requireContains(stdout: String, expected: String, label: String) {
        if (!stdout.contains(expected)) {
            throw RuntimeException("$label verification failed; expected '$expected' in: $stdout")
        }
    }
}
