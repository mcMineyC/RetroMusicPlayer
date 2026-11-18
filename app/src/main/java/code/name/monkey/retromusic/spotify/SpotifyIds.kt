package code.name.monkey.retromusic.spotify

import java.security.MessageDigest

/** Helpers for Spotify URIs, image URLs and Retro's Long IDs. */
object SpotifyIds {

    /** Separator for ordered lists stored in a single Room column. */
    const val LIST_SEPARATOR = "\u001F"

    private const val IMAGE_BASE_URL = "https://i.scdn.co/image/"
    private const val MOSAIC_BASE_URL = "https://mosaic.scdn.co/640/"

    /** Liked Songs, stored as a library item so it shows up with the playlists. */
    const val COLLECTION_URI = "spotify:collection"

    fun isSpotifyUri(value: String?): Boolean = value != null && value.startsWith("spotify:")

    fun isTrackUri(value: String?): Boolean = value != null && value.startsWith("spotify:track:")

    /**
     * Stable Long for a Spotify URI. Always negative so it never collides with MediaStore IDs,
     * and never -1 / -2, which Retro uses as "empty" and "various artists" sentinels.
     */
    fun localId(uri: String): Long {
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xFF)
        }
        value = value or Long.MIN_VALUE
        return if (value == -1L || value == -2L) value - 2 else value
    }

    /**
     * Converts a `spotify:image:<hex>` URI (as found in library metadata) to a CDN URL.
     * Auto-generated playlist covers arrive as `spotify:mosaic:<id>:<id>:<id>:<id>`.
     */
    fun imageUrlFromImageUri(value: String?): String? {
        if (value.isNullOrEmpty() || value == "null") return null
        if (value.startsWith("http")) return value
        if (value.startsWith("spotify:image:")) return IMAGE_BASE_URL + value.removePrefix("spotify:image:")
        if (value.startsWith("spotify:mosaic:")) {
            val ids = value.removePrefix("spotify:mosaic:").split(':').filter { it.isNotEmpty() }
            return when {
                ids.size >= 4 -> MOSAIC_BASE_URL + ids.take(4).joinToString("")
                ids.isNotEmpty() -> IMAGE_BASE_URL + ids.first()
                else -> null
            }
        }
        return null
    }

    fun joinList(values: List<String>): String = values.joinToString(LIST_SEPARATOR)

    fun splitList(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList() else value.split(LIST_SEPARATOR)
}
