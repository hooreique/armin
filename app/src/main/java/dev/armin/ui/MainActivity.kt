package dev.armin.ui

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.armin.R
import dev.armin.browser.BrowserController
import dev.armin.browser.BrowserUiCallbacks
import java.util.concurrent.atomic.AtomicBoolean

// This Activity intentionally owns the small app's view and lifecycle coordination.
@Suppress("TooManyFunctions")
class MainActivity : ComponentActivity(), BrowserUiCallbacks {
    private lateinit var root: FrameLayout
    private lateinit var browserContent: LinearLayout
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var fullscreenController: FullscreenVideoController

    private val destroyed = AtomicBoolean(false)

    private var proxyLease: WebViewProxyLease? = null
    private var browserController: BrowserController? = null
    private var suppressAddressWatcher = false
    private var webViewDestroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        createBrowserViews()
        installBackHandling()

        acquireProxySession()
    }

    override fun replaceAddress(value: String, requestFocus: Boolean) {
        suppressAddressWatcher = true
        try {
            addressBar.setText(value)
            addressBar.setSelection(value.length)
        } finally {
            suppressAddressWatcher = false
        }
        if (requestFocus) {
            addressBar.requestFocus()
            addressBar.post {
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(
                        addressBar,
                        0,
                    )
            }
        }
    }

    override fun showInvalidAddress() {
        Toast.makeText(this, "HTTPS 주소만 열 수 있습니다.", Toast.LENGTH_SHORT).show()
    }

    override fun onRendererTerminated() {
        // BrowserController deliberately avoids touching a crashed WebView in this path.
        browserController = null
        fullscreenController.discardAfterRendererGone()
        webView.destroy()
        webViewDestroyed = true
        Toast.makeText(this, "웹 렌더러가 종료되어 브라우저를 닫습니다.", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        destroyed.set(true)
        fullscreenController.destroy()
        browserController?.close()
        browserController = null
        if (!webViewDestroyed) webView.destroy()
        proxyLease?.close()
        proxyLease = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun configureWindow() {
        val black = ContextCompat.getColor(this, R.color.black)
        window.statusBarColor = black
        window.navigationBarColor = black
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun createBrowserViews() {
        val black = ContextCompat.getColor(this, R.color.black)
        root = FrameLayout(this).apply { setBackgroundColor(black) }
        browserContent =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(black)
            }
        webView = WebView(this).apply { setBackgroundColor(black) }
        addressBar =
            EditText(this).apply {
                setSingleLine(true)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.address_bar_text))
                setHintTextColor(
                    ContextCompat.getColor(this@MainActivity, R.color.address_bar_hint)
                )
                setBackgroundColor(
                    ContextCompat.getColor(this@MainActivity, R.color.address_bar_background)
                )
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isEnabled = false
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

        browserContent.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        browserContent.addView(
            addressBar,
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
        fullscreenController = FullscreenVideoController(this, root, browserContent)
        applySafeAreaInsets()
        bindAddressInput()
    }

    private fun applySafeAreaInsets() {
        val baseLeft = browserContent.paddingLeft
        val baseTop = browserContent.paddingTop
        val baseRight = browserContent.paddingRight
        val baseBottom = browserContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            browserContent.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindAddressInput() {
        addressBar.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    if (!suppressAddressWatcher) browserController?.onAddressEditedByUser()
                }

                override fun afterTextChanged(editable: Editable?) = Unit
            }
        )
        addressBar.setOnEditorActionListener { _, actionId, event ->
            val enterDown =
                event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enterDown) {
                if (browserController?.submitAddress(addressBar.text.toString()) == true) {
                    addressBar.clearFocus()
                    webView.requestFocus()
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(addressBar.windowToken, 0)
                }
                true
            } else {
                false
            }
        }
        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) browserController?.onAddressEditingFinished()
        }
    }

    private fun installBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        fullscreenController.exitFullscreen() -> Unit
                        browserController?.canGoBack() == true -> browserController?.goBack()
                        else -> finish()
                    }
                }
            },
        )
    }

    private fun acquireProxySession() {
        proxyLease =
            WebViewProxySession.acquire(
                object : ProxySessionCallbacks {
                    override fun onProxyReady() {
                        if (!destroyed.get()) initializeBrowserAfterProxyReady()
                    }

                    override fun onProxyFailure(failure: ProxySessionFailure) {
                        if (destroyed.get()) return
                        val message =
                            when (failure) {
                                ProxySessionFailure.UNSUPPORTED ->
                                    "이 기기의 WebView는 앱 프록시를 지원하지 않습니다."
                                ProxySessionFailure.START_FAILED -> "로컬 프록시를 시작하지 못했습니다."
                                ProxySessionFailure.OVERRIDE_FAILED -> "WebView 프록시를 적용하지 못했습니다."
                            }
                        showStartupError(message)
                    }
                }
            )
    }

    private fun initializeBrowserAfterProxyReady() {
        if (browserController != null || destroyed.get()) return
        browserController = BrowserController(webView, this, fullscreenController)
        addressBar.isEnabled = true
        addressBar.requestFocus()
    }

    private fun showStartupError(message: String) {
        addressBar.isEnabled = false
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
