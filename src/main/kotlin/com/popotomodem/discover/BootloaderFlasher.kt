package com.popotomodem.discover

import java.io.File
import java.security.MessageDigest
import java.util.Base64

class BootloaderFlasher(
    private val commandClient: CommandClient,
    private val options: CommandOptions,
    private val onEvent: (FlashEvent) -> Unit,
) {
    fun flashIfRequested(target: TargetSelector, bootloader: File?) {
        bootloader ?: return
        val remoteDir = "/tmp/popoto-discover"
        val remoteImage = "$remoteDir/imx-boot"
        val flashScript = ensureUbootFlash(target, remoteDir)

        uploadRemoteFile(target, bootloader, remoteImage, "600", "imx-boot")
        val command = "${shellQuote(flashScript)} ${shellQuote(remoteImage)} boot0"
        event("Running bootloader command: $flashScript $remoteImage boot0")
        val response = requireOk(
            commandClient.shellExec(
                target,
                command,
                options,
                timeoutSeconds = 60.0,
            ),
            "flash bootloader",
        )
        requireStdoutContains(response, "uboot-flash: OK", "flash bootloader")
        commandClient.shellExec(
            target,
            "rm -f -- ${shellQuote(remoteImage)}",
            options,
            timeoutSeconds = 5.0,
        )
    }

    private fun ensureUbootFlash(target: TargetSelector, remoteDir: String): String {
        val installed = requireOk(
            commandClient.shellExec(
                target,
                "if [ -x /usr/local/bin/uboot-flash ]; then echo /usr/local/bin/uboot-flash; else echo MISSING; fi",
                options,
                timeoutSeconds = 5.0,
            ),
            "check uboot-flash",
            logStdout = false,
        ).text("stdout")?.trim().orEmpty().lineSequence().lastOrNull()?.trim().orEmpty()

        if (installed == "/usr/local/bin/uboot-flash") {
            event("Using device uboot-flash: $installed")
            return installed
        }

        val bundled = javaClass.getResourceAsStream("/tools/uboot-flash")?.use { it.readBytes() }
            ?: throw RuntimeException("Bundled uboot-flash resource is missing")
        val remoteScript = "$remoteDir/uboot-flash"
        event("Device uboot-flash missing; installing bundled copy to $remoteScript")
        uploadRemoteBytes(target, bundled, remoteScript, "755", "uboot-flash")
        return remoteScript
    }

    private fun uploadRemoteFile(
        target: TargetSelector,
        local: File,
        remotePath: String,
        mode: String,
        label: String,
    ) {
        uploadRemoteBytes(target, local.readBytes(), remotePath, mode, label)
    }

    private fun uploadRemoteBytes(
        target: TargetSelector,
        bytes: ByteArray,
        remotePath: String,
        mode: String,
        label: String,
    ) {
        val quotedPath = shellQuote(remotePath)
        val parent = shellQuote(File(remotePath).parent ?: "/tmp")
        requireOk(
            commandClient.shellExec(
                target,
                "mkdir -p -- $parent && rm -f -- $quotedPath && : > $quotedPath && chmod $mode -- $quotedPath",
                options,
                timeoutSeconds = 5.0,
            ),
            "prepare remote $label",
        )

        var offset = 0
        var nextReport = 0
        while (offset < bytes.size) {
            val end = (offset + UPLOAD_CHUNK_BYTES).coerceAtMost(bytes.size)
            val encoded = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))
            requireOk(
                commandClient.shellExec(
                    target,
                    "printf %s ${shellQuote(encoded)} | base64 -d >> $quotedPath",
                    options,
                    timeoutSeconds = 5.0,
                ),
                "upload $label at $offset",
                logStdout = false,
            )
            offset = end
            val percent = if (bytes.isEmpty()) 100 else (offset * 100L / bytes.size).toInt()
            if (percent >= nextReport || offset == bytes.size) {
                event("Uploaded $label: $percent% ($offset/${bytes.size} bytes)")
                nextReport += 10
            }
        }

        val expected = sha256(bytes)
        val actual = requireOk(
            commandClient.shellExec(
                target,
                "sha256sum -- $quotedPath | awk '{print \$1}'",
                options,
                timeoutSeconds = 10.0,
            ),
            "verify uploaded $label",
            logStdout = false,
        ).text("stdout")?.trim().orEmpty().lineSequence().lastOrNull()?.trim().orEmpty()
        if (!actual.equals(expected, ignoreCase = true)) {
            throw RuntimeException("Failed to verify uploaded $label: $actual != $expected")
        }
        event("Verified uploaded $label sha256: $expected")
    }

    private fun requireOk(response: CommandResponse?, action: String, logStdout: Boolean = true): CommandResponse {
        if (response == null) {
            throw RuntimeException("No reply while trying to $action. The PMM discovery service may need the SENG-982 shell_exec update.")
        }
        if (response.text("status") != "ok") {
            throw RuntimeException("Failed to $action: ${response.text("error") ?: "unknown error"}")
        }
        val stdout = response.text("stdout")?.trim().orEmpty()
        if (logStdout && stdout.isNotEmpty()) {
            event("$action stdout: $stdout")
        }
        return response
    }

    private fun requireStdoutContains(response: CommandResponse, expected: String, action: String) {
        val stdout = response.text("stdout").orEmpty()
        if (!stdout.lineSequence().any { it.trim().contains(expected) }) {
            throw RuntimeException("Failed to $action: expected '$expected', got '${stdout.trim()}'")
        }
    }

    private fun event(message: String) {
        onEvent(FlashEvent(message))
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    companion object {
        private const val UPLOAD_CHUNK_BYTES = 512
    }
}
