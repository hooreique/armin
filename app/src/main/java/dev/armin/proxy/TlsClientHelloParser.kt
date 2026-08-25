package dev.armin.proxy

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class TlsClientHello(
    val serverName: String?,
    /** Inclusive ranges in the original encoded TLS records. */
    val serverNameByteRanges: List<IntRange>,
    /** Length of the logical ClientHello handshake message, excluding TLS record headers. */
    val encodedHandshakeLength: Int,
)

enum class ClientHelloFailure {
    NOT_TLS_HANDSHAKE,
    NOT_CLIENT_HELLO,
    TRUNCATED_INPUT,
    MALFORMED_CLIENT_HELLO,
    MALFORMED_EXTENSION,
    TOO_LARGE,
    TOO_MANY_RECORDS,
    READ_TIMEOUT,
}

sealed interface ClientHelloParseResult {
    data class Parsed(val clientHello: TlsClientHello) : ClientHelloParseResult

    data class Failure(val reason: ClientHelloFailure) : ClientHelloParseResult
}

/**
 * Parses a ClientHello from one or more complete TLS handshake records.
 *
 * The parser retains a logical-handshake-byte to encoded-record-byte mapping, so an SNI value split
 * across TLS record boundaries can still be split without rewriting the records.
 */
class TlsClientHelloParser(private val maxEncodedBytes: Int = 64 * 1024) {
    init {
        require(maxEncodedBytes >= TLS_RECORD_HEADER_SIZE) {
            "maxEncodedBytes must fit a TLS record header"
        }
    }

    fun parse(recordBytes: ByteArray): ClientHelloParseResult =
        when (val inspection = inspect(recordBytes)) {
            is Inspection.Complete -> ClientHelloParseResult.Parsed(inspection.clientHello)
            is Inspection.Invalid -> ClientHelloParseResult.Failure(inspection.reason)
            Inspection.NeedMore ->
                ClientHelloParseResult.Failure(ClientHelloFailure.TRUNCATED_INPUT)
        }

    // TLS framing is deliberately validated branch-by-branch before any offset is dereferenced.
    @Suppress("CyclomaticComplexMethod")
    internal fun inspect(recordBytes: ByteArray): Inspection {
        if (recordBytes.size > maxEncodedBytes) {
            return Inspection.Invalid(ClientHelloFailure.TOO_LARGE)
        }

        val handshakeBytes = ByteArrayOutputStream(minOf(recordBytes.size, 4096))
        val encodedPositions = ArrayList<Int>(minOf(recordBytes.size, 4096))
        var encodedOffset = 0
        var expectedHandshakeLength: Int? = null

        while (true) {
            if (recordBytes.size - encodedOffset < TLS_RECORD_HEADER_SIZE) {
                return Inspection.NeedMore
            }
            if (unsigned(recordBytes[encodedOffset]) != HANDSHAKE_CONTENT_TYPE) {
                return Inspection.Invalid(ClientHelloFailure.NOT_TLS_HANDSHAKE)
            }
            if (unsigned(recordBytes[encodedOffset + 1]) != TLS_MAJOR_VERSION) {
                return Inspection.Invalid(ClientHelloFailure.NOT_TLS_HANDSHAKE)
            }

            val recordLength = readNetworkUnsignedShort(recordBytes, encodedOffset + 3)
            if (recordLength == 0) {
                return Inspection.Invalid(ClientHelloFailure.MALFORMED_CLIENT_HELLO)
            }
            val payloadOffset = encodedOffset + TLS_RECORD_HEADER_SIZE
            val recordEnd = payloadOffset.toLong() + recordLength
            if (recordEnd > recordBytes.size) return Inspection.NeedMore
            if (handshakeBytes.size().toLong() + recordLength > maxEncodedBytes) {
                return Inspection.Invalid(ClientHelloFailure.TOO_LARGE)
            }

            for (index in payloadOffset until recordEnd.toInt()) {
                handshakeBytes.write(unsigned(recordBytes[index]))
                encodedPositions += index
            }

            val logicalBytes = handshakeBytes.toByteArray()
            if (logicalBytes.size >= HANDSHAKE_HEADER_SIZE && expectedHandshakeLength == null) {
                if (unsigned(logicalBytes[0]) != CLIENT_HELLO_HANDSHAKE_TYPE) {
                    return Inspection.Invalid(ClientHelloFailure.NOT_CLIENT_HELLO)
                }
                val bodyLength = readUnsignedMedium(logicalBytes, 1)
                val totalLength = bodyLength.toLong() + HANDSHAKE_HEADER_SIZE
                if (totalLength > maxEncodedBytes) {
                    return Inspection.Invalid(ClientHelloFailure.TOO_LARGE)
                }
                expectedHandshakeLength = totalLength.toInt()
            }

            val expected = expectedHandshakeLength
            if (expected != null && logicalBytes.size >= expected) {
                return try {
                    Inspection.Complete(
                        parseCompleteClientHello(logicalBytes, encodedPositions, expected)
                    )
                } catch (failure: ClientHelloParseException) {
                    Inspection.Invalid(failure.reason)
                }
            }

            encodedOffset = recordEnd.toInt()
            if (encodedOffset == recordBytes.size) return Inspection.NeedMore
        }
    }

