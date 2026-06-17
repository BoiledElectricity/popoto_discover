package com.popotomodem.discover

import net.jpountz.lz4.LZ4FrameInputStream
import net.jpountz.lz4.LZ4Factory
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min

class AoEException(message: String) : RuntimeException(message)

data class AoEProgress(
    val phase: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val message: String? = null,
)

data class BmapRange(
    val start: Long,
    val end: Long,
    val checksum: String?,
) {
    val blocks: Long get() = end - start + 1
}

data class Bmap(
    val imageSize: Long,
    val blockSize: Long,
    val checksumType: String,
    val ranges: List<BmapRange>,
) {
    val mappedBytes: Long = ranges.sumOf { rangeSize(it) }

    fun rangeSize(range: BmapRange): Long {
        val startByte = range.start * blockSize
        val endByte = min((range.end + 1) * blockSize, imageSize)
        if (endByte <= startByte) {
            throw AoEException("bmap range ${range.start}-${range.end} starts beyond image size")
        }
        val size = endByte - startByte
        if (size % AoEFlasher.AOE_SECTOR_SIZE != 0L) {
            throw AoEException("bmap range ${range.start}-${range.end} is not sector aligned after image-size clipping")
        }
        return size
    }

    companion object {
        fun parse(path: File): Bmap {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isIgnoringComments = true
            val doc = factory.newDocumentBuilder().parse(path)
            val blockSize = tagValue(doc.documentElement, "BlockSize").toLong()
            val imageSize = tagValue(doc.documentElement, "ImageSize").toLong()
            val checksumType = tagValue(doc.documentElement, "ChecksumType").lowercase()
            if (checksumType != "sha256") {
                throw AoEException("unsupported bmap checksum type: $checksumType")
            }
            if (blockSize % AoEFlasher.AOE_SECTOR_SIZE != 0L) {
                throw AoEException("bmap block size $blockSize is not sector aligned")
            }
            if (imageSize % AoEFlasher.AOE_SECTOR_SIZE != 0L) {
                throw AoEException("image size $imageSize is not sector aligned")
            }

            val ranges = mutableListOf<BmapRange>()
            val nodes = doc.getElementsByTagName("Range")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val text = element.textContent.trim()
                val parts = text.split("-", limit = 2)
                val start = parts[0].toLong()
                val end = if (parts.size == 2) parts[1].toLong() else start
                val checksum = element.getAttribute("chksum").takeIf { it.isNotBlank() }
                ranges += BmapRange(start, end, checksum)
            }
            if (ranges.isEmpty()) {
                throw AoEException("no mapped ranges found in bmap file $path")
            }
            return Bmap(imageSize, blockSize, checksumType, ranges)
        }

        private fun tagValue(root: Element, name: String): String {
            val nodes = root.getElementsByTagName(name)
            if (nodes.length == 0) {
                throw AoEException("missing $name in bmap file")
            }
            return nodes.item(0).textContent.trim()
        }
    }
}

