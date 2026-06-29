package com.popotomodem.discover

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class ModemSshCredentials(
    val host: String,
    val username: String = "root",
    val password: String = "root",
    val port: Int = 22,
)

data class ModemClientSyncResult(
    val host: String,
    val version: String,
    val serviceStatus: String,
    val backupPath: String?,
)

class ModemClientSync(
    private val credentials: ModemSshCredentials,
    private val onProgress: (String) -> Unit = {},
) {
    fun sync(): ModemClientSyncResult {
        val payload = ModemClientPayload.load()
        val session = connect()
        var stage: String? = null
        return try {
            onProgress("Connected to ${credentials.username}@${credentials.host}")
            stage = execChecked(session, "mktemp -d /tmp/popoto_discover_sync.XXXXXX").stdout.trim()
            require(stage.isNotBlank()) { "failed to allocate remote staging directory" }
            execChecked(session, "install -d -m 0755 ${shellQuote(stage)}/client ${shellQuote(stage)}/common")

            uploadPayload(session, stage, payload)
            val installResult = execChecked(session, installCommand(stage))
            val backup = installResult.stdout
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("backup=") }
                ?.removePrefix("backup=")
                ?.takeIf { it.isNotBlank() }
            val status = execChecked(session, "systemctl is-active popoto-discover.service").stdout.trim()
            onProgress("popoto-discover.service is $status")
            ModemClientSyncResult(
                host = credentials.host,
                version = payload.version,
                serviceStatus = status,
                backupPath = backup,
            )
        } finally {
            stage?.let {
                runCatching { exec(session, "rm -rf ${shellQuote(it)}", timeoutMillis = 5_000) }
            }
            session.disconnect()
        }
    }

    private fun connect(): Session {
        val session = JSch().getSession(credentials.username, credentials.host, credentials.port)
        session.setPassword(credentials.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey")
        session.connect(10_000)
        return session
    }

    private fun uploadPayload(session: Session, stage: String, payload: ModemClientPayload) {
        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(10_000)
        try {
            put(channel, payload.clientScript, "$stage/client/popoto_discover_client.py")
            put(channel, payload.secret, "$stage/client/.popoto_secret")
            put(channel, payload.commonInit, "$stage/common/__init__.py")
            put(channel, payload.l2Transport, "$stage/common/l2_transport.py")
            put(channel, payload.protocol, "$stage/common/protocol.py")
            put(channel, payload.serviceUnit, "$stage/popoto-discover.service")
            put(channel, payload.version.toByteArray(StandardCharsets.UTF_8), "$stage/VERSION")
        } finally {
            channel.disconnect()
        }
        onProgress("Uploaded Popoto Discover client payload")
    }

    private fun put(channel: ChannelSftp, bytes: ByteArray, remotePath: String) {
        ByteArrayInputStream(bytes).use { input ->
            channel.put(input, remotePath)
        }
    }

    private fun installCommand(stage: String): String {
        val q = shellQuote(stage)
        return """
            set -eu
            BASE=/opt/popoto/popoto_discover
            BACKUP=""
            if [ -d "${'$'}BASE" ]; then
                BACKUP=/opt/popoto/popoto_discover.backup.${'$'}(date +%Y%m%d%H%M%S)
                cp -a "${'$'}BASE" "${'$'}BACKUP"
                echo "backup=${'$'}BACKUP"
            fi
            install -d -m 0755 "${'$'}BASE/client" "${'$'}BASE/common"
            install -m 0755 $q/client/popoto_discover_client.py "${'$'}BASE/client/popoto_discover_client.py"
            install -m 0600 $q/client/.popoto_secret "${'$'}BASE/client/.popoto_secret"
            install -m 0644 $q/common/__init__.py "${'$'}BASE/common/__init__.py"
            install -m 0644 $q/common/l2_transport.py "${'$'}BASE/common/l2_transport.py"
            install -m 0644 $q/common/protocol.py "${'$'}BASE/common/protocol.py"
            install -m 0644 $q/VERSION "${'$'}BASE/VERSION"
            install -m 0644 $q/popoto-discover.service /etc/systemd/system/popoto-discover.service
            python3 -m py_compile "${'$'}BASE/common/protocol.py" "${'$'}BASE/common/l2_transport.py" "${'$'}BASE/client/popoto_discover_client.py"
            systemctl daemon-reload
            systemctl enable popoto-discover.service
            systemctl restart popoto-discover.service
        """.trimIndent()
    }

    private fun execChecked(session: Session, command: String, timeoutMillis: Int = 30_000): ExecResult {
        val result = exec(session, command, timeoutMillis)
        if (result.exitCode != 0) {
            val detail = buildString {
                append("remote command failed with exit ")
                append(result.exitCode)
                if (result.stderr.isNotBlank()) {
                    append(": ")
                    append(result.stderr.trim())
                } else if (result.stdout.isNotBlank()) {
                    append(": ")
                    append(result.stdout.trim())
                }
            }
            throw RuntimeException(detail)
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

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}

data class ModemClientPayload(
    val version: String,
    val clientScript: ByteArray,
    val secret: ByteArray,
    val commonInit: ByteArray,
    val l2Transport: ByteArray,
    val protocol: ByteArray,
    val serviceUnit: ByteArray,
) {
    companion object {
        fun load(): ModemClientPayload {
            return ModemClientPayload(
                version = AppBuild.version,
                clientScript = resource("/modem-client/client/popoto_discover_client.py"),
                secret = resource("/modem-client/client/.popoto_secret"),
                commonInit = resource("/modem-client/common/__init__.py"),
                l2Transport = resource("/modem-client/common/l2_transport.py"),
                protocol = resource("/modem-client/common/protocol.py"),
                serviceUnit = resource("/modem-client/popoto-discover.service"),
            )
        }

        private fun resource(path: String): ByteArray {
            return ModemClientPayload::class.java.getResourceAsStream(path)?.use { it.readBytes() }
                ?: throw IllegalStateException("missing bundled modem client resource: $path")
        }
    }
}
