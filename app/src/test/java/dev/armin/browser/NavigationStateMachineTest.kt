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
    fun `userinfo cannot match or consume an app allowance`() {
        val state = NavigationStateMachine()
        state.beginAppIssuedNavigation("https://example.com/path")

        assertEquals(
            NavigationDecision.BlockAndPresent("https://user:password@example.com/path"),
            state.evaluate(
                request(
                    "https://user:password@example.com/path",
                    gesture = false,
                    redirect = null,
                )
            ),
        )
        assertFalse(
            NavigationStateMachine.equivalent(
                "https://alice:one@example.com/path",
                "https://bob:two@example.com/path",
            )
        )
        assertFalse(
            NavigationStateMachine.equivalent(
                "https://alice:one@example.com/path",
                "https://alice:one@example.com/path",
            )
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
    fun `gesture-backed script navigation that is not an ordinary link is blocked`() {
        val state = NavigationStateMachine("https://origin.example")

        val decision =
            state.evaluate(
                MainFrameNavigationRequest(
                    url = "https://script.example/path",
                    hasGesture = true,
                    isRedirect = false,
                    isDirectLink = false,
                )
            )

        assertEquals(
            NavigationDecision.BlockAndPresent("https://script.example/path"),
            decision,
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
    fun `normalized callback matches an app URL containing dot segments`() {
        val state = NavigationStateMachine()
        state.beginAppIssuedNavigation("https://example.com/a/../b")

        assertEquals(
            NavigationDecision.Allow,
            state.evaluate(request("https://example.com/b", gesture = false, redirect = false)),
        )
        assertTrue(state.onPageEvent("https://example.com/b"))
    }

    @Test
    fun `Chromium encoded dot segments match a canonical callback path`() {
        listOf("%2e%2e", "%2E.", ".%2e", "..").forEach { parentSegment ->
            val state = NavigationStateMachine()
            state.beginAppIssuedNavigation("https://example.com/a/$parentSegment/b?q=%2e#%2e")

            assertEquals(
                NavigationDecision.Allow,
                state.evaluate(
                    request("https://example.com/b?q=%2E#%2E", gesture = false, redirect = false)
                ),
            )
            assertTrue(state.onPageEvent("https://example.com/b?q=%2E#%2E"))
        }
    }

    @Test
    fun `encoded dots inside an ordinary segment are preserved`() {
        assertFalse(
            NavigationStateMachine.equivalent(
                "https://example.com/foo%2ebar",
                "https://example.com/foobar",
            )
        )
        assertTrue(
            NavigationStateMachine.equivalent(
                "https://example.com/a/../../../b",
                "https://example.com/b",
            )
        )
        assertFalse(
            NavigationStateMachine.equivalent(
                "https://example.com/path?q=%2e#%2e",
                "https://example.com/path?q=.#.",
            )
        )
    }

    @Test
    fun `expanded IPv6 app URL matches Chromium canonical callback host`() {
        listOf(
                "https://[000:01:02:003:004:5:6:007]/" to "https://[0:1:2:3:4:5:6:7]/",
                "https://[1:0:0:2::3:0]/" to "https://[1::2:0:0:3:0]/",
                "https://[0:0:0:0:0:ffff:192.0.2.128]/" to "https://[::ffff:c000:280]/",
            )
            .forEach { (submitted, callback) ->
                val state = NavigationStateMachine()
                state.beginAppIssuedNavigation(submitted)

                assertEquals(
                    NavigationDecision.Allow,
                    state.evaluate(request(callback, gesture = false, redirect = false)),
                )
                assertTrue(state.onPageEvent(callback))
            }
    }

    @Test
    fun `same origin comparison canonicalizes host and default port only`() {
        assertTrue(
            NavigationStateMachine.sameOrigin(
                "https://[000:01:02:003:004:5:6:007]/a",
                "https://[0:1:2:3:4:5:6:7]:443/other",
            )
        )
        assertFalse(
            NavigationStateMachine.sameOrigin(
                "https://example.com/path",
                "https://example.com:8443/path",
            )
        )
        assertFalse(
            NavigationStateMachine.sameOrigin(
                "https://user@example.com/path",
                "https://example.com/path",
            )
        )
    }

    @Test
    fun `same-origin History API update advances the committed document URL`() {
        val state = NavigationStateMachine("https://example.com/old")

        assertTrue(
            state.onHistoryEvent(
                "https://example.com/new?q=1#fragment",
                "https://example.com/new?q=1#fragment",
            )
        )
        assertEquals(
            "https://example.com/new?q=1#fragment",
            state.snapshot().currentDocumentUrl,
        )
    }

    @Test
    fun `history event cannot overwrite pending state`() {
        val state = NavigationStateMachine("https://origin.example/old")
        state.evaluate(request("https://pending.example/", gesture = false))

        assertFalse(
            state.onHistoryEvent(
                "https://origin.example/late",
                "https://origin.example/late",
            )
        )
        assertEquals("https://pending.example/", state.snapshot().pendingNavigationUrl)
    }

    @Test
    fun `committed cross-origin POST callback synchronizes current document`() {
        val state = NavigationStateMachine("https://origin.example/form")

        assertTrue(
            state.onHistoryEvent(
                "https://destination.example/result",
                "https://destination.example/result",
            )
        )
        assertEquals("https://destination.example/result", state.snapshot().currentDocumentUrl)
    }

    @Test
    fun `committed fallback rejects mismatched forbidden and active URLs`() {
        val idle = NavigationStateMachine("https://origin.example/")
        assertFalse(
            idle.onHistoryEvent(
                "https://destination.example/result",
                "https://other.example/result",
            )
        )
        assertFalse(
            idle.onHistoryEvent("http://destination.example/", "http://destination.example/")
        )
        assertFalse(idle.onHistoryEvent("data:text/html,hello", "data:text/html,hello"))

        val active = NavigationStateMachine("https://origin.example/")
        active.beginAppIssuedNavigation("https://expected.example/")
        assertFalse(
            active.onHistoryEvent(
                "https://unexpected.example/",
                "https://unexpected.example/",
            )
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
        assertFalse(NavigationStateMachine.isAllowedMainFrameScheme("https:opaque"))
        assertFalse(
            NavigationStateMachine.isAllowedMainFrameScheme(
                "https://user:password@example.com/path"
            )
        )
        assertFalse(NavigationStateMachine.isAllowedMainFrameScheme("blob:http://example.com/id"))
    }

    private fun request(
        url: String,
        gesture: Boolean,
        redirect: Boolean? = null,
    ) = MainFrameNavigationRequest(url, gesture, redirect)
}
