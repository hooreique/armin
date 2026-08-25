package dev.armin.proxy

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun interface HostResolver {
    fun resolve(host: String): Array<InetAddress>
}

/** Loopback-only, ephemeral-port HTTP CONNECT proxy with bounded resources. */
class LocalConnectProxy(
    private val config: ProxyConfig = ProxyConfig(),
    private val hostResolver: HostResolver = SYSTEM_HOST_RESOLVER,
) : Closeable {
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val instanceNumber = NEXT_INSTANCE.incrementAndGet()

    @Volatile private var state = State.NEW
    @Volatile private var boundAddress: InetSocketAddress? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var connectionExecutor: ThreadPoolExecutor? = null
    @Volatile private var relayExecutor: ThreadPoolExecutor? = null
    @Volatile private var dnsExecutor: ThreadPoolExecutor? = null

    val isRunning: Boolean
        get() = state == State.RUNNING && running.get()

    val localAddress: InetSocketAddress
        get() =
            boundAddress?.takeIf { isRunning }
                ?: throw IllegalStateException("LocalConnectProxy has not been started")

    val port: Int
        get() = localAddress.port

    /** Binds the loopback listener before returning. Calling start twice while running is safe. */
    @Throws(IOException::class)
    fun start(): InetSocketAddress =
        synchronized(lifecycleLock) {
            when (state) {
                State.RUNNING -> return@synchronized checkNotNull(boundAddress)
                State.CLOSED ->
                    throw IllegalStateException("A closed LocalConnectProxy cannot restart")
                State.NEW -> Unit
            }

            val listener = ServerSocket()
            var createdConnections: ThreadPoolExecutor? = null
            var createdRelays: ThreadPoolExecutor? = null
            var createdDns: ThreadPoolExecutor? = null
            try {
                listener.reuseAddress = true
                listener.bind(
                    InetSocketAddress(LOOPBACK_ADDRESS, 0),
                    config.maxConnections + config.maxPendingConnections,
                )

                val connections =
                    boundedExecutor(
                        config.maxConnections,
                        config.maxPendingConnections,
                        "connection",
                    )
                createdConnections = connections
                val relays =
                    boundedExecutor(
                        config.maxConnections,
                        config.maxConnections,
                        "relay",
                    )
                createdRelays = relays
                val dns =
                    boundedExecutor(
                        config.dnsResolverThreads,
                        config.maxPendingDnsResolutions,
                        "dns",
                    )
                createdDns = dns
                val address = listener.localSocketAddress as InetSocketAddress
                serverSocket = listener
                connectionExecutor = connections
                relayExecutor = relays
                dnsExecutor = dns
                boundAddress = InetSocketAddress(LOOPBACK_ADDRESS, address.port)
                running.set(true)
                state = State.RUNNING

                launchAcceptLoop(listener, connections)
                checkNotNull(boundAddress)
            } catch (failure: IOException) {
                markStartFailed(listener, createdConnections, createdRelays, createdDns)
                throw failure
            } catch (failure: SecurityException) {
                markStartFailed(listener, createdConnections, createdRelays, createdDns)
                throw failure
            }
        }

    private fun launchAcceptLoop(listener: ServerSocket, connections: ThreadPoolExecutor) {
        val thread =
            Thread(
                    { acceptLoop(listener, connections) },
                    "armin-proxy-$instanceNumber-accept",
                )
                .apply { isDaemon = true }
        acceptThread = thread
        thread.start()
    }

    private fun markStartFailed(
        listener: ServerSocket,
        connections: ExecutorService?,
        relays: ExecutorService?,
        dns: ExecutorService?,
    ) {
        running.set(false)
        state = State.CLOSED
        cleanupFailedStart(listener, connections, relays, dns)
    }

    override fun close() {
        val resources =
            synchronized(lifecycleLock) {
                if (state == State.CLOSED) return
                state = State.CLOSED
                running.set(false)
                ResourcesToClose(
                    serverSocket.also { serverSocket = null },
                    acceptThread.also { acceptThread = null },
                    connectionExecutor.also { connectionExecutor = null },
                    relayExecutor.also { relayExecutor = null },
                    dnsExecutor.also { dnsExecutor = null },
                )
            }

        resources.serverSocket?.let(::closeQuietly)
        activeSockets.forEach(::closeQuietly)
        resources.connections?.shutdownNow()
        resources.relays?.shutdownNow()
        resources.dns?.shutdownNow()

        val deadline =
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownTimeoutMillis)
        awaitUntil(resources.connections, deadline)
        awaitUntil(resources.relays, deadline)
        awaitUntil(resources.dns, deadline)
        val thread = resources.acceptThread
        if (thread != null && thread !== Thread.currentThread()) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos > 0) {
                try {
                    thread.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        activeSockets.clear()
    }

    internal val activeSocketCount: Int
        get() = activeSockets.size

    internal val activeDnsResolutionCount: Int
        get() = dnsExecutor?.activeCount ?: 0

    internal val pendingDnsResolutionCount: Int
        get() = dnsExecutor?.queue?.size ?: 0

    private fun acceptLoop(listener: ServerSocket, executor: ThreadPoolExecutor) {
        try {
            while (running.get()) {
                val client = acceptClient(listener) ?: break
                dispatchClient(client, executor)
            }
        } finally {
            if (running.get()) close()
        }
    }

    private fun acceptClient(listener: ServerSocket): Socket? =
        try {
            listener.accept()
        } catch (_: SocketException) {
            null
        } catch (_: IOException) {
            null
        }

    private fun dispatchClient(client: Socket, executor: ThreadPoolExecutor) {
        if (!configureAcceptedClient(client)) return
        activeSockets += client
        if (!running.get()) {
            activeSockets -= client
            closeQuietly(client)
            return
        }
        try {
            executor.execute { handleClient(client) }
        } catch (_: RejectedExecutionException) {
            sendResponse(client, SERVICE_UNAVAILABLE_RESPONSE)
            activeSockets -= client
            closeQuietly(client)
        }
    }

    private fun handleClient(client: Socket) {
        var remote: Socket? = null
        try {
            val clientInput = BufferedInputStream(client.getInputStream(), config.tunnelBufferBytes)
            val clientOutput = client.getOutputStream()
            val request = readConnectRequest(client, clientInput, config) ?: return
            val resolvers = dnsExecutor ?: return
            remote =
                RemoteConnector(config, activeSockets, running, resolvers, hostResolver)
                    .open(request, client) ?: return

            clientOutput.write(CONNECTION_ESTABLISHED_RESPONSE)
            clientOutput.flush()
            val captured = captureAndForwardClientHello(client, clientInput, remote, config)
            if (captured.endedAtEndOfStream()) return

            val relays = relayExecutor ?: return
            BidirectionalTunnel(
                    relays,
                    config.idleTimeoutMillis,
                    config.tunnelPollTimeoutMillis,
                    config.tunnelBufferBytes,
                )
                .relay(
                    TunnelEndpoint(client, clientInput, clientOutput),
                    TunnelEndpoint(remote),
                )
        } catch (_: IOException) {
            // A connection-local failure must not stop the accept loop.
        } finally {
            remote?.let {
                activeSockets -= it
                closeQuietly(it)
            }
            activeSockets -= client
            closeQuietly(client)
        }
    }

    private fun configureAcceptedClient(client: Socket): Boolean =
        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = config.headerReadTimeoutMillis
            true
        } catch (_: SocketException) {
            closeQuietly(client)
            false
        }

    private fun boundedExecutor(workers: Int, queueSize: Int, role: String): ThreadPoolExecutor =
        ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueSize),
            NamedThreadFactory(role),
            ThreadPoolExecutor.AbortPolicy(),
        )

    private inner class NamedThreadFactory(private val role: String) : ThreadFactory {
        private val nextThread = AtomicInteger()

        override fun newThread(task: Runnable): Thread =
            Thread(
                    task,
                    "armin-proxy-$instanceNumber-$role-${nextThread.incrementAndGet()}",
                )
                .apply {
                    isDaemon = true
                }
    }

    private data class ResourcesToClose(
        val serverSocket: ServerSocket?,
        val acceptThread: Thread?,
        val connections: ExecutorService?,
        val relays: ExecutorService?,
        val dns: ExecutorService?,
    )

    private enum class State {
        NEW,
        RUNNING,
        CLOSED,
    }

    private companion object {
        val LOOPBACK_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val NEXT_INSTANCE = AtomicInteger()
    }
}

