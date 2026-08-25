package dev.armin.browser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.URI

internal class WebViewContentInterceptor(
    private val engine: ContentBlockingEngine,
    private val topLevelDocumentUrl: () -> String?,
) {
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        return intercept(request, topLevelDocumentUrl())
    }

    /** ServiceWorkerController cannot identify which WebView owns a request. */
    fun interceptWithoutTopLevelContext(request: WebResourceRequest): WebResourceResponse? =
        intercept(request, null)

    private fun intercept(
        request: WebResourceRequest,
        documentUrl: String?,
    ): WebResourceResponse? {
        val contentRequest =
            ContentRequest(
                url = request.url.toString(),
                method = request.method,
                headers = request.requestHeaders.orEmpty().toMap(),
                isMainFrame = request.isForMainFrame,
                hasGesture = request.hasGesture(),
                topLevelDocumentUrl = documentUrl,
                topLevelDocumentHostname = hostname(documentUrl),
            )
        return when (val decision = engine.evaluate(contentRequest)) {
            ContentBlockingDecision.Allow -> null
            ContentBlockingDecision.Block -> emptyResponse(statusCode = 403, reason = "Blocked")
            is ContentBlockingDecision.Redirect -> replacementResponse(decision.replacement)
        }
    }

    private fun hostname(url: String?): String? = url?.let {
        runCatching { URI(it).host }.getOrNull()
    }

    private fun emptyResponse(statusCode: Int, reason: String) =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reason,
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun replacementResponse(replacement: LocalReplacement) =
        WebResourceResponse(
            replacement.mimeType,
            replacement.encoding,
            200,
            "OK",
            replacement.responseHeaders,
            ByteArrayInputStream(replacement.data),
        )
}

/** Process-wide Service Worker hook using the same no-op/filter engine as ordinary requests. */
internal class ServiceWorkerContentBlockingBridge(
    private val interceptor: WebViewContentInterceptor
) : AutoCloseable {
    private var installed = false

    fun installIfSupported(): Boolean {
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
                !WebViewFeature.isFeatureSupported(
                    WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST
                )
        ) {
            return false
        }

        synchronized(OWNER_LOCK) {
            if (ACTIVE_BRIDGES.isEmpty()) {
                ServiceWorkerControllerCompat.getInstance()
                    .setServiceWorkerClient(PROCESS_WIDE_CLIENT)
            }
            ACTIVE_BRIDGES.remove(this)
            ACTIVE_BRIDGES.add(this)
            installed = true
        }
        return true
    }

    override fun close() {
        synchronized(OWNER_LOCK) {
            if (!installed) return
            ACTIVE_BRIDGES.remove(this)
            if (
                ACTIVE_BRIDGES.isEmpty() &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)
            ) {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(null)
            }
            installed = false
        }
    }

    private companion object {
        val OWNER_LOCK = Any()
        val ACTIVE_BRIDGES = linkedSetOf<ServiceWorkerContentBlockingBridge>()
        val PROCESS_WIDE_CLIENT =
            object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val active = synchronized(OWNER_LOCK) { ACTIVE_BRIDGES.lastOrNull() }
                    // The API supplies no originating WebView. Null context is safer than
                    // attributing one Activity's Service Worker request to another document.
                    return active?.interceptor?.interceptWithoutTopLevelContext(request)
                }
            }
    }
}
