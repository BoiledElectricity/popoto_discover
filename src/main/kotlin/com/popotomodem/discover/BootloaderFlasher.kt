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

data class BootloaderFlashResult(
    val partitionConfig: String,
    val activeSlot: String,
    val imageSize: Long,
    val imageSha256: String,
)

internal object ActiveBootloaderWriteVerifier {
    private const val EMMC = "/dev/mmcblk2"

    fun command(imageSize: Long): String {
        require(imageSize > 0) { "imx-boot image is empty" }
        return buildString {
            append("cfg=\$(mmc extcsd read $EMMC 2>/dev/null | grep -i PARTITION_CONFIG | grep -o '0x[0-9a-fA-F]*' | head -1); ")
            append("if [ -z \"\$cfg\" ]; then echo verify_error=partition_config_unavailable; exit 0; fi; ")
            append("dec=\$(printf '%d' \"\$cfg\" 2>/dev/null); ")
            append("active=\$(( (dec >> 3) & 7 )); ")
            append("case \"\$active\" in 1) slot=boot0;; 2) slot=boot1;; *) echo verify_error=unsupported_active_partition_\$active; exit 0;; esac; ")
            append("dev=${EMMC}\$slot; ")
            append("if [ ! -b \"\$dev\" ]; then echo verify_error=active_boot_device_missing; exit 0; fi; ")
            append("hash=\$(head -c $imageSize \"\$dev\" | sha256sum | awk '{print \$1}'); ")
            append("echo partition_config=\$cfg; echo active_slot=\$slot; echo active_sha256=\$hash")
        }
    }

    fun parse(
        output: String,
        expectedSize: Long,
        expectedSha256: String,
    ): BootloaderFlashResult {
        val fields = output.lineSequence()
            .map(String::trim)
            .filter { it.contains('=') }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

        fields["verify_error"]?.let { error ->
            throw RuntimeException("Could not verify the programmed active eMMC boot slot: ${error.replace('_', ' ')}")
        }
        val partitionConfig = fields["partition_config"]
            ?: throw RuntimeException("Could not verify the programmed bootloader: PARTITION_CONFIG was not reported")
        val activeSlot = fields["active_slot"]
            ?.takeIf { it == "boot0" || it == "boot1" }
            ?: throw RuntimeException("Could not verify the programmed bootloader: active boot slot was not reported")
        val actualSha256 = fields["active_sha256"]
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: throw RuntimeException("Could not verify the programmed bootloader: active boot slot SHA-256 was not reported")
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw RuntimeException(
                "Programmed $activeSlot does not match the supplied imx-boot: " +
                    "SHA-256 $actualSha256 != $expectedSha256",
            )
        }
        return BootloaderFlashResult(
            partitionConfig = partitionConfig,
            activeSlot = activeSlot,
            imageSize = expectedSize,
            imageSha256 = expectedSha256.lowercase(),
        )
    }
}

