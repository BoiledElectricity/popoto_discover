package com.popoto.core.auth

import com.popoto.core.json.CanonicalJson
import com.popoto.core.json.JsonValue
import com.popoto.core.json.JsonValue.JsonObject
import com.popoto.core.json.SimpleJsonParser

class MessageAuthenticator(private val signatureEngine: SignatureEngine) {
    fun sign(fields: Map<String, JsonValue>, secret: String): String {
        val payload = canonicalPayload(fields)
        return signatureEngine.hmacSha256Hex(payload, secret)
    }

    fun encode(fields: Map<String, JsonValue>, secret: String?): String {
        val normalizedFields = normalizeFields(fields)

        if (secret.isNullOrEmpty()) {
            return CanonicalJson.renderCompactObject(JsonObject(normalizedFields))
        }

        val signature = sign(normalizedFields, secret)
        val signedFields = normalizedFields + ("auth" to JsonValue.of(signature))
        return CanonicalJson.renderCompactObject(JsonObject(signedFields))
    }

    fun verify(json: String, secret: String?): Boolean {
        if (secret.isNullOrEmpty()) {
            return true
        }

        val parsed = SimpleJsonParser.parseObject(json)
        val auth = parsed.string("auth") ?: return false
        val unsigned = parsed.fields.filterKeys { key -> key != "auth" }
        val computed = sign(unsigned, secret)
        return constantTimeEquals(auth, computed)
    }

    fun canonicalPayload(fields: Map<String, JsonValue>): String {
        val normalizedFields = normalizeFields(fields)
        return CanonicalJson.renderForSignature(JsonObject(normalizedFields))
    }

    private fun normalizeFields(fields: Map<String, JsonValue>): Map<String, JsonValue> {
        return fields
            .filterKeys { key -> key != "auth" }
            .filterValues { value -> value != JsonValue.JsonNull }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }

        var result = 0
        for (index in a.indices) {
            result = result or (a[index].code xor b[index].code)
        }

        return result == 0
    }
}
