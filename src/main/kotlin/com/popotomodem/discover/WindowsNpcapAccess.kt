package com.popotomodem.discover

import org.pcap4j.core.Pcaps
import java.nio.file.Files

object WindowsNpcapAccess {
    private const val NPCAP_RESOURCE = "/windows/npcap-oem.exe"

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
        return isWindows() && transportUsesL2(mode) && !hasNpcap()
    }

    fun hasBundledInstaller(): Boolean {
        return WindowsNpcapAccess::class.java.getResource(NPCAP_RESOURCE) != null
    }

    fun hasNpcap(): Boolean {
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
            return InstallResult(true, 0, "Npcap setup is only needed on Windows.")
        }

        val input = WindowsNpcapAccess::class.java.getResourceAsStream(NPCAP_RESOURCE)
            ?: return InstallResult(
                success = false,
                exitCode = 1,
                output = "This Popoto Discover build does not include the bundled Npcap OEM installer.",
            )

        val tempInstaller = Files.createTempFile("popoto-npcap-oem-", ".exe")
        return try {
            input.use { source ->
                Files.newOutputStream(tempInstaller).use { target -> source.copyTo(target) }
            }

            val script = """
                ${'$'}process = Start-Process -FilePath '${powershellSingleQuote(tempInstaller.toString())}' -ArgumentList @('/loopback_support=no','/winpcap_mode=yes','/admin_only=no','/S') -Verb RunAs -Wait -PassThru
                exit ${'$'}process.ExitCode
            """.trimIndent()
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script,
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            val rebootRequired = exitCode == 3010
            val success = exitCode == 0 && hasNpcap() || rebootRequired

            InstallResult(
                success = success,
                exitCode = exitCode,
                output = output,
                rebootRequired = rebootRequired,
            )
        } finally {
            runCatching { Files.deleteIfExists(tempInstaller) }
        }
    }

    private fun powershellSingleQuote(value: String): String {
        return value.replace("'", "''")
    }
}
