package com.popotomodem.discover

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val rtcFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd-HH:mm:ss")

fun hostRtcString(): String = LocalDateTime.now().format(rtcFormatter)

fun resolveRtcInput(value: String): String {
    val normalized = value.trim().lowercase()
    return if (normalized in setOf("now", "host", "host-clock", "sync")) {
        hostRtcString()
    } else {
        value.trim()
    }
}
