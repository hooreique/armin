package dev.armin.proxy

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidDnsHostResolverTest {
    @Test
    fun numericIpv4AndIpv6BypassDnsQuery() {
        val ipv4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val ipv6 = InetAddress.getByAddress(ByteArray(15) + byteArrayOf(1))
        val addresses = mapOf("127.0.0.1" to ipv4, "::1" to ipv6)
        val launches = AtomicInteger()
        val resolver =
            AndroidDnsHostResolver(
                queryLauncher =
                    DnsQueryLauncher { _, _ ->
                        launches.incrementAndGet()
                        fail("A numeric address must not start DNS")
                        DnsQueryCancellation {}
                    },
                numericAddressParser = NumericAddressParser(addresses::get),
            )

        assertArrayEquals(ipv4.address, resolver.start("127.0.0.1").await(1).single().address)
        assertArrayEquals(ipv6.address, resolver.start("::1").await(1).single().address)
        assertEquals(0, launches.get())
    }

    @Test
    fun asynchronousAnswerCompletesResolution() {
        val callback = AtomicReference<DnsQueryCallback>()
        val expected = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        val resolver = resolverWithCallback(callback)
        val resolution = resolver.start("example.test")

        callback.get().onAnswer(arrayOf(expected))

        assertArrayEquals(
            expected.address,
            resolution.await(TimeUnit.SECONDS.toNanos(1)).single().address,
        )
    }

    @Test
    fun cancellationIsIdempotentAndIgnoresLateAnswer() {
        val callback = AtomicReference<DnsQueryCallback>()
        val cancellationCount = AtomicInteger()
        val resolver = resolverWithCallback(callback) { cancellationCount.incrementAndGet() }
        val resolution = resolver.start("cancel.test")

        resolution.cancel()
        resolution.cancel()
        callback.get().onAnswer(arrayOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))))

        assertThrows(CancellationException::class.java) {
            resolution.await(TimeUnit.SECONDS.toNanos(1))
        }
        assertEquals(1, cancellationCount.get())
    }

    @Test
    fun emptyOrFailedAnswerIsReportedAsUnknownHost() {
        val callback = AtomicReference<DnsQueryCallback>()
        val resolver = resolverWithCallback(callback)
        val emptyResolution = resolver.start("empty.test")
        callback.get().onAnswer(emptyArray())

        val emptyFailure =
            assertThrows(ExecutionException::class.java) {
                emptyResolution.await(TimeUnit.SECONDS.toNanos(1))
            }
        assertTrue(emptyFailure.cause is UnknownHostException)

        val failedResolution = resolver.start("failed.test")
        callback.get().onFailure()
        val queryFailure =
            assertThrows(ExecutionException::class.java) {
                failedResolution.await(TimeUnit.SECONDS.toNanos(1))
            }
        assertTrue(queryFailure.cause is UnknownHostException)
    }

    private fun resolverWithCallback(
        callback: AtomicReference<DnsQueryCallback>,
        onCancel: () -> Unit = {},
    ): AndroidDnsHostResolver =
        AndroidDnsHostResolver(
            queryLauncher =
                DnsQueryLauncher { _, result ->
                    callback.set(result)
                    DnsQueryCancellation(onCancel)
                },
            numericAddressParser = NumericAddressParser { null },
        )
}
