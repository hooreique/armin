package dev.armin.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySessionFailureTest {
    @Test
    fun `only a local listener startup failure is retryable in the same process`() {
        assertTrue(ProxySessionFailure.START_FAILED.retryableWithinProcess)
        assertFalse(ProxySessionFailure.UNSUPPORTED.retryableWithinProcess)
        assertFalse(ProxySessionFailure.OVERRIDE_FAILED.retryableWithinProcess)
    }
}
