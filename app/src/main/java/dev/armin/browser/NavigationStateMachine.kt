package dev.armin.browser

import java.net.URI
import java.util.Locale

data class NavigationSnapshot(
    val currentDocumentUrl: String,
    val pendingNavigationUrl: String?,
    val activeNavigationUrl: String?,
)

data class MainFrameNavigationRequest(
    val url: String,
    val hasGesture: Boolean,
    /** Null means that this WebView cannot expose redirect metadata. */
    val isRedirect: Boolean?,
    /** True only when the gesture hit an ordinary anchor in the current WebView. */
    val isDirectLink: Boolean = hasGesture,
)

sealed interface NavigationDecision {
    data object Allow : NavigationDecision

    data object Block : NavigationDecision

    data class BlockAndPresent(val pendingUrl: String) : NavigationDecision
}

/**
 * Main-frame navigation policy with a one-shot allowance for app-issued loads.
 *
 * All mutation happens on the WebView/UI thread. Volatile fields allow request interception
 * callbacks to read a coherent-enough top-level document snapshot without touching a View.
 */
class NavigationStateMachine(initialDocumentUrl: String = ABOUT_BLANK) {
    @Volatile private var currentDocumentUrl = initialDocumentUrl
    @Volatile private var pendingNavigationUrl: String? = null
    @Volatile private var activeNavigationUrl: String? = null
    private var appIssuedNavigationUrl: String? = null

    @Synchronized
    fun snapshot(): NavigationSnapshot =
        NavigationSnapshot(
            currentDocumentUrl = currentDocumentUrl,
            pendingNavigationUrl = pendingNavigationUrl,
            activeNavigationUrl = activeNavigationUrl,
        )

    /** Authorizes one app-issued load, including an address submission or history traversal. */
    @Synchronized
    fun beginAppIssuedNavigation(url: String) {
        require(isAllowedMainFrameScheme(url)) { "App-issued navigation must be HTTPS or internal" }
        appIssuedNavigationUrl = url
        activeNavigationUrl = url
        pendingNavigationUrl = null
    }

    @Synchronized
    fun evaluate(request: MainFrameNavigationRequest): NavigationDecision {
        if (request.isRedirect == true) return blockAndPresent(request.url)

        if (equivalent(request.url, appIssuedNavigationUrl)) {
            appIssuedNavigationUrl = null
            return accept(request.url)
        }

        if (request.hasGesture && request.isDirectLink) {
            return if (isAllowedMainFrameScheme(request.url)) {
                accept(request.url)
            } else {
                NavigationDecision.Block
            }
        }

        // A missing redirect bit is intentionally handled the same way as a false bit. Once the
        // one-shot app allowance is gone, gesture-less page-initiated movement is conservative.
        return blockAndPresent(request.url)
    }

    /** Consumes the one-shot allowance when app loadUrl() bypasses shouldOverrideUrlLoading(). */
    @Synchronized
    fun onPageStarted(url: String) {
        if (equivalent(url, appIssuedNavigationUrl)) appIssuedNavigationUrl = null
    }

    /**
     * Accepts a page callback only for the currently active target. A blocked redirect clears the
     * active target, so late callbacks from the abandoned load cannot overwrite pending state.
     */
    @Synchronized
    fun onPageEvent(url: String): Boolean {
        if (!equivalent(url, activeNavigationUrl)) return false
        currentDocumentUrl = url
        activeNavigationUrl = null
        return pendingNavigationUrl == null
    }

    /**
     * Accepts the URL that WebView reports as its currently committed history entry.
     *
     * A user-submitted POST may commit without going through shouldOverrideUrlLoading(). History
     * API calls cannot create a cross-origin entry, so an allowed callback that exactly matches
     * WebView's current URL is also the fail-closed way to synchronize such an actual commit.
     */
    @Synchronized
    fun onHistoryEvent(url: String, observedWebViewUrl: String?): Boolean {
        if (!equivalent(url, observedWebViewUrl) || pendingNavigationUrl != null) return false
        if (activeNavigationUrl != null) return onPageEvent(url)
        if (!isAllowedMainFrameScheme(url)) return false
        appIssuedNavigationUrl = null
        currentDocumentUrl = url
        return true
    }

    private fun accept(url: String): NavigationDecision {
        activeNavigationUrl = url
        pendingNavigationUrl = null
        return NavigationDecision.Allow
    }

    private fun blockAndPresent(url: String): NavigationDecision.BlockAndPresent {
        appIssuedNavigationUrl = null
        activeNavigationUrl = null
        pendingNavigationUrl = url
        return NavigationDecision.BlockAndPresent(url)
    }

    companion object {
        const val ABOUT_BLANK = "about:blank"

        fun isAllowedMainFrameScheme(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            return when (scheme) {
                "https" -> {
                    val validAuthority =
                        !uri.isOpaque && uri.rawUserInfo == null && !uri.host.isNullOrBlank()
                    val validPort = uri.port != 0 && uri.port <= MAX_NETWORK_PORT
                    validAuthority && validPort
                }
                "blob" -> url.startsWith("blob:https://", ignoreCase = true)
                "about" -> ABOUT_BLANK_PATTERN.matches(url)
                else -> false
            }
        }

        internal fun equivalent(first: String?, second: String?): Boolean {
            if (first == null || second == null) return false
            if (first == second) return isAllowedMainFrameScheme(first)
            val firstScheme = runCatching { URI(first).scheme }.getOrNull()
            val secondScheme = runCatching { URI(second).scheme }.getOrNull()
            if (
                !firstScheme.equals("https", ignoreCase = true) ||
                    !secondScheme.equals("https", ignoreCase = true)
            ) {
                return false
            }
            val a = comparisonParts(first) ?: return false
            val b = comparisonParts(second) ?: return false
            return a == b
        }

        internal fun sameOrigin(first: String, second: String): Boolean {
            val a = comparisonParts(first) ?: return false
            val b = comparisonParts(second) ?: return false
            return a.host == b.host && a.port == b.port
        }

        private fun comparisonParts(url: String): UrlParts? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (
                !uri.scheme.equals("https", ignoreCase = true) ||
                    uri.isOpaque ||
                    uri.rawUserInfo != null
            ) {
                return null
            }
            if (uri.port == 0 || uri.port > MAX_NETWORK_PORT) return null
            val host = canonicalHost(uri.host) ?: return null
            return UrlParts(
                host = host,
                port = if (uri.port == -1) DEFAULT_HTTPS_PORT else uri.port,
                path = canonicalNavigationPath(uri.rawPath),
                query = uri.rawQuery?.canonicalPercentEscapes(),
                fragment = uri.rawFragment?.canonicalPercentEscapes(),
            )
        }

