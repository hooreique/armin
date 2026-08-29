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

/**
 * Reissues a real main-document anchor activation through the app navigation state machine.
 *
 * The script runs in an isolated JavaScript world before page script. It receives trusted click
 * events, cancels the browser default, and sends the declared HTTPS href over a listener that the
 * page world cannot access. Normal WebView navigation callbacks therefore never need to treat
 * hasGesture or the intentionally racy HitTestResult cache as authorization.
 */
internal class DirectLinkNavigationBridge(
    private val webView: WebView,
    private val onDirectLink: (String) -> Unit,
) : AutoCloseable {
    private val nonce = randomHex(RANDOM_BYTE_COUNT)
    private val objectName = "arminNavigation_${randomHex(RANDOM_BYTE_COUNT)}"
    private var scriptHandler: ScriptHandler? = null
    private var executionWorld: JavaScriptExecutionWorld? = null
    private var listenerInstalled = false

    // These checks are immediately adjacent; lint cannot infer them through the cleanup state.
    @SuppressLint("RequiresFeature")
    fun installIfSupported(): Boolean {
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
        ) {
            return false
        }

        return try {
            val world = WebViewCompat.getExecutionWorld(webView, "armin-navigation-$nonce")
            executionWorld = world
            WebViewCompat.addWebMessageListener(
                webView,
                objectName,
                setOf(ALL_ORIGINS_RULE),
                world,
            ) { view, message, sourceOrigin, isMainFrame, _ ->
                handleMessage(view, message, sourceOrigin, isMainFrame)
            }
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
            close()
            false
        }
    }

    // A listener exists only after all feature checks in installIfSupported succeed.
    @SuppressLint("RequiresFeature")
    override fun close() {
        val world = executionWorld
        listenerInstalled = false
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        if (world != null) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, world, objectName) }
        }
        executionWorld = null
    }

    /** Renderer loss removes its world; avoid invoking any API on the crashed WebView. */
    fun discardAfterRendererGone() {
        listenerInstalled = false
        scriptHandler = null
        executionWorld = null
    }

    private fun handleMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
    ) {
        if (!listenerInstalled || !isMainFrame || message.type != WebMessageCompat.TYPE_STRING) {
            return
        }
        val payload = message.data ?: return
        val decoded = DirectLinkMessageCodec.decode(payload, nonce) ?: return
        val observedUrl = view.url ?: return
        if (!NavigationStateMachine.equivalent(decoded.documentUrl, observedUrl)) return
        if (!NavigationStateMachine.sameOrigin(sourceOrigin.toString(), observedUrl)) return
        if (!NavigationStateMachine.isAllowedMainFrameScheme(decoded.targetUrl)) return
        if (listenerInstalled) onDirectLink(decoded.targetUrl)
    }

    // Keeping this security-sensitive bootstrap contiguous makes its captured primitives and
    // event ordering auditable; splitting the JavaScript string would obscure that relationship.
    @Suppress("LongMethod")
    private fun script(): String =
        """
        (() => {
          'use strict';
          if (window !== window.top || location.protocol !== 'https:') return;

          const channel = globalThis['$objectName'];
          if (!channel || typeof channel.postMessage !== 'function') return;
          const post = channel.postMessage.bind(channel);
          const preventDefault = Event.prototype.preventDefault;
          const stopImmediatePropagation = Event.prototype.stopImmediatePropagation;
          const composedPath = Event.prototype.composedPath;
          const getAttribute = Element.prototype.getAttribute;
          const hasAttribute = Element.prototype.hasAttribute;
          const getElementsByTagNameNS = Document.prototype.getElementsByTagNameNS;
          const HTMLAnchor = globalThis.HTMLAnchorElement;
          const HTMLArea = globalThis.HTMLAreaElement;
          const HTMLBase = globalThis.HTMLBaseElement;
          const HTMLButton = globalThis.HTMLButtonElement;
          const HTMLDetails = globalThis.HTMLDetailsElement;
          const HTMLEmbed = globalThis.HTMLEmbedElement;
          const HTMLElementType = globalThis.HTMLElement;
          const HTMLIFrame = globalThis.HTMLIFrameElement;
          const HTMLImage = globalThis.HTMLImageElement;
          const HTMLInput = globalThis.HTMLInputElement;
          const HTMLLabel = globalThis.HTMLLabelElement;
          const HTMLMedia = globalThis.HTMLMediaElement;
          const HTMLObject = globalThis.HTMLObjectElement;
          const HTMLOption = globalThis.HTMLOptionElement;
          const HTMLSelect = globalThis.HTMLSelectElement;
          const HTMLTextArea = globalThis.HTMLTextAreaElement;
          const SVGA = globalThis.SVGAElement;
          const lowerCase = String.prototype.toLowerCase;
          const schedule = globalThis.setTimeout.bind(globalThis);
          let activation = null;

          const findAnchor = event => {
            let path;
            try {
              path = composedPath.call(event);
            } catch (_) {
              path = [event.target];
            }
            for (const node of path) {
              if (!node || node.nodeType !== Node.ELEMENT_NODE) continue;
              if (node.isContentEditable) return null;
              if (node instanceof HTMLAnchor && hasAttribute.call(node, 'href')) return node;
              if (
                node instanceof HTMLArea ||
                node instanceof HTMLButton ||
                node instanceof HTMLDetails ||
                node instanceof HTMLEmbed ||
                node instanceof HTMLIFrame ||
                node instanceof HTMLInput ||
                node instanceof HTMLLabel ||
                node instanceof HTMLObject ||
                node instanceof HTMLOption ||
                node instanceof HTMLSelect ||
                node instanceof HTMLTextArea ||
                (node instanceof HTMLImage &&
                    (hasAttribute.call(node, 'usemap') || hasAttribute.call(node, 'ismap'))) ||
                (node instanceof HTMLMedia && node.controls) ||
                (node instanceof HTMLElementType && node.localName === 'summary') ||
                (node instanceof HTMLElementType && node.localName.indexOf('-') >= 0) ||
                (node.namespaceURI === 'http://www.w3.org/1998/Math/MathML' &&
                    node.localName === 'a' && hasAttribute.call(node, 'href')) ||
                node.shadowRoot ||
                node instanceof SVGA
              ) return null;
            }
            return null;
          };

          const modifierMask = event =>
              (event.altKey ? 1 : 0) |
              (event.ctrlKey ? 2 : 0) |
              (event.metaKey ? 4 : 0) |
              (event.shiftKey ? 8 : 0);

          const describe = (anchor, event) => {
            const ownTarget = getAttribute.call(anchor, 'target');
            const bases = getElementsByTagNameNS.call(
                document, 'http://www.w3.org/1999/xhtml', 'base');
            let baseTarget = null;
            for (const base of bases) {
              if (base instanceof HTMLBase && hasAttribute.call(base, 'target')) {
                baseTarget = getAttribute.call(base, 'target');
                break;
              }
            }
            // Blink falls back to <base target> when an anchor target is absent or empty.
            const target = (ownTarget === null || ownTarget === '' ? baseTarget : ownTarget) || '';
            const normalizedTarget = lowerCase.call(target);
            let destination = null;
            try {
              destination = new URL(anchor.href, document.baseURI);
            } catch (_) {}
            return {
              anchor,
              documentUrl: document.URL,
              destination,
              href: destination ? destination.href : null,
              target: normalizedTarget,
              download: hasAttribute.call(anchor, 'download'),
              editable: anchor.isContentEditable,
              modifiers: modifierMask(event),
              timestamp: event.timeStamp,
            };
          };

          const beginActivation = event => {
            activation = null;
            if (!event.isTrusted || event.button !== 0 || event.isPrimary === false) return;
            const anchor = findAnchor(event);
            if (anchor) {
              activation = describe(anchor, event);
              stopImmediatePropagation.call(event);
            }
          };

          const stopForCapturedAnchor = event => {
            if (activation && findAnchor(event) === activation.anchor) {
              stopImmediatePropagation.call(event);
            }
          };

          const finishActivationEvent = event => {
            stopForCapturedAnchor(event);
            const captured = activation;
            schedule(() => {
              if (activation === captured) activation = null;
            }, $ACTIVATION_LIFETIME_MILLIS);
          };

          window.addEventListener('pointerdown', beginActivation, true);
          window.addEventListener('touchstart', stopForCapturedAnchor, true);
          window.addEventListener('mousedown', stopForCapturedAnchor, true);
          window.addEventListener('pointermove', stopForCapturedAnchor, true);
          window.addEventListener('touchmove', stopForCapturedAnchor, true);
          window.addEventListener('mousemove', stopForCapturedAnchor, true);
          window.addEventListener('pointerup', finishActivationEvent, true);
          window.addEventListener('touchend', stopForCapturedAnchor, true);
          window.addEventListener('mouseup', stopForCapturedAnchor, true);
          window.addEventListener('pointercancel', () => { activation = null; }, true);
          window.addEventListener('blur', event => {
            if (event.target === window) activation = null;
          }, true);
          window.addEventListener('keydown', event => {
            activation = null;
            if (!event.isTrusted || event.key !== 'Enter') return;
            const anchor = findAnchor(event);
            if (anchor) {
              activation = describe(anchor, event);
              stopImmediatePropagation.call(event);
            }
          }, true);
          window.addEventListener('keypress', stopForCapturedAnchor, true);
          window.addEventListener('keyup', finishActivationEvent, true);

          window.addEventListener('click', event => {
            if (!event.isTrusted || event.button !== 0) return;
            const anchor = findAnchor(event);
            if (!anchor) return;
            const captured = activation;
            activation = null;
            preventDefault.call(event);
            stopImmediatePropagation.call(event);
            if (!captured || captured.anchor !== anchor) return;
            const current = describe(anchor, event);
            const activationAge = current.timestamp - captured.timestamp;

            if (
              captured.documentUrl !== current.documentUrl ||
              captured.href !== current.href ||
              captured.target !== current.target ||
              captured.download !== current.download ||
              captured.editable !== current.editable ||
              captured.modifiers !== current.modifiers ||
              activationAge < 0 ||
              activationAge > $ACTIVATION_LIFETIME_MILLIS ||
              current.modifiers !== 0 ||
              current.editable ||
              current.download ||
              (current.target && current.target !== '_self') ||
              !current.destination ||
              current.destination.protocol !== 'https:' ||
              current.destination.username ||
              current.destination.password
            ) return;

            if (
              current.documentUrl.length > ${DirectLinkMessageCodec.MAX_PAYLOAD_CHARS} ||
              current.href.length > ${DirectLinkMessageCodec.MAX_PAYLOAD_CHARS}
            ) return;
            const payload = '$nonce\n' + current.documentUrl + '\n' + current.href;
            if (payload.length > ${DirectLinkMessageCodec.MAX_PAYLOAD_CHARS}) return;
            post(payload);
          }, true);
        })();
        """
            .trimIndent()

    private companion object {
        const val ALL_ORIGINS_RULE = "*"
        const val ACTIVATION_LIFETIME_MILLIS = 1_500
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
