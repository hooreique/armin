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

    fun relay(
        leftSocket: Socket,
        rightSocket: Socket,
        leftInput: InputStream = leftSocket.getInputStream(),
        leftOutput: OutputStream = leftSocket.getOutputStream(),
        rightInput: InputStream = rightSocket.getInputStream(),
        rightOutput: OutputStream = rightSocket.getOutputStream(),
    ): TunnelEndReason {
        leftSocket.soTimeout = pollTimeoutMillis
        rightSocket.soTimeout = pollTimeoutMillis

        val lastActivityNanos = AtomicLong(System.nanoTime())
        val endReason = AtomicReference<TunnelEndReason>()
        val finish: (TunnelEndReason) -> Unit = { reason ->
            if (endReason.compareAndSet(null, reason)) {
                closeQuietly(leftSocket)
                closeQuietly(rightSocket)
            }
        }

        val reverseFuture =
            try {
                reversePumpExecutor.submit {
                    finish(copy(rightInput, leftOutput, lastActivityNanos, leftSocket, rightSocket))
                }
            } catch (_: RejectedExecutionException) {
                finish(TunnelEndReason.EXECUTOR_REJECTED)
                return endReason.get()
            }

        finish(copy(leftInput, rightOutput, lastActivityNanos, leftSocket, rightSocket))

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
            closeQuietly(leftSocket)
            closeQuietly(rightSocket)
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
            val count =
                try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    if (hasBeenIdle(lastActivityNanos.get())) return TunnelEndReason.IDLE_TIMEOUT
                    continue
                } catch (_: SocketException) {
                    return if (leftSocket.isClosed || rightSocket.isClosed) {
                        TunnelEndReason.CLOSED
                    } else {
                        TunnelEndReason.IO_ERROR
                    }
                } catch (_: IOException) {
                    return TunnelEndReason.IO_ERROR
                }

            if (count < 0) return TunnelEndReason.END_OF_STREAM
            if (count == 0) continue
            lastActivityNanos.set(System.nanoTime())
            try {
                output.write(buffer, 0, count)
                output.flush()
            } catch (_: SocketException) {
                return if (leftSocket.isClosed || rightSocket.isClosed) {
                    TunnelEndReason.CLOSED
                } else {
                    TunnelEndReason.IO_ERROR
                }
            } catch (_: IOException) {
                return TunnelEndReason.IO_ERROR
            }
        }
        return TunnelEndReason.CLOSED
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
}
