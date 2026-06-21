package com.popoto.core.auth

interface SignatureEngine {
    fun hmacSha256Hex(message: String, secret: String): String
}

interface NonceGenerator {
    fun nextNonceHex(byteCount: Int = 16): String
}

object SecretValidator {
    fun isValid(secret: String): Boolean {
        if (secret.length != 64) {
            return false
        }

        return secret.all { character ->
            character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        }
    }
}