class AoEFlasher private constructor(
    private val transport: EthernetFrameTransport,
    private val major: Int = 0,
    private val minor: Int = 0,
    private val timeoutMillis: Int = 2_000,
    private val retries: Int = 5,
) : Closeable {
    private var targetMac: ByteArray? = null
    private var targetBufferCount: Int? = null
    private var nextTag = 1

    fun discover(timeoutMillis: Int = this.timeoutMillis) {
        val tag = tag()
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putShort(0)
            .putShort(0)
            .put(0)
            .put(AOECCMD_READ.toByte())
            .putShort(0)
            .array()
        transport.send(frame(EthernetFrameTransport.BROADCAST_MAC, 0xffff, 0xff, AOECMD_CFG, tag, payload))

        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(1) * 1_000_000L
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1)
            val response = receiveAoE(min(100, remaining)) ?: continue
            if (response.tag != tag || response.command != AOECMD_CFG) {
                continue
            }
            if (response.aoeError != 0) {
                throw AoEException("AoE config error ${response.aoeError}")
            }
            if (response.major == major && response.minor == minor) {
                targetMac = response.source
                if (response.payload.size >= 2) {
                    targetBufferCount = response.payload.u16(0).takeIf { it > 0 }
                }
                return
            }
        }
        throw AoEException("did not discover AoE target e$major.$minor")
    }

    fun readSectors(lba: Long, count: Int): ByteArray {
        return ataCommand(ATA_CMD_PIO_READ_EXT, lba, count, ByteArray(0))
    }

    fun writeBmap(image: File, bmap: Bmap, window: Int = AOE_DEFAULT_WINDOW, onProgress: (AoEProgress) -> Unit) {
        val progress = ByteProgress("write", bmap.mappedBytes, onProgress)
        var currentBlock = 0L
        val expectedDigest = MessageDigest.getInstance("SHA-256")

        ImageReader(image).use { reader ->
            for ((rangeIndex, range) in bmap.ranges.withIndex()) {
                reader.skipExact((range.start - currentBlock) * bmap.blockSize)
                val size = bmap.rangeSize(range)
                val rangeDigest = MessageDigest.getInstance("SHA-256")

                val requests = sequence {
                    var offset = range.start * bmap.blockSize
                    var remaining = size
                    while (remaining > 0) {
                        val chunk = reader.readExact(min(AOE_MAX_DATA.toLong(), remaining).toInt())
                        rangeDigest.update(chunk)
                        expectedDigest.update(chunk)
                        val tag = tag()
                        val lba = offset / AOE_SECTOR_SIZE
                        val count = chunk.size / AOE_SECTOR_SIZE.toInt()
                        val payload = ataPayload(ATA_CMD_WRITE_EXT, lba, count, true) + chunk
                        yield(AoERequest(tag, frame(requireTargetMac(), major, minor, AOECMD_ATA, tag, payload), chunk.size))
                        offset += chunk.size
                        remaining -= chunk.size
                    }
                }

                runPipeline(requests.iterator(), window) { response, bytes ->
                    parseAtaResponse(response, ATA_CMD_WRITE_EXT, 0)
                    progress.add(bytes)
                }
                currentBlock = range.end + 1

                val got = rangeDigest.digest().toHex()
                if (range.checksum != null && got != range.checksum) {
                    throw AoEException("image checksum mismatch for bmap range ${range.start}-${range.end}: $got != ${range.checksum}")
                }
                onProgress(AoEProgress("write", progress.done, bmap.mappedBytes, "write range ${rangeIndex + 1}/${bmap.ranges.size} complete"))
            }
        }

        progress.force()
        onProgress(AoEProgress("write", progress.done, bmap.mappedBytes, "expected mapped-payload sha256: ${expectedDigest.digest().toHex()}"))
    }

    fun writeFull(image: File, window: Int = AOE_DEFAULT_WINDOW, totalBytes: Long? = uncompressedSize(image), onProgress: (AoEProgress) -> Unit) {
        val total = totalBytes ?: 0L
        val progress = ByteProgress("write", total, onProgress)
        val expectedDigest = MessageDigest.getInstance("SHA-256")
        var written = 0L

        ImageReader(image).use { reader ->
            val requests = sequence {
                var offset = 0L
                while (true) {
                    val raw = reader.readBlock(AOE_MAX_DATA)
                    if (raw.isEmpty()) {
                        break
                    }
                    val chunk = if (raw.size % AOE_SECTOR_SIZE.toInt() == 0) {
                        raw
                    } else {
                        raw + ByteArray(AOE_SECTOR_SIZE.toInt() - (raw.size % AOE_SECTOR_SIZE.toInt()))
                    }
                    expectedDigest.update(chunk)
                    val tag = tag()
                    val lba = offset / AOE_SECTOR_SIZE
                    val count = chunk.size / AOE_SECTOR_SIZE.toInt()
                    val payload = ataPayload(ATA_CMD_WRITE_EXT, lba, count, true) + chunk
                    yield(AoERequest(tag, frame(requireTargetMac(), major, minor, AOECMD_ATA, tag, payload), chunk.size))
                    offset += chunk.size
                    written += chunk.size
                }
            }

            runPipeline(requests.iterator(), window) { response, bytes ->
                parseAtaResponse(response, ATA_CMD_WRITE_EXT, 0)
                progress.add(bytes)
            }
        }

        progress.force()
        onProgress(AoEProgress("write", progress.done, total, "wrote $written bytes"))
        onProgress(AoEProgress("write", progress.done, total, "written-payload sha256: ${expectedDigest.digest().toHex()}"))
    }

    fun flush() {
        ataCommand(ATA_CMD_FLUSH_EXT, 0, 0, ByteArray(0))
    }

    fun preferredWindow(): Int = targetBufferCount ?: AOE_DEFAULT_WINDOW

    private fun ataCommand(command: Int, lba: Long, count: Int, data: ByteArray): ByteArray {
        val tag = tag()
        val payload = ataPayload(command, lba, count, data.isNotEmpty()) + data
        val request = AoERequest(tag, frame(requireTargetMac(), major, minor, AOECMD_ATA, tag, payload), 0)
        var result = ByteArray(0)
        runPipeline(listOf(request).iterator(), 1) { response, _ ->
            result = parseAtaResponse(response, command, count)
        }
        return result
    }

    private fun runPipeline(
        producer: Iterator<AoERequest>,
        window: Int,
        handleResponse: (AoEResponse, Int) -> Unit,
    ) {
        val pending = linkedMapOf<Int, PendingAoE>()
        var producerDone = false

        while (pending.isNotEmpty() || !producerDone) {
            while (!producerDone && pending.size < window.coerceAtLeast(1)) {
                if (!producer.hasNext()) {
                    producerDone = true
                    break
                }
                val request = producer.next()
                transport.send(request.frame)
                pending[request.tag] = PendingAoE(request.frame, System.nanoTime(), retries, request.bytes)
            }

            val response = receiveAoE(250)
            if (response != null && pending.containsKey(response.tag)) {
                val pendingRequest = pending.remove(response.tag)!!
                handleResponse(response, pendingRequest.bytes)
            }

            val now = System.nanoTime()
            for ((tag, item) in pending.toMap()) {
                if ((now - item.sentAtNanos) / 1_000_000L < timeoutMillis) {
                    continue
                }
                if (item.retriesLeft <= 0) {
                    throw AoEException("timed out waiting for AoE response tag $tag")
                }
                transport.send(item.frame)
                pending[tag] = item.copy(sentAtNanos = now, retriesLeft = item.retriesLeft - 1)
            }
        }
    }

    private fun parseAtaResponse(response: AoEResponse, command: Int, count: Int): ByteArray {
        if (response.aoeError != 0) {
            throw AoEException("AoE command error ${response.aoeError}")
        }
        if (response.command != AOECMD_ATA || response.payload.size < 12) {
            throw AoEException("malformed ATA AoE response")
        }
        val cmdStat = response.payload[3].toInt() and 0xff
        val errFeat = response.payload[1].toInt() and 0xff
        if ((cmdStat and ATA_ERR) != 0) {
            throw AoEException("ATA command 0x${command.toString(16)} failed, err=0x${errFeat.toString(16)}")
        }
        var data = response.payload.copyOfRange(12, response.payload.size)
        val expected = if (command == ATA_CMD_ID_ATA || command == ATA_CMD_READ_EXT || command == ATA_CMD_PIO_READ_EXT) {
            count * AOE_SECTOR_SIZE.toInt()
        } else {
            0
        }
        if (data.size < expected) {
            throw AoEException("ATA command 0x${command.toString(16)} returned ${data.size} bytes, expected $expected")
        }
        if (data.size > expected) {
            val padding = data.copyOfRange(expected, data.size)
            if (padding.any { it.toInt() != 0 }) {
                throw AoEException("ATA command 0x${command.toString(16)} returned ${data.size} bytes, expected $expected")
            }
            data = data.copyOfRange(0, expected)
        }
        if ((cmdStat and ATA_DRDY) == 0) {
            throw AoEException("ATA command 0x${command.toString(16)} response not ready, status=0x${cmdStat.toString(16)}")
        }
        return data
    }

    private fun receiveAoE(timeoutMillis: Int): AoEResponse? {
        val frame = transport.receive(timeoutMillis) ?: return null
        if (frame.size < EthernetFrameTransport.ETHERNET_HEADER_LEN + 10) {
            return null
        }
        val offset = EthernetFrameTransport.ETHERNET_HEADER_LEN
        val verfl = frame[offset].toInt() and 0xff
        if ((verfl and 0xf0) != AOE_HVER || (verfl and AOEFL_RSP) == 0) {
            return null
        }
        val source = frame.copyOfRange(6, 12)
        val currentTarget = targetMac
        if (currentTarget != null && !source.contentEquals(currentTarget)) {
            return null
        }
        return AoEResponse(
            source = source,
            major = frame.u16(offset + 2),
            minor = frame[offset + 4].toInt() and 0xff,
            command = frame[offset + 5].toInt() and 0xff,
            tag = frame.s32(offset + 6),
            payload = frame.copyOfRange(offset + 10, frame.size),
            aoeError = if ((verfl and AOEFL_ERR) != 0) frame[offset + 1].toInt() and 0xff else 0,
        )
    }

    private fun frame(destination: ByteArray, major: Int, minor: Int, command: Int, tag: Int, payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        header.put(AOE_HVER.toByte())
        header.put(0)
        header.putShort(major.toShort())
        header.put(minor.toByte())
        header.put(command.toByte())
        header.putInt(tag)
        return EthernetFrameTransport.buildFrame(destination, transport.localMac, ETH_P_AOE, header.array() + payload)
    }

    private fun ataPayload(command: Int, lba: Long, count: Int, write: Boolean): ByteArray {
        if (count > 0xff) {
            throw AoEException("AoE target accepts at most 255 sectors in ATA scnt")
        }
        val flags = (if (command != ATA_CMD_ID_ATA) AOEAFL_EXT else 0) or (if (write) AOEAFL_WRITE else 0)
        return byteArrayOf(
            flags.toByte(),
            0,
            (count and 0xff).toByte(),
            command.toByte(),
            (lba and 0xff).toByte(),
            ((lba ushr 8) and 0xff).toByte(),
            ((lba ushr 16) and 0xff).toByte(),
            ((lba ushr 24) and 0xff).toByte(),
            ((lba ushr 32) and 0xff).toByte(),
            ((lba ushr 40) and 0xff).toByte(),
            0,
            0,
        )
    }

    private fun requireTargetMac(): ByteArray {
        return targetMac ?: throw AoEException("AoE target has not been discovered")
    }

    private fun tag(): Int {
        val current = nextTag
        nextTag++
        if (nextTag == 0) {
            nextTag = 1
        }
        return current
    }

    override fun close() {
        transport.close()
    }

    private class ImageReader(private val file: File) : Closeable {
        private val input: InputStream = BufferedInputStream(
            if (file.name.endsWith(".lz4")) {
                lz4Input(file)
            } else {
                FileInputStream(file)
            },
        )

        fun readExact(size: Int): ByteArray {
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(buffer, offset, size - offset)
                if (read < 0) {
                    throw AoEException("short image read from $file: wanted $size, got $offset")
                }
                offset += read
            }
            return buffer
        }

        fun readBlock(size: Int): ByteArray {
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(buffer, offset, size - offset)
                if (read < 0) {
                    break
                }
                offset += read
            }
            return buffer.copyOf(offset)
        }

        fun skipExact(size: Long) {
            var remaining = size
            val scratch = ByteArray(1024 * 1024)
            while (remaining > 0) {
                val read = input.read(scratch, 0, min(scratch.size.toLong(), remaining).toInt())
                if (read < 0) {
                    throw AoEException("short image skip from $file")
                }
                remaining -= read
            }
        }

        override fun close() {
            input.close()
        }

        companion object {
            private fun lz4Input(file: File): InputStream {
                val input = FileInputStream(file)
                val magic = input.readNBytes(4)
                if (magic.size < 4) {
                    input.close()
                    throw IOException("short LZ4 stream header in $file")
                }
                return when {
                    magic.contentEquals(LZ4_FRAME_MAGIC) ->
                        LZ4FrameInputStream(SequenceInputStream(ByteArrayInputStream(magic), input))
                    magic.contentEquals(LZ4_LEGACY_MAGIC) ->
                        LegacyLz4InputStream(input)
                    else -> {
                        input.close()
                        throw IOException(
                            "unsupported LZ4 stream magic " +
                                magic.joinToString(" ") { "%02x".format(it.toInt() and 0xff) },
                        )
                    }
                }
            }

            private val LZ4_FRAME_MAGIC = byteArrayOf(0x04, 0x22, 0x4d, 0x18)
            private val LZ4_LEGACY_MAGIC = byteArrayOf(0x02, 0x21, 0x4c, 0x18)
        }
    }

    private class LegacyLz4InputStream(
        private val input: InputStream,
    ) : InputStream() {
        private val decompressor = LZ4Factory.fastestInstance().safeDecompressor()
        private var buffer = ByteArray(0)
        private var position = 0
        private var closed = false
        private var eof = false

        override fun read(): Int {
            if (!ensureBuffer()) {
                return -1
            }
            return buffer[position++].toInt() and 0xff
        }

        override fun read(target: ByteArray, off: Int, len: Int): Int {
            if (len == 0) {
                return 0
            }
            if (!ensureBuffer()) {
                return -1
            }
            val count = min(len, buffer.size - position)
            buffer.copyInto(target, off, position, position + count)
            position += count
            return count
        }

        override fun close() {
            if (closed) {
                return
            }
            closed = true
            input.close()
        }

        private fun ensureBuffer(): Boolean {
            while (position >= buffer.size && !eof) {
                readBlock()
            }
            return position < buffer.size
        }

        private fun readBlock() {
            val size = readLegacyBlockSize()
            if (size == null || size == 0) {
                eof = true
                buffer = ByteArray(0)
                position = 0
                return
            }
            if (size < 0 || size > LEGACY_MAX_COMPRESSED_BLOCK) {
                throw IOException("invalid legacy LZ4 block size: $size")
            }

            val compressed = input.readNBytes(size)
            if (compressed.size != size) {
                throw IOException("short legacy LZ4 block: wanted $size, got ${compressed.size}")
            }
            val decoded = ByteArray(LEGACY_MAX_DECOMPRESSED_BLOCK)
            val decodedSize = decompressor.decompress(compressed, 0, compressed.size, decoded, 0, decoded.size)
            buffer = decoded.copyOf(decodedSize)
            position = 0
        }

        private fun readLegacyBlockSize(): Int? {
            val bytes = ByteArray(4)
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) {
                    if (offset == 0) {
                        return null
                    }
                    throw IOException("short legacy LZ4 block size")
                }
                offset += read
            }
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
        }

        companion object {
            private const val LEGACY_MAX_DECOMPRESSED_BLOCK = 8 * 1024 * 1024
            private const val LEGACY_MAX_COMPRESSED_BLOCK = LEGACY_MAX_DECOMPRESSED_BLOCK + (LEGACY_MAX_DECOMPRESSED_BLOCK / 255) + 16
        }
    }

    private data class AoEResponse(
        val source: ByteArray,
        val major: Int,
        val minor: Int,
        val command: Int,
        val tag: Int,
        val payload: ByteArray,
        val aoeError: Int,
    )

    private data class AoERequest(
        val tag: Int,
        val frame: ByteArray,
        val bytes: Int,
    )

    private data class PendingAoE(
        val frame: ByteArray,
        val sentAtNanos: Long,
        val retriesLeft: Int,
        val bytes: Int,
    )

    private class ByteProgress(
        private val phase: String,
        private val total: Long,
        private val onProgress: (AoEProgress) -> Unit,
    ) {
        var done = 0L
            private set
        private var lastEmit = 0L

        fun add(bytes: Int) {
            done += bytes
            val now = System.currentTimeMillis()
            if (now - lastEmit >= 250 || done >= total) {
                lastEmit = now
                onProgress(AoEProgress(phase, done, total))
            }
        }

        fun force() {
            onProgress(AoEProgress(phase, done, total))
        }
    }

    companion object {
        const val ETH_P_AOE = 0x88A2
        const val AOE_HVER = 0x10
        const val AOEFL_RSP = 1 shl 3
        const val AOEFL_ERR = 1 shl 2
        const val AOEAFL_EXT = 1 shl 6
        const val AOEAFL_WRITE = 1
        const val AOECMD_ATA = 0
        const val AOECMD_CFG = 1
        const val AOECCMD_READ = 0
        const val AOE_SECTOR_SIZE = 512L
        const val AOE_MAX_SECTORS = 2
        const val AOE_MAX_DATA = AOE_SECTOR_SIZE.toInt() * AOE_MAX_SECTORS
        const val AOE_DEFAULT_WINDOW = 128
        const val ATA_CMD_READ_EXT = 0x25
        const val ATA_CMD_PIO_READ_EXT = 0x24
        const val ATA_CMD_WRITE_EXT = 0x35
        const val ATA_CMD_FLUSH_EXT = 0xea
        const val ATA_CMD_ID_ATA = 0xec
        const val ATA_DRDY = 0x40
        const val ATA_ERR = 0x01

        fun open(interfaceName: String, timeoutMillis: Int = 2_000): AoEFlasher {
            return AoEFlasher(EthernetFrameTransport.open(interfaceName, ETH_P_AOE, timeoutMillis), timeoutMillis = timeoutMillis)
        }

        fun uncompressedSize(image: File): Long? {
            if (!image.name.endsWith(".lz4")) {
                return image.length()
            }
            FileInputStream(image).use { input ->
                val header = input.readNBytes(14)
                if (header.size < 14) {
                    return null
                }
                val magic = byteArrayOf(0x04, 0x22, 0x4d, 0x18)
                if (!header.copyOfRange(0, 4).contentEquals(magic)) {
                    return null
                }
                val flg = header[4].toInt() and 0xff
                if ((flg and 0x08) == 0) {
                    return null
                }
                return ByteBuffer.wrap(header, 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
            }
        }
    }
}

private fun ByteArray.u16(offset: Int): Int {
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

private fun ByteArray.s32(offset: Int): Int {
    return ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).int
}
