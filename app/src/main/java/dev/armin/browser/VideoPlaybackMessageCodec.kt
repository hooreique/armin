package dev.armin.browser

internal data class VideoPlaybackMessage(val frameId: String, val isPlaying: Boolean)

internal object VideoPlaybackMessageCodec {
    private const val FRAME_ID_LENGTH = 32
    private val HEX = Regex("[0-9a-f]{$FRAME_ID_LENGTH}")

    fun encode(nonce: String, frameId: String, isPlaying: Boolean): String =
        "$nonce\n$frameId\n${if (isPlaying) "1" else "0"}"

    fun decode(payload: String, expectedNonce: String): VideoPlaybackMessage? {
        val parts = payload.split('\n')
        if (parts.size != 3 || parts[0] != expectedNonce || !HEX.matches(parts[1])) return null
        val isPlaying =
            when (parts[2]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
        return VideoPlaybackMessage(parts[1], isPlaying)
    }
}

internal class VideoPlaybackStateTracker(private val onStateChanged: (Boolean) -> Unit) {
    private val playingFrames = mutableSetOf<String>()

    val isPlaying: Boolean
        get() = playingFrames.isNotEmpty()

    fun update(frameId: String, isPlaying: Boolean) {
        val wasPlaying = this.isPlaying
        if (isPlaying) playingFrames.add(frameId) else playingFrames.remove(frameId)
        publishIfChanged(wasPlaying)
    }

    fun clear() {
        val wasPlaying = isPlaying
        playingFrames.clear()
        publishIfChanged(wasPlaying)
    }

    private fun publishIfChanged(wasPlaying: Boolean) {
        if (wasPlaying != isPlaying) onStateChanged(isPlaying)
    }
}
