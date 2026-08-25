package dev.armin.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig as WebViewProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import dev.armin.proxy.LocalConnectProxy
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class ProxySessionFailure(internal val retryableWithinProcess: Boolean) {
    UNSUPPORTED(false),
    START_FAILED(true),
    OVERRIDE_FAILED(false),
}

internal interface ProxySessionCallbacks {
    fun onProxyReady()

    fun onProxyFailure(failure: ProxySessionFailure)
}

/** A lifecycle lease on the process-wide WebView proxy override. */
internal class WebViewProxyLease internal constructor(private val id: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) WebViewProxySession.release(id)
    }
}

/**
 * Owns the process-wide WebView proxy override and its loopback listener.
 *
 * Multiple standard Activity instances can overlap. Sharing one ref-counted session prevents an
 * older Activity from clearing a newer Activity's override and silently restoring direct access.
 * Every state transition happens on the main thread; only listener startup uses a worker. Keeping
 * the transitions in one owner makes the process-global set/clear ordering auditable and is the
 * reason for the focused TooManyFunctions suppression.
 */
@Suppress("TooManyFunctions")
internal object WebViewProxySession {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private val nextLeaseId = AtomicLong()
    private val callbacks = linkedMapOf<Long, ProxySessionCallbacks>()

    private var startupGeneration = 0L
    private var clearGeneration = 0L
    private var state = State.IDLE
    private var lastFailure: ProxySessionFailure? = null
    private var proxy: LocalConnectProxy? = null
    private var startupExecutor: ExecutorService? = null
    private var pendingTimeout: Runnable? = null

    fun acquire(callback: ProxySessionCallbacks): WebViewProxyLease {
        checkMainThread()
        val id = nextLeaseId.incrementAndGet()
        callbacks[id] = callback
        when (state) {
            State.IDLE -> beginStartup()
            State.READY -> notifyReady(id, callback)
            State.FAILED -> notifyFailure(id, callback, checkNotNull(lastFailure))
            State.STARTING,
            State.APPLYING,
            State.STOPPING -> Unit
        }
        return WebViewProxyLease(id)
    }

