package dev.armin.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentBlockingEngineTest {
    @Test
    fun `MVP engine allows every request without side effects`() {
        val request =
            ContentRequest(
                url = "https://cdn.example/script.js",
                method = "GET",
                headers = mapOf("Accept" to "text/javascript"),
                isMainFrame = false,
                hasGesture = false,
                topLevelDocumentUrl = "https://example.com/",
                topLevelDocumentHostname = "example.com",
                resourceType = null,
                partyRelation = null,
            )

        assertEquals(ContentBlockingDecision.Allow, NoOpContentBlockingEngine.evaluate(request))
    }
}
