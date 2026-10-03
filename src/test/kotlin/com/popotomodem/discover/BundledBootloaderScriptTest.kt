package com.popotomodem.discover

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundledBootloaderScriptTest {
    @Test
    fun bundledScriptUsesLinuxLineEndingsOnEveryBuildHost() {
        val script = requireNotNull(javaClass.getResourceAsStream("/tools/uboot-flash"))
            .use { it.readBytes().toString(Charsets.UTF_8) }

        assertTrue(script.startsWith("#!/bin/sh\n"), "Linux must recognize the bundled script interpreter")
        assertFalse('\r' in script, "The modem script must keep LF line endings when packaged on Windows")
    }
}
