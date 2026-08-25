package dev.armin.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectLinkMessageCodecTest {
    @Test
    fun `valid isolated-world message is decoded without changing URL bytes`() {
        assertEquals(
            DirectLinkMessage(
                "https://origin.example/a?q=1#fragment",
                "https://destination.example/path?q=%2F#value",
            ),
            DirectLinkMessageCodec.decode(
                "nonce\nhttps://origin.example/a?q=1#fragment\n" +
                    "https://destination.example/path?q=%2F#value",
                "nonce",
            ),
        )
    }

    @Test
    fun `forged truncated and empty messages are rejected`() {
        listOf(
                "wrong\nhttps://origin.example/\nhttps://destination.example/",
                "nonce\nhttps://origin.example/",
                "nonce\n\nhttps://destination.example/",
                "nonce\nhttps://origin.example/\n",
            )
            .forEach { payload -> assertNull(DirectLinkMessageCodec.decode(payload, "nonce")) }
    }

    @Test
    fun `oversized message is rejected before field parsing`() {
        val oversized =
            "nonce\nhttps://origin.example/\nhttps://destination.example/" +
                "a".repeat(DirectLinkMessageCodec.MAX_PAYLOAD_CHARS)

        assertNull(DirectLinkMessageCodec.decode(oversized, "nonce"))
    }
}
