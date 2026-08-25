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

    fun snapshot(): NavigationSnapshot =
        NavigationSnapshot(
            currentDocumentUrl = currentDocumentUrl,
            pendingNavigationUrl = pendingNavigationUrl,
            activeNavigationUrl = activeNavigationUrl,
        )

    /** Authorizes one app-issued load, including an address submission or history traversal. */
    fun beginAppIssuedNavigation(url: String) {
        require(isAllowedMainFrameScheme(url)) { "App-issued navigation must be HTTPS or internal" }
        appIssuedNavigationUrl = url
        activeNavigationUrl = url
        pendingNavigationUrl = null
    }

    fun evaluate(request: MainFrameNavigationRequest): NavigationDecision {
        if (request.isRedirect == true) return blockAndPresent(request.url)

        if (equivalent(request.url, appIssuedNavigationUrl)) {
            appIssuedNavigationUrl = null
            return accept(request.url)
        }

        if (request.hasGesture) {
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
    fun onPageStarted(url: String) {
        if (equivalent(url, appIssuedNavigationUrl)) appIssuedNavigationUrl = null
    }

    /**
     * Accepts a page callback only for the currently active target. A blocked redirect clears the
     * active target, so late callbacks from the abandoned load cannot overwrite pending state.
     */
    fun onPageEvent(url: String): Boolean {
        if (!equivalent(url, activeNavigationUrl)) return false
        currentDocumentUrl = url
        return pendingNavigationUrl == null
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
            val scheme = runCatching { URI(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
            return when (scheme) {
                "https", "blob" -> true
                "about" -> ABOUT_BLANK_PATTERN.matches(url)
                else -> false
            }
        }

        private fun equivalent(first: String?, second: String?): Boolean {
            if (first == null || second == null) return false
            if (first == second) return true
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

        private fun comparisonParts(url: String): UrlParts? =
            runCatching {
                    val uri = URI(url)
                    UrlParts(
                        scheme = uri.scheme?.lowercase(Locale.ROOT),
                        host = uri.host?.lowercase(Locale.ROOT),
                        port = if (uri.port == -1) 443 else uri.port,
                        path = uri.rawPath.orEmpty().ifEmpty { "/" },
                        query = uri.rawQuery,
                        fragment = uri.rawFragment,
                    )
                }
                .getOrNull()

        private data class UrlParts(
            val scheme: String?,
            val host: String?,
            val port: Int,
            val path: String,
            val query: String?,
            val fragment: String?,
        )

        private val ABOUT_BLANK_PATTERN = Regex("^about:blank(?:[?#].*)?$", RegexOption.IGNORE_CASE)
    }
}
