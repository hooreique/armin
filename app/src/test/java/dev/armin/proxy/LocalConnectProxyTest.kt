package dev.armin.proxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalConnectProxyTest {
    @Test
    fun bindsOnlyIpv4LoopbackOnAnEphemeralPortAndClosesCleanly() {
        val proxy = LocalConnectProxy(testConfig())
        val address = proxy.start()

        assertEquals("127.0.0.1", address.address.hostAddress)
        assertTrue(address.address.isLoopbackAddress)
        assertTrue(address.port in 1..65535)
        assertEquals(address, proxy.start())
        assertTrue(proxy.isRunning)

        proxy.close()

        assertFalse(proxy.isRunning)
        assertEquals(0, proxy.activeSocketCount)
        try {
            Socket().use { it.connect(address, 200) }
            fail("Closed proxy still accepted a connection")
        } catch (_: IOException) {
            // Expected: the loopback listener was closed.
        }
    }

    @Test(timeout = 8_000)
    fun connectHandshakeFallbackAndBidirectionalRelayAreLossless() {
        ServerSocket(0, 4, LOOPBACK).use { upstream ->
            val upstreamExecutor = Executors.newSingleThreadExecutor()
            val original = byteArrayOf(23, 3, 3, 0, 4, 1, 2, 3, 4)
            val upstreamResult =
                upstreamExecutor.submit<ByteArray> {
                    upstream.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val received = socket.getInputStream().readExactly(original.size)
                        socket.getOutputStream().write("reply".toByteArray())
                        socket.getOutputStream().flush()
                        received
                    }
                }

            LocalConnectProxy(testConfig()).use { proxy ->
                val proxyAddress = proxy.start()
                Socket().use { client ->
                    client.soTimeout = 3_000
                    client.connect(proxyAddress)
                    establishTunnel(client, upstream.localPort)
                    client.getOutputStream().write(original)
                    client.getOutputStream().flush()

                    assertArrayEquals("reply".toByteArray(), client.getInputStream().readExactly(5))
                }
                assertArrayEquals(original, upstreamResult.get(3, TimeUnit.SECONDS))
            }
            upstreamExecutor.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun splitClientHelloCompletesARealTlsHandshakeAndRelaysApplicationData() {
        createTlsServerSocket().use { tlsServer ->
            val serverExecutor = Executors.newSingleThreadExecutor()
            val serverResult = serverExecutor.submit<String> { serveTlsRequest(tlsServer) }

            exerciseTlsThroughProxy(tlsServer)

            assertEquals("ping", serverResult.get(4, TimeUnit.SECONDS))
            serverExecutor.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun remoteConnectionFailureReturnsBadGateway() {
        val unavailablePort = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

        LocalConnectProxy(testConfig()).use { proxy ->
            Socket().use { client ->
                client.soTimeout = 2_000
                client.connect(proxy.start())
                writeConnect(client, unavailablePort)

                assertTrue(readHttpHeader(client).startsWith("HTTP/1.1 502 "))
            }
        }
    }

    @Test(timeout = 5_000)
    fun partialConnectHeaderTimesOutWithAnExplicitResponse() {
        LocalConnectProxy(
                testConfig()
                    .copy(
                        headerReadTimeoutMillis = 100,
                        initialTlsReadTimeoutMillis = 500,
                    )
            )
            .use { proxy ->
                Socket().use { client ->
                    client.soTimeout = 2_000
                    client.connect(proxy.start())
                    client.getOutputStream().write("CONNECT 127.0.0.1".toByteArray())
                    client.getOutputStream().flush()

                    assertTrue(readHttpHeader(client).startsWith("HTTP/1.1 408 "))
                }
            }
    }

    @Test(timeout = 6_000)
    fun tunnelIdleTimeoutClosesBothDirectionsAndReleasesResources() {
        ServerSocket(0, 2, LOOPBACK).use { upstream ->
            val upstreamExecutor = Executors.newSingleThreadExecutor()
            val upstreamSawEof =
                upstreamExecutor.submit<Boolean> {
                    upstream.accept().use { server ->
                        server.soTimeout = 3_000
                        server.getInputStream().readExactly(6)
                        server.getInputStream().read() == -1
                    }
                }
            val config =
                testConfig()
                    .copy(
                        idleTimeoutMillis = 200,
                        tunnelPollTimeoutMillis = 50,
                    )

            LocalConnectProxy(config).use { proxy ->
                Socket().use { client ->
                    client.soTimeout = 3_000
                    client.connect(proxy.start())
                    establishTunnel(client, upstream.localPort)
                    client.getOutputStream().write(byteArrayOf(23, 3, 3, 0, 1, 7))
                    client.getOutputStream().flush()

                    assertPeerClosed(client)
                }
                eventually { proxy.activeSocketCount == 0 }
            }

            assertTrue(upstreamSawEof.get(3, TimeUnit.SECONDS))
            upstreamExecutor.shutdownNow()
        }
    }

    @Test(timeout = 6_000)
    fun clientHalfCloseClosesTheRemoteSide() {
        ServerSocket(0, 2, LOOPBACK).use { upstream ->
            val upstreamExecutor = Executors.newSingleThreadExecutor()
            val upstreamSawEof =
                upstreamExecutor.submit<Boolean> {
                    upstream.accept().use { server ->
                        server.soTimeout = 3_000
                        server.getInputStream().readExactly(6)
                        server.getInputStream().read() == -1
                    }
                }

            LocalConnectProxy(testConfig()).use { proxy ->
                Socket().use { client ->
                    client.soTimeout = 3_000
                    client.connect(proxy.start())
                    establishTunnel(client, upstream.localPort)
                    client.getOutputStream().write(byteArrayOf(23, 3, 3, 0, 1, 9))
                    client.getOutputStream().flush()
                    client.shutdownOutput()

                    assertPeerClosed(client)
                }
            }

            assertTrue(upstreamSawEof.get(3, TimeUnit.SECONDS))
            upstreamExecutor.shutdownNow()
        }
    }

    @Test(timeout = 6_000)
    fun boundedConnectionExecutorRejectsWorkBeyondItsQueue() {
        val config =
            testConfig()
                .copy(
                    maxConnections = 1,
                    maxPendingConnections = 1,
                    headerReadTimeoutMillis = 4_000,
                )
        LocalConnectProxy(config).use { proxy ->
            val address = proxy.start()
            val first = Socket()
            val queued = Socket()
            val rejected = Socket()
            try {
                first.connect(address)
                eventually { proxy.activeSocketCount == 1 }
                queued.connect(address)
                eventually { proxy.activeSocketCount == 2 }
                rejected.soTimeout = 2_000
                rejected.connect(address)
                assertTrue(readHttpHeader(rejected).startsWith("HTTP/1.1 503 "))
            } finally {
                first.close()
                queued.close()
                rejected.close()
            }
        }
    }

    @Test(timeout = 6_000)
    fun dnsResolutionTimeoutReleasesConnectionWorkerAndBoundsUninterruptibleResolver() {
        val resolverStarted = CountDownLatch(1)
        val releaseResolver = CountDownLatch(1)
        val resolverFinished = CountDownLatch(1)
        val resolverInterrupted = AtomicBoolean(false)
        val resolverWasDaemon = AtomicBoolean(false)
        val resolver = HostResolver {
            resolverStarted.countDown()
            resolverWasDaemon.set(Thread.currentThread().isDaemon)
            try {
                while (releaseResolver.count > 0) {
                    try {
                        releaseResolver.await()
                    } catch (_: InterruptedException) {
                        resolverInterrupted.set(true)
                    }
                }
                arrayOf(LOOPBACK)
            } finally {
                resolverFinished.countDown()
            }
        }
        val config =
            testConfig()
                .copy(
                    dnsResolverThreads = 1,
                    maxPendingDnsResolutions = 1,
                    connectTimeoutMillis = 120,
                    shutdownTimeoutMillis = 120,
                )
        val proxy = LocalConnectProxy(config, resolver)
        val client = Socket()
        var closeElapsedMillis = Long.MAX_VALUE
        try {
            client.soTimeout = 2_000
            client.connect(proxy.start())
            writeConnect(client, 443, "uninterruptible.test")
            assertTrue(resolverStarted.await(1, TimeUnit.SECONDS))

            assertTrue(readHttpHeader(client).startsWith("HTTP/1.1 504 "))
            eventually { proxy.activeSocketCount == 0 }
            eventually { resolverInterrupted.get() }
            assertTrue(resolverWasDaemon.get())
            assertEquals(1, proxy.activeDnsResolutionCount)

            val closeStarted = System.nanoTime()
            proxy.close()
            closeElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted)
            assertFalse(resolverFinished.await(50, TimeUnit.MILLISECONDS))
        } finally {
            releaseResolver.countDown()
            client.close()
            proxy.close()
        }

        assertTrue(resolverFinished.await(1, TimeUnit.SECONDS))
        assertTrue("close waited $closeElapsedMillis ms", closeElapsedMillis < 1_000)
    }

    @Test(timeout = 6_000)
    fun closeInterruptsDnsExecutorAndConnectionWaiter() {
        val resolverStarted = CountDownLatch(1)
        val resolverInterrupted = CountDownLatch(1)
        val resolverFinished = CountDownLatch(1)
        val neverRelease = CountDownLatch(1)
        val resolver = HostResolver {
            resolverStarted.countDown()
            try {
                neverRelease.await()
                arrayOf(LOOPBACK)
            } catch (_: InterruptedException) {
                resolverInterrupted.countDown()
                emptyArray()
            } finally {
                resolverFinished.countDown()
            }
        }
        val proxy =
            LocalConnectProxy(
                testConfig()
                    .copy(
                        dnsResolverThreads = 1,
                        maxPendingDnsResolutions = 1,
                        connectTimeoutMillis = 5_000,
                        shutdownTimeoutMillis = 1_000,
                    ),
                resolver,
            )
        val client = Socket()
        try {
            client.connect(proxy.start())
            writeConnect(client, 443, "close-cleanup.test")
            assertTrue(resolverStarted.await(1, TimeUnit.SECONDS))

            proxy.close()

            assertTrue(resolverInterrupted.await(1, TimeUnit.SECONDS))
            assertTrue(resolverFinished.await(1, TimeUnit.SECONDS))
            assertEquals(0, proxy.activeSocketCount)
        } finally {
            client.close()
            proxy.close()
        }
    }

    @Test(timeout = 8_000)
    fun fullDnsQueueRejectsAdditionalResolutionWithServiceUnavailable() {
        val resolverStarted = CountDownLatch(1)
        val releaseResolver = CountDownLatch(1)
        val resolver = HostResolver {
            resolverStarted.countDown()
            releaseResolver.await()
            arrayOf(LOOPBACK)
        }
        val unavailablePort = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        val config =
            testConfig()
                .copy(
                    dnsResolverThreads = 1,
                    maxPendingDnsResolutions = 1,
                    connectTimeoutMillis = 3_000,
                )
        val proxy = LocalConnectProxy(config, resolver)
        val clients = List(3) { Socket().apply { soTimeout = 3_000 } }
        try {
            val proxyAddress = proxy.start()
            clients.forEach { it.connect(proxyAddress) }
            writeConnect(clients[0], unavailablePort, "active-resolution.test")
            assertTrue(resolverStarted.await(1, TimeUnit.SECONDS))
            writeConnect(clients[1], unavailablePort, "queued-resolution.test")
            eventually { proxy.pendingDnsResolutionCount == 1 }
            assertEquals(1, proxy.activeDnsResolutionCount)

            writeConnect(clients[2], unavailablePort, "rejected-resolution.test")
            assertTrue(readHttpHeader(clients[2]).startsWith("HTTP/1.1 503 "))

            releaseResolver.countDown()
            assertTrue(readHttpHeader(clients[0]).startsWith("HTTP/1.1 502 "))
            assertTrue(readHttpHeader(clients[1]).startsWith("HTTP/1.1 502 "))
        } finally {
            releaseResolver.countDown()
            clients.forEach(Socket::close)
            proxy.close()
        }
    }

    private fun serveTlsRequest(tlsServer: SSLServerSocket): String =
        (tlsServer.accept() as SSLSocket).use { server ->
            server.soTimeout = 4_000
            server.startHandshake()
            val request = String(server.inputStream.readExactly(4), Charsets.US_ASCII)
            server.outputStream.write("pong".toByteArray(Charsets.US_ASCII))
            server.outputStream.flush()
            request
        }

    private fun exerciseTlsThroughProxy(tlsServer: SSLServerSocket) {
        val proxy = LocalConnectProxy(testConfig())
        val raw = Socket()
        var client: SSLSocket? = null
        try {
            raw.soTimeout = 4_000
            raw.connect(proxy.start())
            establishTunnel(raw, tlsServer.localPort)
            client =
                trustingClientContext()
                    .socketFactory
                    .createSocket(raw, "example.test", tlsServer.localPort, true) as SSLSocket
            client.sslParameters =
                client.sslParameters.apply { serverNames = listOf(SNIHostName("example.test")) }
            client.startHandshake()
            client.outputStream.write("ping".toByteArray(Charsets.US_ASCII))
            client.outputStream.flush()
            assertEquals("pong", String(client.inputStream.readExactly(4), Charsets.US_ASCII))
        } finally {
            client?.close()
            raw.close()
            proxy.close()
        }
    }

    private fun testConfig(): ProxyConfig =
        ProxyConfig(
            maxConnections = 4,
            maxPendingConnections = 4,
            connectTimeoutMillis = 1_000,
            headerReadTimeoutMillis = 1_000,
            initialTlsReadTimeoutMillis = 1_000,
            idleTimeoutMillis = 2_000,
            tunnelPollTimeoutMillis = 100,
            interSegmentDelayMillis = 0,
            shutdownTimeoutMillis = 2_000,
        )

    private fun establishTunnel(client: Socket, remotePort: Int) {
        writeConnect(client, remotePort)
        val response = readHttpHeader(client)
        assertTrue("Unexpected CONNECT response: $response", response.startsWith("HTTP/1.1 200 "))
    }

    private fun writeConnect(
        client: Socket,
        remotePort: Int,
        host: String = "127.0.0.1",
    ) {
        client
            .getOutputStream()
            .write(
                "CONNECT $host:$remotePort HTTP/1.1\r\n"
                    .plus("Host: $host:$remotePort\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1)
            )
        client.getOutputStream().flush()
    }

    private fun readHttpHeader(socket: Socket): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (output.size() < 16 * 1024) {
            val value = socket.getInputStream().read()
            if (value < 0) throw EOFException("HTTP response ended before its header terminator")
            output.write(value)
            matched = if (value == terminator[matched].toInt()) matched + 1 else 0
            if (matched == terminator.size) return output.toString(Charsets.ISO_8859_1.name())
        }
        throw IOException("HTTP response header was too large")
    }

    private fun java.io.InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            if (count < 0) throw EOFException("Expected $size bytes, received $offset")
            offset += count
        }
        return bytes
    }

    private fun assertPeerClosed(socket: Socket) {
        try {
            assertEquals(-1, socket.getInputStream().read())
        } catch (_: SocketException) {
            // A TCP reset also proves the opposite direction was cleaned up.
        } catch (_: SocketTimeoutException) {
            fail("Peer did not close before the timeout")
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Condition was not met before timeout", condition())
    }

    private fun createTlsServerSocket(): SSLServerSocket {
        val privateKey =
            KeyFactory.getInstance("RSA")
                .generatePrivate(
                    PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_KEY_BASE64))
                )
        val certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.getDecoder().decode(CERTIFICATE_BASE64).inputStream())
                as X509Certificate
        val password = "test-only".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("server", privateKey, password, arrayOf(certificate))
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagers.init(keyStore, password)
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers.keyManagers, null, SecureRandom())
        return context.serverSocketFactory.createServerSocket(0, 4, LOOPBACK) as SSLServerSocket
    }

    private fun trustingClientContext(): SSLContext {
        val trustAll =
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    Unit

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
                    Unit
            }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        // Public fixture material used only by the loopback JSSE integration test; this is not an
        // application signing key and neither constant is packaged in the production APK.
        const val PRIVATE_KEY_BASE64 =
            "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCrKbB+o9YQvgkhdun6O3mDik6S/ui70u6pnirwQ0+9vo8Y+xh6Kfml6YVq/3wDn9/RYFpj3AE94ko3chcNxOA2V+np27QkquEF5OvFB0vQKTvt3aQgbkNox5QBRxTsL7uP7pUC29IRRHZcJ0Z8EZJRQ/dtqZGtsyIJhlqzV1xPbvv9FuZGQQtNta71o/B+3IAKfglzinxsQsaA4iFP0/DRpmoHOqzUUz8sN8lgqKHd+qTeJ+SOHDf4BOtWMJxTiXJc6ba0koeluBT/lyjqtEXp9bpVrK1jkn2ZbfRvkZnnFJ/dXvSFFCd7d5wp8tJ9VcVKo9zKJjDaL4fe/W2Zsu8lAgMBAAECggEAF3tVsloJwSxBFm049lJ4fjpYQ0Rja3kpsu13GJUzeGWPPi8ckY32qPNbkW1RdzHUw+XDINYK+ZF0+xxU21e6JtpyxdmVYj5rNqLMUbCJjxpJFX4FyUk3SEWxIWe+EBUQV1O077p2nFXXIxdkTRsgBrQbWtkbmkfsEIB1Kwt+J4tp54x6SR2S8nVEtzjCHM7dWsth+wSdPll0YdlODW+5jpEyTFGOWpA5/Jk/hFNJgxYYR2YYdJZZKs/bVsvlvcconNVIs7pAalWBJ4qwIYRFKUkQxAYN5UMpImGeqE0RWhjwE2cAlg6G8OiuOjjEVwUZmcyD2Oc4n7TKwEbUvBeiFQKBgQDUbD+PcqJjXOhJ55O2Kv/SVZ/RAn9RpJ1AxISM+C5FBUr5aSm7SkoaaOQEqCIUUmtoGYYqH2rMdtRR7SNLXjmivZGWig1WdtQb9jQvOm6zBcez+NH4WBnC1zLeyD+WNrFBBQiv/a85dm97WgJJRHl07sjSdPxLbvDp0hYYpIIkAwKBgQDORpn0zlPprwYUUfx8kWSEqISt92PwLwMI/MBws5766oul/U/vV+goA44xCz64aiwG/0Z6CSK/x6jdVAD4304Xbm5DKLMHigGSGxKiU+w026Gm4/akNVM/dJ7hjOfDa8Kbgtvrfg1KSbgdimis/XtnI9iaDVMU21vudLpQ3B+7twKBgCnlh8vFMl4irvYUpL+jT32uway5r17s9s8Vc4dSU93tI1J5+W8lQeRfl8mLV91mPCT2E84vECNvtITMHs/4r6l3dkWYyPSqzz8MmlJOVhMdKrxGcTNoPPx+8VubZ187Pk6yrXn4sRzGTUqpABZvWP1FM3q+9Bv0r7OOailGGRz9AoGAYQonvVofcczr8D1NqZCAmEPq/yhl9IU5SU4Wfi6SOPqELdeIdlGFnuVlTD89B2azRatsDVck6NHV6CFvv6TVyQIjjyajaoWLDZ/82S7f7VHxr2CJFGEdw7lqUTOHiJC2YZBjQ1ruh6c8nXESo2cwWaosaPShxCsaCYkSaZ3Us+sCgYBBvRabITbE54LyCw4VXogp79c2A9PKn/dWXoAhFXg3j4Qq0KLMv2VCo+AGpZGIy/nH78RR7rfqABoQVXtI0g2T0oFh31WgaJQXR9SBfmqeYGMpung8A0CFl9gmSbdLWjNxkvX4BfFN4jbz66oYOGzp6d09PcNqds8wPINx5wVgIw=="

        const val CERTIFICATE_BASE64 =
            "MIIDDzCCAfegAwIBAgIUQuVEEsIHQN3L6CY4TmGGnlNSCuMwDQYJKoZIhvcNAQELBQAwFzEVMBMGA1UEAwwMZXhhbXBsZS50ZXN0MB4XDTI2MDgyNTEyMjM0MFoXDTM2MDgyMjEyMjM0MFowFzEVMBMGA1UEAwwMZXhhbXBsZS50ZXN0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqymwfqPWEL4JIXbp+jt5g4pOkv7ou9LuqZ4q8ENPvb6PGPsYein5pemFav98A5/f0WBaY9wBPeJKN3IXDcTgNlfp6du0JKrhBeTrxQdL0Ck77d2kIG5DaMeUAUcU7C+7j+6VAtvSEUR2XCdGfBGSUUP3bamRrbMiCYZas1dcT277/RbmRkELTbWu9aPwftyACn4Jc4p8bELGgOIhT9Pw0aZqBzqs1FM/LDfJYKih3fqk3ifkjhw3+ATrVjCcU4lyXOm2tJKHpbgU/5co6rRF6fW6VaytY5J9mW30b5GZ5xSf3V70hRQne3ecKfLSfVXFSqPcyiYw2i+H3v1tmbLvJQIDAQABo1MwUTAdBgNVHQ4EFgQUpT2Iibj9KO1nX0agzNDTPoQeLzAwHwYDVR0jBBgwFoAUpT2Iibj9KO1nX0agzNDTPoQeLzAwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEATww1O7eHF6asOSNp73Q1unbi/X91Z0gD9RhujY5/sMw+DCun52FgjlxEhVDxQiWLNEDuuf31ZYBuvGEEahlgIMslQsJhYLO0xt8Px2CwK6GcHKWes94dED/G9fGY9ia3fMlN/P5dJIt7IvAPibqdT/1iU8cM4XI1+90LHdi42aHIqB1arthXLjY2+qp37lXk3Zj5PPphDvxdx9xX+JzhbHj+mUwYWLHajZaeeP3xIWk2+DTcTbo1npNcphYqe+Yn5GU/jP9EzOyAENBZQ2t3csZwl+J0ubq6F1daL9MXTd6uoVKTc7oxtKs0fOJyV2rZJRd2WdwXcN22RZM1+05XEw=="
    }
}
