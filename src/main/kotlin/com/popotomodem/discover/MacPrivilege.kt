package com.popotomodem.discover

import java.io.File
import java.nio.file.Paths

object MacPrivilege {
    fun shouldRelaunchByDefault(): Boolean = isMac() && !isRoot()

    fun relaunchCurrentWithAdmin(rawArgs: List<String>): AdminResult {
        val isGui = rawArgs.firstOrNull() == "gui"
        return runWithAdministratorPrivileges(commandForCurrentApp(rawArgs), waitForOutput = !isGui)
    }

    fun requiresAdminForRawEthernet(mode: TransportMode): Boolean {
        return isMac() && !isRoot() && mode in setOf(TransportMode.L2, TransportMode.ALL)
    }

    fun cliShouldRelaunch(rawArgs: List<String>): Boolean {
        if (!isMac() || isRoot()) {
            return false
        }

        val args = rawArgs.toMutableList()
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--secret-file" -> {
                    if (index + 1 < args.size) {
                        args.removeAt(index)
                        args.removeAt(index)
                        continue
                    }
                }
                "--no-auth" -> {
                    args.removeAt(index)
                    continue
                }
            }
            index++
        }

        val command = args.firstOrNull() ?: return false
        if (command != "discover") {
            return false
        }

        index = 1
        while (index < args.size) {
            if (args[index] == "--transport" && index + 1 < args.size) {
                val mode = TransportMode.parse(args[index + 1])
                return requiresAdminForRawEthernet(mode)
            }
            index++
        }
        return false
    }

    fun relaunchCliWithAdmin(rawArgs: List<String>): AdminResult {
        return runWithAdministratorPrivileges(commandForCurrentApp(rawArgs), waitForOutput = true)
    }

    fun relaunchGuiWithAdmin(): AdminResult {
        return runWithAdministratorPrivileges(commandForCurrentApp(listOf("gui")), waitForOutput = false)
    }

    private fun isMac(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

    private fun isRoot(): Boolean {
        return runCatching {
            ProcessBuilder("/usr/bin/id", "-u")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim() == "0"
        }.getOrDefault(false)
    }

    private fun commandForCurrentApp(args: List<String>): List<String> {
        val processCommand = ProcessHandle.current().info().command().orElse("")
        if (processCommand.contains("/Contents/MacOS/") && File(processCommand).canExecute()) {
            return listOf(processCommand) + args
        }

        val jar = runCatching {
            File(MacPrivilege::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        if (jar != null && jar.isFile && jar.extension == "jar") {
            val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
            return listOf(java, "--enable-native-access=ALL-UNNAMED", "-jar", jar.absolutePath) + args
        }

        if (processCommand.isNotBlank()) {
            return listOf(processCommand) + args
        }

        throw IllegalStateException("could not determine current application command")
    }

    private fun runWithAdministratorPrivileges(command: List<String>, waitForOutput: Boolean): AdminResult {
        val shellCommand = command.joinToString(" ") { shellQuote(it) } +
            if (waitForOutput) " 2>&1" else " >/tmp/popoto-discover-admin.log 2>&1 &"
        val script = "do shell script ${appleScriptString(shellCommand)} with administrator privileges"
        val process = ProcessBuilder("/usr/bin/osascript", "-e", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        return AdminResult(code == 0, output, code)
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun appleScriptString(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

data class AdminResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int,
)