        private data class UrlParts(
            val host: String,
            val port: Int,
            val path: String,
            val query: String?,
            val fragment: String?,
        )

        private val ABOUT_BLANK_PATTERN = Regex("^about:blank(?:[?#].*)?$", RegexOption.IGNORE_CASE)
        private const val DEFAULT_HTTPS_PORT = 443
        private const val MAX_NETWORK_PORT = 65_535
    }
}

private fun canonicalNavigationPath(rawPath: String?): String {
    val path = rawPath.orEmpty().ifEmpty { "/" }
    val decodedDotSegments =
        ENCODED_DOT_SEGMENT.replace(path) { match ->
            match.groupValues[1] + ENCODED_DOT.replace(match.groupValues[2]) { "." }
        }
    return removeDotSegments(decodedDotSegments).canonicalPercentEscapes()
}

/** RFC 3986 section 5.2.4, with parent traversal clamped at the URL root. */
private fun removeDotSegments(path: String): String {
    var input = path
    val output = StringBuilder()
    while (input.isNotEmpty()) {
        when {
            input.startsWith("../") -> input = input.drop(3)
            input.startsWith("./") -> input = input.drop(2)
            input.startsWith("/./") -> input = "/" + input.drop(3)
            input == "/." -> input = "/"
            input.startsWith("/../") -> {
                input = "/" + input.drop(4)
                output.removeLastPathSegment()
            }
            input == "/.." -> {
                input = "/"
                output.removeLastPathSegment()
            }
            input == "." || input == ".." -> input = ""
            else -> {
                val nextSlash = input.indexOf('/', startIndex = if (input[0] == '/') 1 else 0)
                if (nextSlash < 0) {
                    output.append(input)
                    input = ""
                } else {
                    output.append(input, 0, nextSlash)
                    input = input.substring(nextSlash)
                }
            }
        }
    }
    return output.toString().ifEmpty { "/" }
}

private fun StringBuilder.removeLastPathSegment() {
    val slash = lastIndexOf("/")
    if (slash >= 0) delete(slash, length) else setLength(0)
}

private fun String.canonicalPercentEscapes(): String =
    PERCENT_ESCAPE.replace(this) { match ->
        "%" + match.groupValues[1].uppercase(Locale.ROOT)
    }

/** Compares equivalent compressed and expanded IPv6 literals without performing a DNS lookup. */
private fun canonicalHost(host: String?): String? {
    val lowercase = host?.lowercase(Locale.ROOT) ?: return null
    if (!lowercase.startsWith('[') || !lowercase.endsWith(']')) return lowercase
    val groups = parseIpv6Groups(lowercase.substring(1, lowercase.lastIndex)) ?: return lowercase
    return groups.joinToString(separator = ":", prefix = "ipv6:") { it.toString(16) }
}

private fun parseIpv6Groups(literal: String): List<Int>? {
    if ('%' in literal || literal.count { it == ':' } < 2) return null
    val compression = literal.indexOf("::")
    if (compression >= 0 && literal.indexOf("::", compression + 2) >= 0) return null

    val leftText = if (compression >= 0) literal.substring(0, compression) else literal
    val rightText = if (compression >= 0) literal.substring(compression + 2) else ""
    val left = parseIpv6Side(leftText) ?: return null
    val right = parseIpv6Side(rightText) ?: return null
    val missing = IPV6_GROUP_COUNT - left.size - right.size
    if (compression < 0) {
        if (missing != 0) return null
    } else if (missing < 1) {
        return null
    }
    return left + List(missing) { 0 } + right
}

private fun parseIpv6Side(side: String): List<Int>? {
    if (side.isEmpty()) return emptyList()
    val parts = side.split(':')
    val groups = mutableListOf<Int>()
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty()) return null
        if ('.' in part) {
            if (index != parts.lastIndex) return null
            val octets = part.split('.').map { it.toIntOrNull() ?: return null }
            if (octets.size != IPV4_OCTET_COUNT || octets.any { it !in 0..255 }) return null
            groups += (octets[0] shl 8) or octets[1]
            groups += (octets[2] shl 8) or octets[3]
        } else {
            if (part.length > IPV6_GROUP_HEX_LENGTH) return null
            groups += part.toIntOrNull(radix = 16) ?: return null
        }
    }
    return groups
}

private val ENCODED_DOT = Regex("%2e", RegexOption.IGNORE_CASE)
private val ENCODED_DOT_SEGMENT = Regex("(^|/)((?:\\.|%2e){1,2})(?=/|$)", RegexOption.IGNORE_CASE)
private val PERCENT_ESCAPE = Regex("%([0-9a-fA-F]{2})")
private const val IPV6_GROUP_COUNT = 8
private const val IPV6_GROUP_HEX_LENGTH = 4
private const val IPV4_OCTET_COUNT = 4
