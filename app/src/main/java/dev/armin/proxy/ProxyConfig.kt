package dev.armin.proxy

/** Resource and timeout limits for [LocalConnectProxy]. */
data class ProxyConfig(
    val maxConnections: Int = 16,
    val maxPendingConnections: Int = 16,
    val maxConnectHeaderBytes: Int = 16 * 1024,
    val maxClientHelloBytes: Int = 64 * 1024,
    val maxClientHelloRecords: Int = 32,
    val connectTimeoutMillis: Int = 10_000,
    val headerReadTimeoutMillis: Int = 10_000,
    val initialTlsReadTimeoutMillis: Int = 10_000,
    val idleTimeoutMillis: Int = 60_000,
    val tunnelPollTimeoutMillis: Int = 1_000,
    val tunnelBufferBytes: Int = 16 * 1024,
    val sniChunkSize: Int = 1,
    val interSegmentDelayMillis: Long = 1,
    val shutdownTimeoutMillis: Long = 2_000,
) {
    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(maxPendingConnections > 0) { "maxPendingConnections must be positive" }
        require(maxConnectHeaderBytes >= 4) { "maxConnectHeaderBytes must be at least 4" }
        require(maxClientHelloBytes >= 5) { "maxClientHelloBytes must be at least 5" }
        require(maxClientHelloRecords > 0) { "maxClientHelloRecords must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(headerReadTimeoutMillis > 0) { "headerReadTimeoutMillis must be positive" }
        require(initialTlsReadTimeoutMillis > 0) {
            "initialTlsReadTimeoutMillis must be positive"
        }
        require(idleTimeoutMillis > 0) { "idleTimeoutMillis must be positive" }
        require(tunnelPollTimeoutMillis > 0) { "tunnelPollTimeoutMillis must be positive" }
        require(tunnelPollTimeoutMillis <= idleTimeoutMillis) {
            "tunnelPollTimeoutMillis must not exceed idleTimeoutMillis"
        }
        require(tunnelBufferBytes > 0) { "tunnelBufferBytes must be positive" }
        require(sniChunkSize > 0) { "sniChunkSize must be positive" }
        require(interSegmentDelayMillis in 0..100) {
            "interSegmentDelayMillis must be between 0 and 100"
        }
        require(shutdownTimeoutMillis >= 0) { "shutdownTimeoutMillis must not be negative" }
    }
}
