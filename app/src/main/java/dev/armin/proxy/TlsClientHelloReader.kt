package dev.armin.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

data class CapturedClientHello(
    val bytes: ByteArray,
    val parseResult: ClientHelloParseResult,
)

/** Bounded accumulator for the first ClientHello carried by one or more TLS records. */
class TlsClientHelloReader(
    private val parser: TlsClientHelloParser = TlsClientHelloParser(),
    private val maxBufferedBytes: Int = 64 * 1024,
    private val maxRecords: Int = 32,
) {
    init {
        require(maxBufferedBytes >= TLS_RECORD_HEADER_SIZE) {
            "maxBufferedBytes must fit a TLS record header"
        }
        require(maxRecords > 0) { "maxRecords must be positive" }
    }

    @Throws(IOException::class)
    fun read(input: InputStream): CapturedClientHello {
        val captured = ByteArrayOutputStream(minOf(maxBufferedBytes, 4096))
        var records = 0

        while (records < maxRecords) {
            if (captured.size() > maxBufferedBytes - TLS_RECORD_HEADER_SIZE) {
                return fallback(captured, ClientHelloFailure.TOO_LARGE)
            }

            when (readInto(input, captured, TLS_RECORD_HEADER_SIZE)) {
                ReadOutcome.COMPLETE -> Unit
                ReadOutcome.END_OF_STREAM ->
                    return fallback(captured, ClientHelloFailure.TRUNCATED_INPUT)
                ReadOutcome.TIMEOUT ->
                    return fallback(captured, ClientHelloFailure.READ_TIMEOUT)
            }

            val current = captured.toByteArray()
            val headerOffset = current.size - TLS_RECORD_HEADER_SIZE
            val recordLength =
                ((current[headerOffset + 3].toInt() and 0xff) shl 8) or
                    (current[headerOffset + 4].toInt() and 0xff)
            if (recordLength > maxBufferedBytes - captured.size()) {
                return fallback(captured, ClientHelloFailure.TOO_LARGE)
            }

            when (readInto(input, captured, recordLength)) {
                ReadOutcome.COMPLETE -> Unit
                ReadOutcome.END_OF_STREAM ->
                    return fallback(captured, ClientHelloFailure.TRUNCATED_INPUT)
                ReadOutcome.TIMEOUT ->
                    return fallback(captured, ClientHelloFailure.READ_TIMEOUT)
            }
            records += 1

            val encoded = captured.toByteArray()
            when (val inspection = parser.inspect(encoded)) {
                is TlsClientHelloParser.Inspection.Complete ->
                    return CapturedClientHello(
                        encoded,
                        ClientHelloParseResult.Parsed(inspection.clientHello),
                    )
                is TlsClientHelloParser.Inspection.Invalid ->
                    return CapturedClientHello(
                        encoded,
                        ClientHelloParseResult.Failure(inspection.reason),
                    )
                TlsClientHelloParser.Inspection.NeedMore -> Unit
            }
        }

        return fallback(captured, ClientHelloFailure.TOO_MANY_RECORDS)
    }

    private fun readInto(
        input: InputStream,
        destination: ByteArrayOutputStream,
        byteCount: Int,
    ): ReadOutcome {
        var remaining = byteCount
        val buffer = ByteArray(minOf(COPY_BUFFER_SIZE, maxOf(1, byteCount)))
        while (remaining > 0) {
            val count =
                try {
                    input.read(buffer, 0, minOf(buffer.size, remaining))
                } catch (_: SocketTimeoutException) {
                    return ReadOutcome.TIMEOUT
                }
            if (count < 0) return ReadOutcome.END_OF_STREAM
            if (count == 0) continue
            destination.write(buffer, 0, count)
            remaining -= count
        }
        return ReadOutcome.COMPLETE
    }

    private fun fallback(
        captured: ByteArrayOutputStream,
        failure: ClientHelloFailure,
    ): CapturedClientHello =
        CapturedClientHello(
            captured.toByteArray(),
            ClientHelloParseResult.Failure(failure),
        )

    private enum class ReadOutcome {
        COMPLETE,
        END_OF_STREAM,
        TIMEOUT,
    }

    private companion object {
        const val TLS_RECORD_HEADER_SIZE = 5
        const val COPY_BUFFER_SIZE = 8 * 1024
    }
}
