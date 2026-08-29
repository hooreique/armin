package dev.armin.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackMessageCodecTest {
    private val nonce = "trusted-nonce"
    private val frameA = "0123456789abcdef0123456789abcdef"
    private val frameB = "fedcba9876543210fedcba9876543210"

    @Test
    fun acceptsOnlyTheExpectedNonceAndFixedMessageFormat() {
        assertEquals(
            VideoPlaybackMessage(frameA, true),
            VideoPlaybackMessageCodec.decode(
                VideoPlaybackMessageCodec.encode(nonce, frameA, true),
                nonce,
            ),
        )
        assertNull(VideoPlaybackMessageCodec.decode("wrong\n$frameA\n1", nonce))
        assertNull(VideoPlaybackMessageCodec.decode("$nonce\nshort\n1", nonce))
        assertNull(VideoPlaybackMessageCodec.decode("$nonce\n$frameA\ntrue", nonce))
        assertNull(VideoPlaybackMessageCodec.decode("$nonce\n$frameA\n1\nextra", nonce))
    }

    @Test
    fun aggregatesFramesAndPublishesOnlyAggregateTransitions() {
        val changes = mutableListOf<Boolean>()
        val tracker = VideoPlaybackStateTracker(changes::add)

        tracker.update(frameA, true)
        tracker.update(frameB, true)
        tracker.update(frameA, false)

        assertTrue(tracker.isPlaying)
        assertEquals(listOf(true), changes)

        tracker.update(frameB, false)

        assertFalse(tracker.isPlaying)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun clearResetsNavigationOrRendererState() {
        val changes = mutableListOf<Boolean>()
        val tracker = VideoPlaybackStateTracker(changes::add)
        tracker.update(frameA, true)

        tracker.clear()
        tracker.clear()

        assertFalse(tracker.isPlaying)
        assertEquals(listOf(true, false), changes)
    }
}
