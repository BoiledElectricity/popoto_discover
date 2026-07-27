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

    val appName: String = properties.getProperty("app_name")?.takeIf { it.isNotBlank() } ?: "Popoto Discover"
    val version: String = properties.getProperty("version")?.takeIf { it.isNotBlank() } ?: "development"
    val packageVersion: String = properties.getProperty("package_version")?.takeIf { it.isNotBlank() } ?: version
    val gitBranch: String = properties.getProperty("git_branch")?.takeIf { it.isNotBlank() } ?: "unknown"
    val gitCommit: String = properties.getProperty("git_commit")?.takeIf { it.isNotBlank() } ?: "unknown"
    val gitDirty: Boolean = properties.getProperty("git_dirty").toBoolean()
    val buildTime: String = properties.getProperty("build_time")?.takeIf { it.isNotBlank() } ?: "unknown"
    val releaseHighlights: List<String> = properties.getProperty("release_highlights")
        ?.split("|")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val id: String = properties.getProperty("build_id")?.takeIf { it.isNotBlank() } ?: version
    val pmmNdisBundled: Boolean = properties.getProperty("pmm_ndis_bundled").toBoolean()
}
