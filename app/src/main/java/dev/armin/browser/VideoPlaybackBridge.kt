package dev.armin.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionWorld
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.security.SecureRandom

internal enum class VideoPlaybackBridgeMode {
    ISOLATED_DOCUMENT_START,
    DOCUMENT_START_FALLBACK,
    PAGE_FINISHED_FALLBACK,
    DISABLED,
}

/** Reports whether any connected HTML video in any HTTPS frame is currently playing. */
@Suppress("TooManyFunctions")
internal class VideoPlaybackBridge(
    private val webView: WebView,
    onStateChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val nonce = randomHex(RANDOM_BYTE_COUNT)
    private val objectName = "arminVideo_${randomHex(RANDOM_BYTE_COUNT)}"
    private val stateTracker = VideoPlaybackStateTracker(onStateChanged)
    private var scriptHandler: ScriptHandler? = null
    private var executionWorld: JavaScriptExecutionWorld? = null
    private var listenerInstalled = false
    private var mode: VideoPlaybackBridgeMode? = null

    @SuppressLint("RequiresFeature")
    fun installBeforeFirstLoad(): VideoPlaybackBridgeMode {
        check(mode == null) { "Video playback bridge is already configured" }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            mode = VideoPlaybackBridgeMode.DISABLED
            return checkNotNull(mode)
        }

        val isolatedSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
        if (isolatedSupported && installIsolated()) {
            mode = VideoPlaybackBridgeMode.ISOLATED_DOCUMENT_START
            return checkNotNull(mode)
        }

        if (!installPageWorldListener()) {
            mode = VideoPlaybackBridgeMode.DISABLED
            return checkNotNull(mode)
        }
        mode =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                scriptHandler =
                    WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        script(),
                        setOf(ALL_ORIGINS_RULE),
                    )
                VideoPlaybackBridgeMode.DOCUMENT_START_FALLBACK
            } else {
                VideoPlaybackBridgeMode.PAGE_FINISHED_FALLBACK
            }
        return checkNotNull(mode)
    }

    fun injectFallbackAfterPageFinished(url: String) {
        if (mode == VideoPlaybackBridgeMode.PAGE_FINISHED_FALLBACK && url.isHttps()) {
            webView.evaluateJavascript(script(), null)
        }
    }

    fun resetForNavigation() {
        stateTracker.clear()
    }

    @SuppressLint("RequiresFeature")
    override fun close() {
        stateTracker.clear()
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        if (listenerInstalled) {
            val world = executionWorld
            if (world != null) {
                runCatching { WebViewCompat.removeWebMessageListener(webView, world, objectName) }
            } else {
                runCatching { WebViewCompat.removeWebMessageListener(webView, objectName) }
            }
        }
        listenerInstalled = false
        executionWorld = null
        mode = null
    }

    /** Renderer loss has already removed scripts and listeners; never touch the dead WebView. */
    fun discardAfterRendererGone() {
        stateTracker.clear()
        scriptHandler = null
        listenerInstalled = false
        executionWorld = null
        mode = null
    }

    @SuppressLint("RequiresFeature")
    private fun installIsolated(): Boolean =
        try {
            val world = WebViewCompat.getExecutionWorld(webView, "armin-video-$nonce")
            executionWorld = world
            WebViewCompat.addWebMessageListener(
                webView,
                objectName,
                setOf(ALL_ORIGINS_RULE),
                world,
                ::handleMessage,
            )
            listenerInstalled = true
            scriptHandler =
                WebViewCompat.addJavaScriptOnEvent(
                    webView,
                    script(),
                    WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                    setOf(ALL_ORIGINS_RULE),
                    world,
                )
            true
        } catch (_: RuntimeException) {
            cleanupFailedInstall()
            false
        }

    @SuppressLint("RequiresFeature")
    private fun installPageWorldListener(): Boolean =
        try {
            WebViewCompat.addWebMessageListener(
                webView,
                objectName,
                setOf(ALL_ORIGINS_RULE),
                ::handleMessage,
            )
            listenerInstalled = true
            true
        } catch (_: RuntimeException) {
            cleanupFailedInstall()
            false
        }

    @Suppress("UNUSED_PARAMETER")
    private fun handleMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: androidx.webkit.JavaScriptReplyProxy,
    ) {
        if (
            !listenerInstalled ||
                message.type != WebMessageCompat.TYPE_STRING ||
                !sourceOrigin.scheme.equals("https", ignoreCase = true)
        ) {
            return
        }
        val decoded = VideoPlaybackMessageCodec.decode(message.data ?: return, nonce) ?: return
        stateTracker.update(decoded.frameId, decoded.isPlaying)
    }

    @SuppressLint("RequiresFeature")
    private fun cleanupFailedInstall() {
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        if (listenerInstalled) {
            val world = executionWorld
            if (world != null) {
                runCatching { WebViewCompat.removeWebMessageListener(webView, world, objectName) }
            } else {
                runCatching { WebViewCompat.removeWebMessageListener(webView, objectName) }
            }
        }
        listenerInstalled = false
        executionWorld = null
    }

    @Suppress("LongMethod")
    private fun script(): String =
        """
        (() => {
          'use strict';
          if (location.protocol !== 'https:') return;
          const marker = Symbol.for('dev.armin.video-playback-bridge');
          if (globalThis[marker]) return;
          globalThis[marker] = true;

          const channel = globalThis['$objectName'];
          if (!channel || typeof channel.postMessage !== 'function') return;
          const post = channel.postMessage.bind(channel);
          const bytes = new Uint8Array($RANDOM_BYTE_COUNT);
          crypto.getRandomValues(bytes);
          const frameId = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
          const prefix = '$nonce\n' + frameId + '\n';
          let lastState = null;
          let scheduled = false;

          const computeState = () => {
            scheduled = false;
            let playing = false;
            for (const video of document.querySelectorAll('video')) {
              if (video.isConnected && !video.paused && !video.ended) {
                playing = true;
                break;
              }
            }
            if (playing === lastState) return;
            lastState = playing;
            post(prefix + (playing ? '1' : '0'));
          };
          const scheduleCompute = () => {
            if (scheduled) return;
            scheduled = true;
            queueMicrotask(computeState);
          };
          for (const eventName of ['play', 'pause', 'ended', 'emptied', 'abort', 'error']) {
            document.addEventListener(eventName, scheduleCompute, true);
          }
          new MutationObserver(scheduleCompute).observe(document, {childList: true, subtree: true});
          addEventListener('pagehide', () => {
            scheduled = false;
            if (lastState !== false) {
              lastState = false;
              post(prefix + '0');
            }
          }, true);
          scheduleCompute();
        })();
        """
            .trimIndent()

    private fun String.isHttps(): Boolean = startsWith("https://", ignoreCase = true)

    private companion object {
        const val ALL_ORIGINS_RULE = "*"
        const val RANDOM_BYTE_COUNT = 16

        fun randomHex(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }
    }
}
