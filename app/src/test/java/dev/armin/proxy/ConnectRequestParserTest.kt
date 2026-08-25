package dev.armin.proxy

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ConnectRequestParserTest {
    private val parser = ConnectRequestParser()

    @Test
    fun parsesHostnameAndDefaultHttpsPort() {
        val request = parser.read(request("example.com:443"))

        assertEquals("example.com", request.host)
        assertEquals(443, request.port)
        assertEquals("HTTP/1.1", request.httpVersion)
    }

    @Test
    fun parsesNonStandardPort() {
        val request = parser.read(request("example.com:8443", version = "HTTP/1.0"))

        assertEquals("example.com", request.host)
        assertEquals(8443, request.port)
        assertEquals("HTTP/1.0", request.httpVersion)
    }

    @Test
    fun parsesBracketedIpv6() {
        val request = parser.read(request("[2001:db8::7]:9443"))

        assertEquals("2001:db8::7", request.host)
        assertEquals(9443, request.port)
    }

    @Test
    fun rejectsMalformedRequestLine() {
        expectFailure(ConnectRequestFailure.MALFORMED_REQUEST) {
            parser.read(stream("CONNECT example.com HTTP/1.1\r\n\r\n"))
        }
        expectFailure(ConnectRequestFailure.UNSUPPORTED_METHOD) {
            parser.read(stream("GET example.com:443 HTTP/1.1\r\n\r\n"))
        }
        expectFailure(ConnectRequestFailure.MALFORMED_REQUEST) {
            parser.read(stream("CONNECT 2001:db8::7:443 HTTP/1.1\r\n\r\n"))
        }
        expectFailure(ConnectRequestFailure.MALFORMED_REQUEST) {
            parser.read(stream("CONNECT example.com:0 HTTP/1.1\r\n\r\n"))
        }
    }

    @Test
    fun rejectsHeaderBeyondConfiguredLimit() {
        val limited = ConnectRequestParser(maxHeaderBytes = 64)
        val oversized = "CONNECT example.com:443 HTTP/1.1\r\nX-Padding: ${"x".repeat(80)}\r\n\r\n"

        expectFailure(ConnectRequestFailure.HEADER_TOO_LARGE) {
            limited.read(stream(oversized))
        }
    }

    @Test
    fun acceptsHeaderArrivingOneByteAtATimeWithoutConsumingTunnelBytes() {
        val header =
            "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1)
        val input =
            ChunkedInputStream(
                ByteArrayInputStream(header + byteArrayOf(22, 3, 1)),
                maxChunk = 1,
            )

        val parsed = parser.read(input)

        assertEquals("example.com", parsed.host)
        assertEquals(22, input.read())
    }

    private fun request(authority: String, version: String = "HTTP/1.1"): InputStream =
        stream("CONNECT $authority $version\r\nHost: $authority\r\n\r\n")

    private fun stream(value: String): InputStream =
        ByteArrayInputStream(value.toByteArray(Charsets.ISO_8859_1))

    private fun expectFailure(
        expected: ConnectRequestFailure,
        action: () -> Unit,
    ) {
        try {
            action()
            fail("Expected ConnectRequestException")
        } catch (failure: ConnectRequestException) {
            assertEquals(expected, failure.failure)
        }
    }
}

internal class ChunkedInputStream(
    private val delegate: InputStream,
    private val maxChunk: Int,
) : InputStream() {
    override fun read(): Int = delegate.read()

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        delegate.read(bytes, offset, minOf(length, maxChunk))
}