private fun cleanupFailedStart(
    listener: ServerSocket,
    connections: ExecutorService?,
    relays: ExecutorService?,
    dns: ExecutorService?,
) {
    closeQuietly(listener)
    connections?.shutdownNow()
    relays?.shutdownNow()
    dns?.shutdownNow()
}

private fun readConnectRequest(
    client: Socket,
    clientInput: BufferedInputStream,
    config: ProxyConfig,
): ConnectRequest? {
    client.soTimeout = config.headerReadTimeoutMillis
    return try {
        ConnectRequestParser(config.maxConnectHeaderBytes).read(clientInput)
    } catch (failure: ConnectRequestException) {
        val response =
            when (failure.failure) {
                ConnectRequestFailure.HEADER_TOO_LARGE -> HEADER_TOO_LARGE_RESPONSE
                ConnectRequestFailure.UNSUPPORTED_METHOD -> METHOD_NOT_ALLOWED_RESPONSE
                ConnectRequestFailure.MALFORMED_REQUEST -> BAD_REQUEST_RESPONSE
            }
        sendResponse(client, response)
        null
    } catch (_: SocketTimeoutException) {
        sendResponse(client, REQUEST_TIMEOUT_RESPONSE)
        null
    }
}

private class RemoteConnector(
    private val config: ProxyConfig,
    private val activeSockets: MutableSet<Socket>,
    private val running: AtomicBoolean,
    private val dnsExecutor: ThreadPoolExecutor,
    private val hostResolver: HostResolver,
) {
    fun open(request: ConnectRequest, client: Socket): Socket? {
        val deadline = ConnectDeadline(config.connectTimeoutMillis)
        val addresses = resolveHost(request.host, client, deadline) ?: return null
        return connectResolvedAddress(addresses, request.port, client, deadline)
    }

    private fun resolveHost(
        host: String,
        client: Socket,
        deadline: ConnectDeadline,
    ): Array<InetAddress>? {
        val resolution =
            try {
                dnsExecutor.submit<Array<InetAddress>> { hostResolver.resolve(host) }
            } catch (_: RejectedExecutionException) {
                sendResponse(client, SERVICE_UNAVAILABLE_RESPONSE)
                return null
            }

        val remainingNanos = deadline.remainingNanos()
        if (remainingNanos <= 0) {
            cancelResolution(resolution)
            sendResponse(client, GATEWAY_TIMEOUT_RESPONSE)
            return null
        }
        return try {
            resolution.get(remainingNanos, TimeUnit.NANOSECONDS).takeIf { it.isNotEmpty() }
                ?: run {
                    sendResponse(client, BAD_GATEWAY_RESPONSE)
                    null
                }
        } catch (_: TimeoutException) {
            cancelResolution(resolution)
            sendResponse(client, GATEWAY_TIMEOUT_RESPONSE)
            null
        } catch (_: InterruptedException) {
            cancelResolution(resolution)
            Thread.currentThread().interrupt()
            if (running.get()) sendResponse(client, SERVICE_UNAVAILABLE_RESPONSE)
            null
        } catch (_: ExecutionException) {
            sendResponse(client, BAD_GATEWAY_RESPONSE)
            null
        } catch (_: CancellationException) {
            if (running.get()) sendResponse(client, SERVICE_UNAVAILABLE_RESPONSE)
            null
        }
    }

    private fun cancelResolution(resolution: java.util.concurrent.Future<Array<InetAddress>>) {
        resolution.cancel(true)
        dnsExecutor.purge()
    }

    private fun connectResolvedAddress(
        addresses: Array<InetAddress>,
        port: Int,
        client: Socket,
        deadline: ConnectDeadline,
    ): Socket? {
        var timedOut = false
        for (address in addresses) {
            if (!running.get()) return null
            val timeoutMillis = deadline.remainingSocketTimeoutMillis()
            if (timeoutMillis == 0) {
                timedOut = true
                break
            }

            val remote = Socket()
            activeSockets += remote
            if (!running.get()) {
                discardSocket(remote)
                return null
            }
            try {
                remote.tcpNoDelay = true
                remote.keepAlive = true
                remote.connect(InetSocketAddress(address, port), timeoutMillis)
                return remote
            } catch (_: SocketTimeoutException) {
                timedOut = true
            } catch (_: IOException) {
                // Try the next resolved address while the shared deadline permits.
            } catch (_: SecurityException) {
                // Treat a denied destination like an unreachable one.
            }
            discardSocket(remote)
        }

        val response =
            if (timedOut || deadline.remainingNanos() <= 0) {
                GATEWAY_TIMEOUT_RESPONSE
            } else {
                BAD_GATEWAY_RESPONSE
            }
        sendResponse(client, response)
        return null
    }

    private fun discardSocket(socket: Socket) {
        activeSockets -= socket
        closeQuietly(socket)
    }
}

