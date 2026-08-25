package dev.armin.proxy

import java.io.InterruptedIOException
import java.io.OutputStream

/** Builds and writes a lossless split plan around the SNI hostname bytes. */
class TlsClientHelloSplitter(private val sniChunkSize: Int = 1) {
    init {
        require(sniChunkSize > 0) { "sniChunkSize must be positive" }
    }

    fun split(encodedRecords: ByteArray, clientHello: TlsClientHello?): List<ByteArray> {
        if (encodedRecords.isEmpty()) return emptyList()
        val ranges = clientHello?.serverNameByteRanges.orEmpty()
        if (!areValidRanges(ranges, encodedRecords.size)) return listOf(encodedRecords.copyOf())

        val hostnameBytes = ranges.sumOf { range -> range.last - range.first + 1 }
        val segments = ArrayList<ByteArray>(hostnameBytes + ranges.size + 1)
        var cursor = 0
        for (range in ranges) {
            if (cursor < range.first) {
                segments += encodedRecords.copyOfRange(cursor, range.first)
            }
            var chunkStart = range.first
            while (chunkStart <= range.last) {
                val chunkEnd = minOf(range.last + 1, chunkStart + sniChunkSize)
                segments += encodedRecords.copyOfRange(chunkStart, chunkEnd)
                chunkStart = chunkEnd
            }
            cursor = range.last + 1
        }
        if (cursor < encodedRecords.size) {
            segments += encodedRecords.copyOfRange(cursor, encodedRecords.size)
        }
        return segments.filter(ByteArray::isNotEmpty)
    }

    /** Returns the number of OutputStream.write calls made. */
    fun writeTo(
        output: OutputStream,
        encodedRecords: ByteArray,
        clientHello: TlsClientHello?,
        interSegmentDelayMillis: Long = 0,
    ): Int {
        require(interSegmentDelayMillis >= 0) { "interSegmentDelayMillis must not be negative" }
        val segments = split(encodedRecords, clientHello)
        for ((index, segment) in segments.withIndex()) {
            output.write(segment)
            output.flush()
            if (interSegmentDelayMillis > 0 && index < segments.lastIndex) {
                try {
                    Thread.sleep(interSegmentDelayMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted while splitting ClientHello").apply {
                        initCause(interrupted)
                    }
                }
            }
        }
        return segments.size
    }

    private fun areValidRanges(ranges: List<IntRange>, encodedSize: Int): Boolean {
        if (ranges.isEmpty()) return false
        var previousEnd = -1
        for (range in ranges) {
            if (range.isEmpty() || range.first <= previousEnd || range.last >= encodedSize) {
                return false
            }
            previousEnd = range.last
        }
        return true
    }
}
