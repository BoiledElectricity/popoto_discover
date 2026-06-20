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
        event("Checking device identity/license files to preserve")
        return PRESERVED_DEVICE_FILES.mapNotNull { path ->
            readDeviceFile(target, path)
        }.also { preserved ->
            event("Preserved ${preserved.size}/${PRESERVED_DEVICE_FILES.size} device file(s)")
        }
    }

    fun restore(target: TargetSelector, preservedFiles: List<PreservedDeviceFile>) {
        if (preservedFiles.isEmpty()) {
            event("No device identity/license files to restore")
            return
        }

        event("Restoring ${preservedFiles.size} device identity/license file(s)")
        for (file in preservedFiles) {
            restoreDeviceFile(target, file)
        }
        requireOk(
            commandClient.shellExec(target, "sync", options, timeoutSeconds = 5.0),
            "sync restored device files",
        )
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
