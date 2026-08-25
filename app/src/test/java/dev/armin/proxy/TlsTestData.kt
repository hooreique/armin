package dev.armin.proxy

import java.io.ByteArrayOutputStream

internal object TlsTestData {
    fun encodedClientHello(
        serverName: String? = "example.test",
        recordPayloadSizes: List<Int> = emptyList(),
    ): ByteArray = encodeHandshake(clientHelloHandshake(serverName), recordPayloadSizes)

    fun clientHelloHandshake(serverName: String? = "example.test"): ByteArray {
        val extensions = ByteArrayOutputStream()
        if (serverName != null) {
            val hostname = serverName.toByteArray(Charsets.US_ASCII)
            val serverNameList = ByteArrayOutputStream()
            serverNameList.writeUnsignedShort(1 + 2 + hostname.size)
            serverNameList.write(0)
            serverNameList.writeUnsignedShort(hostname.size)
            serverNameList.write(hostname)

            extensions.writeUnsignedShort(0)
            extensions.writeUnsignedShort(serverNameList.size())
            extensions.write(serverNameList.toByteArray())
        }

        // supported_versions: TLS 1.3
        extensions.writeUnsignedShort(43)
        extensions.writeUnsignedShort(3)
        extensions.write(2)
        extensions.write(byteArrayOf(3, 4))

        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(3, 3))
        body.write(ByteArray(32) { it.toByte() })
        body.write(0) // legacy_session_id
        body.writeUnsignedShort(2)
        body.write(byteArrayOf(0x13, 0x01))
        body.write(1)
        body.write(0)
        body.writeUnsignedShort(extensions.size())
        body.write(extensions.toByteArray())

        return ByteArrayOutputStream()
            .apply {
                write(1)
                writeUnsignedMedium(body.size())
                write(body.toByteArray())
            }
            .toByteArray()
    }

    fun encodeHandshake(handshake: ByteArray, recordPayloadSizes: List<Int>): ByteArray {
        val encoded = ByteArrayOutputStream()
        var cursor = 0
        for (requestedSize in recordPayloadSizes) {
            if (cursor == handshake.size) break
            require(requestedSize > 0)
            val size = minOf(requestedSize, handshake.size - cursor)
            encoded.writeRecord(handshake, cursor, size)
            cursor += size
        }
        if (cursor < handshake.size) {
            encoded.writeRecord(handshake, cursor, handshake.size - cursor)
        }
        return encoded.toByteArray()
    }

    fun findSubsequence(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { offset -> haystack[start + offset] == needle[offset] }) {
                return start
            }
        }
        return -1
    }

    private fun ByteArrayOutputStream.writeRecord(bytes: ByteArray, offset: Int, size: Int) {
        write(22)
        write(byteArrayOf(3, 1))
        writeUnsignedShort(size)
        write(bytes, offset, size)
    }

    private fun ByteArrayOutputStream.writeUnsignedShort(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeUnsignedMedium(value: Int) {
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }
}
