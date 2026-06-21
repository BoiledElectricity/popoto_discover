package com.popoto.core.message

import com.popoto.core.auth.MessageAuthenticator
import com.popoto.core.json.JsonValue.JsonObject
import com.popoto.core.json.SimpleJsonParser

class ProtocolEncoder(private val authenticator: MessageAuthenticator) {
    fun encode(message: WireMessage, secret: String?): String = authenticator.encode(message.fields(), secret)
}

object ProtocolCodec {
    fun parseJsonObject(json: String): JsonObject = SimpleJsonParser.parseObject(json)

    fun command(json: String): String? = parseJsonObject(json).string("cmd")

    fun parseDiscoverReply(json: String): DiscoverReplyMessage =
        DiscoverReplyMessage.fromJsonObject(parseJsonObject(json))

    fun parseGetVersionReply(json: String): GetVersionReplyMessage =
        GetVersionReplyMessage.fromJsonObject(parseJsonObject(json))

    fun parseSetIpReply(json: String): SetIpReplyMessage =
        SetIpReplyMessage.fromJsonObject(parseJsonObject(json))

    fun parseSetRtcReply(json: String): SetRtcReplyMessage =
        SetRtcReplyMessage.fromJsonObject(parseJsonObject(json))

    fun parseGetRtcReply(json: String): GetRtcReplyMessage =
        GetRtcReplyMessage.fromJsonObject(parseJsonObject(json))

    fun parseSetParamReply(json: String): SetParamReplyMessage =
        SetParamReplyMessage.fromJsonObject(parseJsonObject(json))

    fun parseShellExecReply(json: String): ShellExecReplyMessage =
        ShellExecReplyMessage.fromJsonObject(parseJsonObject(json))
}
