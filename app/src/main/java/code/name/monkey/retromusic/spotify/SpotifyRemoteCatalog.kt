package code.name.monkey.retromusic.spotify

import java.util.concurrent.ConcurrentHashMap

/**
 * Spotify albums and artists seen in search results that may not be in the library cache.
 * Retro navigates by Long ID, so this maps those IDs back to URIs for the album and artist
 * screens, and keeps the subtitle Spotify gave each result. Lives for the process only.
 */
object SpotifyRemoteCatalog {
    private val uris = ConcurrentHashMap<Long, String>()
    private val descriptions = ConcurrentHashMap<Long, String>()

    /** Remembers [uri] and returns its Retro ID. */
    fun put(uri: String, description: String? = null): Long {
        val id = SpotifyIds.localId(uri)
        uris[id] = uri
        if (!description.isNullOrBlank()) descriptions[id] = description
        return id
    }

    fun uri(id: Long): String? = uris[id]

    fun description(id: Long): String? = descriptions[id]
}
