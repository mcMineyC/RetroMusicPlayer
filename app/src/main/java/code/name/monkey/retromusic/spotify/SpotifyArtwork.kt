package code.name.monkey.retromusic.spotify

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory artwork URLs for Spotify albums, artists and library playlists.
 *
 * Glide models are chosen on the main thread, so they cannot query Room. [SpotifyRepository]
 * fills this map whenever it builds Songs, and the player fills it from librespot's metadata
 * events, so any Spotify Song on screen already has its URL here.
 */
object SpotifyArtwork {
    private val albumImages = ConcurrentHashMap<Long, String>()
    private val artistImages = ConcurrentHashMap<Long, String>()
    private val playlistImages = ConcurrentHashMap<Long, String>()

    fun putAlbum(albumId: Long, url: String?) {
        if (!url.isNullOrEmpty()) albumImages[albumId] = url
    }

    fun putArtist(artistId: Long, url: String?) {
        if (!url.isNullOrEmpty()) artistImages[artistId] = url
    }

    fun putPlaylist(playlistId: Long, url: String?) {
        if (!url.isNullOrEmpty()) playlistImages[playlistId] = url
    }

    fun albumUrl(albumId: Long): String? = albumImages[albumId]

    fun artistUrl(artistId: Long): String? = artistImages[artistId]

    fun playlistUrl(playlistId: Long): String? = playlistImages[playlistId]
}
