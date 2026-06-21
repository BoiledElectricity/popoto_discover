package com.popoto.core.auth

class HmacSha256SignatureEngine : SignatureEngine {
    override fun hmacSha256Hex(message: String, secret: String): String {
        val blockSize = 64
        var key = secret.encodeToByteArray()

        if (key.size > blockSize) {
            key = Sha256.digest(key)
        }

        if (key.size < blockSize) {
            key = key + ByteArray(blockSize - key.size)
        }

        val innerPad = ByteArray(blockSize) { index ->
            (key[index].toInt() xor 0x36).toByte()
        }
        val outerPad = ByteArray(blockSize) { index ->
            (key[index].toInt() xor 0x5c).toByte()
        }

        val innerHash = Sha256.digest(innerPad + message.encodeToByteArray())
        val finalHash = Sha256.digest(outerPad + innerHash)

        return finalHash.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
