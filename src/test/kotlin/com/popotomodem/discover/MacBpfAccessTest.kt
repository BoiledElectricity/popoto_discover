package com.popotomodem.discover

import kotlin.test.Test
import kotlin.test.assertFalse

class MacBpfAccessTest {
    @Test
    fun udpDoesNotRequireCaptureSetup() {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Mac OS X")
            assertFalse(MacBpfAccess.needsSetupFor(TransportMode.UDP))
        } finally {
            System.setProperty("os.name", original)
        }
    }
}
