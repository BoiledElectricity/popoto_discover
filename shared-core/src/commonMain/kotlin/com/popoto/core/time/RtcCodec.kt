package com.popoto.core.time

interface RtcCodec {
    fun parseUtcMillis(value: String): Long?

    fun formatUtcMillis(epochMillis: Long): String
}
