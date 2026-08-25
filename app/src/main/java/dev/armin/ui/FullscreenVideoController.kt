package dev.armin.ui

import android.content.pm.ActivityInfo
import android.view.View
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.armin.browser.FullscreenViewHost

class FullscreenVideoController(
    private val activity: ComponentActivity,
    private val root: FrameLayout,
    private val browserContent: View,
) : FullscreenViewHost {
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var previousBarsBehavior = 0
    private var statusBarWasVisible = true
    private var navigationBarWasVisible = true

    val isFullscreen: Boolean
        get() = customView != null

    override fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (isFullscreen) {
            callback.onCustomViewHidden()
            return
        }

        ViewCompat.getRootWindowInsets(root)?.let { insets ->
            statusBarWasVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
            navigationBarWasVisible =
                insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        }
        previousOrientation = activity.requestedOrientation

        val barsController = WindowCompat.getInsetsController(activity.window, root)
        previousBarsBehavior = barsController.systemBarsBehavior

        customView = view
        customViewCallback = callback
        view.setBackgroundColor(android.graphics.Color.BLACK)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        browserContent.visibility = View.GONE
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        barsController.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        barsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun hideCustomView() {
        finishFullscreen(notifyWebView = false)
    }

    /** Returns true when back was consumed by an active custom video view. */
    fun exitFullscreen(): Boolean {
        if (!isFullscreen) return false
        finishFullscreen(notifyWebView = true)
        return true
    }

    fun destroy() {
        finishFullscreen(notifyWebView = true)
    }

    private fun finishFullscreen(notifyWebView: Boolean) {
        val view = customView ?: return
        val callback = customViewCallback
        customView = null
        customViewCallback = null

        root.removeView(view)
        browserContent.visibility = View.VISIBLE
        activity.requestedOrientation = previousOrientation

        val barsController = WindowCompat.getInsetsController(activity.window, root)
        barsController.systemBarsBehavior = previousBarsBehavior
        restoreBar(
            barsController,
            WindowInsetsCompat.Type.statusBars(),
            statusBarWasVisible,
        )
        restoreBar(
            barsController,
            WindowInsetsCompat.Type.navigationBars(),
            navigationBarWasVisible,
        )

        if (notifyWebView) callback?.onCustomViewHidden()
    }

    private fun restoreBar(
        controller: androidx.core.view.WindowInsetsControllerCompat,
        type: Int,
        wasVisible: Boolean,
    ) {
        if (wasVisible) controller.show(type) else controller.hide(type)
    }
}
