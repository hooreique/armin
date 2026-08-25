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
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Loopback-only, ephemeral-port HTTP CONNECT proxy with bounded resources. */
class LocalConnectProxy(private val config: ProxyConfig = ProxyConfig()) : Closeable {
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
                State.CLOSED -> throw IllegalStateException("A closed LocalConnectProxy cannot restart")
                State.NEW -> Unit
            }

            val listener = ServerSocket()
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
                val relays =
                    boundedExecutor(
                        config.maxConnections,
                        config.maxConnections,
                        "relay",
                    )
                val address = listener.localSocketAddress as InetSocketAddress
                serverSocket = listener
                connectionExecutor = connections
                relayExecutor = relays
                boundAddress = InetSocketAddress(LOOPBACK_ADDRESS, address.port)
                running.set(true)
                state = State.RUNNING

                val thread =
                    Thread({ acceptLoop(listener, connections) }, threadName("accept")).apply {
                        isDaemon = true
                    }
                acceptThread = thread
                thread.start()
                checkNotNull(boundAddress)
            } catch (failure: Throwable) {
                running.set(false)
                state = State.CLOSED
                closeQuietly(listener)
                connectionExecutor?.shutdownNow()
                relayExecutor?.shutdownNow()
                throw failure
            }
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
                )
            }

        resources.serverSocket?.let(::closeQuietly)
        activeSockets.forEach(::closeQuietly)
        resources.connections?.shutdownNow()
        resources.relays?.shutdownNow()

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownTimeoutMillis)
        awaitUntil(resources.connections, deadline)
        awaitUntil(resources.relays, deadline)
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

    private fun acceptLoop(listener: ServerSocket, executor: ThreadPoolExecutor) {
        try {
            while (running.get()) {
                val client =
                    try {
                        listener.accept()
                    } catch (_: SocketException) {
                        break
                    } catch (_: IOException) {
                        if (!running.get()) break else continue
                    }

                if (!configureAcceptedClient(client)) continue
                activeSockets += client
                try {
                    executor.execute { handleClient(client) }
                } catch (_: RejectedExecutionException) {
                    sendResponse(client, SERVICE_UNAVAILABLE_RESPONSE)
                    activeSockets -= client
                    closeQuietly(client)
                }
            }
        } finally {
            if (running.get()) close()
        }
    }

    private fun handleClient(client: Socket) {
        var remote: Socket? = null
        try {
            val clientInput = BufferedInputStream(client.getInputStream(), config.tunnelBufferBytes)
            val clientOutput = client.getOutputStream()
            client.soTimeout = config.headerReadTimeoutMillis
            val request =
                try {
                    ConnectRequestParser(config.maxConnectHeaderBytes).read(clientInput)
                } catch (failure: ConnectRequestException) {
                    val response =
                        when (failure.failure) {
                            ConnectRequestFailure.HEADER_TOO_LARGE -> HEADER_TOO_LARGE_RESPONSE
                            ConnectRequestFailure.UNSUPPORTED_METHOD -> METHOD_NOT_ALLOWED_RESPONSE
                            ConnectRequestFailure.MALFORMED_REQUEST -> BAD_REQUEST_RESPONSE
                        }
                    sendResponse(client, response)
                    return
                } catch (_: SocketTimeoutException) {
                    sendResponse(client, REQUEST_TIMEOUT_RESPONSE)
                    return
                }

            remote = Socket()
            activeSockets += remote
            remote.tcpNoDelay = true
            remote.keepAlive = true
            try {
                remote.connect(
                    InetSocketAddress(request.host, request.port),
                    config.connectTimeoutMillis,
                )
            } catch (_: IOException) {
                sendResponse(client, BAD_GATEWAY_RESPONSE)
                return
            } catch (_: SecurityException) {
                sendResponse(client, BAD_GATEWAY_RESPONSE)
                return
            }

            clientOutput.write(CONNECTION_ESTABLISHED_RESPONSE)
            clientOutput.flush()
            client.soTimeout = config.initialTlsReadTimeoutMillis

            val parser = TlsClientHelloParser(config.maxClientHelloBytes)
            val captured =
                TlsClientHelloReader(
                        parser,
                        config.maxClientHelloBytes,
                        config.maxClientHelloRecords,
                    )
                    .read(clientInput)
            val parsed =
                (captured.parseResult as? ClientHelloParseResult.Parsed)?.clientHello
            TlsClientHelloSplitter(config.sniChunkSize)
                .writeTo(
                    remote.getOutputStream(),
                    captured.bytes,
                    parsed,
                    config.interSegmentDelayMillis,
                )

            if (
                captured.parseResult ==
                    ClientHelloParseResult.Failure(ClientHelloFailure.TRUNCATED_INPUT)
            ) {
                return
            }

            client.soTimeout = config.tunnelPollTimeoutMillis
            remote.soTimeout = config.tunnelPollTimeoutMillis
            val relays = relayExecutor ?: return
            BidirectionalTunnel(
                    relays,
                    config.idleTimeoutMillis,
                    config.tunnelPollTimeoutMillis,
                    config.tunnelBufferBytes,
                )
                .relay(
                    client,
                    remote,
                    leftInput = clientInput,
                    leftOutput = clientOutput,
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

    private fun threadName(role: String) = "armin-proxy-$instanceNumber-$role"

    private inner class NamedThreadFactory(private val role: String) : ThreadFactory {
        private val nextThread = AtomicInteger()

        override fun newThread(task: Runnable): Thread =
            Thread(task, "${threadName(role)}-${nextThread.incrementAndGet()}").apply {
                isDaemon = true
            }
    }

    private data class ResourcesToClose(
        val serverSocket: ServerSocket?,
        val acceptThread: Thread?,
        val connections: ExecutorService?,
        val relays: ExecutorService?,
    )

    private enum class State {
        NEW,
        RUNNING,
        CLOSED,
    }

    private companion object {
        val LOOPBACK_ADDRESS: InetAddress =
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val NEXT_INSTANCE = AtomicInteger()

        val CONNECTION_ESTABLISHED_RESPONSE =
            "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val BAD_REQUEST_RESPONSE = response(400, "Bad Request")
        val REQUEST_TIMEOUT_RESPONSE = response(408, "Request Timeout")
        val METHOD_NOT_ALLOWED_RESPONSE = response(405, "Method Not Allowed")
        val HEADER_TOO_LARGE_RESPONSE = response(431, "Request Header Fields Too Large")
        val BAD_GATEWAY_RESPONSE = response(502, "Bad Gateway")
        val SERVICE_UNAVAILABLE_RESPONSE = response(503, "Service Unavailable")

        fun response(status: Int, reason: String): ByteArray =
            "HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII)
    }
}
