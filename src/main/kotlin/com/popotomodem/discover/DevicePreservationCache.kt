package com.popotomodem.discover

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

data class PendingDevicePreservation(
    val targetIdentity: String,
    val createdAtEpochMillis: Long,
    val files: List<PreservedDeviceFile>,
)

class DevicePreservationCache(
    private val directory: Path = defaultDirectory(),
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    fun load(targetIdentity: String): PendingDevicePreservation? {
        val path = cachePath(targetIdentity)
        if (!Files.isRegularFile(path)) return null

        DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
            require(input.readInt() == MAGIC) { "invalid preservation cache header: $path" }
            require(input.readInt() == FORMAT_VERSION) { "unsupported preservation cache version: $path" }
            val storedIdentity = input.readUTF()
            require(storedIdentity.equals(targetIdentity, ignoreCase = true)) {
                "preservation cache identity mismatch: $storedIdentity != $targetIdentity"
            }
            val createdAt = input.readLong()
            val ageMillis = currentTimeMillis() - createdAt
            require(ageMillis in 0..maxAgeMillis) {
                "preservation cache is stale (${ageMillis.coerceAtLeast(0L) / 3_600_000L} hours old): $path"
            }
            val count = input.readInt()
            require(count in 0..MAX_FILES) { "invalid preservation cache file count $count: $path" }
            val files = List(count) {
                val devicePath = input.readUTF()
                require(devicePath.startsWith('/')) { "invalid preserved device path: $devicePath" }
                val mode = input.readNullableText()
                val owner = input.readNullableText()
                val group = input.readNullableText()
                val size = input.readInt()
                require(size in 0..MAX_FILE_BYTES) { "invalid preserved file size $size for $devicePath" }
                val bytes = ByteArray(size).also(input::readFully)
                val expectedDigest = ByteArray(SHA256_BYTES).also(input::readFully)
                require(MessageDigest.isEqual(expectedDigest, sha256(bytes))) {
                    "preservation cache checksum mismatch for $devicePath"
                }
                PreservedDeviceFile(devicePath, bytes, mode, owner, group)
            }
            require(input.read() == -1) { "unexpected trailing data in preservation cache: $path" }
            return PendingDevicePreservation(storedIdentity, createdAt, files)
        }
    }

    fun save(targetIdentity: String, files: List<PreservedDeviceFile>): PendingDevicePreservation {
        require(files.size <= MAX_FILES) { "too many files to preserve: ${files.size}" }
        files.forEach { file ->
            require(file.path.startsWith('/')) { "invalid preserved device path: ${file.path}" }
            require(file.bytes.size <= MAX_FILE_BYTES) { "preserved file is too large: ${file.path}" }
        }

        Files.createDirectories(directory)
        setOwnerOnlyPermissions(directory, isDirectory = true)
        val destination = cachePath(targetIdentity)
        val temporary = destination.resolveSibling("${destination.fileName}.new")
        val createdAt = currentTimeMillis()
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeUTF(targetIdentity)
            output.writeLong(createdAt)
            output.writeInt(files.size)
            files.forEach { file ->
                output.writeUTF(file.path)
                output.writeNullableText(file.mode)
                output.writeNullableText(file.owner)
                output.writeNullableText(file.group)
                output.writeInt(file.bytes.size)
                output.write(file.bytes)
                output.write(sha256(file.bytes))
            }
        }
        setOwnerOnlyPermissions(temporary, isDirectory = false)
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        setOwnerOnlyPermissions(destination, isDirectory = false)
        return PendingDevicePreservation(targetIdentity, createdAt, files)
    }

    fun delete(targetIdentity: String) {
        Files.deleteIfExists(cachePath(targetIdentity))
    }

    fun pathFor(targetIdentity: String): Path = cachePath(targetIdentity)

    private fun cachePath(targetIdentity: String): Path {
        val readable = targetIdentity.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "device" }
        val identityDigest = sha256(targetIdentity.lowercase().toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return directory.resolve("$readable-$identityDigest.preserved")
    }

    private fun DataOutputStream.writeNullableText(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableText(): String? = if (readBoolean()) readUTF() else null

    private fun setOwnerOnlyPermissions(path: Path, isDirectory: Boolean) {
        runCatching {
            val permissions = if (isDirectory) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    companion object {
        private const val MAGIC = 0x50445043
        private const val FORMAT_VERSION = 1
        private const val MAX_FILES = 256
        private const val MAX_FILE_BYTES = 1_048_576
        private const val SHA256_BYTES = 32
        internal const val DEFAULT_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L

        fun defaultDirectory(): Path = Path.of(
            System.getProperty("user.home"),
            ".popoto-discover",
            "pending-preservation",
        )

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
