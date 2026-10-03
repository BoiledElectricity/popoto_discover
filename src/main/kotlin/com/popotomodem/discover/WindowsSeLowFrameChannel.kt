package com.popotomodem.discover

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object SeLowProtocol {
    const val MAX_FRAME_SIZE = 1600
    const val MAX_PACKETS = 256
    const val PACKET_STRIDE = MAX_FRAME_SIZE + 4
    const val EXCHANGE_SIZE = 8 + PACKET_STRIDE * (MAX_PACKETS + 1)
    const val ADAPTER_SIZE = 652
    const val ADAPTER_LIST_SIZE = 16 + ADAPTER_SIZE * 256
    val etherTypes = setOf(0x88A2, 0x88B5, 0x88B6)

    data class Adapter(val id: String, val mac: ByteArray)

    fun adapters(buffer: ByteBuffer): List<Adapter> {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.capacity() >= ADAPTER_LIST_SIZE) { "short SeLow adapter list" }
        require(buffer.getInt(0) == 0xDEADBEEF.toInt()) { "invalid SeLow adapter signature" }
        require(buffer.getInt(4) == 48) { "unsupported SeLow driver API ${buffer.getInt(4)}" }
        val count = buffer.getInt(12)
        require(count in 0..256) { "invalid SeLow adapter count $count" }
        return (0 until count).map { index ->
            val offset = 16 + index * ADAPTER_SIZE
            val id = ByteArray(128) { buffer.get(offset + it) }.toString(Charsets.UTF_16LE).substringBefore('\u0000')
            require(Regex("SELOW_A_\\{[0-9a-fA-F-]{36}\\}").matches(id)) { "invalid SeLow adapter identifier" }
            Adapter(id, ByteArray(6) { buffer.get(offset + 128 + it) })
        }
    }

    fun packetCount(buffer: ByteBuffer): Int {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.capacity() >= EXCHANGE_SIZE) { "short SeLow packet buffer" }
        return buffer.getInt(0).also { require(it in 0..MAX_PACKETS) { "invalid SeLow packet count $it" } }
    }

    fun packet(buffer: ByteBuffer, index: Int): ByteArray {
        require(index in 0 until packetCount(buffer)) { "invalid SeLow packet index" }
        val offset = 4 + index * PACKET_STRIDE
        val size = buffer.getInt(offset)
        require(size in 14..MAX_FRAME_SIZE) { "invalid SeLow frame size $size" }
        return ByteArray(size) { buffer.get(offset + 4 + it) }
    }

    fun putPacket(buffer: ByteBuffer, frame: ByteArray, mac: ByteArray, etherType: Int) {
        require(frame.size in 14..MAX_FRAME_SIZE) { "unsupported SeLow frame size ${frame.size}" }
        require(etherType in etherTypes && EthernetFrameTransport.etherType(frame) == etherType) { "unexpected Popoto EtherType" }
        require(frame.copyOfRange(6, 12).contentEquals(mac)) { "frame source does not match the selected adapter" }
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 1)
        buffer.putInt(4, frame.size)
        frame.forEachIndexed { index, byte -> buffer.put(8 + index, byte) }
    }
}

internal object SeLowNative {
    const val DEVICE = "\\\\.\\SELOW_BASIC_DEVICE"
    val api: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

    fun open(path: String): Pointer {
        val handle = api.CreateFileW(WString(path), 0xC0000000.toInt(), 3, null, 3, 0, null)
        if (handle == null || Pointer.nativeValue(handle) == -1L) {
            throw EthernetFrameException("Cannot open Windows Ethernet driver ($path): Win32 error ${Native.getLastError()}. Run Popoto Discover as administrator.")
        }
        return handle
    }

    fun adapters(waitForMac: ByteArray? = null): List<SeLowProtocol.Adapter> {
        val handle = open(DEVICE)
        try {
            Memory(SeLowProtocol.ADAPTER_LIST_SIZE.toLong()).use { buffer ->
                val deadline = System.nanoTime() + 15_000_000_000L
                while (true) {
                    buffer.clear()
                    check(api.ReadFile(handle, buffer, buffer.size().toInt(), IntByReference(), null)) {
                        "SeLow adapter read failed: Win32 error ${Native.getLastError()}"
                    }
                    val adapters = SeLowProtocol.adapters(buffer.getByteBuffer(0, buffer.size()))
                    if (waitForMac == null || adapters.any { it.mac.contentEquals(waitForMac) } || System.nanoTime() >= deadline) {
                        return adapters
                    }
                    Thread.sleep(100)
                }
            }
        } finally {
            api.CloseHandle(handle)
        }
    }

