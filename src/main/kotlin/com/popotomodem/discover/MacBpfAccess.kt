package com.popotomodem.discover

import java.nio.file.Files

object MacBpfAccess {
    private const val GROUP = "access_bpf"
    private const val PLIST = "/Library/LaunchDaemons/com.popotomodem.ChmodBPF.plist"
    private const val INSTALL_DIR = "/Library/Application Support/PopotoDiscover"
    private const val SCRIPT = "$INSTALL_DIR/ChmodBPF"

    data class InstallResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
    )

    fun isMac(): Boolean {
        return System.getProperty("os.name").contains("mac", ignoreCase = true)
    }

    fun transportUsesL2(mode: TransportMode): Boolean {
        return mode == TransportMode.AUTO || mode == TransportMode.L2 || mode == TransportMode.ALL
    }

    fun needsSetupFor(@Suppress("UNUSED_PARAMETER") mode: TransportMode): Boolean {
        return isMac() && !hasBpfAccess()
    }

    fun hasBpfAccess(): Boolean {
        if (!isMac()) {
            return true
        }

        val process = ProcessBuilder(
            "/bin/sh",
            "-c",
            "for dev in /dev/bpf*; do [ -e \"\$dev\" ] || continue; [ -r \"\$dev\" ] && [ -w \"\$dev\" ] && exit 0; done; exit 1",
        ).redirectErrorStream(true).start()
        return process.waitFor() == 0
    }

    fun install(): InstallResult {
        if (!isMac()) {
            return InstallResult(true, 0, "BPF setup is only needed on macOS.")
        }

        val userName = System.getProperty("user.name").orEmpty()
        if (userName.isBlank() || userName == "root") {
            return InstallResult(false, 1, "Run Popoto Discover as the normal desktop user, not root.")
        }

        val tempScript = Files.createTempFile("popoto-chmodbpf-", ".sh")
        return try {
            Files.writeString(tempScript, installerShellScript(userName))
            tempScript.toFile().setReadable(true, true)
            tempScript.toFile().setExecutable(true, true)

            val command = "/bin/sh ${shellQuote(tempScript.toString())}"
            val appleScript = "do shell script ${appleScriptString(command)} with administrator privileges"
            val process = ProcessBuilder("/usr/bin/osascript", "-e", appleScript)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            InstallResult(
                success = exitCode == 0 && hasBpfAccess(),
                exitCode = exitCode,
                output = output,
            )
        } finally {
            runCatching { Files.deleteIfExists(tempScript) }
        }
    }

    private fun installerShellScript(userName: String): String {
        val quotedUser = shellQuote(userName)
        return listOf(
            "set -eu",
            "USER_NAME=$quotedUser",
            "GROUP=$GROUP",
            "INSTALL_DIR=${shellQuote(INSTALL_DIR)}",
            "SCRIPT=${shellQuote(SCRIPT)}",
            "PLIST=${shellQuote(PLIST)}",
            "/usr/sbin/dseditgroup -o create \"\$GROUP\" >/dev/null 2>&1 || true",
            "/usr/sbin/dseditgroup -o edit -a \"\$USER_NAME\" -t user \"\$GROUP\" >/dev/null 2>&1 || true",
            "/bin/mkdir -p \"\$INSTALL_DIR\"",
            "cat > \"\$SCRIPT\" <<'EOF'",
            "#!/bin/sh",
            "GROUP=\"$GROUP\"",
            "CONSOLE_USER=\$(/usr/bin/stat -f %Su /dev/console 2>/dev/null || echo \"\")",
            "for dev in /dev/bpf*; do",
            "    [ -e \"\$dev\" ] || continue",
            "    /usr/sbin/chown root:\"\$GROUP\" \"\$dev\" 2>/dev/null || true",
            "    /bin/chmod g+rw \"\$dev\" 2>/dev/null || true",
            "    if [ -n \"\$CONSOLE_USER\" ] && [ \"\$CONSOLE_USER\" != \"root\" ]; then",
            "        /bin/chmod +a \"\$CONSOLE_USER allow read,write\" \"\$dev\" 2>/dev/null || true",
            "    fi",
            "done",
            "exit 0",
            "EOF",
            "/usr/sbin/chown root:wheel \"\$SCRIPT\"",
            "/bin/chmod 755 \"\$SCRIPT\"",
            "cat > \"\$PLIST\" <<EOF",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
            "<plist version=\"1.0\">",
            "<dict>",
            "    <key>Label</key>",
            "    <string>com.popotomodem.ChmodBPF</string>",
            "    <key>ProgramArguments</key>",
            "    <array>",
            "        <string>$SCRIPT</string>",
            "    </array>",
            "    <key>RunAtLoad</key>",
            "    <true/>",
            "</dict>",
            "</plist>",
            "EOF",
            "/usr/sbin/chown root:wheel \"\$PLIST\"",
            "/bin/chmod 644 \"\$PLIST\"",
            "\"\$SCRIPT\"",
            "/bin/launchctl bootout system \"\$PLIST\" >/dev/null 2>&1 || true",
            "/bin/launchctl bootstrap system \"\$PLIST\" >/dev/null 2>&1 || /bin/launchctl load -w \"\$PLIST\" >/dev/null 2>&1 || true",
            "/bin/launchctl kickstart -k system/com.popotomodem.ChmodBPF >/dev/null 2>&1 || true",
        ).joinToString("\n")
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun appleScriptString(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .plus("\"")
    }
}
