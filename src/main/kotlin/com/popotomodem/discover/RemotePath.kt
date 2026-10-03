package com.popotomodem.discover

internal fun remoteParentDirectory(path: String): String {
    require(path.startsWith('/')) { "Remote path must be absolute: $path" }
    return path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
}
