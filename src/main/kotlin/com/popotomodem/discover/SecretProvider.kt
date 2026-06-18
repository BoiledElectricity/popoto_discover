package com.popotomodem.discover

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object SecretProvider {
    const val DEFAULT_SECRET_FILE = ".popoto_secret"

    private const val DEFAULT_SECRET = "5a21884927730b6388938cd1d2f2937e1e801dae2e4c4561f951fd3974be9de8"

    fun load(secretFile: String?): String {
        val pathText = secretFile?.trim().orEmpty()
        if (pathText.isEmpty()) {
            return DEFAULT_SECRET
        }

        val path = Path.of(pathText)
        if (!path.exists()) {
            throw IllegalArgumentException(
                "secret file not found: $pathText; omit --secret-file to use the built-in Popoto default",
            )
        }

        val secret = Files.readString(path).trim()
        if (secret.length < 16) {
            throw IllegalArgumentException("secret must be at least 16 characters")
        }
        return secret
    }
}
