package com.popotomodem.discover

import org.pcap4j.core.Pcaps

object WindowsPacketAccess {
    data class InstallResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val rebootRequired: Boolean = false,
    )

    fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("windows", ignoreCase = true)
    }

    fun transportUsesL2(mode: TransportMode): Boolean {
        return mode == TransportMode.AUTO || mode == TransportMode.L2 || mode == TransportMode.ALL
    }

    fun needsSetupFor(mode: TransportMode): Boolean {
        return isWindows() && transportUsesL2(mode) && !hasPacketAccess()
    }

    fun hasPacketAccess(): Boolean {
        return WindowsSeLowAccess.hasDriver() || WindowsPmmNdisAccess.hasDriver() || hasPcap()
    }

    private fun hasPcap(): Boolean {
        if (!isWindows()) {
            return true
        }
        return runCatching {
            Pcaps.findAllDevs()
            true
        }.getOrDefault(false)
    }

    fun install(): InstallResult {
        if (!isWindows()) {
            return InstallResult(true, 0, "Windows packet setup is only needed on Windows.")
        }

        if (hasPacketAccess()) {
            return InstallResult(true, 0, "Windows raw Ethernet access is ready.")
        }
        return WindowsSeLowAccess.install()
    }
}
