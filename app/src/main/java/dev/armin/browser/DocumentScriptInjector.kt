package dev.armin.browser

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

enum class DocumentScriptMode {
    DOCUMENT_START,
    PAGE_FINISHED_FALLBACK,
}

fun interface DocumentBootstrapSource {
    /** Returns a short, static, locally trusted bootstrap. */
    fun script(): String
}

object DefaultDocumentBootstrap : DocumentBootstrapSource {
    override fun script(): String =
        """
        (() => {
          'use strict';
          if (location.protocol !== 'https:') return;
          document.addEventListener('play', event => {
            const video = event.target;
            if (!(video instanceof HTMLVideoElement) || document.fullscreenElement) return;
            const enter = video.requestFullscreen || video.webkitRequestFullscreen;
            if (!enter) return;
            try {
              const result = enter.call(video);
              if (result && result.catch) result.catch(() => {});
            } catch (_) {}
          }, true);
        })();
        """
            .trimIndent()
}

/** Single installation point for future cosmetic/scriptlet bootstrap generation. */
class DocumentScriptInjector(
    private val bootstrapSource: DocumentBootstrapSource = DefaultDocumentBootstrap
) : AutoCloseable {
    private var handler: ScriptHandler? = null
    private var mode: DocumentScriptMode? = null

    fun installBeforeFirstLoad(webView: WebView): DocumentScriptMode {
        check(mode == null) { "Document script injection is already configured" }
        val script = bootstrapSource.script()
        mode =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // The API has no all-HTTPS/all-port origin rule. The static script is harmless on
                // other origins and immediately returns unless the frame itself is HTTPS.
                handler = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
                DocumentScriptMode.DOCUMENT_START
            } else {
                DocumentScriptMode.PAGE_FINISHED_FALLBACK
            }
        return checkNotNull(mode)
    }

    fun injectFallbackAfterPageFinished(webView: WebView, url: String) {
        if (mode == DocumentScriptMode.PAGE_FINISHED_FALLBACK && url.isHttps()) {
            webView.evaluateJavascript(bootstrapSource.script(), null)
        }
    }

    override fun close() {
        handler?.remove()
        handler = null
        mode = null
    }

    /** Renderer loss already removed its script world; do not call its dead provider again. */
    fun discardAfterRendererGone() {
        handler = null
        mode = null
    }

    private fun String.isHttps(): Boolean = startsWith("https://", ignoreCase = true)
}
