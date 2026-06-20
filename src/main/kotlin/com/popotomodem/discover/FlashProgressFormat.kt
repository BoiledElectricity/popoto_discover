package com.popotomodem.discover

import java.util.Locale
import kotlin.math.roundToLong

fun formatFlashProgress(progress: AoEProgress): String {
    if (progress.totalBytes <= 0) {
        return progress.phase
    }

    val written = formatBytes(progress.doneBytes)
    val rate = progress.rateBytesPerSecond()
    val eta = progress.etaMillis(rate)

    return buildString {
        append(progress.phase)
        append(": ")
        append(written)
        append(" written")
        if (rate > 0.0) {
            append(" @ ")
            append(formatBytes(rate.roundToLong()))
            append("/s")
        }
        if (eta != null) {
            append(" · ETA ")
            append(formatDuration(eta))
        }
    }
}

private fun AoEProgress.rateBytesPerSecond(): Double {
    if (elapsedMillis <= 0 || doneBytes <= 0) {
        return 0.0
    }
    return doneBytes * 1000.0 / elapsedMillis
}

private fun AoEProgress.etaMillis(rateBytesPerSecond: Double): Long? {
    if (rateBytesPerSecond <= 0.0 || doneBytes >= totalBytes) {
        return null
    }
    return (((totalBytes - doneBytes) / rateBytesPerSecond) * 1000.0).roundToLong().coerceAtLeast(0)
}

private fun formatBytes(bytes: Long): String {
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MiB", mib)
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm".format(hours, minutes)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}
