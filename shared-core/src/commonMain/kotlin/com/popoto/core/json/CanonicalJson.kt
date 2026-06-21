package com.popoto.core.json

import com.popoto.core.json.JsonValue.JsonBoolean
import com.popoto.core.json.JsonValue.JsonArray
import com.popoto.core.json.JsonValue.JsonNull
import com.popoto.core.json.JsonValue.JsonNumber
import com.popoto.core.json.JsonValue.JsonObject
import com.popoto.core.json.JsonValue.JsonString

object CanonicalJson {
    fun renderCompactObject(value: JsonObject): String = renderObject(value, colonSeparator = ":", itemSeparator = ",")

    fun renderForSignature(value: JsonObject): String = renderObject(value, colonSeparator = ": ", itemSeparator = ", ")

    private fun render(value: JsonValue, colonSeparator: String, itemSeparator: String): String = when (value) {
        is JsonString -> "\"${escape(value.value)}\""
        is JsonNumber -> value.raw
        is JsonBoolean -> if (value.value) "true" else "false"
        is JsonArray -> renderArray(value, colonSeparator, itemSeparator)
        JsonNull -> "null"
        is JsonObject -> renderObject(value, colonSeparator, itemSeparator)
    }

    private fun renderArray(value: JsonArray, colonSeparator: String, itemSeparator: String): String {
        val entries = value.values.joinToString(separator = itemSeparator) { entryValue ->
            render(entryValue, colonSeparator, itemSeparator)
        }

        return "[$entries]"
    }

    private fun renderObject(value: JsonObject, colonSeparator: String, itemSeparator: String): String {
        val entries = value.fields.keys
            .sorted()
            .joinToString(separator = itemSeparator) { key ->
                val entryValue = value.fields.getValue(key)
                "\"${escape(key)}\"$colonSeparator${render(entryValue, colonSeparator, itemSeparator)}"
            }

        return "{$entries}"
    }

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }
}
