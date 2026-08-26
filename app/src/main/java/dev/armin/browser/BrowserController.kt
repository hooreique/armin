package dev.armin.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebResourceRequestCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.armin.BuildConfig

interface BrowserUiCallbacks {
    fun replaceAddress(value: String, requestFocus: Boolean)

    fun showInvalidAddress()

    fun onRendererTerminated()
}

interface FullscreenViewHost {
    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback)

    fun hideCustomView()
}

class BrowserController(
    private val webView: WebView,
    private val ui: BrowserUiCallbacks,
    private val fullscreenViewHost: FullscreenViewHost,
    contentBlockingEngine: ContentBlockingEngine = NoOpContentBlockingEngine,
    private val documentScriptInjector: DocumentScriptInjector = DocumentScriptInjector(),
) : AutoCloseable {
    private val navigationState = NavigationStateMachine()
    private val contentInterceptor =
        WebViewContentInterceptor(contentBlockingEngine) {
            navigationState.snapshot().let { it.activeNavigationUrl ?: it.currentDocumentUrl }
        }
    private val serviceWorkerBridge = ServiceWorkerContentBlockingBridge(contentInterceptor)
    private var addressEditedByUser = false
    private var trustedDirectLinkBridgeInstalled = false
    private val directLinkBridge =
        DirectLinkNavigationBridge(webView) { url ->
            addressEditedByUser = false
            navigationState.beginAppIssuedNavigation(url)
            webView.loadUrl(url)
        }

    val currentDocumentUrl: String
        get() = navigationState.snapshot().currentDocumentUrl

    val pendingNavigationUrl: String?
        get() = navigationState.snapshot().pendingNavigationUrl

    init {
        configureWebView()
        trustedDirectLinkBridgeInstalled = directLinkBridge.installIfSupported()
        documentScriptInjector.installBeforeFirstLoad(webView)
        serviceWorkerBridge.installIfSupported()
        showBlankStartPage()
    }

    fun onAddressEditedByUser() {
        addressEditedByUser = true
    }

    fun onAddressEditingFinished() {
        addressEditedByUser = false
    }

    fun submitAddress(input: String): Boolean =
        when (val result = UrlNormalizer.normalize(input)) {
            UrlNormalizationResult.Empty -> false
            is UrlNormalizationResult.Invalid -> {
                ui.showInvalidAddress()
                false
            }
            is UrlNormalizationResult.Valid -> {
                addressEditedByUser = false
                navigationState.beginAppIssuedNavigation(result.url)
                webView.loadUrl(result.url)
                true
            }
        }

    fun canGoBack(): Boolean = webView.allowedBackEntry() != null

    fun goBack() {
        val entry = webView.allowedBackEntry() ?: return
        navigationState.beginAppIssuedNavigation(entry.url)
        webView.goBackOrForward(entry.offset)
    }

    override fun close() {
        webView.stopLoading()
        directLinkBridge.close()
        serviceWorkerBridge.close()
        documentScriptInjector.close()
        webView.setDownloadListener(null)
    }

    @Suppress("SetJavaScriptEnabled", "DEPRECATION")
    private fun configureWebView() {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(webView.context, dev.armin.R.color.black)
        )
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = BrowserWebViewClient()
        webView.webChromeClient = BrowserChromeClient()
        webView.setDownloadListener { _, _, _, _, _ ->
            /* Downloads are intentionally unsupported. */
        }
    }

    private fun showBlankStartPage() {
        addressEditedByUser = false
        navigationState.beginAppIssuedNavigation(NavigationStateMachine.ABOUT_BLANK)
        ui.replaceAddress("", requestFocus = false)
        webView.loadDataWithBaseURL(
            NavigationStateMachine.ABOUT_BLANK,
            BLACK_DOCUMENT,
            "text/html",
            "UTF-8",
            null,
        )
    }

    private fun presentPending(url: String) {
        addressEditedByUser = false
        ui.replaceAddress(UrlNormalizer.displayText(url), requestFocus = true)
    }

    private fun publishAddressIfAccepted(url: String, accepted: Boolean) {
        if (!accepted || addressEditedByUser) return
        val value =
            if (url.startsWith("https://", ignoreCase = true)) {
                UrlNormalizer.displayText(url)
            } else {
                ""
            }
        ui.replaceAddress(value, requestFocus = false)
    }

    // WebView requires one callback owner for navigation, TLS, rendering, and request policy.
    @Suppress("TooManyFunctions")
    // AndroidX lint misses the override in this private inner class; it is implemented below.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class BrowserWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (!request.isForMainFrame) return false
            val redirect =
                if (
                    WebViewFeature.isFeatureSupported(
                        WebViewFeature.WEB_RESOURCE_REQUEST_IS_REDIRECT
                    ) &&
                        WebViewFeature.isFeatureSupported(
                            WebViewFeature.SHOULD_OVERRIDE_WITH_REDIRECTS
                        )
                ) {
                    runCatching { WebResourceRequestCompat.isRedirect(request) }.getOrNull()
                } else {
                    null
                }
            return handleMainFrameRequest(
                MainFrameNavigationRequest(
                    url = request.url.toString(),
                    hasGesture = request.hasGesture(),
                    isRedirect = redirect,
                    // Prefer the isolated document-start bridge when available. Older WebViews
                    // cannot expose that trusted channel, so hasGesture is the best signal they
                    // provide for the requirement's ordinary-link fallback.
                    isDirectLink = !trustedDirectLinkBridgeInstalled && request.hasGesture(),
                )
            )
        }

        @Deprecated("Only used by old WebView implementations")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleMainFrameRequest(
                MainFrameNavigationRequest(url = url, hasGesture = false, isRedirect = null)
            )

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = contentInterceptor.intercept(request)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            navigationState.onPageStarted(url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            publishAddressIfAccepted(url, navigationState.onPageEvent(url))
        }

        override fun onPageFinished(view: WebView, url: String) {
            documentScriptInjector.injectFallbackAfterPageFinished(view, url)
            publishAddressIfAccepted(url, navigationState.onPageEvent(url))
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            publishAddressIfAccepted(url, navigationState.onHistoryEvent(url, view.url))
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            handler.cancel()
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            // Do not call stopLoading or any other method on the crashed WebView. Only detach the
            // process-wide hooks before the Activity destroys this instance.
            directLinkBridge.discardAfterRendererGone()
            serviceWorkerBridge.close()
            documentScriptInjector.discardAfterRendererGone()
            ui.onRendererTerminated()
            return true
        }

        private fun handleMainFrameRequest(request: MainFrameNavigationRequest): Boolean =
            when (val decision = navigationState.evaluate(request)) {
                NavigationDecision.Allow -> false
                NavigationDecision.Block -> true
                is NavigationDecision.BlockAndPresent -> {
                    presentPending(decision.pendingUrl)
                    true
                }
            }
    }

    private inner class BrowserChromeClient : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean = false

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullscreenViewHost.showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            fullscreenViewHost.hideCustomView()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            callback.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = false
    }

    companion object {
        private const val BLACK_DOCUMENT =
            "<!doctype html><html><head><meta name=\"color-scheme\" content=\"dark\">" +
                "<style>html,body{margin:0;background:#000}</style></head><body></body></html>"
    }
}

private data class BackEntry(val offset: Int, val url: String)

private fun WebView.allowedBackEntry(): BackEntry? {
    val history = copyBackForwardList()
    for (index in history.currentIndex - 1 downTo 0) {
        val url = history.getItemAtIndex(index)?.url ?: continue
        if (NavigationStateMachine.isAllowedMainFrameScheme(url)) {
            return BackEntry(index - history.currentIndex, url)
        }
    }
    return null
}
