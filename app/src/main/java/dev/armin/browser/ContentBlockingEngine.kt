package dev.armin.browser

data class ContentRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val isMainFrame: Boolean,
    val hasGesture: Boolean,
    val topLevelDocumentUrl: String?,
    val topLevelDocumentHostname: String?,
    /** WebResourceRequest does not reliably expose a Chromium resource type. */
    val resourceType: ResourceType? = null,
    /** Registrable-domain/party calculation is intentionally deferred to a future engine. */
    val partyRelation: PartyRelation? = null,
)

enum class ResourceType {
    DOCUMENT,
    SUBDOCUMENT,
    SCRIPT,
    STYLESHEET,
    IMAGE,
    MEDIA,
    FONT,
    XHR,
    OTHER,
}

enum class PartyRelation {
    FIRST_PARTY,
    THIRD_PARTY,
}

data class LocalReplacement(
    val mimeType: String,
    val encoding: String,
    val data: ByteArray,
    val responseHeaders: Map<String, String> = emptyMap(),
)

sealed interface ContentBlockingDecision {
    data object Allow : ContentBlockingDecision

    data object Block : ContentBlockingDecision

    /** Reserved for a future safe, locally packaged replacement resource. */
    data class Redirect(val replacement: LocalReplacement) : ContentBlockingDecision
}

/** Implementations must be thread-safe, fast, and perform no synchronous network I/O. */
fun interface ContentBlockingEngine {
    fun evaluate(request: ContentRequest): ContentBlockingDecision
}

object NoOpContentBlockingEngine : ContentBlockingEngine {
    override fun evaluate(request: ContentRequest): ContentBlockingDecision =
        ContentBlockingDecision.Allow
}
