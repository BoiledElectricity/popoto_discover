package com.popotomodem.discover

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UbootFinalizationModeTest {
    @Test
    fun prefersExplicitFinalizeWhenAdvertised() {
        assertEquals(
            UbootFinalizationMode.FINALIZE_FLASH,
            UbootFinalizationMode.forDevice(device(finalize = true, bootLinux = true)),
        )
    }

    @Test
    fun usesBootLinuxForLegacyDiscoverCapableUboot() {
        assertEquals(
            UbootFinalizationMode.BOOT_LINUX,
            UbootFinalizationMode.forDevice(device(finalize = false, bootLinux = true)),
        )
    }

    @Test
    fun rejectsUbootWithoutAnyCompletionCommand() {
        assertFailsWith<IllegalArgumentException> {
            UbootFinalizationMode.forDevice(device(finalize = false, bootLinux = false))
        }
    }

    private fun device(finalize: Boolean, bootLinux: Boolean): Device {
        return Device(
            fields = mutableMapOf(
                "supports_finalize_flash" to JsonPrimitive(if (finalize) "1" else "0"),
                "supports_boot_linux" to JsonPrimitive(if (bootLinux) "1" else "0"),
            ),
        )
    }
}
