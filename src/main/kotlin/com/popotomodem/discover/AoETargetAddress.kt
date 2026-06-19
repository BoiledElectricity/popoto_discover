package com.popotomodem.discover

import java.security.MessageDigest

data class AoETargetAddress(
    val major: Int,
    val minor: Int,
) {
    init {
        require(major in 0..0xfffe) { "AoE shelf must be between 0 and 65534" }
        require(minor in 0..0xfe) { "AoE slot must be between 0 and 254" }
    }

    val label: String = "e$major.$minor"

    fun commandSuffix(): String = "$major $minor"

    companion object {
        val DEFAULT = AoETargetAddress(0, 0)

        fun forDevice(device: Device): AoETargetAddress {
            val identity = device.deviceIdText() ?: return DEFAULT
            return fromIdentity(identity)
        }

        fun fromIdentity(identity: String): AoETargetAddress {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.trim().lowercase().toByteArray(Charsets.UTF_8))
            val shelfSeed = ((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)
            val slotSeed = digest[2].toInt() and 0xff
            val shelf = (shelfSeed % 0xfffe) + 1
            val slot = slotSeed % 0xff
            return AoETargetAddress(shelf, slot)
        }
    }
}
