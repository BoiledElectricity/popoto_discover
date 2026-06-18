package com.popotomodem.discover

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object WindowsPmmNdisAccess {
    private const val DEVICE_PATH = "\\\\.\\PmmNdis"
    private const val DRIVER_RESOURCE_DIR = "/windows/pmmndis"
    private val DRIVER_RESOURCES = listOf("pmmndis630.inf", "pmmndis630.sys", "pmmndis630.cat")

    data class InstallResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val rebootRequired: Boolean = false,
    )

    fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("windows", ignoreCase = true)
    }

    fun transportUsesL2(mode: TransportMode): Boolean {
        return mode == TransportMode.AUTO || mode == TransportMode.L2 || mode == TransportMode.ALL
    }

    fun needsSetupFor(mode: TransportMode): Boolean {
        return isWindows() && transportUsesL2(mode) && !hasDriver()
    }

    fun hasDriver(): Boolean {
        if (!isWindows()) {
            return true
        }
        return runCatching {
            val handle = WindowsKernel32.openDeviceHandle()
            try {
                true
            } finally {
                WindowsKernel32.closeHandle(handle)
            }
        }.getOrDefault(false)
    }

    fun install(): InstallResult {
        if (!isWindows()) {
            return InstallResult(true, 0, "PMM NDIS setup is only needed on Windows.")
        }
        if (hasDriver()) {
            return InstallResult(true, 0, "PMM NDIS driver is already installed.")
        }
        if (!hasBundledDriver()) {
            return InstallResult(
                success = false,
                exitCode = 1,
                output = "This Popoto Discover build does not include a signed PMM NDIS driver package.",
            )
        }

        val tempDir = Files.createTempDirectory("popoto-pmmndis-")
        return try {
            extractDriverPackage(tempDir)
            val inf = tempDir.resolve("pmmndis630.inf").toString()
            val script = """
                ${'$'}ErrorActionPreference = 'Stop'
                pnputil.exe /add-driver '${powershellSingleQuote(inf)}' /install
                if (${ '$' }LASTEXITCODE -ne 0) { exit ${ '$' }LASTEXITCODE }
                sc.exe start PmmNdis | Out-Null
                exit 0
            """.trimIndent()
            val elevated = """
                ${'$'}process = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-Command','${powershellSingleQuote(script)}') -Verb RunAs -Wait -PassThru
                exit ${'$'}process.ExitCode
            """.trimIndent()
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                elevated,
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            InstallResult(
                success = exitCode == 0 && hasDriver(),
                exitCode = exitCode,
                output = output,
                rebootRequired = false,
            )
        } finally {
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    internal fun devicePath(): String = DEVICE_PATH

    private fun hasBundledDriver(): Boolean {
        return DRIVER_RESOURCES.all { resource ->
            WindowsPmmNdisAccess::class.java.getResource("$DRIVER_RESOURCE_DIR/$resource") != null
        }
    }

    private fun extractDriverPackage(targetDir: Path) {
        for (resource in DRIVER_RESOURCES) {
            val input = WindowsPmmNdisAccess::class.java.getResourceAsStream("$DRIVER_RESOURCE_DIR/$resource")
                ?: throw IllegalStateException("missing bundled PMM NDIS resource: $resource")
            input.use { source ->
                Files.newOutputStream(targetDir.resolve(resource)).use { output -> source.copyTo(output) }
            }
        }
    }

    private fun powershellSingleQuote(value: String): String {
        return value.replace("'", "''")
    }
}

internal class WindowsPmmNdisFrameChannel private constructor(
    override val interfaceName: String,
    override val localMac: ByteArray,
    private val etherType: Int,
    private val handle: Pointer,
) : RawFrameChannel {
    private val closed = AtomicBoolean(false)
    private val received = LinkedBlockingQueue<ByteArray>(4096)
    private val reader = Thread({ readLoop() }, "pmmndis-$interfaceName-${etherType.toString(16)}")

    init {
        reader.isDaemon = true
        reader.start()
    }

    override fun send(frame: ByteArray) {
        val written = IntByReference()
        if (!WindowsKernel32.INSTANCE.WriteFile(handle, frame, frame.size, written, null)) {
            throw EthernetFrameException("PMM NDIS WriteFile failed: ${WindowsKernel32.lastErrorMessage()}")
        }
        if (written.value != frame.size) {
            throw EthernetFrameException("PMM NDIS short write: ${written.value}/${frame.size} bytes")
        }
    }

    override fun receive(timeoutMillis: Int): ByteArray? {
        return if (timeoutMillis <= 0) {
            received.poll()
        } else {
            received.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            WindowsKernel32.closeHandle(handle)
            reader.interrupt()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(MAX_FRAME_SIZE)
        while (!closed.get()) {
            val read = IntByReference()
            val ok = WindowsKernel32.INSTANCE.ReadFile(handle, buffer, buffer.size, read, null)
            if (!ok) {
                if (!closed.get()) {
                    Thread.sleep(5)
                }
                continue
            }
            val size = read.value
            if (size >= EthernetFrameTransport.ETHERNET_HEADER_LEN) {
                val frame = buffer.copyOf(size)
                if (EthernetFrameTransport.etherType(frame) == etherType) {
                    received.offer(frame)
                }
            }
        }
    }

    companion object {
        private const val MAX_FRAME_SIZE = 2048

        fun open(interfaceName: String, sourceMac: ByteArray, etherType: Int): WindowsPmmNdisFrameChannel {
            if (!WindowsPmmNdisAccess.isWindows()) {
                throw EthernetFrameException("PMM NDIS is only available on Windows")
            }

            val bindings = enumerateBindings()
            if (bindings.isEmpty()) {
                throw EthernetFrameException("PMM NDIS driver is loaded but has no Ethernet bindings")
            }

            val binding = selectBinding(bindings, interfaceName, sourceMac)
                ?: throw EthernetFrameException("PMM NDIS has no binding matching $interfaceName")
            val handle = WindowsKernel32.openDeviceHandle()
            try {
                bindDevice(handle, binding.deviceName)
                val adapterMac = queryCurrentAddress(handle)
                return WindowsPmmNdisFrameChannel(interfaceName, adapterMac, etherType, handle)
            } catch (e: Throwable) {
                WindowsKernel32.closeHandle(handle)
                throw e
            }
        }

        private fun enumerateBindings(): List<NdisBinding> {
            val handle = WindowsKernel32.openDeviceHandle()
            try {
                bindWait(handle)
                val bindings = mutableListOf<NdisBinding>()
                var index = 0
                while (index < 256) {
                    val binding = queryBinding(handle, index) ?: break
                    bindings += binding
                    index++
                }
                return bindings
            } finally {
                WindowsKernel32.closeHandle(handle)
            }
        }

        private fun selectBinding(
            bindings: List<NdisBinding>,
            interfaceName: String,
            sourceMac: ByteArray,
        ): NdisBinding? {
            for (binding in bindings) {
                val mac = runCatching { queryMacForBinding(binding) }.getOrNull()
                if (mac != null && mac.contentEquals(sourceMac)) {
                    return binding
                }
            }

            val wanted = interfaceName.lowercase(Locale.US)
            return bindings.firstOrNull {
                it.deviceName.lowercase(Locale.US).contains(wanted) ||
                    it.description.lowercase(Locale.US).contains(wanted)
            } ?: bindings.singleOrNull()
        }

        private fun queryMacForBinding(binding: NdisBinding): ByteArray {
            val handle = WindowsKernel32.openDeviceHandle()
            try {
                bindDevice(handle, binding.deviceName)
                return queryCurrentAddress(handle)
            } finally {
                WindowsKernel32.closeHandle(handle)
            }
        }

        private fun bindWait(handle: Pointer) {
            val returned = IntByReference()
            if (!WindowsKernel32.INSTANCE.DeviceIoControl(
                    handle,
                    IOCTL_NDISPROT_BIND_WAIT,
                    null,
                    0,
                    null,
                    0,
                    returned,
                    null,
                )
            ) {
                throw EthernetFrameException("PMM NDIS bind wait failed: ${WindowsKernel32.lastErrorMessage()}")
            }
        }

        private fun queryBinding(handle: Pointer, index: Int): NdisBinding? {
            val buffer = Memory(QUERY_BINDING_BUFFER_SIZE.toLong())
            buffer.clear(QUERY_BINDING_BUFFER_SIZE.toLong())
            buffer.setInt(0, index)
            val returned = IntByReference()
            val ok = WindowsKernel32.INSTANCE.DeviceIoControl(
                handle,
                IOCTL_NDISPROT_QUERY_BINDING,
                buffer,
                NDISPROT_QUERY_BINDING_HEADER_SIZE,
                buffer,
                QUERY_BINDING_BUFFER_SIZE,
                returned,
                null,
            )
            if (!ok) {
                val error = WindowsKernel32.INSTANCE.GetLastError()
                if (error == ERROR_NO_MORE_ITEMS) {
                    return null
                }
                throw EthernetFrameException("PMM NDIS query binding $index failed: ${WindowsKernel32.errorMessage(error)}")
            }

            val deviceNameOffset = buffer.getInt(4).toLong()
            val deviceNameLength = buffer.getInt(8)
            val descriptionOffset = buffer.getInt(12).toLong()
            val descriptionLength = buffer.getInt(16)
            return NdisBinding(
                deviceName = buffer.readUtf16(deviceNameOffset, deviceNameLength),
                description = buffer.readUtf16(descriptionOffset, descriptionLength),
            )
        }

        private fun bindDevice(handle: Pointer, deviceName: String) {
            val bytes = deviceName.toByteArray(StandardCharsets.UTF_16LE)
            val buffer = Memory(bytes.size.toLong())
            buffer.write(0, bytes, 0, bytes.size)
            val returned = IntByReference()
            if (!WindowsKernel32.INSTANCE.DeviceIoControl(
                    handle,
                    IOCTL_NDISPROT_OPEN_DEVICE,
                    buffer,
                    bytes.size,
                    null,
                    0,
                    returned,
                    null,
                )
            ) {
                throw EthernetFrameException("PMM NDIS open binding '$deviceName' failed: ${WindowsKernel32.lastErrorMessage()}")
            }
        }

        private fun queryCurrentAddress(handle: Pointer): ByteArray {
            val bufferSize = NDISPROT_QUERY_OID_HEADER_SIZE + EthernetFrameTransport.ETHERNET_ADDR_LEN
            val buffer = Memory(bufferSize.toLong())
            buffer.clear(bufferSize.toLong())
            buffer.setInt(0, OID_802_3_CURRENT_ADDRESS)
            buffer.setInt(4, 0)
            val returned = IntByReference()
            if (!WindowsKernel32.INSTANCE.DeviceIoControl(
                    handle,
                    IOCTL_NDISPROT_QUERY_OID_VALUE,
                    buffer,
                    bufferSize,
                    buffer,
                    bufferSize,
                    returned,
                    null,
                )
            ) {
                throw EthernetFrameException("PMM NDIS query adapter MAC failed: ${WindowsKernel32.lastErrorMessage()}")
            }
            return buffer.getByteArray(NDISPROT_QUERY_OID_HEADER_SIZE.toLong(), EthernetFrameTransport.ETHERNET_ADDR_LEN)
        }

        private fun Memory.readUtf16(offset: Long, length: Int): String {
            if (offset <= 0 || length <= 0) {
                return ""
            }
            return String(getByteArray(offset, length), StandardCharsets.UTF_16LE).trimEnd('\u0000')
        }

        private const val ERROR_NO_MORE_ITEMS = 259
        private const val FILE_DEVICE_NETWORK = 0x00000012
        private const val METHOD_BUFFERED = 0
        private const val FILE_READ_ACCESS = 0x0001
        private const val FILE_WRITE_ACCESS = 0x0002
        private const val NDISPROT_QUERY_BINDING_HEADER_SIZE = 20
        private const val NDISPROT_QUERY_OID_HEADER_SIZE = 8
        private const val QUERY_BINDING_BUFFER_SIZE = 4096
        private const val OID_802_3_CURRENT_ADDRESS = 0x01010102
        private val IOCTL_NDISPROT_OPEN_DEVICE = ctlCode(0x200)
        private val IOCTL_NDISPROT_QUERY_OID_VALUE = ctlCode(0x201)
        private val IOCTL_NDISPROT_QUERY_BINDING = ctlCode(0x203)
        private val IOCTL_NDISPROT_BIND_WAIT = ctlCode(0x204)

        private fun ctlCode(function: Int): Int {
            val access = FILE_READ_ACCESS or FILE_WRITE_ACCESS
            return (FILE_DEVICE_NETWORK shl 16) or (access shl 14) or (function shl 2) or METHOD_BUFFERED
        }
    }
}

private data class NdisBinding(
    val deviceName: String,
    val description: String,
)

private object WindowsKernel32 {
    val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)

    fun openDeviceHandle(): Pointer {
        val handle = INSTANCE.CreateFileW(
            WString(WindowsPmmNdisAccess.devicePath()),
            GENERIC_READ or GENERIC_WRITE,
            FILE_SHARE_READ or FILE_SHARE_WRITE,
            null,
            OPEN_EXISTING,
            0,
            null,
        )
        if (isInvalidHandle(handle)) {
            throw EthernetFrameException("PMM NDIS device ${WindowsPmmNdisAccess.devicePath()} is not available: ${lastErrorMessage()}")
        }
        return handle
    }

    fun closeHandle(handle: Pointer) {
        if (!isInvalidHandle(handle)) {
            INSTANCE.CloseHandle(handle)
        }
    }

    fun lastErrorMessage(): String = errorMessage(INSTANCE.GetLastError())

    fun errorMessage(error: Int): String = "Win32 error $error"

    private fun isInvalidHandle(handle: Pointer?): Boolean {
        return handle == null || Pointer.nativeValue(handle) == -1L
    }

    private const val GENERIC_READ = -0x80000000
    private const val GENERIC_WRITE = 0x40000000
    private const val FILE_SHARE_READ = 0x00000001
    private const val FILE_SHARE_WRITE = 0x00000002
    private const val OPEN_EXISTING = 3

    interface Kernel32 : Library {
        fun CreateFileW(
            lpFileName: WString,
            dwDesiredAccess: Int,
            dwShareMode: Int,
            lpSecurityAttributes: Pointer?,
            dwCreationDisposition: Int,
            dwFlagsAndAttributes: Int,
            hTemplateFile: Pointer?,
        ): Pointer

        fun DeviceIoControl(
            hDevice: Pointer,
            dwIoControlCode: Int,
            lpInBuffer: Pointer?,
            nInBufferSize: Int,
            lpOutBuffer: Pointer?,
            nOutBufferSize: Int,
            lpBytesReturned: IntByReference,
            lpOverlapped: Pointer?,
        ): Boolean

        fun ReadFile(
            hFile: Pointer,
            lpBuffer: ByteArray,
            nNumberOfBytesToRead: Int,
            lpNumberOfBytesRead: IntByReference,
            lpOverlapped: Pointer?,
        ): Boolean

        fun WriteFile(
            hFile: Pointer,
            lpBuffer: ByteArray,
            nNumberOfBytesToWrite: Int,
            lpNumberOfBytesWritten: IntByReference,
            lpOverlapped: Pointer?,
        ): Boolean

        fun CloseHandle(hObject: Pointer): Boolean

        fun GetLastError(): Int
    }
}