    private fun parseCompleteClientHello(
        bytes: ByteArray,
        encodedPositions: List<Int>,
        handshakeLength: Int,
    ): TlsClientHello {
        val cursor = Cursor(bytes, HANDSHAKE_HEADER_SIZE, handshakeLength)
        cursor.skip(2) // legacy_version
        cursor.skip(32) // random

        val sessionIdLength = cursor.readUnsignedByte()
        if (sessionIdLength > MAX_SESSION_ID_BYTES) malformedClientHello()
        cursor.skip(sessionIdLength)

        val cipherSuitesLength = cursor.readUnsignedShort()
        if (cipherSuitesLength == 0 || cipherSuitesLength % 2 != 0) malformedClientHello()
        cursor.skip(cipherSuitesLength)

        val compressionMethodsLength = cursor.readUnsignedByte()
        if (compressionMethodsLength == 0) malformedClientHello()
        cursor.skip(compressionMethodsLength)

        if (cursor.position == handshakeLength) {
            return TlsClientHello(null, emptyList(), handshakeLength)
        }

        val extensionsLength = cursor.readUnsignedShort()
        val extensionsEnd = cursor.position.toLong() + extensionsLength
        if (extensionsEnd != handshakeLength.toLong()) malformedExtension()
        val serverName = parseExtensions(bytes, encodedPositions, cursor, handshakeLength)
        return TlsClientHello(
            serverName?.name,
            serverName?.encodedRanges ?: emptyList(),
            handshakeLength,
        )
    }

    private fun parseExtensions(
        bytes: ByteArray,
        encodedPositions: List<Int>,
        cursor: Cursor,
        handshakeLength: Int,
    ): ParsedServerName? {
        var serverNameExtensionSeen = false
        var serverName: ParsedServerName? = null
        while (cursor.position < handshakeLength) {
            val extensionType = cursor.readUnsignedShort()
            val extensionLength = cursor.readUnsignedShort()
            val extensionEnd = cursor.position.toLong() + extensionLength
            if (extensionEnd > handshakeLength) malformedExtension()

            if (extensionType == SERVER_NAME_EXTENSION_TYPE) {
                if (serverNameExtensionSeen) malformedExtension()
                serverNameExtensionSeen = true
                serverName =
                    parseServerNameExtension(
                        bytes,
                        cursor.position,
                        extensionEnd.toInt(),
                        encodedPositions,
                    )
            }
            cursor.position = extensionEnd.toInt()
        }
        return serverName
    }

    private fun parseServerNameExtension(
        bytes: ByteArray,
        start: Int,
        end: Int,
        encodedPositions: List<Int>,
    ): ParsedServerName? {
        if (end - start < 2) malformedExtension()
        val namesLength = readNetworkUnsignedShort(bytes, start)
        val namesStart = start + 2
        if (namesStart.toLong() + namesLength != end.toLong()) malformedExtension()

        var cursor = namesStart
        var parsedName: ParsedServerName? = null
        while (cursor < end) {
            val entry = parseServerNameEntry(bytes, cursor, end, encodedPositions)
            if (entry.serverName != null) {
                if (parsedName != null) malformedExtension()
                parsedName = entry.serverName
            }
            cursor = entry.end
        }
        if (cursor != end) malformedExtension()
        return parsedName
    }

