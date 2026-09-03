package com.popotomodem.discover

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicePreservationCacheTest {
    @Test
    fun `round trips preserved files and metadata`() {
        val directory = createTempDirectory("popoto-preservation-test")
        val cache = DevicePreservationCache(directory)
        val identity = "fe64bada09122316"
        val files = listOf(
            PreservedDeviceFile(
                path = "/etc/network/interfaces",
                bytes = "auto eth0\n".toByteArray(),
                mode = "644",
                owner = "root",
                group = "root",
            ),
            PreservedDeviceFile(
                path = "/opt/popoto/license.json",
                bytes = byteArrayOf(0, 1, 2, 3),
                mode = "600",
                owner = "root",
                group = "root",
            ),
        )

        val saved = cache.save(identity, files)
        assertTrue(Files.isRegularFile(cache.pathFor(identity)))
        val loaded = assertNotNull(cache.load(identity))
        assertEquals(saved.createdAtEpochMillis, loaded.createdAtEpochMillis)
        assertEquals(identity, loaded.targetIdentity)
        assertEquals(files.map { it.path }, loaded.files.map { it.path })
        assertContentEquals(files[0].bytes, loaded.files[0].bytes)
        assertContentEquals(files[1].bytes, loaded.files[1].bytes)
        assertEquals("600", loaded.files[1].mode)

        cache.delete(identity)
        assertNull(cache.load(identity))
        assertFalse(Files.exists(cache.pathFor(identity)))
    }

    @Test
    fun `rejects a corrupted snapshot instead of restoring it`() {
        val directory = createTempDirectory("popoto-preservation-corrupt-test")
        val cache = DevicePreservationCache(directory)
        val identity = "fe64bada09122316"
        cache.save(
            identity,
            listOf(PreservedDeviceFile("/etc/network/interfaces", "data".toByteArray(), "644", "root", "root")),
        )
        val path = cache.pathFor(identity)
        val bytes = Files.readAllBytes(path)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0xff).toByte()
        Files.write(path, bytes)

        assertFailsWith<IllegalArgumentException> { cache.load(identity) }
    }

    @Test
    fun `rejects a stale snapshot instead of restoring old device state`() {
        val directory = createTempDirectory("popoto-preservation-stale-test")
        var now = 1_000_000_000L
        val cache = DevicePreservationCache(
            directory = directory,
            maxAgeMillis = 1_000L,
            currentTimeMillis = { now },
        )
        val identity = "fe64bada09122316"
        cache.save(
            identity,
            listOf(PreservedDeviceFile("/etc/network/interfaces", "old".toByteArray(), "644", "root", "root")),
        )

        now += 1_001L

        val error = assertFailsWith<IllegalArgumentException> { cache.load(identity) }
        assertTrue(error.message.orEmpty().contains("preservation cache is stale"))
    }
}