    internal fun release(id: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release(id) }
            return
        }
        if (callbacks.remove(id) == null || callbacks.isNotEmpty()) return

        when (state) {
            State.STARTING -> Unit // The startup completion observes the empty lease set.
            State.APPLYING,
            State.READY -> beginShutdown()
            State.FAILED -> {
                if (lastFailure?.retryableWithinProcess == true) resetToIdle()
            }
            State.IDLE,
            State.STOPPING -> Unit
        }
    }

    private fun beginStartup() {
        check(state == State.IDLE)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            state = State.FAILED
            lastFailure = ProxySessionFailure.UNSUPPORTED
            notifyAllFailures(ProxySessionFailure.UNSUPPORTED)
            return
        }

        state = State.STARTING
        lastFailure = null
        scheduleTimeout(State.STARTING, ProxySessionFailure.START_FAILED)
        val generation = ++startupGeneration
        val executor = Executors.newSingleThreadExecutor()
        startupExecutor = executor
        executor.execute {
            val candidate = LocalConnectProxy()
            try {
                val address = candidate.start()
                mainHandler.post { onProxyStarted(generation, candidate, address) }
            } catch (_: Exception) {
                candidate.close()
                mainHandler.post { onProxyStartFailed(generation) }
            }
        }
    }

    // STARTING is reachable only after beginStartup's PROXY_OVERRIDE feature check.
    @SuppressLint("RequiresFeature")
    private fun onProxyStarted(
        generation: Long,
        candidate: LocalConnectProxy,
        address: InetSocketAddress,
    ) {
        if (generation != startupGeneration) {
            closeProxyAsync(candidate)
            return
        }
        cancelTimeout()
        finishStartupExecutor()
        if (state != State.STARTING) {
            closeProxyAsync(candidate)
            return
        }
        if (callbacks.isEmpty()) {
            closeProxyAsync(candidate)
            state = State.IDLE
            return
        }

        proxy = candidate
        state = State.APPLYING
        scheduleTimeout(State.APPLYING, ProxySessionFailure.OVERRIDE_FAILED)
        val proxyConfig =
            WebViewProxyConfig.Builder()
                .addProxyRule(
                    "${address.hostString}:${address.port}",
                    WebViewProxyConfig.MATCH_HTTPS,
                )
                .removeImplicitRules()
                .build()
        try {
            ProxyController.getInstance().setProxyOverride(proxyConfig, mainExecutor) {
                onProxyOverrideApplied(candidate)
            }
        } catch (_: RuntimeException) {
            onProxyOverrideFailed(candidate)
        }
    }

    private fun onProxyStartFailed(generation: Long) {
        if (generation != startupGeneration) return
        cancelTimeout()
        finishStartupExecutor()
        if (state != State.STARTING) return
        state = State.FAILED
        lastFailure = ProxySessionFailure.START_FAILED
        notifyAllFailures(ProxySessionFailure.START_FAILED)
        if (callbacks.isEmpty() && ProxySessionFailure.START_FAILED.retryableWithinProcess) {
            resetToIdle()
        }
    }

    private fun onProxyOverrideApplied(candidate: LocalConnectProxy) {
        if (state != State.APPLYING || proxy !== candidate) return
        cancelTimeout()
        if (callbacks.isEmpty()) {
            beginShutdown()
            return
        }
        state = State.READY
        callbacks.toList().forEach { (id, callback) ->
            if (callbacks[id] === callback) callback.onProxyReady()
        }
    }

    private fun onProxyOverrideFailed(candidate: LocalConnectProxy) {
        cancelTimeout()
        if (proxy === candidate) proxy = null
        closeProxyAsync(candidate)
        if (state != State.APPLYING) return
        state = State.FAILED
        lastFailure = ProxySessionFailure.OVERRIDE_FAILED
        clearOverrideBestEffort()
        notifyAllFailures(ProxySessionFailure.OVERRIDE_FAILED)
        // A set/clear callback can still arrive after failure. Keep this process-wide state sticky:
        // restarting could let a delayed clear erase a newer override and restore direct access.
    }

    // APPLYING/READY can only follow a successful PROXY_OVERRIDE feature check.
    @SuppressLint("RequiresFeature")
    private fun beginShutdown() {
        if (state == State.STOPPING) return
        state = State.STOPPING
        val generation = ++clearGeneration
        proxy?.let(::closeProxyAsync)
        proxy = null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onProxyOverrideCleared(generation)
            return
        }
        try {
            scheduleTimeout(State.STOPPING, ProxySessionFailure.OVERRIDE_FAILED)
            ProxyController.getInstance().clearProxyOverride(mainExecutor) {
                onProxyOverrideCleared(generation)
            }
        } catch (_: RuntimeException) {
            cancelTimeout()
            onProxyOverrideCleared(generation)
        }
    }

    private fun onProxyOverrideCleared(generation: Long) {
        if (generation != clearGeneration) return
        if (state != State.STOPPING) return
        cancelTimeout()
        state = State.IDLE
        if (callbacks.isNotEmpty()) beginStartup()
    }

    private fun clearOverrideBestEffort() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        runCatching {
            ProxyController.getInstance().clearProxyOverride(mainExecutor) {
                // A failed set has no usable listener to retain after this best-effort clear.
            }
        }
    }

    private fun notifyReady(id: Long, callback: ProxySessionCallbacks) {
        mainHandler.post {
            if (state == State.READY && callbacks[id] === callback) callback.onProxyReady()
        }
    }

    private fun notifyFailure(
        id: Long,
        callback: ProxySessionCallbacks,
        failure: ProxySessionFailure,
    ) {
        mainHandler.post {
            if (state == State.FAILED && callbacks[id] === callback) {
                callback.onProxyFailure(failure)
            }
        }
    }

    private fun notifyAllFailures(failure: ProxySessionFailure) {
        callbacks.toList().forEach { (id, callback) ->
            if (callbacks[id] === callback) callback.onProxyFailure(failure)
        }
    }

    private fun resetToIdle() {
        cancelTimeout()
        proxy?.let(::closeProxyAsync)
        proxy = null
        lastFailure = null
        finishStartupExecutor()
        state = State.IDLE
    }

    private fun scheduleTimeout(expectedState: State, failure: ProxySessionFailure) {
        cancelTimeout()
        val timeout = Runnable {
            if (state != expectedState) return@Runnable
            when (expectedState) {
                State.STARTING -> finishStartupExecutor()
                State.APPLYING -> {
                    proxy?.let(::closeProxyAsync)
                    proxy = null
                    clearOverrideBestEffort()
                }
                State.STOPPING -> Unit
                else -> return@Runnable
            }
            state = State.FAILED
            lastFailure = failure
            notifyAllFailures(failure)
            if (callbacks.isEmpty() && failure.retryableWithinProcess) resetToIdle()
        }
        pendingTimeout = timeout
        mainHandler.postDelayed(timeout, PROXY_OPERATION_TIMEOUT_MILLIS)
    }

    private fun cancelTimeout() {
        pendingTimeout?.let(mainHandler::removeCallbacks)
        pendingTimeout = null
    }

    private fun closeProxyAsync(candidate: LocalConnectProxy) {
        Thread(
                { candidate.close() },
                "armin-proxy-close-${nextCloseThread.incrementAndGet()}",
            )
            .apply { isDaemon = true }
            .start()
    }

    private fun finishStartupExecutor() {
        startupExecutor?.shutdownNow()
        startupExecutor = null
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebView proxy leases must be acquired on the main thread"
        }
    }

    private enum class State {
        IDLE,
        STARTING,
        APPLYING,
        READY,
        FAILED,
        STOPPING,
    }

    private const val PROXY_OPERATION_TIMEOUT_MILLIS = 15_000L
    private val nextCloseThread = AtomicLong()
}
