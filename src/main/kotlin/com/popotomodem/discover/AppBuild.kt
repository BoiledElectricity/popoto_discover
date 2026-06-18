package com.popotomodem.discover

import java.util.Properties

object AppBuild {
    private val properties: Properties = runCatching {
        val properties = Properties()
        AppBuild::class.java.getResourceAsStream("/popoto-discover-build.properties").use { stream ->
            if (stream != null) {
                properties.load(stream)
            }
        }
        properties
    }.getOrDefault(Properties())

    val id: String = properties.getProperty("build_id")?.takeIf { it.isNotBlank() } ?: "development"
    val npcapOemBundled: Boolean = properties.getProperty("npcap_oem_bundled").toBoolean()
}
