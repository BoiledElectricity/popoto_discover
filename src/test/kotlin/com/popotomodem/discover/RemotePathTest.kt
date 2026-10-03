package com.popotomodem.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemotePathTest {
    @Test
    fun keepsLinuxSeparatorsForUploadsAndPreservedFiles() {
        val paths = mapOf(
            "/root/popoto-discover/imx-boot" to "/root/popoto-discover",
            "/usr/local/bin/uboot-flash" to "/usr/local/bin",
            "/usr/local/bin/mmc" to "/usr/local/bin",
            "/etc/hostname" to "/etc",
            "/etc/network/interfaces.d/eth0" to "/etc/network/interfaces.d",
            "/root/.ssh/authorized_keys" to "/root/.ssh",
        )
        paths.forEach { (path, parent) -> assertEquals(parent, remoteParentDirectory(path)) }
    }

    @Test
    fun handlesRootAndDirectoryNamesWithoutHostNormalization() {
        assertEquals("/", remoteParentDirectory("/hostname"))
        assertEquals("/", remoteParentDirectory("/"))
        assertEquals("/root", remoteParentDirectory("/root/staging///"))
        assertEquals("/tmp/operator's files", remoteParentDirectory("/tmp/operator's files/imx-boot"))
        assertEquals("/tmp/a\\b", remoteParentDirectory("/tmp/a\\b/image"))
    }

    @Test
    fun rejectsLocalOrRelativePaths() {
        for (path in listOf("", "imx-boot", "root/imx-boot", "C:\\root\\imx-boot")) {
            assertFailsWith<IllegalArgumentException> { remoteParentDirectory(path) }
        }
    }
}
