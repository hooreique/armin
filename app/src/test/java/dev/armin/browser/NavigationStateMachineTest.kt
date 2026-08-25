package dev.armin.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStateMachineTest {
    @Test
    fun `address navigation gets one non-gesture allowance`() {
        val state = NavigationStateMachine()
        state.beginAppIssuedNavigation("https://example.com")

        assertEquals(
            NavigationDecision.Allow,
            state.evaluate(request("https://example.com/", gesture = false, redirect = false)),
        )
        state.onPageStarted("https://example.com/")
        assertTrue(state.onPageEvent("https://example.com/"))
        assertEquals("https://example.com/", state.snapshot().currentDocumentUrl)

        val redirect = state.evaluate(request("https://example.net/next", gesture = false))
        assertEquals(
            NavigationDecision.BlockAndPresent("https://example.net/next"),
            redirect,
        )
    }

    @Test
    fun `server redirect is blocked even when it matches an app allowance`() {
        val state = NavigationStateMachine()
        state.beginAppIssuedNavigation("https://example.com/")

        assertEquals(
            NavigationDecision.BlockAndPresent("https://example.com/"),
            state.evaluate(request("https://example.com/", gesture = true, redirect = true)),
        )
    }

    @Test
    fun `direct HTTPS gesture is allowed but direct HTTP gesture is blocked`() {
        val state = NavigationStateMachine()

        assertEquals(
            NavigationDecision.Allow,
            state.evaluate(request("https://example.com/link", gesture = true)),
        )
        assertEquals(
            NavigationDecision.Block,
            state.evaluate(request("http://example.com/link", gesture = true)),
        )
    }

    @Test
    fun `gesture-less movement is blocked when redirect metadata is unavailable`() {
        val state = NavigationStateMachine()

        assertEquals(
            NavigationDecision.BlockAndPresent("https://example.com/automatic"),
            state.evaluate(request("https://example.com/automatic", gesture = false)),
        )
    }

    @Test
    fun `pending target survives late callbacks from previous page`() {
        val state = NavigationStateMachine("https://origin.example/")
        state.beginAppIssuedNavigation("https://destination.example/")
        state.onPageStarted("https://destination.example/")
        state.evaluate(request("https://redirect.example/a?q=1#fragment", gesture = false))

        assertFalse(state.onPageEvent("https://destination.example/"))
        val snapshot = state.snapshot()
        assertEquals("https://origin.example/", snapshot.currentDocumentUrl)
        assertEquals("https://redirect.example/a?q=1#fragment", snapshot.pendingNavigationUrl)
        assertNull(snapshot.activeNavigationUrl)
    }

    @Test
    fun `approving pending HTTPS target starts a new explicit navigation`() {
        val state = NavigationStateMachine("https://origin.example/")
        state.evaluate(request("https://pending.example/path", gesture = false))

        state.beginAppIssuedNavigation("https://pending.example/path")
        assertNull(state.snapshot().pendingNavigationUrl)
        assertEquals(
            NavigationDecision.Allow,
            state.evaluate(request("https://pending.example/path", gesture = false)),
        )
        assertTrue(state.onPageEvent("https://pending.example/path"))
        assertEquals("https://pending.example/path", state.snapshot().currentDocumentUrl)
    }

    @Test
    fun `about blank matching does not allow lookalike schemes`() {
        assertTrue(NavigationStateMachine.isAllowedMainFrameScheme("about:blank"))
        assertTrue(NavigationStateMachine.isAllowedMainFrameScheme("about:blank#fragment"))
        assertFalse(NavigationStateMachine.isAllowedMainFrameScheme("about:blankevil"))
        assertFalse(NavigationStateMachine.isAllowedMainFrameScheme("data:text/html,hello"))
    }

    private fun request(
        url: String,
        gesture: Boolean,
        redirect: Boolean? = null,
    ) = MainFrameNavigationRequest(url, gesture, redirect)
}
