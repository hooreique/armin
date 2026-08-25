package dev.armin.browser

internal data class DirectLinkMessage(val documentUrl: String, val targetUrl: String)

/** Strict wire format shared only by the isolated document-start world and native code. */
internal object DirectLinkMessageCodec {
    fun decode(payload: String, expectedNonce: String): DirectLinkMessage? {
        if (payload.length > MAX_PAYLOAD_CHARS) return null
        val fields = payload.split(DELIMITER, limit = FIELD_COUNT)
        if (fields.size != FIELD_COUNT || fields[0] != expectedNonce) return null
        val documentUrl = fields[1].takeIf(String::isNotBlank) ?: return null
        val targetUrl = fields[2].takeIf(String::isNotBlank) ?: return null
        return DirectLinkMessage(documentUrl, targetUrl)
    }

    private const val DELIMITER = '\n'
    private const val FIELD_COUNT = 3
    internal const val MAX_PAYLOAD_CHARS = 16_384
}
