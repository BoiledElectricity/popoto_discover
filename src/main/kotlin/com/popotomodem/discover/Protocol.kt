package com.popotomodem.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ProtocolException(message: String) : RuntimeException(message)

object Protocol {
    const val DISCOVERY_PORT = 33333
    const val PROTOCOL_VERSION = "1.0"
    const val DEFAULT_TIMEOUT_SECONDS = 2.0
    const val BROADCAST_ADDRESS = "255.255.255.255"

    const val MSG_DISCOVER = "discover_hydrophone"
    const val MSG_DISCOVER_REPLY = "discover_reply"
    const val MSG_SET_IP = "set_ip"
    const val MSG_SET_IP_REPLY = "set_ip_reply"

    const val DEFAULT_SECRET_FILE = ".popoto_secret"

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    private val ipPattern = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$")
    private val macPattern = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")

    fun createDiscoverMessage(nonce: String, secret: String?): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "cmd" to JsonPrimitive(MSG_DISCOVER),
            "nonce" to JsonPrimitive(nonce),
        )
        return withAuth(fields, secret)
    }

    fun withAuth(fields: MutableMap<String, JsonElement>, secret: String?): JsonObject {
        if (!secret.isNullOrEmpty()) {
            val auth = computeMessageAuth(fields, secret)
            fields["auth"] = JsonPrimitive(auth)
        }
        return JsonObject(fields)
    }

    fun computeMessageAuth(message: Map<String, JsonElement>, secret: String): String {
        val canonical = canonicalJson(message.filterKeys { it != "auth" })
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    fun verifyAuth(message: JsonObject, secret: String): Boolean {
        val provided = message["auth"]?.jsonPrimitive?.contentOrNull
            ?: throw ProtocolException("missing authentication field")
        val expected = computeMessageAuth(message, secret)
        return MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.US_ASCII),
            expected.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    fun encodeCompact(message: JsonObject): ByteArray {
        return json.encodeToString(JsonObject.serializer(), message).toByteArray(StandardCharsets.UTF_8)
    }

    fun parseMessage(data: ByteArray): JsonObject {
        return json.parseToJsonElement(data.toString(StandardCharsets.UTF_8)).jsonObject
    }

    fun text(message: JsonObject, field: String): String? {
        return message[field]?.jsonPrimitive?.contentOrNull
    }

    fun validateDiscoverReply(message: JsonObject) {
        for (field in listOf("cmd", "nonce", "model", "serial", "ip", "mac", "fw")) {
            if (message[field] == null) {
                throw ProtocolException("missing required field: $field")
            }
        }
        val cmd = text(message, "cmd")
        if (cmd != MSG_DISCOVER_REPLY) {
            throw ProtocolException("invalid command: $cmd")
        }
        val ip = text(message, "ip").orEmpty()
        if (!validateIpAddress(ip)) {
            throw ProtocolException("invalid IP address: $ip")
        }
        val mac = text(message, "mac").orEmpty()
        if (!validateMacAddress(mac)) {
            throw ProtocolException("invalid MAC address: $mac")
        }
    }

    fun validateIpAddress(ip: String): Boolean {
        if (!ipPattern.matcher(ip).matches()) {
            return false
        }
        return ip.split(".").all { it.toIntOrNull()?.let { value -> value in 0..255 } == true }
    }

    fun validateMacAddress(mac: String): Boolean {
        return macPattern.matcher(mac).matches()
    }

    fun canonicalJson(message: Map<String, JsonElement>): String {
        return message.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}", separator = ", ") { (key, value) ->
                "\"${escapeJsonString(key)}\": ${canonicalElement(value)}"
            }
    }

    private fun canonicalElement(element: JsonElement): String {
        return when (element) {
            is JsonObject -> canonicalJson(element)
            is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ", ") {
                canonicalElement(it)
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    "\"${escapeJsonString(element.content)}\""
                } else {
                    element.booleanOrNull?.toString() ?: element.content
                }
            }
            JsonNull -> "null"
        }
    }

    private fun escapeJsonString(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append("\\u")
                        out.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        return out.toString()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