class BootloaderFlasher(
    private val commandClient: CommandClient,
    private val options: CommandOptions,
    private val onEvent: (FlashEvent) -> Unit,
    private val sshHost: String? = null,
) {
    private var reachableSshHost: String? = sshHost
    private var mmcUtilsReady = false

    fun flashIfRequested(target: TargetSelector, bootloader: File?): BootloaderFlashResult? {
        bootloader ?: return null
        require(bootloader.isFile) { "imx-boot image not found: $bootloader" }
        require(bootloader.length() > 0) { "imx-boot image is empty: $bootloader" }
        val remoteDir = "/root/popoto-discover"
        val remoteImage = "$remoteDir/imx-boot"
        val remoteLog = "$remoteDir/uboot-flash.log"
        val remoteStatus = "$remoteDir/uboot-flash.status"
        val expectedSha256 = sha256(bootloader)
        ensureMmcUtils(target)
        val flashScript = ensureUbootFlash(target)

        try {
            uploadRemoteFile(target, bootloader, remoteImage, "600", "imx-boot")
            val command = "${shellQuote(flashScript)} ${shellQuote(remoteImage)} auto"
            event("Running bootloader command: $flashScript $remoteImage auto")
            runTrackedCommand(
                target = target,
                command = command,
                remoteLog = remoteLog,
                remoteStatus = remoteStatus,
                action = "flash bootloader",
                timeoutSeconds = 180,
            )

            val verification = requireOk(
                commandClient.shellExec(
                    target,
                    ActiveBootloaderWriteVerifier.command(bootloader.length()),
                    options,
                    timeoutSeconds = 30.0,
                    repeatRequest = true,
                ),
                "verify active eMMC bootloader",
                logStdout = false,
            )
            val result = ActiveBootloaderWriteVerifier.parse(
                output = verification.text("stdout").orEmpty(),
                expectedSize = bootloader.length(),
                expectedSha256 = expectedSha256,
            )
            event(
                "Verified active eMMC ${result.activeSlot}: ${result.imageSize} bytes, " +
                    "sha256=${result.imageSha256}, PARTITION_CONFIG=${result.partitionConfig}",
            )
            return result
        } finally {
            commandClient.shellExec(
                target,
                "rm -f -- ${shellQuote(remoteImage)} ${shellQuote(remoteLog)} ${shellQuote(remoteStatus)}",
                options,
                timeoutSeconds = 5.0,
            )
        }
    }

    private fun runTrackedCommand(
        target: TargetSelector,
        command: String,
        remoteLog: String,
        remoteStatus: String,
        action: String,
        timeoutSeconds: Int,
    ) {
        val quotedLog = shellQuote(remoteLog)
        val quotedStatus = shellQuote(remoteStatus)
        val launch = "rm -f -- $quotedLog $quotedStatus; " +
            "( $command >$quotedLog 2>&1; rc=\$?; printf '%s\\n' \"\$rc\" >$quotedStatus ) " +
            "</dev/null >/dev/null 2>&1 &"
        requireOk(
            commandClient.shellExec(target, launch, options, timeoutSeconds = 5.0),
            "start $action",
            logStdout = false,
        )

        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        var lastPollError: String? = null
        while (System.nanoTime() < deadline) {
            val response = commandClient.shellExec(
                target,
                "if [ -f $quotedStatus ]; then printf 'DONE '; cat $quotedStatus; else echo RUNNING; fi",
                options,
                timeoutSeconds = 5.0,
                repeatRequest = true,
            )
            if (response == null) {
                lastPollError = "no status reply"
                Thread.sleep(1_000)
                continue
            }
            if (response.text("status") != "ok") {
                lastPollError = response.text("error") ?: "status query failed"
                Thread.sleep(1_000)
                continue
            }

            val state = response.text("stdout").orEmpty().trim().lineSequence().lastOrNull().orEmpty()
            if (!state.startsWith("DONE ")) {
                Thread.sleep(1_000)
                continue
            }

            val exitCode = state.removePrefix("DONE ").trim().toIntOrNull()
                ?: throw RuntimeException("Failed to $action: invalid remote exit status '$state'")
            val output = commandClient.shellExec(
                target,
                "tail -c 320 -- $quotedLog 2>/dev/null",
                options,
                timeoutSeconds = 5.0,
                repeatRequest = true,
            )?.text("stdout").orEmpty().trim()
            if (output.isNotBlank()) {
                event("$action output: $output")
            }
            if (exitCode != 0) {
                throw RuntimeException(
                    "Failed to $action: remote command exited $exitCode" +
                        if (output.isBlank()) "" else ": $output",
                )
            }
            return
        }
        throw RuntimeException(
            "Timed out after ${timeoutSeconds}s waiting to $action" +
                if (lastPollError.isNullOrBlank()) "" else " ($lastPollError)",
        )
    }

    private fun ensureUbootFlash(target: TargetSelector): String {
        val bundled = javaClass.getResourceAsStream("/tools/uboot-flash")?.use { it.readBytes() }
            ?: throw RuntimeException("Bundled uboot-flash resource is missing")
        val expected = sha256(bundled)
        val remoteScript = "/usr/local/bin/uboot-flash"
        val installedHash = requireOk(
            commandClient.shellExec(
                target,
                "if [ -x $remoteScript ]; then if command -v sha256sum >/dev/null 2>&1; then sha256sum -- $remoteScript | awk '{print \$1}'; else echo INSTALLED_NO_HASH; fi; else echo MISSING; fi",
                options,
                timeoutSeconds = 5.0,
                repeatRequest = true,
            ),
            "check uboot-flash",
            logStdout = false,
        ).text("stdout")?.trim().orEmpty().lineSequence().lastOrNull()?.trim().orEmpty()

        if (installedHash.equals(expected, ignoreCase = true)) {
            event("Using current device uboot-flash: $remoteScript")
            return remoteScript
        }

        val reason = when (installedHash) {
            "MISSING" -> "missing"
            "INSTALLED_NO_HASH" -> "not hashable"
            else -> "out of date"
        }
        event("Device uboot-flash is $reason; installing bundled copy to $remoteScript")
        uploadRemoteBytes(target, bundled, remoteScript, "755", "uboot-flash")
        return remoteScript
    }

    fun ensureMmcUtils(target: TargetSelector) {
        if (mmcUtilsReady) return

        val state = requireOk(
            commandClient.shellExec(
                target,
                "if command -v mmc >/dev/null 2>&1; then command -v mmc; else echo MISSING; fi",
                options,
                timeoutSeconds = 5.0,
                repeatRequest = true,
            ),
            "check mmc-utils",
            logStdout = false,
        ).text("stdout")?.trim().orEmpty().lineSequence().lastOrNull()?.trim().orEmpty()

        if (state != "MISSING" && state.isNotBlank()) {
            event("Using device mmc utility: $state")
            mmcUtilsReady = true
            return
        }

        val bundled = javaClass.getResourceAsStream("/tools/bin/mmc-aarch64")?.use { it.readBytes() }
            ?: throw RuntimeException("Bundled mmc utility resource is missing")
        val remoteMmc = "/usr/local/bin/mmc"
        event("Device mmc utility missing; installing bundled mmc to $remoteMmc")
        uploadRemoteBytes(target, bundled, remoteMmc, "755", "mmc")
        val installed = requireOk(
            commandClient.shellExec(
                target,
                "command -v mmc",
                options,
                timeoutSeconds = 5.0,
                repeatRequest = true,
            ),
            "verify mmc-utils",
            logStdout = false,
        ).text("stdout")?.trim().orEmpty().lineSequence().lastOrNull()?.trim().orEmpty()
        if (installed.isBlank()) {
            throw RuntimeException("Bundled mmc install completed but mmc is still not available")
        }
        mmcUtilsReady = true
        event("Installed device mmc utility: $installed")
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
                repeatRequest = true,
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
                gateway = "",
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

    private fun event(message: String) {
        onEvent(FlashEvent(message))
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
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
