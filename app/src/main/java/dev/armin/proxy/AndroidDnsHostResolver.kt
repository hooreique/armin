package dev.armin.proxy

import android.net.DnsResolver
import android.net.InetAddresses
import android.os.CancellationSignal
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Starts cancellable platform DNS queries without creating an application-owned DNS thread. */
internal class AndroidDnsHostResolver(
    private val queryLauncher: DnsQueryLauncher = PlatformDnsQueryLauncher,
    private val numericAddressParser: NumericAddressParser = PlatformNumericAddressParser,
) : HostResolver {
    override fun start(host: String): HostResolution {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        numericAddressParser.parseOrNull(host)?.let {
            return CompletedHostResolution(it)
        }

        val completion = CompletableFuture<Array<InetAddress>>()
        val cancellation =
            queryLauncher.query(
                host,
                object : DnsQueryCallback {
                    override fun onAnswer(addresses: Array<InetAddress>) {
                        if (addresses.isEmpty()) {
                            onFailure()
                        } else {
                            completion.complete(addresses)
                        }
                    }

                    override fun onFailure() {
                        completion.completeExceptionally(UnknownHostException(host))
                    }
                },
            )
        val resolution = PendingHostResolution(completion, cancellation)
        if (Thread.currentThread().isInterrupted) {
            resolution.cancel()
            throw InterruptedException()
        }
        return resolution
    }
}

internal fun interface DnsQueryLauncher {
    fun query(host: String, callback: DnsQueryCallback): DnsQueryCancellation
}

internal interface DnsQueryCallback {
    fun onAnswer(addresses: Array<InetAddress>)

    fun onFailure()
}

internal fun interface DnsQueryCancellation {
    fun cancel()
}

internal fun interface NumericAddressParser {
    fun parseOrNull(host: String): InetAddress?
}

private class CompletedHostResolution(address: InetAddress) : HostResolution {
    private val addresses = arrayOf(address)

    override fun await(timeoutNanos: Long): Array<InetAddress> = addresses.copyOf()

    override fun cancel() = Unit
}

private class PendingHostResolution(
    private val completion: CompletableFuture<Array<InetAddress>>,
    private val platformCancellation: DnsQueryCancellation,
) : HostResolution {
    private val cancelled = AtomicBoolean(false)

    override fun await(timeoutNanos: Long): Array<InetAddress> =
        completion.get(timeoutNanos, TimeUnit.NANOSECONDS)

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        completion.cancel(false)
        try {
            platformCancellation.cancel()
        } catch (_: RuntimeException) {
            // The completion is already terminal; cleanup must not crash the proxy worker.
        }
    }
}

private object PlatformNumericAddressParser : NumericAddressParser {
    override fun parseOrNull(host: String): InetAddress? =
        if (InetAddresses.isNumericAddress(host)) InetAddresses.parseNumericAddress(host) else null
}

@Suppress("DEPRECATION")
private object PlatformDnsQueryLauncher : DnsQueryLauncher {
    override fun query(host: String, callback: DnsQueryCallback): DnsQueryCancellation {
        val cancellationSignal = CancellationSignal()
        DnsResolver.getInstance()
            .query(
                null,
                host,
                DnsResolver.FLAG_EMPTY,
                DIRECT_EXECUTOR,
                cancellationSignal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (rcode == DNS_RCODE_SUCCESS && answer.isNotEmpty()) {
                            callback.onAnswer(answer.toTypedArray())
                        } else {
                            callback.onFailure()
                        }
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        callback.onFailure()
                    }
                },
            )
        return DnsQueryCancellation(cancellationSignal::cancel)
    }
}

private val DIRECT_EXECUTOR = Executor(Runnable::run)
private const val DNS_RCODE_SUCCESS = 0
