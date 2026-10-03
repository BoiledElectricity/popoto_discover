package com.popotomodem.discover

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SeLowProtocolTest {
    private val mac = byteArrayOf(2, 3, 4, 5, 6, 7)
    private fun buffer() = ByteBuffer.allocate(SeLowProtocol.EXCHANGE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private fun frame(type: Int) = ByteArray(60).apply {
        mac.copyInto(this, 6)
        this[12] = (type shr 8).toByte()
        this[13] = type.toByte()
    }

    @Test
    fun roundTripsEveryPopotoEtherType() {
        for (type in listOf(0x88A2, 0x88B5, 0x88B6)) {
            val data = buffer()
            val packet = frame(type)
            SeLowProtocol.putPacket(data, packet, mac, type)
            assertEquals(1, SeLowProtocol.packetCount(data))
            assertContentEquals(packet, SeLowProtocol.packet(data, 0))
        }
    }

    @Test
    fun rejectsInvalidFrameLengthsCountsAndProtocols() {
        for (count in listOf(-1, 257)) {
            assertFailsWith<IllegalArgumentException> { SeLowProtocol.packetCount(buffer().putInt(0, count)) }
        }
        for (size in listOf(-1, 0, 13, 1601)) {
            val data = buffer().putInt(0, 1).putInt(4, size)
            assertFailsWith<IllegalArgumentException> { SeLowProtocol.packet(data, 0) }
        }
        assertFailsWith<IllegalArgumentException> { SeLowProtocol.putPacket(buffer(), frame(0x0800), mac, 0x0800) }
        assertFailsWith<IllegalArgumentException> { SeLowProtocol.putPacket(buffer(), frame(0x88B6), ByteArray(6), 0x88B6) }
        assertFailsWith<IllegalArgumentException> { SeLowProtocol.packetCount(ByteBuffer.allocate(16)) }
    }

    @Test
    fun parsesAdapterLayoutAndRejectsUnsupportedApi() {
        val data = ByteBuffer.allocate(SeLowProtocol.ADAPTER_LIST_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(0, 0xDEADBEEF.toInt()).putInt(4, 48).putInt(12, 1)
        val id = "SELOW_A_{E89A6624-E563-4C4F-9002-1A379E8B9676}"
        id.toByteArray(Charsets.UTF_16LE).forEachIndexed { i, byte -> data.put(16 + i, byte) }
        mac.forEachIndexed { i, byte -> data.put(16 + 128 + i, byte) }
        val adapter = SeLowProtocol.adapters(data).single()
        assertEquals(id, adapter.id)
        assertContentEquals(mac, adapter.mac)
        data.putInt(4, 49)
        assertFailsWith<IllegalArgumentException> { SeLowProtocol.adapters(data) }
        data.putInt(4, 48).putInt(12, 257)
        assertFailsWith<IllegalArgumentException> { SeLowProtocol.adapters(data) }
        data.putInt(12, 0).putInt(0, 0)
        assertFailsWith<IllegalArgumentException> { SeLowProtocol.adapters(data) }
    }

    @Test
    fun udpDoesNotRequireWindowsDriverSetup() {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            assertFalse(WindowsPacketAccess.needsSetupFor(TransportMode.UDP))
        } finally {
            System.setProperty("os.name", original)
        }
    }

    @Test
    fun quotesWindowsArgumentsIncludingTrailingSlashes() {
        assertEquals("\"C:\\Program Files\\Popoto\"", WindowsSeLowAccess.quoteArgument("C:\\Program Files\\Popoto"))
        assertEquals("\"path\\\\\"", WindowsSeLowAccess.quoteArgument("path\\"))
        assertEquals("\"a\\\"b\"", WindowsSeLowAccess.quoteArgument("a\"b"))
    }
}
