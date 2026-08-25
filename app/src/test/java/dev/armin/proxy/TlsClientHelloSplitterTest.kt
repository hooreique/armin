package dev.armin.proxy

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsClientHelloSplitterTest {
    private val parser = TlsClientHelloParser()
    private val splitter = TlsClientHelloSplitter(sniChunkSize = 1)

    @Test
    fun segmentsReassembleToExactlyTheOriginalRecords() {
        val encoded = TlsTestData.encodedClientHello("example.test")
        val hello = parsed(encoded)

        val segments = splitter.split(encoded, hello)

        assertArrayEquals(encoded, segments.reduce(ByteArray::plus))
        assertFalse(segments.any(ByteArray::isEmpty))
    }

    @Test
    fun eachSniHostnameByteGetsItsOwnSegment() {
        val hostname = "split.example"
        val encoded = TlsTestData.encodedClientHello(hostname)
        val hello = parsed(encoded)

        val segments = splitter.split(encoded, hello)

        val hostnameSegments = segments.drop(1).take(hostname.length)
        assertEquals(hostname.length, hostnameSegments.size)
        hostnameSegments.forEach { assertEquals(1, it.size) }
        assertArrayEquals(
            hostname.toByteArray(Charsets.US_ASCII),
            hostnameSegments.reduce(ByteArray::plus),
        )
    }

    @Test
    fun splitAcrossRecordBoundariesRemainsLossless() {
        val hostname = "records.example"
        val handshake = TlsTestData.clientHelloHandshake(hostname)
        val hostnameOffset =
            TlsTestData.findSubsequence(handshake, hostname.toByteArray(Charsets.US_ASCII))
        val encoded = TlsTestData.encodeHandshake(handshake, listOf(hostnameOffset + 2, 2, 1))
        val hello = parsed(encoded)

        val segments = splitter.split(encoded, hello)

        assertTrue(hello.serverNameByteRanges.size > 1)
        assertArrayEquals(encoded, segments.reduce(ByteArray::plus))
        assertFalse(segments.any(ByteArray::isEmpty))
    }

    @Test
    fun missingOrInvalidSniRangeFallsBackToOneUnmodifiedWrite() {
        val encoded = TlsTestData.encodedClientHello(serverName = null)
        val noSni = parsed(encoded)
        val invalid = TlsClientHello("bad", listOf(999..1001), 10)

        assertEquals(1, splitter.split(encoded, noSni).size)
        assertArrayEquals(encoded, splitter.split(encoded, noSni).single())
        assertEquals(1, splitter.split(encoded, invalid).size)
        assertArrayEquals(encoded, splitter.split(encoded, invalid).single())
        assertEquals(1, splitter.split(encoded, null).size)
    }

    @Test
    fun writeToUsesTheComputedSegmentPlan() {
        val hostname = "write.example"
        val encoded = TlsTestData.encodedClientHello(hostname)
        val hello = parsed(encoded)
        val output = RecordingOutputStream()

        val count = splitter.writeTo(output, encoded, hello)

        assertEquals(output.writes.size, count)
        assertTrue(count >= hostname.length + 1)
        assertArrayEquals(encoded, output.toByteArray())
        assertFalse(output.writes.any(ByteArray::isEmpty))
    }

    private fun parsed(encoded: ByteArray): TlsClientHello =
        (parser.parse(encoded) as ClientHelloParseResult.Parsed).clientHello

    private class RecordingOutputStream : ByteArrayOutputStream() {
        val writes = mutableListOf<ByteArray>()

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writes += bytes.copyOfRange(offset, offset + length)
            super.write(bytes, offset, length)
        }
    }
}
