package com.popotomodem.discover

import java.io.Closeable
import java.util.UUID

class UbootL2Client private constructor(
    private val transport: RawEthernetTransport,
) : Closeable {
    fun reboot(target: TargetSelector, secret: String?, timeoutMillis: Int = 5_000) {
        val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
        val request = Protocol.createRebootMessage(nonce, target, secret)
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(1) * 1_000_000L
        var nextSend = 0L

        while (System.nanoTime() < deadline) {
            val nowMillis = System.currentTimeMillis()
            if (nowMillis >= nextSend) {
                transport.sendJson(L2Protocol.broadcastAddress(), request)
                nextSend = nowMillis + 250
            }
            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1)
            val packet = transport.receive(remainingMillis.coerceAtMost(100)) ?: continue
            val message = packet.message
            if (Protocol.text(message, "cmd") == Protocol.MSG_REBOOT_REPLY &&
                Protocol.text(message, "nonce") == nonce &&
                Protocol.text(message, "status") == "ok"
            ) {
                return
            }
        }

        throw RuntimeException("timed out waiting for U-Boot reboot acknowledgement from ${target.label}")
    }

    override fun close() {
        transport.close()
    }

    companion object {
        fun open(interfaceName: String, timeoutMillis: Int = 2_000): UbootL2Client {
            return UbootL2Client(RawEthernetTransport.open(interfaceName, timeoutMillis))
        }
    }
}
