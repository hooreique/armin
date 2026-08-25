package dev.armin.proxy

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TlsClientHelloParserTest {
    private val parser = TlsClientHelloParser()

    @Test
    fun parsesSniAndReportsItsEncodedByteRange() {
        val encoded = TlsTestData.encodedClientHello("www.example.test")

        val hello = parsed(parser.parse(encoded))

        assertEquals("www.example.test", hello.serverName)
        assertArrayEquals(
            "www.example.test".toByteArray(Charsets.US_ASCII),
            bytesAt(encoded, hello.serverNameByteRanges),
        )
    }

    @Test
    fun parsesClientHelloWithoutSni() {
        val hello = parsed(parser.parse(TlsTestData.encodedClientHello(serverName = null)))

        assertNull(hello.serverName)
        assertTrue(hello.serverNameByteRanges.isEmpty())
    }

    @Test
    fun parsesClientHelloSplitAcrossReadsAndTlsRecords() {
        val hostname = "fragmented.example.test"
        val handshake = TlsTestData.clientHelloHandshake(hostname)
        val hostnameOffset =
            TlsTestData.findSubsequence(handshake, hostname.toByteArray(Charsets.US_ASCII))
        val encoded =
            TlsTestData.encodeHandshake(
                handshake,
                listOf(hostnameOffset + 4, 3, 2),
            )
        val input = ChunkedInputStream(ByteArrayInputStream(encoded), maxChunk = 2)

        val captured = TlsClientHelloReader().read(input)
        val hello = parsed(captured.parseResult)

        assertArrayEquals(encoded, captured.bytes)
        assertEquals(hostname, hello.serverName)
        assertTrue(hello.serverNameByteRanges.size >= 2)
        assertArrayEquals(
            hostname.toByteArray(Charsets.US_ASCII),
            bytesAt(encoded, hello.serverNameByteRanges),
        )
    }

    @Test
    fun rejectsBadExtensionLength() {
        val handshake = TlsTestData.clientHelloHandshake()
        // The extension-vector length is at offset 45 in this minimal fixture.
        handshake[46] = (handshake[46].toInt() + 1).toByte()
        val result = parser.parse(TlsTestData.encodeHandshake(handshake, emptyList()))

        assertEquals(
            ClientHelloFailure.MALFORMED_EXTENSION,
            failure(result),
        )
    }

    @Test
    fun rejectsTruncatedInput() {
        val encoded = TlsTestData.encodedClientHello()

        val result = parser.parse(encoded.copyOf(encoded.size - 3))

        assertEquals(ClientHelloFailure.TRUNCATED_INPUT, failure(result))
    }

    @Test
    fun enforcesConfiguredMaximumWithoutBufferingRecordPayload() {
        val encoded = TlsTestData.encodedClientHello()
        val reader =
            TlsClientHelloReader(
                parser = TlsClientHelloParser(maxEncodedBytes = 32),
                maxBufferedBytes = 32,
            )

        val captured = reader.read(ByteArrayInputStream(encoded))

        assertEquals(ClientHelloFailure.TOO_LARGE, failure(captured.parseResult))
        assertEquals(5, captured.bytes.size)
        assertArrayEquals(encoded.copyOfRange(0, 5), captured.bytes)
    }

    @Test
    fun nonHandshakeRecordUsesFallbackResult() {
        val encoded = byteArrayOf(23, 3, 3, 0, 3, 1, 2, 3)

        assertEquals(ClientHelloFailure.NOT_TLS_HANDSHAKE, failure(parser.parse(encoded)))
    }

    private fun parsed(result: ClientHelloParseResult): TlsClientHello =
        when (result) {
            is ClientHelloParseResult.Parsed -> result.clientHello
            is ClientHelloParseResult.Failure -> {
                fail("Expected parsed ClientHello, got ${result.reason}")
                error("unreachable")
            }
        }

    private fun failure(result: ClientHelloParseResult): ClientHelloFailure =
        when (result) {
            is ClientHelloParseResult.Failure -> result.reason
            is ClientHelloParseResult.Parsed -> {
                fail("Expected parse failure")
                error("unreachable")
            }
        }

    private fun bytesAt(encoded: ByteArray, ranges: List<IntRange>): ByteArray =
        ranges.flatMap { range -> range.map(encoded::get) }.toByteArray()
}
