package dev.armin.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class ConnectRequest(val host: String, val port: Int, val httpVersion: String)

enum class ConnectRequestFailure {
    MALFORMED_REQUEST,
    UNSUPPORTED_METHOD,
    HEADER_TOO_LARGE,
}

class ConnectRequestException(
    val failure: ConnectRequestFailure,
    message: String,
) : IOException(message)

/** Parses exactly one HTTP CONNECT header without consuming bytes after its CRLF terminator. */
class ConnectRequestParser(private val maxHeaderBytes: Int = 16 * 1024) {
    init {
        require(maxHeaderBytes >= HEADER_TERMINATOR.size) {
            "maxHeaderBytes must fit an empty HTTP header"
        }
    }

    @Throws(IOException::class)
    fun read(input: InputStream): ConnectRequest {
        val header = ByteArrayOutputStream(minOf(maxHeaderBytes, 1024))
        var terminatorState = 0

        while (true) {
            if (header.size() == maxHeaderBytes) {
                throw ConnectRequestException(
                    ConnectRequestFailure.HEADER_TOO_LARGE,
                    "CONNECT header exceeds the configured limit",
                )
            }

            val next = input.read()
            if (next < 0) {
                throw ConnectRequestException(
                    ConnectRequestFailure.MALFORMED_REQUEST,
                    "CONNECT header ended before its terminator",
                )
            }
            header.write(next)
            terminatorState = advanceTerminatorState(terminatorState, next)
            if (terminatorState == HEADER_TERMINATOR.size) break
        }

        return parse(header.toByteArray())
    }

    fun parse(headerBytes: ByteArray): ConnectRequest {
        if (headerBytes.size > maxHeaderBytes) {
            throw ConnectRequestException(
                ConnectRequestFailure.HEADER_TOO_LARGE,
                "CONNECT header exceeds the configured limit",
            )
        }
        if (!headerBytes.endsWith(HEADER_TERMINATOR)) {
            throw ConnectRequestException(
                ConnectRequestFailure.MALFORMED_REQUEST,
                "CONNECT header has no CRLF terminator",
            )
        }
        if (headerBytes.any { it == 0.toByte() }) {
            throw malformed("CONNECT header contains a NUL byte")
        }

        val header = String(headerBytes, StandardCharsets.ISO_8859_1)
        val lines = header.removeSuffix("\r\n\r\n").split("\r\n")
        if (lines.isEmpty() || lines.first().isEmpty()) {
            throw malformed("CONNECT request line is empty")
        }
        validateHeaders(lines.drop(1))

        val requestParts = lines.first().split(HTTP_WHITESPACE).filter(String::isNotEmpty)
        if (requestParts.size != 3) throw malformed("CONNECT request line is malformed")
        if (requestParts[0] != "CONNECT") {
            throw ConnectRequestException(
                ConnectRequestFailure.UNSUPPORTED_METHOD,
                "Only HTTP CONNECT is supported",
            )
        }

        val version = requestParts[2]
        if (version != "HTTP/1.0" && version != "HTTP/1.1") {
            throw malformed("Unsupported HTTP version")
        }
        val (host, port) = parseAuthority(requestParts[1])
        return ConnectRequest(host, port, version)
    }

    private fun validateHeaders(lines: List<String>) {
        for (line in lines) {
            if (line.isEmpty()) throw malformed("Unexpected empty header line")
            val colon = line.indexOf(':')
            if (colon <= 0 || !line.substring(0, colon).all(::isTokenCharacter)) {
                throw malformed("Malformed HTTP header field")
            }
            if (line.substring(colon + 1).any { it == '\r' || it == '\n' }) {
                throw malformed("Malformed HTTP header value")
            }
        }
    }

    private fun parseAuthority(authority: String): Pair<String, Int> {
        if (authority.isEmpty() || authority.any(::isForbiddenAuthorityCharacter)) {
            throw malformed("CONNECT authority is invalid")
        }

        val host: String
        val portText: String
        if (authority.startsWith('[')) {
            val closingBracket = authority.indexOf(']')
            if (closingBracket <= 1 || closingBracket + 1 >= authority.length) {
                throw malformed("Bracketed CONNECT authority is invalid")
            }
            if (authority[closingBracket + 1] != ':' || authority.indexOf('[', 1) >= 0) {
                throw malformed("Bracketed CONNECT authority is invalid")
            }
            host = authority.substring(1, closingBracket)
            portText = authority.substring(closingBracket + 2)
            if (!host.contains(':') || !host.all(::isIpv6LiteralCharacter)) {
                throw malformed("Bracketed host is not an IPv6 literal")
            }
        } else {
            val colon = authority.indexOf(':')
            if (colon <= 0 || colon != authority.lastIndexOf(':')) {
                throw malformed("CONNECT authority must contain host and port")
            }
            host = authority.substring(0, colon)
            portText = authority.substring(colon + 1)
            if (!host.all(::isHostnameCharacter)) {
                throw malformed("CONNECT hostname is invalid")
            }
        }

        if (portText.isEmpty() || !portText.all(Char::isDigit)) {
            throw malformed("CONNECT port is invalid")
        }
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) throw malformed("CONNECT port is out of range")
        return host to port
    }

    private fun malformed(message: String) =
        ConnectRequestException(ConnectRequestFailure.MALFORMED_REQUEST, message)

    private fun advanceTerminatorState(current: Int, next: Int): Int =
        when (current) {
            0 -> if (next == CR) 1 else 0
            1 ->
                when (next) {
                    LF -> 2
                    CR -> 1
                    else -> 0
                }
            2 -> if (next == CR) 3 else 0
            3 ->
                when (next) {
                    LF -> 4
                    CR -> 1
                    else -> 0
                }
            else -> current
        }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
        if (size < suffix.size) return false
        for (index in suffix.indices) {
            if (this[size - suffix.size + index] != suffix[index]) return false
        }
        return true
    }

    private fun isForbiddenAuthorityCharacter(character: Char): Boolean =
        character.code <= 0x20 || character.code >= 0x7f || character in "/?#@\\"

    private fun isHostnameCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '.' || character == '-' || character == '_'

    private fun isIpv6LiteralCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == ':' || character == '.' || character == '%' ||
            character == '-' || character == '_'

    private fun isTokenCharacter(character: Char): Boolean =
        character.code in 0x21..0x7e && character !in "()<>@,;:\\\"/[]?={} \t"

    private companion object {
        const val CR = '\r'.code
        const val LF = '\n'.code
        val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val HTTP_WHITESPACE = Regex("[ \\t]+")
    }
}
