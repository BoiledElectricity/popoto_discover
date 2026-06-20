package com.popotomodem.discover

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

data class PreservedDeviceFile(
    val path: String,
    val bytes: ByteArray,
    val mode: String?,
    val owner: String?,
    val group: String?,
)

class DeviceFilePreserver(
    private val commandClient: CommandClient,
    private val options: CommandOptions,
    private val onEvent: (FlashEvent) -> Unit,
) {
    fun preserve(target: TargetSelector): List<PreservedDeviceFile> {
        event("Checking device identity/license/network files to preserve")
        val paths = preservedFilePaths(target)
        return paths.mapNotNull { path ->
            readDeviceFile(target, path)
        }.also { preserved ->
            event("Preserved ${preserved.size}/${paths.size} device file(s)")
        }
    }

    fun restore(target: TargetSelector, preservedFiles: List<PreservedDeviceFile>) {
        if (preservedFiles.isEmpty()) {
            event("No device identity/license/network files to restore")
            return
        }

        event("Restoring ${preservedFiles.size} device identity/license/network file(s)")
        for (file in preservedFiles) {
            restoreDeviceFile(target, file)
        }
        requireOk(
            commandClient.shellExec(target, "sync", options, timeoutSeconds = 5.0),
            "sync restored device files",
        )
        restartNetworkingIfNeeded(target, preservedFiles)
    }

    private fun preservedFilePaths(target: TargetSelector): List<String> {
        val paths = PRESERVED_DEVICE_FILES.toMutableList()
        val response = commandClient.shellExec(
            target,
            "find /etc/network/interfaces.d -maxdepth 1 -type f -print 2>/dev/null | sort",
            options,
            timeoutSeconds = 5.0,
        )
        if (response?.text("status") == "ok") {
            response.text("stdout")
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.startsWith("/etc/network/interfaces.d/") }
                ?.forEach(paths::add)
        } else {
            event("Preserve skipped, could not enumerate /etc/network/interfaces.d")
        }
        return paths.distinct()
    }

    private fun readDeviceFile(target: TargetSelector, path: String): PreservedDeviceFile? {
        val quoted = shellQuote(path)
        val metadata = requireOk(
            commandClient.shellExec(
                target,
                "if [ -f $quoted ]; then stat -c '%s %a %U %G' -- $quoted; else echo MISSING; fi",
                options,
                timeoutSeconds = 5.0,
            ),
            "inspect $path",
            logStdout = false,
        ).text("stdout")?.trim().orEmpty().lineSequence().lastOrNull()?.trim().orEmpty()

        if (metadata == "MISSING") {
            event("Preserve skipped, missing: $path")
            return null
        }

        val parts = metadata.split(Regex("\\s+"), limit = 4)
        val size = parts.getOrNull(0)?.toLongOrNull()
            ?: throw RuntimeException("Unable to inspect $path: '$metadata'")
        if (size > MAX_PRESERVED_FILE_BYTES) {
            throw RuntimeException("Refusing to preserve $path: $size bytes exceeds limit $MAX_PRESERVED_FILE_BYTES")
        }
        val mode = parts.getOrNull(1)
        val owner = parts.getOrNull(2)
        val group = parts.getOrNull(3)

        val out = ByteArrayOutputStream(size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var offset = 0L
        while (offset < size) {
            val response = requireOk(
                commandClient.shellExec(
                    target,
                    "dd if=$quoted bs=$PRESERVE_CHUNK_BYTES skip=$offset iflag=skip_bytes,count_bytes count=$PRESERVE_CHUNK_BYTES 2>/dev/null | base64 -w0",
                    options,
                    timeoutSeconds = 5.0,
                ),
                "read $path at $offset",
                logStdout = false,
            )
            val encoded = response.text("stdout")?.trim().orEmpty()
            if (encoded.contains("[truncated]")) {
                throw RuntimeException("Failed to read $path: shell output was truncated")
            }
            val chunk = if (encoded.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(encoded)
            out.write(chunk)
            offset += chunk.size
            if (chunk.isEmpty()) {
                throw RuntimeException("Failed to read $path: empty chunk at offset $offset")
            }
        }
        val bytes = out.toByteArray()
        if (bytes.size.toLong() != size) {
            throw RuntimeException("Failed to read $path: expected $size bytes, got ${bytes.size}")
        }
        event("Preserved $path (${bytes.size} bytes)")
        return PreservedDeviceFile(path, bytes, mode, owner, group)
    }

    private fun restoreDeviceFile(target: TargetSelector, file: PreservedDeviceFile) {
        val quoted = shellQuote(file.path)
        requireOk(
            commandClient.shellExec(
                target,
                "mkdir -p -- ${shellQuote(File(file.path).parent ?: "/")} && : > $quoted",
                options,
                timeoutSeconds = 5.0,
            ),
            "prepare ${file.path}",
        )

        var offset = 0
        while (offset < file.bytes.size) {
            val end = (offset + RESTORE_CHUNK_BYTES).coerceAtMost(file.bytes.size)
            val encoded = Base64.getEncoder().encodeToString(file.bytes.copyOfRange(offset, end))
            requireOk(
                commandClient.shellExec(
                    target,
                    "python3 -c ${shellQuote("import base64,sys;p=sys.argv[1];o=int(sys.argv[2]);d=base64.b64decode(sys.argv[3]);f=open(p,'r+b');f.seek(o);f.write(d);f.close()")} $quoted $offset ${shellQuote(encoded)}",
                    options,
                    timeoutSeconds = 5.0,
                ),
                "restore ${file.path} at $offset",
                logStdout = false,
            )
            offset = end
        }

        file.mode?.takeIf { it.matches(Regex("[0-7]{3,4}")) }?.let { mode ->
            requireOk(
                commandClient.shellExec(target, "chmod $mode -- $quoted", options, timeoutSeconds = 5.0),
                "chmod ${file.path}",
            )
        }
        if (!file.owner.isNullOrBlank() && !file.group.isNullOrBlank()) {
            requireOk(
                commandClient.shellExec(
                    target,
                    "chown ${shellQuote(file.owner)}:${shellQuote(file.group)} -- $quoted",
                    options,
                    timeoutSeconds = 5.0,
                ),
                "chown ${file.path}",
            )
        }
        event("Restored ${file.path} (${file.bytes.size} bytes)")
    }

    private fun restartNetworkingIfNeeded(target: TargetSelector, preservedFiles: List<PreservedDeviceFile>) {
        if (preservedFiles.none { it.path.startsWith("/etc/network/") }) {
            return
        }

        event("Restarting networking with restored interface files")
        val script = "( sleep 1; " +
            "if command -v systemctl >/dev/null 2>&1; then systemctl restart networking.service || systemctl restart networking || true; fi; " +
            "if [ -x /etc/init.d/networking ]; then /etc/init.d/networking restart || true; fi; " +
            "if command -v ifdown >/dev/null 2>&1 && command -v ifup >/dev/null 2>&1; then ifdown eth0 2>/dev/null || true; ifup eth0 2>/dev/null || true; fi " +
            ") >/tmp/popoto-discover-network-restart.log 2>&1 </dev/null &"
        val response = commandClient.shellExec(
            target,
            "sh -c ${shellQuote(script)}",
            options,
            timeoutSeconds = 2.0,
        )
        if (response?.text("status") == "ok") {
            event("Networking restart queued")
        } else {
            event("Networking restart queued without confirmation")
        }
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

    private fun event(message: String) {
        onEvent(FlashEvent(message))
    }

    companion object {
        private val PRESERVED_DEVICE_FILES = listOf(
            "/etc/PopotoSerialNumber.txt",
            "/etc/network/interfaces",
            "/opt/popoto/config.json",
            "/opt/popoto/license.json",
        )
        private const val PRESERVE_CHUNK_BYTES = 240
        private const val RESTORE_CHUNK_BYTES = 512
        private const val MAX_PRESERVED_FILE_BYTES = 1_048_576L
    }
}

fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}
