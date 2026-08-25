package dev.armin.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun `bare hostname gets HTTPS scheme`() {
        assertValid("example.com", "https://example.com")
    }

    @Test
    fun `path and query are preserved`() {
        assertValid("example.com/a?q=1", "https://example.com/a?q=1")
    }

    @Test
    fun `explicit HTTPS is accepted`() {
        assertValid("https://example.com", "https://example.com")
    }

    @Test
    fun `uppercase HTTPS scheme is accepted and normalized`() {
        assertValid("HTTPS://example.com/path", "https://example.com/path")
    }

    @Test
    fun `surrounding whitespace is removed`() {
        assertValid("  example.com/a  ", "https://example.com/a")
    }

    @Test
    fun `blank input is ignored`() {
        assertEquals(UrlNormalizationResult.Empty, UrlNormalizer.normalize(" \t\n"))
    }

    @Test
    fun `HTTP is blocked`() {
        assertInvalid("http://example.com")
    }

    @Test
    fun `non web schemes are blocked including numeric-looking payloads`() {
        listOf(
                "file:///tmp/a",
                "content://provider/a",
                "intent://example.com",
                "javascript:alert(1)",
                "javascript:443",
                "data:text/plain,hello",
                "mailto:user@example.com",
                "tel:1234",
            )
            .forEach(::assertInvalid)
    }

    @Test
    fun `missing or malformed host is blocked`() {
        listOf("https://", "https://not a host", "https:///path").forEach(::assertInvalid)
    }

    @Test
    fun `userinfo is blocked`() {
        assertInvalid("https://user:password@example.com/path")
    }

    @Test
    fun `nonstandard HTTPS port and path are preserved`() {
        assertValid(
            "example.com:8443/path?q=1",
            "https://example.com:8443/path?q=1",
        )
    }

    @Test
    fun `invalid ports are blocked`() {
        listOf(
                "example.com:",
                "example.com:abc/path",
                "example.com:0/path",
                "example.com:65536/path",
                "example.com:999999/path",
            )
            .forEach(::assertInvalid)
    }

    @Test
    fun `display omits only HTTPS scheme`() {
        assertEquals("example.com/a?q=1#f", UrlNormalizer.displayText("https://example.com/a?q=1#f"))
        assertEquals("http://example.com/a", UrlNormalizer.displayText("http://example.com/a"))
    }

    private fun assertValid(input: String, expected: String) {
        assertEquals(UrlNormalizationResult.Valid(expected), UrlNormalizer.normalize(input))
    }

    private fun assertInvalid(input: String) {
        assertTrue(
            "Expected invalid input: $input",
            UrlNormalizer.normalize(input) is UrlNormalizationResult.Invalid,
        )
    }
}