    interface Kernel32 : StdCallLibrary {
        fun CreateFileW(path: WString, access: Int, share: Int, security: Pointer?, creation: Int, flags: Int, template: Pointer?): Pointer?
        fun ReadFile(handle: Pointer, buffer: Pointer, size: Int, read: IntByReference, overlapped: Pointer?): Boolean
        fun WriteFile(handle: Pointer, buffer: Pointer, size: Int, written: IntByReference, overlapped: Pointer?): Boolean
        fun DeviceIoControl(handle: Pointer, code: Int, input: Pointer, inputSize: Int, output: Pointer, outputSize: Int, returned: IntByReference, overlapped: Pointer?): Boolean
        fun OpenEventA(access: Int, inherit: Boolean, name: String): Pointer?
        fun WaitForSingleObject(handle: Pointer, milliseconds: Int): Int
        fun CloseHandle(handle: Pointer): Boolean
    }
}

internal class WindowsSeLowFrameChannel private constructor(
    override val interfaceName: String,
    override val localMac: ByteArray,
    private val etherType: Int,
    private val handle: Pointer,
    private val event: Pointer,
) : RawFrameChannel {
    private val input = Memory(SeLowProtocol.EXCHANGE_SIZE.toLong()).apply { clear() }
    private val output = Memory(SeLowProtocol.EXCHANGE_SIZE.toLong()).apply { clear() }
    private val inputBytes = input.getByteBuffer(0, input.size()).order(ByteOrder.LITTLE_ENDIAN)
    private var packetIndex = 0
    private var packetCount = 0
    private var closed = false

    @Synchronized
    override fun send(frame: ByteArray) {
        check(!closed) { "SeLow channel is closed" }
        SeLowProtocol.putPacket(output.getByteBuffer(0, output.size()), frame, localMac, etherType)
        val written = IntByReference()
        if (!SeLowNative.api.WriteFile(handle, output, output.size().toInt(), written, null) || written.value != output.size().toInt()) {
            throw EthernetFrameException("SeLow send failed on $interfaceName: Win32 error ${Native.getLastError()}")
        }
    }

    @Synchronized
    override fun receive(timeoutMillis: Int): ByteArray? {
        check(!closed) { "SeLow channel is closed" }
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (true) {
            while (packetIndex < packetCount) {
                val frame = SeLowProtocol.packet(inputBytes, packetIndex++)
                if (EthernetFrameTransport.etherType(frame) == etherType) return frame
            }
            val read = IntByReference()
            if (!SeLowNative.api.ReadFile(handle, input, input.size().toInt(), read, null) || read.value != input.size().toInt()) {
                throw EthernetFrameException("SeLow receive failed on $interfaceName: Win32 error ${Native.getLastError()}")
            }
            packetCount = SeLowProtocol.packetCount(inputBytes)
            packetIndex = 0
            if (packetCount > 0) {
                // Each batch is bounded so unrelated traffic cannot extend the caller's deadline indefinitely.
                while (packetIndex < packetCount) {
                    val frame = SeLowProtocol.packet(inputBytes, packetIndex++)
                    if (EthernetFrameTransport.etherType(frame) == etherType) return frame
                }
            }
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remaining <= 0) return null
            if (inputBytes.getInt(4 + SeLowProtocol.MAX_PACKETS * SeLowProtocol.PACKET_STRIDE) != 0) continue
            val result = SeLowNative.api.WaitForSingleObject(event, remaining.coerceAtMost(50))
            if (result != 0 && result != 258) throw EthernetFrameException("SeLow wait failed: Win32 error ${Native.getLastError()}")
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            SeLowNative.api.CloseHandle(event)
            SeLowNative.api.CloseHandle(handle)
            input.close()
            output.close()
        }
    }

    companion object {
        fun open(interfaceName: String, sourceMac: ByteArray, etherType: Int): WindowsSeLowFrameChannel {
            require(etherType in SeLowProtocol.etherTypes) { "unsupported Popoto EtherType" }
            val matches = SeLowNative.adapters(sourceMac).filter { it.mac.contentEquals(sourceMac) }
            val adapter = matches.singleOrNull()
                ?: throw EthernetFrameException("SeLow needs one adapter matching $interfaceName; found ${matches.size}")
            val handle = SeLowNative.open("\\\\.\\${adapter.id}")
            var event: Pointer? = null
            try {
                Memory(128).use { name ->
                    name.clear()
                    if (!SeLowNative.api.DeviceIoControl(handle, 0x80000007.toInt(), name, 128, name, 128, IntByReference(), null)) {
                        throw EthernetFrameException("SeLow event query failed: Win32 error ${Native.getLastError()}")
                    }
                    event = SeLowNative.api.OpenEventA(0x00100000, false, name.getString(0))
                }
                val readyEvent = event ?: throw EthernetFrameException("SeLow event open failed: Win32 error ${Native.getLastError()}")
                L2Debug.log("using signed SoftEther SeLow backend on $interfaceName (${adapter.id})")
                return WindowsSeLowFrameChannel(interfaceName, sourceMac, etherType, handle, readyEvent)
            } catch (failure: Throwable) {
                event?.let { SeLowNative.api.CloseHandle(it) }
                SeLowNative.api.CloseHandle(handle)
                throw failure
            }
        }
    }
}
