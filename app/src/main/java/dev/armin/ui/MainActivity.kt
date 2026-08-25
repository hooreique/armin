package dev.armin.ui

import android.graphics.Color
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import dev.armin.browser.BrowserController
import dev.armin.browser.BrowserUiCallbacks
import dev.armin.proxy.LocalConnectProxy
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity(), BrowserUiCallbacks {
    private lateinit var root: FrameLayout
    private lateinit var browserContent: LinearLayout
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var fullscreenController: FullscreenVideoController

    private val startupExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val destroyed = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)
    private val mainThreadExecutor = Executor { command -> runOnUiThread(command) }

    @Volatile private var localProxy: LocalConnectProxy? = null
    private var proxyOverrideRequested = false
    private var browserController: BrowserController? = null
    private var suppressAddressWatcher = false
    private var webViewDestroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        createBrowserViews()
        installBackHandling()

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            showStartupError("이 기기의 WebView는 앱 프록시를 지원하지 않습니다.")
            return
        }
        startAndApplyProxy()
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
                    .showSoftInput(addressBar, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun showInvalidAddress() {
        Toast.makeText(this, "HTTPS 주소만 열 수 있습니다.", Toast.LENGTH_SHORT).show()
    }

    override fun onRendererTerminated() {
        browserController?.close()
        browserController = null
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
        cleanupProxy()
        startupExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun configureWindow() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun createBrowserViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        browserContent =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }
        webView = WebView(this).apply { setBackgroundColor(Color.BLACK) }
        addressBar =
            EditText(this).apply {
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setBackgroundColor(Color.rgb(24, 24, 24))
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
        applySystemBarInsets()
        bindAddressInput()
    }

    private fun applySystemBarInsets() {
        val baseLeft = browserContent.paddingLeft
        val baseTop = browserContent.paddingTop
        val baseRight = browserContent.paddingRight
        val baseBottom = addressBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            browserContent.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                browserContent.paddingBottom,
            )
            addressBar.setPadding(
                addressBar.paddingLeft,
                addressBar.paddingTop,
                addressBar.paddingRight,
                baseBottom + bars.bottom,
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

    private fun startAndApplyProxy() {
        startupExecutor.execute {
            val proxy = LocalConnectProxy()
            try {
                val address = proxy.start()
                localProxy = proxy
                if (destroyed.get()) {
                    proxy.close()
                    return@execute
                }
                runOnUiThread { applyProxyOverride(address) }
            } catch (_: Exception) {
                proxy.close()
                if (!destroyed.get()) {
                    runOnUiThread { showStartupError("로컬 프록시를 시작하지 못했습니다.") }
                }
            }
        }
    }

    private fun applyProxyOverride(address: InetSocketAddress) {
        if (destroyed.get()) return
        val proxyConfig =
            ProxyConfig.Builder()
                .addProxyRule(
                    "${address.hostString}:${address.port}",
                    ProxyConfig.MATCH_HTTPS,
                )
                .removeImplicitRules()
                .build()
        proxyOverrideRequested = true
        try {
            ProxyController.getInstance()
                .setProxyOverride(proxyConfig, mainThreadExecutor) {
                    if (destroyed.get()) {
                        cleanupProxy()
                    } else {
                        initializeBrowserAfterProxyReady()
                    }
                }
        } catch (_: RuntimeException) {
            showStartupError("WebView 프록시를 적용하지 못했습니다.")
            cleanupProxy()
        }
    }

    private fun initializeBrowserAfterProxyReady() {
        if (browserController != null || destroyed.get()) return
        browserController = BrowserController(webView, this, fullscreenController)
        addressBar.isEnabled = true
        addressBar.requestFocus()
    }

    private fun cleanupProxy() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        val closeProxy = Runnable {
            localProxy?.close()
            localProxy = null
        }
        if (proxyOverrideRequested) {
            runCatching {
                    ProxyController.getInstance()
                        .clearProxyOverride(mainThreadExecutor, closeProxy)
                }
                .onFailure { closeProxy.run() }
            // The callback is normally prompt, but shutdown must not leak the listening socket if
            // a WebView provider process dies before invoking it. LocalConnectProxy.close is
            // idempotent, so the callback may safely repeat this operation.
            closeProxy.run()
        } else {
            closeProxy.run()
        }
    }

    private fun showStartupError(message: String) {
        addressBar.isEnabled = false
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
