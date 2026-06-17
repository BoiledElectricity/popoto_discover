package com.popotomodem.discover

import java.util.Properties

object AppBuild {
    val id: String = runCatching {
        val properties = Properties()
        AppBuild::class.java.getResourceAsStream("/popoto-discover-build.properties").use { stream ->
            if (stream != null) {
                properties.load(stream)
            }
        }
        properties.getProperty("build_id")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "development"
}
