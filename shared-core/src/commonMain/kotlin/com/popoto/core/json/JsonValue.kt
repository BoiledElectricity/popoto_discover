package com.popoto.core.json

sealed interface JsonValue {
    data class JsonString(val value: String) : JsonValue

    data class JsonNumber(val raw: String) : JsonValue

    data class JsonBoolean(val value: Boolean) : JsonValue

    data class JsonArray(val values: List<JsonValue>) : JsonValue

    data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]

        fun string(key: String): String? = (fields[key] as? JsonString)?.value

        fun int(key: String): Int? = number(key)?.toIntOrNull()

        fun long(key: String): Long? = number(key)?.toLongOrNull()

        fun double(key: String): Double? = number(key)?.toDoubleOrNull()

        fun boolean(key: String): Boolean? = (fields[key] as? JsonBoolean)?.value

        fun number(key: String): String? = (fields[key] as? JsonNumber)?.raw

        fun array(key: String): JsonArray? = fields[key] as? JsonArray
    }

    data object JsonNull : JsonValue

    companion object {
        fun of(value: Any?): JsonValue = when (value) {
            null -> JsonNull
            is JsonValue -> value
            is String -> JsonString(value)
            is Boolean -> JsonBoolean(value)
            is Int -> JsonNumber(value.toString())
            is Long -> JsonNumber(value.toString())
            is Double -> JsonNumber(value.toString())
            is Float -> JsonNumber(value.toString())
            is List<*> -> JsonArray(value.map { entryValue -> of(entryValue) })
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, entryValue) ->
                    require(key is String) { "JSON object keys must be strings" }
                    key to of(entryValue)
                }
            )
            else -> error("Unsupported JSON value type: ${value::class}")
        }
    }
}
