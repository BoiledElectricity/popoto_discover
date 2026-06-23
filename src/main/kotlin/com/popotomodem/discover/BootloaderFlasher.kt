package com.popotomodem.discover

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.min

class BootloaderFlasher(
    private val commandClient: CommandClient,
    private val options: CommandOptions,
    private val onEvent: (FlashEvent) -> Unit,
    private val sshHost: String? = null,
) {
    private var reachableSshHost: String? = sshHost

    fun flashIfRequested(target: TargetSelector, bootloader: File?) {
        bootloader ?: return
        val remoteDir = "/root/popoto-discover"
        val remoteImage = "$remoteDir/imx-boot"
        val flashScript = ensureUbootFlash(target)

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

    private fun ensureUbootFlash(target: TargetSelector): String {
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
        val remoteScript = "/usr/local/bin/uboot-flash"
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
        if (uploadRemoteBytesOverSsh(target, bytes, remotePath, mode, label)) {
            return
        }

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

    private fun uploadRemoteBytesOverSsh(
        target: TargetSelector,
        bytes: ByteArray,
        remotePath: String,
        mode: String,
        label: String,
    ): Boolean {
        var host = reachableSshHost?.takeIf { it.isNotBlank() } ?: return false
        var session: Session? = null
        return runCatching {
            val expected = sha256(bytes)
            host = ensureSshHostOnLocalSubnet(target, host, force = false) ?: host
            session = runCatching { connectSsh(host) }.getOrElse { firstError ->
                val retryHost = ensureSshHostOnLocalSubnet(target, host, force = true)
                if (retryHost == null || retryHost == host) {
                    throw firstError
                }
                event("Retrying SSH/SFTP upload to reassigned IP $retryHost")
                host = retryHost
                connectSsh(host)
            }
            reachableSshHost = host
            event("Uploading $label over SSH/SFTP to $host")
            execChecked(session!!, "mkdir -p -- ${shellQuote(File(remotePath).parent ?: "/tmp")}")
            val sftp = session!!.openChannel("sftp") as ChannelSftp
            sftp.connect(10_000)
            try {
                ByteArrayInputStream(bytes).use { input ->
                    sftp.put(input, remotePath)
                }
            } finally {
                sftp.disconnect()
            }
            execChecked(session!!, "chmod $mode -- ${shellQuote(remotePath)}")
            val actual = execChecked(
                session!!,
                "sha256sum -- ${shellQuote(remotePath)} | awk '{print \$1}'",
                timeoutMillis = 10_000,
            ).stdout.trim().lineSequence().lastOrNull()?.trim().orEmpty()
            if (!actual.equals(expected, ignoreCase = true)) {
                throw RuntimeException("uploaded $label sha256 mismatch: $actual != $expected")
            }
            event("Verified uploaded $label sha256 over SSH: $expected")
            true
        }.onFailure { error ->
            event("SSH/SFTP upload for $label failed on $host; falling back to discovery upload: ${error.message}")
        }.getOrDefault(false).also {
            session?.disconnect()
        }
    }

    private fun ensureSshHostOnLocalSubnet(target: TargetSelector, host: String, force: Boolean): String? {
        val plan = HostIpv4Plan.forInterface(options.interfaces.firstOrNull()) ?: return null
        if (!force && plan.contains(host)) {
            return host
        }

        val nextIp = plan.proposeAddress(target.label)
        if (!force && nextIp == host) {
            return host
        }

        val reason = if (force) {
            "SSH to $host failed"
        } else {
            "discovered IP $host is outside ${plan.interfaceName} subnet ${plan.networkText}/${plan.prefixLength}"
        }
        event("$reason; setting ${target.label} to $nextIp/${plan.netmaskText} for SSH/SFTP")
        runCatching {
            NetworkConfigActions.setIp(
                target = target,
                currentIp = host,
                newIp = nextIp,
                netmask = plan.netmaskText,
                gateway = plan.gatewayText,
                options = options.copy(
                    timeoutSeconds = maxOf(options.timeoutSeconds, 20.0),
                    interfaces = listOf(plan.interfaceName),
                    transportMode = TransportMode.L2,
                ),
            )
        }.onFailure { error ->
            event("L2 IP reassignment command did not confirm: ${error.message}; trying SSH to $nextIp anyway")
        }
        Thread.sleep(1_500)
        reachableSshHost = nextIp
        return nextIp
    }

    private fun connectSsh(host: String): Session {
        val session = JSch().getSession("root", host, 22)
        session.setPassword("root")
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey")
        session.connect(10_000)
        return session
    }

    private fun execChecked(session: Session, command: String, timeoutMillis: Int = 30_000): ExecResult {
        val result = exec(session, command, timeoutMillis)
        if (result.exitCode != 0) {
            val detail = result.stderr.ifBlank { result.stdout }.trim()
            throw RuntimeException("remote command failed with exit ${result.exitCode}${if (detail.isBlank()) "" else ": $detail"}")
        }
        return result
    }

    private fun exec(session: Session, command: String, timeoutMillis: Int): ExecResult {
        val channel = session.openChannel("exec") as ChannelExec
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        channel.setCommand(command)
        channel.setInputStream(null)
        channel.setOutputStream(stdout)
        channel.setErrStream(stderr)
        channel.connect(10_000)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        try {
            while (!channel.isClosed) {
                if (System.nanoTime() > deadline) {
                    throw RuntimeException("remote command timed out: $command")
                }
                Thread.sleep(20)
            }
            return ExecResult(
                exitCode = channel.exitStatus,
                stdout = stdout.toString(StandardCharsets.UTF_8),
                stderr = stderr.toString(StandardCharsets.UTF_8),
            )
        } finally {
            channel.disconnect()
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

    private data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private data class HostIpv4Plan(
        val interfaceName: String,
        val hostAddress: Int,
        val prefixLength: Short,
    ) {
        private val mask: Int = prefixMask(prefixLength)
        private val network: Int = hostAddress and mask
        private val broadcast: Int = network or mask.inv()
        val netmaskText: String = intToIpv4(mask)
        val networkText: String = intToIpv4(network)
        val gatewayText: String = intToIpv4(network + 1)

        fun contains(ip: String): Boolean {
            val parsed = ipv4ToIntOrNull(ip) ?: return false
            return (parsed and mask) == network
        }

        fun proposeAddress(seed: String): String {
            val usable = (broadcast.toLong() - network.toLong() - 1L).coerceAtLeast(1L)
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.lowercase().toByteArray(StandardCharsets.UTF_8))
            val hash = digest.take(4).fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }.toLong() and 0xffff_ffffL
            val startOffset = 2L + (hash % (usable - 1L).coerceAtLeast(1L))
            val maxAttempts = min(usable, 512L).toInt().coerceAtLeast(1)
            for (attempt in 0 until maxAttempts) {
                val offset = 1L + ((startOffset - 1L + attempt) % usable)
                val candidate = network + offset.toInt()
                if (candidate != hostAddress && candidate != network + 1 && candidate != broadcast) {
                    return intToIpv4(candidate)
                }
            }
            return intToIpv4(network + 2)
        }

        companion object {
            fun forInterface(preferred: String?): HostIpv4Plan? {
                val interfaces = if (!preferred.isNullOrBlank()) {
                    sequenceOf(NetworkInterface.getByName(preferred)).filterNotNull()
                } else {
                    NetworkInterface.getNetworkInterfaces().asSequence()
                }
                return interfaces
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { nif ->
                        nif.interfaceAddresses.asSequence().mapNotNull { address ->
                            val inet = address.address as? Inet4Address ?: return@mapNotNull null
                            if (inet.isLoopbackAddress || inet.isLinkLocalAddress) {
                                return@mapNotNull null
                            }
                            val prefix = address.networkPrefixLength
                            if (prefix !in 1..30) {
                                return@mapNotNull null
                            }
                            HostIpv4Plan(nif.name, ipv4ToInt(inet.address), prefix)
                        }
                    }
                    .firstOrNull()
            }

            private fun prefixMask(prefixLength: Short): Int {
                return (-1 shl (32 - prefixLength.toInt()))
            }

            private fun ipv4ToInt(bytes: ByteArray): Int {
                return bytes.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }
            }

            private fun ipv4ToIntOrNull(text: String): Int? {
                val parts = text.split(".")
                if (parts.size != 4) {
                    return null
                }
                return parts.fold(0) { acc, part ->
                    val octet = part.toIntOrNull() ?: return null
                    if (octet !in 0..255) {
                        return null
                    }
                    (acc shl 8) or octet
                }
            }

            private fun intToIpv4(value: Int): String {
                return listOf(24, 16, 8, 0).joinToString(".") { shift ->
                    ((value ushr shift) and 0xff).toString()
                }
            }
        }
    }
}
