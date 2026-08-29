package dev.armin.ui

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.armin.browser.BrowserController
import dev.armin.browser.ContentBlockingDecision
import dev.armin.browser.ContentBlockingEngine
import dev.armin.browser.LocalReplacement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserAcceptanceTest {
    @Test
    fun targetBlankNeverReplacesTheCurrentDocumentOrCreatesAnAppWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.prepareFixture("<a id='popup' target='_blank' href='#target-blank'>popup</a>")
            val addressBefore = scenario.addressText()

            scenario.tapWebContent(TAP_X, TAP_Y)
            SystemClock.sleep(NAVIGATION_SETTLE_MILLIS)

            assertEquals(FIXTURE_URL, scenario.webViewUrl())
            assertEquals(addressBefore, scenario.addressText())
            scenario.onActivity { activity ->
                assertEquals(1, activity.contentRoot().descendantsOfType(WebView::class.java).size)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun automaticAndGestureWindowOpenNeverReplaceTheCurrentDocument() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.prepareFixture(
                "<button id='popup' onclick=\"window.open('#gesture-popup','_blank')\">" +
                    "popup</button>"
            )
            val addressBefore = scenario.addressText()

            assertEquals(
                "true",
                scenario.evaluateJavascript(
                    "(() => { window.open('#automatic-popup', '_blank'); return true; })()"
                ),
            )
            SystemClock.sleep(NAVIGATION_SETTLE_MILLIS)
            assertEquals(FIXTURE_URL, scenario.webViewUrl())

            scenario.tapWebContent(TAP_X, TAP_Y)
            SystemClock.sleep(NAVIGATION_SETTLE_MILLIS)

            assertEquals(FIXTURE_URL, scenario.webViewUrl())
            assertEquals(addressBefore, scenario.addressText())
            scenario.onActivity { activity ->
                assertEquals(1, activity.contentRoot().descendantsOfType(WebView::class.java).size)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun javascriptLocationIsPendingFocusedAndOnlyLoadsAfterApproval() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.prepareFixture("<p>source document</p>")

            scenario.runJavascript("window.location.assign('$JAVASCRIPT_DESTINATION')")

            assertTrue(
                "JavaScript navigation was not presented as pending",
                waitUntil {
                    scenario.addressText() == JAVASCRIPT_DESTINATION_DISPLAY &&
                        scenario.addressHasFocus()
                },
            )
            SystemClock.sleep(LATE_CALLBACK_SETTLE_MILLIS)
            assertEquals(FIXTURE_URL, scenario.webViewUrl())
            assertEquals(JAVASCRIPT_DESTINATION_DISPLAY, scenario.addressText())
            assertTrue(scenario.addressHasFocus())

            onView(isAssignableFrom(EditText::class.java)).perform(pressImeActionButton())

            assertTrue(
                "The approved pending destination did not load",
                waitUntil { scenario.webViewUrl() == JAVASCRIPT_DESTINATION },
            )
        }
    }

    @Test
    fun dynamicMetaRefreshIsPendingAndCannotBeCommittedByLateCallbacks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.prepareFixture("<p>source document</p>")

            scenario.runJavascript(
                """
                (() => {
                  const refresh = document.createElement('meta');
                  refresh.httpEquiv = 'refresh';
                  refresh.content = '0; url=$META_REFRESH_DESTINATION';
                  document.head.appendChild(refresh);
                })()
                """
                    .trimIndent()
            )

            assertTrue(
                "Meta refresh was not presented as pending",
                waitUntil {
                    scenario.addressText() == META_REFRESH_DESTINATION_DISPLAY &&
                        scenario.addressHasFocus()
                },
            )
            SystemClock.sleep(LATE_CALLBACK_SETTLE_MILLIS)
            assertEquals(FIXTURE_URL, scenario.webViewUrl())
            assertEquals(META_REFRESH_DESTINATION_DISPLAY, scenario.addressText())
            assertTrue(scenario.addressHasFocus())
        }
    }

    @Test
    fun fullscreenBackTakesPriorityThenTheNextBackTraversesHistory() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.prepareFixture(
                """
                <button id="fullscreen"
                  onclick="const e=document.documentElement;
                    (e.requestFullscreen||e.webkitRequestFullscreen).call(e)">
                  fullscreen
                </button>
                """
                    .trimIndent()
            )
            val previousOrientation = scenario.requestedOrientation()
            assertEquals(
                "\"$HISTORY_URL\"",
                scenario.evaluateJavascript(
                    "history.pushState({}, '', '#history-entry'); window.location.href"
                ),
            )
            assertTrue(waitUntil { scenario.webViewUrl() == HISTORY_URL })

            scenario.tapWebContent(TAP_X, TAP_Y)

            assertTrue(
                "The WebView provider did not enter a custom fullscreen view",
                waitUntil {
                    !scenario.addressIsShown() &&
                        scenario.requestedOrientation() ==
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                },
            )

            scenario.pressAppBack()

            assertTrue(
                "Back did not close the custom view",
                waitUntil {
                    scenario.addressIsShown() &&
                        scenario.requestedOrientation() == previousOrientation
                },
            )
            assertEquals(HISTORY_URL, scenario.webViewUrl())
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }

            scenario.pressAppBack()

            assertTrue(
                "The second back did not traverse WebView history",
                waitUntil { scenario.webViewUrl() == FIXTURE_URL },
            )
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }

    @Test
    fun backWithoutFullscreenOrHistoryFinishesTheActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assumeTrue(
                "Proxy override is unavailable in this WebView",
                waitUntilAddressReady(scenario),
            )
            var finishing = false

            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                finishing = activity.isFinishing
            }

            assertTrue(finishing)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun darkThemeIsExposedToPrefersColorSchemeContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.prepareFixture(
                """
                <style>
                  #probe { color: rgb(255, 0, 0); }
                  @media (prefers-color-scheme: dark) {
                    #probe { color: rgb(1, 2, 3); }
                  }
                </style>
                <div id="probe">scheme probe</div>
                """
                    .trimIndent()
            )
            scenario.onActivity { activity ->
                val isLightTheme = TypedValue()
                assertTrue(
                    activity.theme.resolveAttribute(
                        android.R.attr.isLightTheme,
                        isLightTheme,
                        true,
                    )
                )
                assertEquals(0, isLightTheme.data)
                assertEquals(
                    Color.BLACK,
                    (activity.contentRoot().getChildAt(0).background as ColorDrawable).color,
                )
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).run {
                    assertFalse(isAppearanceLightStatusBars)
                    assertFalse(isAppearanceLightNavigationBars)
                }
            }

            assertEquals(
                "true",
                scenario.evaluateJavascript(
                    "window.matchMedia('(prefers-color-scheme: dark)').matches"
                ),
            )
            assertEquals(
                "\"rgb(1, 2, 3)\"",
                scenario.evaluateJavascript(
                    "window.getComputedStyle(document.getElementById('probe')).color"
                ),
            )
        }
    }

    @Test
    fun nativeSafeAreaIsAppliedOnceAndNotExposedToWebContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.prepareFixture(SAFE_AREA_PROBE)
            scenario.hideIme()
            assertTrue("IME did not close before the test", scenario.waitForImeVisibility(false))

            scenario.onActivity { activity ->
                val browserContent = activity.browserContent()
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(browserContent))
                val safeArea =
                    insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout()
                    )

                assertTrue(
                    "The test window did not report a system safe area",
                    safeArea.top > 0 || safeArea.bottom > 0,
                )
                assertEquals(safeArea.left, browserContent.paddingLeft)
                assertEquals(safeArea.top, browserContent.paddingTop)
                assertEquals(safeArea.right, browserContent.paddingRight)
                assertEquals(safeArea.bottom, browserContent.paddingBottom)
            }
            assertEquals("\"0px|0px\"", scenario.safeAreaProbe())
        }
    }

    @Test
    fun imeResizesWebContentOnceAndRestoresItAfterClosing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.prepareFixture(SAFE_AREA_PROBE)
            scenario.hideIme()
            assertTrue("IME did not close before the test", scenario.waitForImeVisibility(false))

            val closedHeight = scenario.webViewHeight()
            val closedSafeArea = scenario.safeAreaProbe()

            scenario.showIme()
            assumeTrue(
                "The test device did not show its software keyboard",
                scenario.waitForImeVisibility(true),
            )

            var expectedReduction = 0
            scenario.onActivity { activity ->
                val browserContent = activity.browserContent()
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(browserContent))
                val safeArea =
                    insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout()
                    )
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                expectedReduction = maxOf(safeArea.bottom, ime.bottom) - safeArea.bottom
                assertEquals(maxOf(safeArea.bottom, ime.bottom), browserContent.paddingBottom)
            }
            assertTrue("IME did not reduce the WebView height", expectedReduction > 0)
            assertEquals(expectedReduction, closedHeight - scenario.webViewHeight())
            assertEquals("\"0px|0px\"", scenario.safeAreaProbe())

            scenario.hideIme()
            assertTrue("IME did not close after the test", scenario.waitForImeVisibility(false))
            assertTrue(
                "WebView height was not restored after closing the IME",
                waitUntil { scenario.webViewHeight() == closedHeight },
            )
            assertEquals(closedSafeArea, scenario.safeAreaProbe())
            assertEquals("\"0px|0px\"", scenario.safeAreaProbe())
        }
    }

    @Test
    fun persistentCookieAndLocalStorageSurviveActivityRecreation() {
        val suffix = System.nanoTime().toString().replace('-', 'n')
        val storageKey = "armin_acceptance_storage_$suffix"
        val cookieName = "armin_acceptance_cookie_$suffix"
        val value = "value_$suffix"

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.prepareFixture("<p>storage fixture</p>")
            assertEquals(
                "true",
                scenario.evaluateJavascript(
                    """
                    (() => {
                      localStorage.setItem('$storageKey', '$value');
                      document.cookie =
                        '$cookieName=$value; Max-Age=600; Path=/; Secure; SameSite=Strict';
                      return localStorage.getItem('$storageKey') === '$value' &&
                        document.cookie.split('; ').includes('$cookieName=$value');
                    })()
                    """
                        .trimIndent()
                ),
            )
            scenario.onActivity { CookieManager.getInstance().flush() }
            SystemClock.sleep(STORAGE_FLUSH_SETTLE_MILLIS)

            scenario.recreate()
            scenario.prepareFixture("<p>storage fixture after recreation</p>")

            try {
                assertEquals(
                    "true",
                    scenario.evaluateJavascript(
                        "localStorage.getItem('$storageKey') === '$value' && " +
                            "document.cookie.split('; ').includes('$cookieName=$value')"
                    ),
                )
            } finally {
                scenario.evaluateJavascript(
                    """
                    (() => {
                      localStorage.removeItem('$storageKey');
                      document.cookie =
                        '$cookieName=; Max-Age=0; Path=/; Secure; SameSite=Strict';
                      return true;
                    })()
                    """
                        .trimIndent()
                )
                scenario.onActivity { CookieManager.getInstance().flush() }
            }
        }
    }

    private fun ActivityScenario<MainActivity>.prepareFixture(body: String) {
        assumeTrue(
            "Proxy override is unavailable in this WebView",
            waitUntilAddressReady(this),
        )
        lateinit var controller: BrowserController
        onActivity { activity -> controller = activity.installFixtureHarness(body) }
        assertTrue(
            "Test harness blank document did not load",
            waitUntil { webViewUrl() == "about:blank" },
        )
        onActivity { assertTrue(controller.submitAddress(FIXTURE_URL)) }
        assumeTrue("Fixture did not load", waitUntil { webViewUrl() == FIXTURE_URL })
        assertTrue(
            "Fixture document did not finish loading",
            waitUntil {
                evaluateJavascript(
                    "location.href === '$FIXTURE_URL' && document.readyState === 'complete'"
                ) == "true"
            },
        )
        SystemClock.sleep(SCRIPT_INSTALL_SETTLE_MILLIS)
    }

    private fun waitUntilAddressReady(scenario: ActivityScenario<MainActivity>): Boolean =
        waitUntil {
            var ready = false
            scenario.onActivity { activity ->
                val addressBar = activity.addressBar()
                ready = addressBar.isEnabled && activity.webView().url == "about:blank"
            }
            ready
        }

    private fun ActivityScenario<MainActivity>.runJavascript(script: String) {
        onActivity { activity -> activity.webView().evaluateJavascript(script, null) }
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
            callback.await(JAVASCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return checkNotNull(result)
    }

    private fun ActivityScenario<MainActivity>.safeAreaProbe(): String =
        evaluateJavascript(
            "(() => { const style = getComputedStyle(document.getElementById('safe-area-probe')); " +
                "return `${'$'}{style.paddingTop}|${'$'}{style.paddingBottom}`; })()"
        )

    private fun ActivityScenario<MainActivity>.webViewHeight(): Int {
        var height = 0
        onActivity { activity -> height = activity.webView().height }
        return height
    }

    private fun ActivityScenario<MainActivity>.showIme() {
        onActivity { activity ->
            val addressBar = activity.addressBar()
            addressBar.requestFocus()
            activity
                .getSystemService(InputMethodManager::class.java)
                .showSoftInput(
                    addressBar,
                    0,
                )
        }
    }

    private fun ActivityScenario<MainActivity>.hideIme() {
        onActivity { activity ->
            activity.webView().requestFocus()
            WindowCompat.getInsetsController(activity.window, activity.browserContent())
                .hide(WindowInsetsCompat.Type.ime())
        }
    }

    private fun ActivityScenario<MainActivity>.waitForImeVisibility(visible: Boolean): Boolean =
        waitUntil {
            var isVisible = false
            onActivity { activity ->
                isVisible =
                    ViewCompat.getRootWindowInsets(activity.browserContent())
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            isVisible == visible
        }

    private fun ActivityScenario<MainActivity>.tapWebContent(x: Float, y: Float) {
        onActivity { activity ->
            val webView = activity.webView()
            webView.requestFocus()
            val downTime = SystemClock.uptimeMillis()
            val down = touchEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
            webView.dispatchTouchEvent(down)
            down.recycle()
            SystemClock.sleep(TAP_DURATION_MILLIS)
            val up =
                touchEvent(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    x,
                    y,
                )
            webView.dispatchTouchEvent(up)
            up.recycle()
        }
    }

    private fun ActivityScenario<MainActivity>.pressAppBack() {
        onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun ActivityScenario<MainActivity>.webViewUrl(): String? {
        var result: String? = null
        onActivity { activity -> result = activity.webView().url }
        return result
    }

    private fun ActivityScenario<MainActivity>.addressText(): String {
        var result = ""
        onActivity { activity -> result = activity.addressBar().text.toString() }
        return result
    }

    private fun ActivityScenario<MainActivity>.addressHasFocus(): Boolean {
        var result = false
        onActivity { activity -> result = activity.addressBar().hasFocus() }
        return result
    }

    private fun ActivityScenario<MainActivity>.addressIsShown(): Boolean {
        var result = false
        onActivity { activity -> result = activity.addressBar().isShown }
        return result
    }

    private fun ActivityScenario<MainActivity>.requestedOrientation(): Int {
        var result = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onActivity { activity -> result = activity.requestedOrientation }
        return result
    }

    private fun MainActivity.contentRoot(): ViewGroup =
        findViewById<ViewGroup>(android.R.id.content)

    private fun MainActivity.webView(): WebView =
        contentRoot().descendantsOfType(WebView::class.java).single()

    private fun MainActivity.addressBar(): EditText =
        contentRoot().descendantsOfType(EditText::class.java).single()

    private fun MainActivity.browserContent(): LinearLayout =
        privateField("browserContent") as LinearLayout

    private fun MainActivity.installFixtureHarness(body: String): BrowserController {
        val oldFullscreenController =
            privateField("fullscreenController") as FullscreenVideoController
        val oldBrowserController = privateField("browserController") as BrowserController?
        val oldWebView = privateField("webView") as WebView
        setPrivateField("browserController", null)
        oldFullscreenController.destroy()
        oldBrowserController?.close()
        oldWebView.destroy()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val browserContent =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }
        val fixtureWebView = WebView(this).apply { setBackgroundColor(Color.BLACK) }
        val fixtureAddressBar =
            EditText(this).apply {
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.DKGRAY)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isEnabled = true
            }
        browserContent.addView(
            fixtureWebView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        browserContent.addView(
            fixtureAddressBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            browserContent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        val fullscreenController = FullscreenVideoController(this, root, browserContent)
        setPrivateField("root", root)
        setPrivateField("browserContent", browserContent)
        setPrivateField("webView", fixtureWebView)
        setPrivateField("addressBar", fixtureAddressBar)
        setPrivateField("fullscreenController", fullscreenController)
        MainActivity::class
            .java
            .getDeclaredMethod("applySafeAreaInsets")
            .apply { isAccessible = true }
            .invoke(this)

        val controller =
            BrowserController(
                fixtureWebView,
                this,
                fullscreenController,
                fixtureEngine(body),
            )
        setPrivateField("browserController", controller)
        MainActivity::class
            .java
            .getDeclaredMethod("bindAddressInput")
            .apply { isAccessible = true }
            .invoke(this)
        fixtureAddressBar.requestFocus()
        return controller
    }

    private fun fixtureEngine(body: String): ContentBlockingEngine {
        val document =
            ("<!doctype html><meta name='viewport' " +
                    "content='width=device-width,viewport-fit=cover'>" +
                    "<style>html,body{margin:0}a,button{display:block;width:200px;" +
                    "height:120px}</style>$body")
                .toByteArray(Charsets.UTF_8)
        return ContentBlockingEngine { request ->
            if (request.isMainFrame && request.url == FIXTURE_URL) {
                ContentBlockingDecision.Redirect(
                    LocalReplacement(
                        mimeType = "text/html",
                        encoding = "UTF-8",
                        data = document,
                        responseHeaders = mapOf("Cache-Control" to "no-store"),
                    )
                )
            } else {
                ContentBlockingDecision.Allow
            }
        }
    }

    private fun MainActivity.privateField(name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(this)
        }

    private fun MainActivity.setPrivateField(name: String, value: Any?) {
        MainActivity::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(this, value)
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

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (condition()) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun touchEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

    companion object {
        private const val FIXTURE_URL = "https://fixture.test/page"
        private const val JAVASCRIPT_DESTINATION =
            "https://fixture.test/js-pending?source=javascript#fragment"
        private const val JAVASCRIPT_DESTINATION_DISPLAY =
            "fixture.test/js-pending?source=javascript#fragment"
        private const val META_REFRESH_DESTINATION =
            "https://fixture.test/meta-pending?source=refresh#fragment"
        private const val META_REFRESH_DESTINATION_DISPLAY =
            "fixture.test/meta-pending?source=refresh#fragment"
        private const val HISTORY_URL = "$FIXTURE_URL#history-entry"
        private const val SAFE_AREA_PROBE =
            "<div id='safe-area-probe' style='padding-top:env(safe-area-inset-top);" +
                "padding-bottom:env(safe-area-inset-bottom)'>safe area probe</div>"
        private const val POLL_ATTEMPTS = 120
        private const val POLL_MILLIS = 50L
        private const val SCRIPT_INSTALL_SETTLE_MILLIS = 250L
        private const val NAVIGATION_SETTLE_MILLIS = 400L
        private const val LATE_CALLBACK_SETTLE_MILLIS = 400L
        private const val STORAGE_FLUSH_SETTLE_MILLIS = 250L
        private const val TAP_DURATION_MILLIS = 50L
        private const val TAP_X = 50f
        private const val TAP_Y = 50f
        private const val JAVASCRIPT_TIMEOUT_SECONDS = 5L
    }
}
