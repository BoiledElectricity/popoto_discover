package com.popotomodem.discover

internal object L2Debug {
    private val enabled = System.getenv("POPOTO_L2_DEBUG") == "1"

    fun log(message: String) {
        if (enabled) {
            System.err.println("L2 debug: $message")
        }
    }
}