private class ConnectDeadline(timeoutMillis: Int) {
    private val deadlineNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())

    fun remainingNanos(): Long = deadlineNanos - System.nanoTime()

    fun remainingSocketTimeoutMillis(): Int {
        val nanos = remainingNanos()
        if (nanos <= 0) return 0
        val wholeMillis = TimeUnit.NANOSECONDS.toMillis(nanos)
        val roundedUp = wholeMillis + if (nanos % NANOS_PER_MILLISECOND == 0L) 0 else 1
        return roundedUp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private fun captureAndForwardClientHello(
    client: Socket,
    clientInput: BufferedInputStream,
    remote: Socket,
    config: ProxyConfig,
): CapturedClientHello {
    client.soTimeout = config.initialTlsReadTimeoutMillis
    val parser = TlsClientHelloParser(config.maxClientHelloBytes)
    val captured =
        TlsClientHelloReader(parser, config.maxClientHelloBytes, config.maxClientHelloRecords)
            .read(clientInput)
    val parsed = (captured.parseResult as? ClientHelloParseResult.Parsed)?.clientHello
    TlsClientHelloSplitter(config.sniChunkSize)
        .writeTo(
            remote.getOutputStream(),
            captured.bytes,
            parsed,
            config.interSegmentDelayMillis,
        )
    return captured
}

private fun CapturedClientHello.endedAtEndOfStream(): Boolean =
    parseResult == ClientHelloParseResult.Failure(ClientHelloFailure.TRUNCATED_INPUT)

private fun sendResponse(socket: Socket, response: ByteArray) {
    try {
        socket.getOutputStream().write(response)
        socket.getOutputStream().flush()
    } catch (_: IOException) {
        // The peer may already be gone.
    }
}

private fun awaitUntil(executor: ExecutorService?, deadlineNanos: Long) {
    if (executor == null) return
    val remaining = deadlineNanos - System.nanoTime()
    if (remaining <= 0) return
    try {
        executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

private fun closeQuietly(socket: Socket) {
    try {
        socket.close()
    } catch (_: IOException) {
        // Best-effort cleanup.
    }
}

private fun closeQuietly(socket: ServerSocket) {
    try {
        socket.close()
    } catch (_: IOException) {
        // Best-effort cleanup.
    }
}

private fun response(status: Int, reason: String): ByteArray =
    "HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
        .toByteArray(StandardCharsets.US_ASCII)

private val CONNECTION_ESTABLISHED_RESPONSE =
    "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
private val BAD_REQUEST_RESPONSE = response(400, "Bad Request")
private val REQUEST_TIMEOUT_RESPONSE = response(408, "Request Timeout")
private val METHOD_NOT_ALLOWED_RESPONSE = response(405, "Method Not Allowed")
private val HEADER_TOO_LARGE_RESPONSE = response(431, "Request Header Fields Too Large")
private val BAD_GATEWAY_RESPONSE = response(502, "Bad Gateway")
private val GATEWAY_TIMEOUT_RESPONSE = response(504, "Gateway Timeout")
private val SERVICE_UNAVAILABLE_RESPONSE = response(503, "Service Unavailable")
private val SYSTEM_HOST_RESOLVER = HostResolver(InetAddress::getAllByName)
