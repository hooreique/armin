package dev.armin.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @Test
    fun blankStartHasExactlyOneWebViewAndOneEmptyAddressBar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val webViews = content.descendantsOfType(WebView::class.java)
                val addressBars = content.descendantsOfType(EditText::class.java)

                assertEquals(1, webViews.size)
                assertEquals(1, addressBars.size)
                assertEquals("", addressBars.single().text.toString())
                val webView = webViews.single()
                val rendered =
                    Bitmap.createBitmap(
                        webView.width.coerceAtLeast(1),
                        webView.height.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                webView.draw(Canvas(rendered))
                assertEquals(
                    ContextCompat.getColor(activity, dev.armin.R.color.black),
                    rendered.getPixel(rendered.width / 2, rendered.height / 2),
                )
                rendered.recycle()
                assertTrue(webViews.single().url == null || webViews.single().url == "about:blank")
            }
        }
    }

    @Test
    fun HTTPAddressDoesNotReplaceBlankDocument() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            val before = scenario.webViewUrl()

            onView(isAssignableFrom(EditText::class.java))
                .perform(replaceText("http://example.com"), pressImeActionButton())

            assertEquals(before, scenario.webViewUrl())
        }
    }

    @Test
    fun emptyAddressDoesNotReplaceBlankDocument() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            val before = scenario.webViewUrl()

            onView(isAssignableFrom(EditText::class.java))
                .perform(replaceText(""), pressImeActionButton())

            assertEquals(before, scenario.webViewUrl())
        }
    }

    @Test
    fun trustedOrdinaryAnchorOpensInTheCurrentWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<a id='link' href='/armin-direct-destination'>destination</a>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)
            assertEquals(
                "\"link\"",
                scenario.evaluateJavascript("document.elementFromPoint($TAP_X, $TAP_Y)?.id"),
            )

            scenario.tapFixtureLink()

            val expected = "$FIXTURE_ORIGIN/armin-direct-destination"
            if (!waitUntilWebViewUrl(scenario, expected)) {
                assertEquals(
                    "$expected|example.com/armin-direct-destination",
                    "${scenario.webViewUrl()}|${scenario.addressText()}",
                )
            }
        }
    }

    @Test
    fun syntheticAnchorClickCannotUseGestureNavigationAllowance() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<a id='link' href='/armin-synthetic-destination'>destination</a>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.onActivity { activity ->
                activity
                    .webView()
                    .evaluateJavascript("document.getElementById('link').click()", null)
            }

            assertTrue(
                "Synthetic click did not become a pending navigation",
                waitUntilAddressText(scenario, "example.com/armin-synthetic-destination"),
            )
            assertEquals(FIXTURE_URL, scenario.webViewUrl())
        }
    }

    @Test
    fun pointerHandlerCannotReplaceTheCapturedAnchorDestination() {
        assumeDirectLinkBridgeSupported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<a id='link' href='/armin-declared' " +
                    "onpointerdown=\"this.href='/armin-mutated'\">" +
                    "destination</a>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.tapFixtureLink()
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            assertTrue(waitUntilWebViewUrl(scenario, "$FIXTURE_ORIGIN/armin-declared"))
        }
    }

    @Test
    fun pageClickHandlerCannotSuppressTheCapturedAnchorDestination() {
        assumeDirectLinkBridgeSupported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<a id='link' href='/armin-cancelled' " +
                    "onclick='event.preventDefault()'>destination</a>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.tapFixtureLink()
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            assertTrue(waitUntilWebViewUrl(scenario, "$FIXTURE_ORIGIN/armin-cancelled"))
        }
    }

    @Test
    fun emptyAnchorTargetStillInheritsPopupBaseTarget() {
        assumeDirectLinkBridgeSupported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<base target='_blank'><a id='link' target='' " +
                    "href='/armin-popup'>destination</a>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.tapFixtureLink()
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            assertEquals(FIXTURE_URL, scenario.webViewUrl())
        }
    }

    @Test
    fun svgBaseCannotHideTheHtmlPopupBaseTarget() {
        assumeDirectLinkBridgeSupported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<svg><base target='_self'></base></svg>" +
                    "<base target='_blank'><a id='link' target='' " +
                    "href='/armin-popup'>destination</a>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.tapFixtureLink()
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            assertEquals(FIXTURE_URL, scenario.webViewUrl())
        }
    }

    @Test
    fun svgAnchorIsNotPromotedToAnHtmlDirectLink() {
        assumeDirectLinkBridgeSupported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<svg width='200' height='120'>" +
                    "<a id='link' href='/armin-svg'><rect width='200' height='120'/></a></svg>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.tapFixtureLink()
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            assertEquals(FIXTURE_URL, scenario.webViewUrl())
        }
    }

    @Test
    fun nestedSvgAnchorIsNotConfusedWithAnOuterHtmlAnchor() {
        assumeDirectLinkBridgeSupported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<a href='/armin-outer'><svg width='200' height='120'>" +
                    "<a id='link' href='/armin-inner'><rect width='200' height='120'/></a>" +
                    "</svg></a>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.tapFixtureLink()
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            assertEquals(FIXTURE_URL, scenario.webViewUrl())
        }
    }

    @Test
    fun submitButtonInsideAnchorIsNotPromotedToTheOuterLink() {
        assumeDirectLinkBridgeSupported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<form action='/armin-inner'><a href='/armin-outer'>" +
                    "<button id='link' style='width:200px;height:120px'>submit</button>" +
                    "</a></form>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.tapFixtureLink()
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            assertEquals(FIXTURE_URL, scenario.webViewUrl())
        }
    }

    @Test
    fun selectInsideAnchorIsNotPromotedToTheOuterLink() {
        assumeDirectLinkBridgeSupported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<a href='/armin-outer'><select id='link' style='width:200px;height:120px'>" +
                    "<option>choice</option></select></a>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.tapFixtureLink()
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            assertEquals(FIXTURE_URL, scenario.webViewUrl())
        }
    }

    @Test
    fun serverSideImageMapIsNotReissuedWithoutCoordinates() {
        assumeDirectLinkBridgeSupported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            scenario.loadHtmlFixture(
                "<a href='/armin-mapped'><img id='link' ismap width='200' height='120' " +
                    "src='data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs='></a>"
            )
            assumeTrue("Fixture did not load", waitUntilWebViewUrl(scenario, FIXTURE_URL))
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            scenario.tapFixtureLink()
            SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)

            assertEquals(FIXTURE_URL, scenario.webViewUrl())
        }
    }

    private fun waitUntilAddressReady(scenario: ActivityScenario<MainActivity>): Boolean {
        repeat(STARTUP_POLL_ATTEMPTS) {
            var ready = false
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val addressEnabled =
                    content.descendantsOfType(EditText::class.java).single().isEnabled
                val blankLoaded =
                    content.descendantsOfType(WebView::class.java).single().url == "about:blank"
                ready = addressEnabled && blankLoaded
            }
            if (ready) return true
            SystemClock.sleep(STARTUP_POLL_MILLIS)
        }
        return false
    }

    private fun waitUntilWebViewUrl(
        scenario: ActivityScenario<MainActivity>,
        expected: String,
    ): Boolean = waitUntil { scenario.webViewUrl() == expected }

    private fun waitUntilAddressText(
        scenario: ActivityScenario<MainActivity>,
        expected: String,
    ): Boolean = waitUntil {
        var value = ""
        scenario.onActivity { activity ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            value = content.descendantsOfType(EditText::class.java).single().text.toString()
        }
        value == expected
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(STARTUP_POLL_ATTEMPTS) {
            if (condition()) return true
            SystemClock.sleep(STARTUP_POLL_MILLIS)
        }
        return false
    }

    private fun ActivityScenario<MainActivity>.loadHtmlFixture(body: String) {
        onView(isAssignableFrom(EditText::class.java))
            .perform(replaceText(FIXTURE_URL), pressImeActionButton())
        if (!waitUntilWebViewUrl(this, FIXTURE_URL) || !waitUntilDocumentReady(this)) return

        val markup =
            "<style>html,body{margin:0}a{display:block;width:200px;height:120px}</style>$body"
        val injected = AtomicBoolean(false)
        onActivity { activity ->
            activity.webView().evaluateJavascript(
                "document.body.innerHTML = ${JSONObject.quote(markup)}; true"
            ) { result ->
                injected.set(result == "true")
            }
        }
        assertTrue("Fixture HTML injection failed", waitUntil { injected.get() })
    }

    private fun waitUntilDocumentReady(scenario: ActivityScenario<MainActivity>): Boolean {
        repeat(STARTUP_POLL_ATTEMPTS) {
            val ready = AtomicBoolean(false)
            val callback = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.webView().evaluateJavascript(
                    "location.href === ${JSONObject.quote(FIXTURE_URL)} && " +
                        "document.readyState === 'complete'"
                ) { result ->
                    ready.set(result == "true")
                    callback.countDown()
                }
            }
            if (
                callback.await(JAVASCRIPT_CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) &&
                    ready.get()
            ) {
                return true
            }
            SystemClock.sleep(STARTUP_POLL_MILLIS)
        }
        return false
    }

    private fun ActivityScenario<MainActivity>.tapFixtureLink() {
        onActivity { activity ->
            val webView = activity.webView()
            webView.requestFocus()
            val downTime = SystemClock.uptimeMillis()
            val down = touchEvent(downTime, downTime, MotionEvent.ACTION_DOWN)
            webView.dispatchTouchEvent(down)
            down.recycle()
            SystemClock.sleep(TAP_DURATION_MILLIS)
            val up =
                touchEvent(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                )
            webView.dispatchTouchEvent(up)
            up.recycle()
        }
    }

    private fun MainActivity.webView(): WebView {
        val content = findViewById<ViewGroup>(android.R.id.content)
        return content.descendantsOfType(WebView::class.java).single()
    }

    private fun ActivityScenario<MainActivity>.webViewUrl(): String? {
        var result: String? = null
        onActivity { activity ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            result = content.descendantsOfType(WebView::class.java).single().url
        }
        return result
    }

    private fun ActivityScenario<MainActivity>.addressText(): String {
        var result = ""
        onActivity { activity ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            result = content.descendantsOfType(EditText::class.java).single().text.toString()
        }
        return result
    }

    private fun ActivityScenario<MainActivity>.evaluateJavascript(script: String): String {
        val callback = CountDownLatch(1)
        var result: String? = null
        onActivity { activity ->
            activity.webView().evaluateJavascript(script) { value ->
                result = value
                callback.countDown()
            }
        }
        assertTrue(
            "JavaScript result callback timed out",
            callback.await(JAVASCRIPT_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return checkNotNull(result)
    }

    private fun <T : View> View.descendantsOfType(type: Class<T>): List<T> {
        val matches = mutableListOf<T>()
        if (type.isInstance(this)) type.cast(this)?.let(matches::add)
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                matches += getChildAt(index).descendantsOfType(type)
            }
        }
        return matches
    }

    private fun assumeDirectLinkBridgeSupported() {
        assumeTrue(
            "Isolated document-start JavaScript is unavailable in this WebView",
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD),
        )
    }

    private fun touchEvent(downTime: Long, eventTime: Long, action: Int): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, TAP_X, TAP_Y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

    companion object {
        private const val FIXTURE_ORIGIN = "https://example.com"
        private const val FIXTURE_URL = "$FIXTURE_ORIGIN/"
        private const val STARTUP_POLL_ATTEMPTS = 100
        private const val STARTUP_POLL_MILLIS = 50L
        private const val JAVASCRIPT_CALLBACK_TIMEOUT_MILLIS = 250L
        private const val JAVASCRIPT_RESULT_TIMEOUT_SECONDS = 5L
        private const val SCRIPT_INSTALL_SETTLE_MILLIS = 250L
        private const val TAP_DURATION_MILLIS = 50L
        private const val TAP_X = 50f
        private const val TAP_Y = 50f
    }
}
