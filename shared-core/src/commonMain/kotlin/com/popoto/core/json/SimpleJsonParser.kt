package com.popoto.core.json

import com.popoto.core.json.JsonValue.JsonBoolean
import com.popoto.core.json.JsonValue.JsonArray
import com.popoto.core.json.JsonValue.JsonNull
import com.popoto.core.json.JsonValue.JsonNumber
import com.popoto.core.json.JsonValue.JsonObject
import com.popoto.core.json.JsonValue.JsonString

object SimpleJsonParser {
    fun parseObject(input: String): JsonObject {
        val parser = Parser(input)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.isAtEnd()) { "Unexpected trailing characters in JSON" }
        return value as? JsonObject ?: error("Expected JSON object")
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun parseValue(): JsonValue {
            skipWhitespace()
            require(!isAtEnd()) { "Unexpected end of JSON input" }

            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonString(parseString())
                't', 'f' -> JsonBoolean(parseBoolean())
                'n' -> {
                    parseNull()
                    JsonNull
                }
                '-', in '0'..'9' -> JsonNumber(parseNumber())
                else -> error("Unexpected character '${peek()}'")
            }
        }

        fun skipWhitespace() {
            while (!isAtEnd() && peek().isWhitespace()) {
                index += 1
            }
        }

        fun isAtEnd(): Boolean = index >= input.length

        private fun parseObject(): JsonObject {
            expect('{')
            skipWhitespace()

            val fields = linkedMapOf<String, JsonValue>()
            if (!isAtEnd() && peek() == '}') {
                index += 1
                return JsonObject(fields)
            }

            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                fields[key] = value
                skipWhitespace()

                when {
                    isAtEnd() -> error("Unexpected end of JSON object")
                    peek() == ',' -> index += 1
                    peek() == '}' -> {
                        index += 1
                        return JsonObject(fields)
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): JsonArray {
            expect('[')
            skipWhitespace()

            val values = mutableListOf<JsonValue>()
            if (!isAtEnd() && peek() == ']') {
                index += 1
                return JsonArray(values)
            }

            while (true) {
                values += parseValue()
                skipWhitespace()

                when {
                    isAtEnd() -> error("Unexpected end of JSON array")
                    peek() == ',' -> {
                        index += 1
                        skipWhitespace()
                    }
                    peek() == ']' -> {
                        index += 1
                        return JsonArray(values)
                    }
                    else -> error("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()

            while (!isAtEnd()) {
                val character = input[index++]
                when (character) {
                    '"' -> return result.toString()
                    '\\' -> result.append(parseEscape())
                    else -> result.append(character)
                }
            }

            error("Unterminated string literal")
        }

        private fun parseEscape(): Char {
            require(!isAtEnd()) { "Incomplete escape sequence" }

            return when (val escaped = input[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val hex = input.substring(index, index + 4)
                    index += 4
                    hex.toInt(16).toChar()
                }
                else -> error("Unsupported escape sequence: \\$escaped")
            }
        }

        private fun parseBoolean(): Boolean = when {
            input.startsWith("true", startIndex = index) -> {
                index += 4
                true
            }
            input.startsWith("false", startIndex = index) -> {
                index += 5
                false
            }
            else -> error("Invalid boolean literal")
        }

        private fun parseNull() {
            require(input.startsWith("null", startIndex = index)) { "Invalid null literal" }
            index += 4
        }

        private fun parseNumber(): String {
            val start = index

            if (peek() == '-') {
                index += 1
            }

            consumeDigits()

            if (!isAtEnd() && peek() == '.') {
                index += 1
                consumeDigits()
            }

            if (!isAtEnd() && (peek() == 'e' || peek() == 'E')) {
                index += 1
                if (!isAtEnd() && (peek() == '+' || peek() == '-')) {
                    index += 1
                }
                consumeDigits()
            }

            return input.substring(start, index)
        }

        private fun consumeDigits() {
            val start = index
            while (!isAtEnd() && peek().isDigit()) {
                index += 1
            }
            require(index > start) { "Invalid numeric literal" }
        }

        private fun expect(expected: Char) {
            require(!isAtEnd() && input[index] == expected) {
                "Expected '$expected'"
            }
            index += 1
        }

        private fun peek(): Char = input[index]
    }
}