    private fun parseServerNameEntry(
        bytes: ByteArray,
        start: Int,
        limit: Int,
        encodedPositions: List<Int>,
    ): ParsedServerNameEntry {
        if (limit - start < 3) malformedExtension()
        val nameType = unsigned(bytes[start])
        val nameLength = readNetworkUnsignedShort(bytes, start + 1)
        val nameStart = start + 3
        val nameEnd = nameStart.toLong() + nameLength
        if (nameEnd > limit) malformedExtension()
        val parsed =
            if (nameType == HOST_NAME_TYPE) {
                parseHostName(bytes, nameStart, nameLength, encodedPositions)
            } else {
                null
            }
        return ParsedServerNameEntry(nameEnd.toInt(), parsed)
    }

    private fun parseHostName(
        bytes: ByteArray,
        start: Int,
        length: Int,
        encodedPositions: List<Int>,
    ): ParsedServerName {
        if (length !in 1..MAX_SERVER_NAME_BYTES) malformedExtension()
        for (index in start until start + length) {
            if (unsigned(bytes[index]) !in PRINTABLE_ASCII_RANGE) malformedExtension()
        }
        val name = String(bytes, start, length, StandardCharsets.US_ASCII)
        val positions =
            (start until start + length).map { logicalIndex ->
                encodedPositions.getOrElse(logicalIndex) { malformedExtension() }
            }
        return ParsedServerName(name, collapseContiguousPositions(positions))
    }

    private fun collapseContiguousPositions(positions: List<Int>): List<IntRange> {
        if (positions.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var start = positions.first()
        var previous = start
        for (position in positions.drop(1)) {
            if (position != previous + 1) {
                ranges += start..previous
                start = position
            }
            previous = position
        }
        ranges += start..previous
        return ranges
    }

    private class Cursor(
        private val bytes: ByteArray,
        var position: Int,
        private val limit: Int,
    ) {
        fun readUnsignedByte(): Int {
            requireAvailable(1)
            return unsigned(bytes[position++])
        }

        fun readUnsignedShort(): Int {
            requireAvailable(2)
            val value = readNetworkUnsignedShort(bytes, position)
            position += 2
            return value
        }

        fun skip(count: Int) {
            if (count < 0) malformedClientHello()
            requireAvailable(count)
            position += count
        }

        private fun requireAvailable(count: Int) {
            if (position.toLong() + count > limit) malformedClientHello()
        }
    }

    internal sealed interface Inspection {
        data class Complete(val clientHello: TlsClientHello) : Inspection

        data class Invalid(val reason: ClientHelloFailure) : Inspection

        data object NeedMore : Inspection
    }

    private data class ParsedServerName(val name: String, val encodedRanges: List<IntRange>)

    private data class ParsedServerNameEntry(val end: Int, val serverName: ParsedServerName?)

    private companion object {
        const val TLS_RECORD_HEADER_SIZE = 5
        const val HANDSHAKE_HEADER_SIZE = 4
        const val HANDSHAKE_CONTENT_TYPE = 22
        const val CLIENT_HELLO_HANDSHAKE_TYPE = 1
        const val TLS_MAJOR_VERSION = 3
        const val SERVER_NAME_EXTENSION_TYPE = 0
        const val HOST_NAME_TYPE = 0
        const val MAX_SESSION_ID_BYTES = 32
        const val MAX_SERVER_NAME_BYTES = 253
        val PRINTABLE_ASCII_RANGE = 0x21..0x7e
    }
}

private fun malformedClientHello(): Nothing =
    throw ClientHelloParseException(ClientHelloFailure.MALFORMED_CLIENT_HELLO)

private fun malformedExtension(): Nothing =
    throw ClientHelloParseException(ClientHelloFailure.MALFORMED_EXTENSION)

private class ClientHelloParseException(val reason: ClientHelloFailure) :
    RuntimeException(null, null, false, false)

private fun unsigned(byte: Byte): Int = byte.toInt() and 0xff

private fun readNetworkUnsignedShort(bytes: ByteArray, offset: Int): Int =
    (unsigned(bytes[offset]) shl 8) or unsigned(bytes[offset + 1])

private fun readUnsignedMedium(bytes: ByteArray, offset: Int): Int =
    (unsigned(bytes[offset]) shl 16) or
        (unsigned(bytes[offset + 1]) shl 8) or
        unsigned(bytes[offset + 2])
