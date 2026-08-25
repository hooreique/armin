package dev.armin.browser

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

sealed interface UrlNormalizationResult {
    data object Empty : UrlNormalizationResult

    data class Valid(val url: String) : UrlNormalizationResult

    data class Invalid(val reason: InvalidUrlReason) : UrlNormalizationResult
}

enum class InvalidUrlReason {
    UNSUPPORTED_SCHEME,
    MALFORMED_URL,
    MISSING_HOST,
    INVALID_PORT,
}

/** Strict normalization for user-initiated top-level navigation. */
object UrlNormalizer {
    private val schemePrefix = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val numericPortSuffix = Regex("^[0-9]{1,5}(?:[/?#].*)?$")
    private val forbiddenOpaqueSchemes =
        setOf(
            "http",
            "file",
            "content",
            "intent",
            "javascript",
            "data",
            "mailto",
            "tel",
            "about",
            "blob",
        )

    fun normalize(input: String): UrlNormalizationResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return UrlNormalizationResult.Empty
        if (trimmed.any { it.isISOControl() }) {
            return UrlNormalizationResult.Invalid(InvalidUrlReason.MALFORMED_URL)
        }

        val candidate = buildCandidate(trimmed) ?: return unsupportedScheme()
        val uri =
            try {
                URI(candidate).parseServerAuthority()
            } catch (_: URISyntaxException) {
                return UrlNormalizationResult.Invalid(InvalidUrlReason.MALFORMED_URL)
            }

        if (!uri.scheme.equals(HTTPS_SCHEME, ignoreCase = true)) return unsupportedScheme()
        if (uri.isOpaque || uri.userInfo != null) {
            return UrlNormalizationResult.Invalid(InvalidUrlReason.MALFORMED_URL)
        }
        if (uri.host.isNullOrBlank()) {
            return UrlNormalizationResult.Invalid(InvalidUrlReason.MISSING_HOST)
        }
        if (uri.port == 0 || uri.port > MAX_PORT) {
            return UrlNormalizationResult.Invalid(InvalidUrlReason.INVALID_PORT)
        }

        val ascii = uri.toASCIIString()
        val normalizedScheme = HTTPS_SCHEME + ascii.substring(uri.scheme.length)
        return UrlNormalizationResult.Valid(normalizedScheme)
    }

    /** HTTPS is omitted only for presentation; non-HTTPS pending targets remain unmodified. */
    fun displayText(url: String): String =
        if (url.regionMatches(0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length, ignoreCase = true)) {
            url.substring(HTTPS_PREFIX.length)
        } else {
            url
        }

    private fun buildCandidate(input: String): String? {
        val match = schemePrefix.find(input) ?: return HTTPS_PREFIX + input
        val scheme = match.groupValues[1]
        val suffix = input.substring(match.range.last + 1)

        // URI treats "example.com:8443" as an opaque URI. For browser input, a numeric
        // suffix is a port and the entire string is an authority without a scheme.
        if (
            !input.startsWith("$scheme://") &&
                numericPortSuffix.matches(suffix) &&
                scheme.lowercase(Locale.ROOT) !in forbiddenOpaqueSchemes
        ) {
            return HTTPS_PREFIX + input
        }
        if (!scheme.lowercase(Locale.ROOT).equals(HTTPS_SCHEME)) return null
        return input
    }

    private fun unsupportedScheme() =
        UrlNormalizationResult.Invalid(InvalidUrlReason.UNSUPPORTED_SCHEME)

    private const val HTTPS_SCHEME = "https"
    private const val HTTPS_PREFIX = "https://"
    private const val MAX_PORT = 65_535
}
