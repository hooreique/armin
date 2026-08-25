package dev.armin.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class TunnelEndReason {
    END_OF_STREAM,
    IDLE_TIMEOUT,
    IO_ERROR,
    CLOSED,
    EXECUTOR_REJECTED,
}

data class TunnelEndpoint(
    val socket: Socket,
    val input: InputStream = socket.getInputStream(),
    val output: OutputStream = socket.getOutputStream(),
)

/** Relays bytes in both directions and closes both sockets as soon as either pump terminates. */
class BidirectionalTunnel(
    private val reversePumpExecutor: ExecutorService,
    private val idleTimeoutMillis: Int,
    private val pollTimeoutMillis: Int,
    private val bufferBytes: Int = 16 * 1024,
) {
    init {
        require(idleTimeoutMillis > 0) { "idleTimeoutMillis must be positive" }
        require(pollTimeoutMillis > 0 && pollTimeoutMillis <= idleTimeoutMillis) {
            "pollTimeoutMillis must be positive and not exceed idleTimeoutMillis"
        }
        require(bufferBytes > 0) { "bufferBytes must be positive" }
    }

    fun relay(left: TunnelEndpoint, right: TunnelEndpoint): TunnelEndReason {
        left.socket.soTimeout = pollTimeoutMillis
        right.socket.soTimeout = pollTimeoutMillis

        val lastActivityNanos = AtomicLong(System.nanoTime())
        val endReason = AtomicReference<TunnelEndReason>()
        val finish: (TunnelEndReason) -> Unit = { reason ->
            if (endReason.compareAndSet(null, reason)) {
                closeQuietly(left.socket)
                closeQuietly(right.socket)
            }
        }

        val reverseFuture =
            try {
                reversePumpExecutor.submit {
                    finish(
                        copy(right.input, left.output, lastActivityNanos, left.socket, right.socket)
                    )
                }
            } catch (_: RejectedExecutionException) {
                finish(TunnelEndReason.EXECUTOR_REJECTED)
                return endReason.get()
            }

        finish(copy(left.input, right.output, lastActivityNanos, left.socket, right.socket))

        try {
            reverseFuture.get(cleanupWaitMillis(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            reverseFuture.cancel(true)
        } catch (_: ExecutionException) {
            // The first pump result already owns socket cleanup.
        } catch (_: TimeoutException) {
            reverseFuture.cancel(true)
        } finally {
            closeQuietly(left.socket)
            closeQuietly(right.socket)
        }
        return endReason.get() ?: TunnelEndReason.CLOSED
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        lastActivityNanos: AtomicLong,
        leftSocket: Socket,
        rightSocket: Socket,
    ): TunnelEndReason {
        val buffer = ByteArray(bufferBytes)
        while (!Thread.currentThread().isInterrupted) {
            when (val read = readChunk(input, buffer, leftSocket, rightSocket)) {
                PumpRead.Timeout -> {
                    if (hasBeenIdle(lastActivityNanos.get())) return TunnelEndReason.IDLE_TIMEOUT
                    continue
                }
                PumpRead.EndOfStream -> return TunnelEndReason.END_OF_STREAM
                is PumpRead.Failed -> return read.reason
                is PumpRead.Bytes -> {
                    if (read.count == 0) continue
                    lastActivityNanos.set(System.nanoTime())
                    writeChunk(output, buffer, read.count, leftSocket, rightSocket)?.let {
                        return it
                    }
                }
            }
        }
        return TunnelEndReason.CLOSED
    }

    private fun readChunk(
        input: InputStream,
        buffer: ByteArray,
        leftSocket: Socket,
        rightSocket: Socket,
    ): PumpRead =
        try {
            val count = input.read(buffer)
            if (count < 0) PumpRead.EndOfStream else PumpRead.Bytes(count)
        } catch (_: SocketTimeoutException) {
            PumpRead.Timeout
        } catch (_: SocketException) {
            PumpRead.Failed(socketFailureReason(leftSocket, rightSocket))
        } catch (_: IOException) {
            PumpRead.Failed(TunnelEndReason.IO_ERROR)
        }

    private fun writeChunk(
        output: OutputStream,
        buffer: ByteArray,
        count: Int,
        leftSocket: Socket,
        rightSocket: Socket,
    ): TunnelEndReason? =
        try {
            output.write(buffer, 0, count)
            output.flush()
            null
        } catch (_: SocketException) {
            socketFailureReason(leftSocket, rightSocket)
        } catch (_: IOException) {
            TunnelEndReason.IO_ERROR
        }

    private fun socketFailureReason(leftSocket: Socket, rightSocket: Socket): TunnelEndReason =
        if (leftSocket.isClosed || rightSocket.isClosed) {
            TunnelEndReason.CLOSED
        } else {
            TunnelEndReason.IO_ERROR
        }

    private fun hasBeenIdle(lastActivityNanos: Long): Boolean =
        System.nanoTime() - lastActivityNanos >=
            TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis.toLong())

    private fun cleanupWaitMillis(): Long = maxOf(1_000L, pollTimeoutMillis.toLong() * 2)

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // Best-effort cleanup.
        }
    }

    private sealed interface PumpRead {
        data class Bytes(val count: Int) : PumpRead

        data class Failed(val reason: TunnelEndReason) : PumpRead

        data object Timeout : PumpRead

        data object EndOfStream : PumpRead
    }
}
