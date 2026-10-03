package com.popotomodem.discover

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files

internal object WindowsSeLowAccess {
    private const val RESOURCE_DIR = "/windows/selow"
    private val files = listOf("SeLow_x64.inf", "SeLow_x64.sys", "SeLow_Win10_x64.cat", "install.ps1")

    fun hasDriver(): Boolean {
        if (!WindowsPacketAccess.isWindows()) return false
        return runCatching {
            val handle = SeLowNative.open(SeLowNative.DEVICE)
            SeLowNative.api.CloseHandle(handle)
            true
        }.getOrDefault(false)
    }

    fun hasBundledDriver(): Boolean = files.all { WindowsSeLowAccess::class.java.getResource("$RESOURCE_DIR/$it") != null }

    fun install(): WindowsPacketAccess.InstallResult {
        if (hasDriver()) return WindowsPacketAccess.InstallResult(true, 0, "Windows Ethernet driver is ready.")
        if (!hasBundledDriver()) return WindowsPacketAccess.InstallResult(false, 1, "This build does not include the Windows Ethernet driver. Install the complete Windows MSI.")
        if (!isAdministrator()) return WindowsPacketAccess.InstallResult(false, 5, "Run Popoto Discover as administrator to enable raw Ethernet discovery and flashing.")
        val directory = Files.createTempDirectory("popoto-selow-")
        return try {
            for (file in files) {
                WindowsSeLowAccess::class.java.getResourceAsStream("$RESOURCE_DIR/$file")!!.use { input ->
                    Files.copy(input, directory.resolve(file))
                }
            }
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", directory.resolve("install.ps1").toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            WindowsPacketAccess.InstallResult(code in listOf(0, 3010) && (hasDriver() || code == 3010), code, output, code == 3010)
        } catch (failure: Exception) {
            WindowsPacketAccess.InstallResult(false, 1, failure.message ?: "Windows Ethernet setup failed.")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    fun isAdministrator(): Boolean = shell.IsUserAnAdmin()

    fun relaunchGuiIfNeeded(args: Array<String>): Boolean {
        if (!WindowsPacketAccess.isWindows() || args.firstOrNull() != "gui" || !hasBundledDriver() || isAdministrator()) return false
        val executable = System.getProperty("jpackage.app-path") ?: return false
        val parameters = args.joinToString(" ", transform = ::quoteArgument)
        val result = shell.ShellExecuteW(null, WString("runas"), WString(executable), WString(parameters), null, 1)
        val code = Pointer.nativeValue(result)
        if (code <= 32) throw IllegalStateException("Administrator approval is required for Windows raw Ethernet access (error $code).")
        return true
    }

    internal fun quoteArgument(value: String): String = buildString {
        append('"')
        var slashes = 0
        for (character in value) {
            if (character == '\\') {
                slashes++
            } else {
                repeat(if (character == '"') slashes * 2 + 1 else slashes) { append('\\') }
                slashes = 0
                append(character)
            }
        }
        repeat(slashes * 2) { append('\\') }
        append('"')
    }

    private val shell: Shell32 by lazy { Native.load("shell32", Shell32::class.java) }
    private interface Shell32 : StdCallLibrary {
        fun IsUserAnAdmin(): Boolean
        fun ShellExecuteW(window: Pointer?, operation: WString, file: WString, parameters: WString, directory: WString?, show: Int): Pointer?
    }
}
