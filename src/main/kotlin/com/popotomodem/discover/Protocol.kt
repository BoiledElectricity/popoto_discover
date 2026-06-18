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
    const val MSG_SET_RTC = "set_rtc"
    const val MSG_SET_RTC_REPLY = "set_rtc_reply"
    const val MSG_GET_RTC = "get_rtc"
    const val MSG_GET_RTC_REPLY = "get_rtc_reply"
    const val MSG_SET_PARAM = "set_param"
    const val MSG_SET_PARAM_REPLY = "set_param_reply"
    const val MSG_GET_VERSION = "get_version"
    const val MSG_GET_VERSION_REPLY = "get_version_reply"
    const val MSG_SET_UBOOT_ENV = "set_uboot_env"
    const val MSG_SET_UBOOT_ENV_REPLY = "set_uboot_env_reply"
    const val MSG_REBOOT = "reboot"
    const val MSG_REBOOT_REPLY = "reboot_reply"
    const val MSG_SHELL_EXEC = "shell_exec"
    const val MSG_SHELL_EXEC_REPLY = "shell_exec_reply"

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

    fun createSetIpMessage(
        nonce: String,
        target: TargetSelector,
        newIp: String,
        netmask: String,
        gateway: String,
        secret: String?,
    ): JsonObject {
        requireValidIp("new IP address", newIp)
        requireValidNetmask(netmask)
        requireValidIp("gateway address", gateway)

        val fields = linkedMapOf<String, JsonElement>(
            "cmd" to JsonPrimitive(MSG_SET_IP),
            "nonce" to JsonPrimitive(nonce),
        )
        addTargetSelector(fields, target)
        fields["new_ip"] = JsonPrimitive(newIp)
        fields["netmask"] = JsonPrimitive(netmask)
        fields["gateway"] = JsonPrimitive(gateway)
        return withAuth(fields, secret)
    }

    fun createSetRtcMessage(nonce: String, target: TargetSelector, rtc: String, secret: String?): JsonObject {
        if (!validateRtcFormat(rtc)) {
            throw ProtocolException("invalid RTC format: $rtc (expected YYYY.MM.DD-HH:MM:SS)")
        }
        val fields = linkedMapOf<String, JsonElement>(
            "cmd" to JsonPrimitive(MSG_SET_RTC),
            "nonce" to JsonPrimitive(nonce),
            "rtc" to JsonPrimitive(rtc),
        )
        addTargetSelector(fields, target)
        return withAuth(fields, secret)
    }

    fun createGetRtcMessage(nonce: String, target: TargetSelector, secret: String?): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "cmd" to JsonPrimitive(MSG_GET_RTC),
            "nonce" to JsonPrimitive(nonce),
        )
        addTargetSelector(fields, target)
        return withAuth(fields, secret)
    }

    fun createSetParamMessage(
        nonce: String,
        target: TargetSelector,
        paramName: String,
        paramValue: JsonPrimitive,
        secret: String?,
    ): JsonObject {
        if (paramName.isBlank()) {
            throw ProtocolException("parameter name is required")
        }
        val fields = linkedMapOf<String, JsonElement>(
            "cmd" to JsonPrimitive(MSG_SET_PARAM),
            "nonce" to JsonPrimitive(nonce),
            "param_name" to JsonPrimitive(paramName),
            "param_value" to paramValue,
        )
        addTargetSelector(fields, target)
        return withAuth(fields, secret)
    }

    fun createGetVersionMessage(nonce: String, target: TargetSelector, secret: String?): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "cmd" to JsonPrimitive(MSG_GET_VERSION),
            "nonce" to JsonPrimitive(nonce),
        )
        addTargetSelector(fields, target)
        return withAuth(fields, secret)
    }

    fun createSetUbootEnvMessage(
        nonce: String,
        target: TargetSelector,
        name: String,
        value: String,
        secret: String?,
    ): JsonObject {
        if (!Regex("^[A-Za-z0-9_]+$").matches(name)) {
            throw ProtocolException("invalid U-Boot environment name: $name")
        }
        val fields = linkedMapOf<String, JsonElement>(
            "cmd" to JsonPrimitive(MSG_SET_UBOOT_ENV),
            "nonce" to JsonPrimitive(nonce),
            "name" to JsonPrimitive(name),
            "value" to JsonPrimitive(value),
        )
        addTargetSelector(fields, target)
        return withAuth(fields, secret)
    }

    fun createRebootMessage(nonce: String, target: TargetSelector, secret: String?): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "cmd" to JsonPrimitive(MSG_REBOOT),
            "nonce" to JsonPrimitive(nonce),
        )
        addTargetSelector(fields, target)
        return withAuth(fields, secret)
    }

    fun createShellExecMessage(
        nonce: String,
        target: TargetSelector,
        command: String,
        timeoutSeconds: Double,
        secret: String?,
    ): JsonObject {
        if (command.isBlank()) {
            throw ProtocolException("shell command is required")
        }
        if (command.length > 2048) {
            throw ProtocolException("shell command is too long")
        }
        val fields = linkedMapOf<String, JsonElement>(
            "cmd" to JsonPrimitive(MSG_SHELL_EXEC),
            "nonce" to JsonPrimitive(nonce),
            "command" to JsonPrimitive(command),
            "timeout_seconds" to JsonPrimitive(timeoutSeconds.coerceIn(1.0, 60.0)),
        )
        addTargetSelector(fields, target)
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

    fun validateStatusReply(message: JsonObject, expectedCommand: String) {
        for (field in listOf("cmd", "nonce", "status")) {
            if (message[field] == null) {
                throw ProtocolException("missing required field: $field")
            }
        }
        val cmd = text(message, "cmd")
        if (cmd != expectedCommand) {
            throw ProtocolException("invalid command: $cmd")
        }
        val status = text(message, "status")
        if (status !in setOf("ok", "error")) {
            throw ProtocolException("invalid status: $status")
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

    fun validateNetmask(netmask: String): Boolean {
        if (!validateIpAddress(netmask)) {
            return false
        }
        val binary = netmask.split(".").joinToString("") {
            it.toInt().toString(2).padStart(8, '0')
        }
        return '1' !in binary.dropWhile { it == '1' }
    }

    fun validateRtcFormat(rtc: String): Boolean {
        return Pattern.compile("^\\d{4}\\.\\d{2}\\.\\d{2}-\\d{2}:\\d{2}:\\d{2}$").matcher(rtc).matches()
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

    private fun addTargetSelector(fields: MutableMap<String, JsonElement>, target: TargetSelector) {
        if (target.serial != null) {
            fields["target_serial"] = JsonPrimitive(target.serial)
            fields["target_id"] = JsonPrimitive(target.serial)
        }
        if (target.mac != null) {
            fields["target_mac"] = JsonPrimitive(target.mac)
        }
        if (target.serial == null && target.mac == null) {
            throw ProtocolException("missing target selector")
        }
    }

    private fun requireValidIp(label: String, ip: String) {
        if (!validateIpAddress(ip)) {
            throw ProtocolException("invalid $label: $ip")
        }
    }

    private fun requireValidNetmask(netmask: String) {
        if (!validateNetmask(netmask)) {
            throw ProtocolException("invalid netmask: $netmask")
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

data class TargetSelector(
    val mac: String?,
    val serial: String?,
    val label: String,
) {
    companion object {
        fun parse(target: String): TargetSelector {
            val clean = target.trim()
            if (clean.isEmpty()) {
                throw ProtocolException("empty target")
            }
            return if (Protocol.validateMacAddress(clean)) {
                TargetSelector(mac = clean.lowercase(), serial = null, label = clean.lowercase())
            } else {
                TargetSelector(mac = null, serial = clean, label = clean)
            }
        }
    }
}
