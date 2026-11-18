package code.name.monkey.retromusic.spotify

import code.name.monkey.retromusic.db.SpotifyAlbumEntity
import code.name.monkey.retromusic.db.SpotifyArtistEntity
import code.name.monkey.retromusic.db.SpotifyTrackEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the typed JSON the bridge returns (see LIBRESPOT_BRIDGE.md §5.3/§5.4). Fields may be
 * added or omitted, so everything is read leniently.
 */
object SpotifyMetadataParser {

    class ParsedTrack(
        val track: SpotifyTrackEntity,
        val album: SpotifyAlbumEntity?,
        val artists: List<SpotifyArtistEntity>,
    )

    class ParsedAlbum(
        val album: SpotifyAlbumEntity,
        val artists: List<SpotifyArtistEntity>,
        val tracks: List<ParsedTrack>,
    )

    class ParsedPlaylist(
        val unchanged: Boolean,
        val revision: String?,
        val name: String?,
        val description: String?,
        val imageUrl: String?,
        /** Every entry's URI in Spotify's order, including episodes and local files, so that
         *  positions match the indices playlist edits use. */
        val itemUris: List<String>,
        val owner: String?,
        val collaborative: Boolean,
    )

    /** `getTracksMetadataJSON`: an array of track details, in input order. */
    fun parseTracks(json: String): List<ParsedTrack> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return objects(array).mapNotNull { parseTrack(it, null) }
    }

    /**
     * One track details object. Tracks inside `getAlbumJSON` may omit `album`, so the
     * enclosing album can be passed as [albumFallback].
     */
    fun parseTrack(obj: JSONObject, albumFallback: SpotifyAlbumEntity?): ParsedTrack? {
        val uri = obj.optString("uri").ifEmpty { return null }
        if (!SpotifyIds.isTrackUri(uri)) return null
        val name = obj.optString("name").ifEmpty { return null }
        val artists = parseArtists(obj.optJSONArray("artists"))
        val albumObj = obj.optJSONObject("album")
        val album = albumObj?.let { parseAlbumHeader(it) } ?: albumFallback
        val albumArtists = albumObj?.let { parseArtists(it.optJSONArray("artists")) }.orEmpty()
        val track = SpotifyTrackEntity(
            uri = uri,
            localId = SpotifyIds.localId(uri),
            name = name,
            albumUri = album?.uri,
            durationMs = obj.optLong("duration_ms"),
            trackNumber = obj.optInt("track_number"),
            discNumber = obj.optInt("disc_number", 1).coerceAtLeast(1),
            isrc = obj.optString("isrc").ifEmpty { null },
            artistUris = SpotifyIds.joinList(artists.map { it.uri }),
            artistNames = SpotifyIds.joinList(artists.map { it.name }),
            updatedAt = System.currentTimeMillis(),
        )
        return ParsedTrack(track, album, (artists + albumArtists).distinctBy { it.uri })
    }

    /** `getAlbumJSON`. */
    fun parseAlbum(json: String): ParsedAlbum? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val album = parseAlbumHeader(obj) ?: return null
        val tracks = obj.optJSONArray("tracks")?.let { objects(it) }.orEmpty()
            .mapNotNull { parseTrack(it, album) }
            .map { if (it.track.albumUri == null) ParsedTrack(it.track.copy(albumUri = album.uri), album, it.artists) else it }
        return ParsedAlbum(album, parseArtists(obj.optJSONArray("artists")), tracks)
    }

    /** `getSavedAlbumsJSON`: album summaries (no tracks). */
    fun parseAlbumSummaries(json: String): List<SpotifyAlbumEntity> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return objects(array).mapNotNull { parseAlbumHeader(it) }
    }

    /** `getArtistJSON`: the artist's top tracks, as full track details. */
    fun parseArtistTopTracks(json: String): List<ParsedTrack> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        return obj.optJSONArray("top_tracks")?.let { objects(it) }.orEmpty().mapNotNull { parseTrack(it, null) }
    }

    /** One `search` result. [uri] is the full Spotify URI from `spotify_id`. */
    class SearchItem(
        val uri: String,
        val kind: String,
        val title: String,
        val description: String?,
        val imageUrl: String?,
    )

    /** `search`: results of every kind, in Spotify's relevance order. */
    fun parseSearch(json: String): List<SearchItem> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return objects(array).mapNotNull { o ->
            val uri = o.optString("spotify_id").ifEmpty { return@mapNotNull null }
            SearchItem(
                uri = uri,
                kind = o.optString("kind").lowercase(),
                title = o.optString("title"),
                description = o.optString("description").ifEmpty { null },
                imageUrl = SpotifyIds.imageUrlFromImageUri(o.optString("image_url").ifEmpty { null }),
            )
        }
    }

    /** `getArtistJSON`: the artist's portrait URL, if any. */
    fun parseArtistImage(json: String): String? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return SpotifyIds.imageUrlFromImageUri(obj.optString("image_url").ifEmpty { null })
    }

    /** `getPlaylistJSON`. */
    fun parsePlaylist(json: String): ParsedPlaylist? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val uris = obj.optJSONArray("tracks")?.let { objects(it) }.orEmpty()
            .map { it.optString("uri") }
        return ParsedPlaylist(
            unchanged = obj.optBoolean("unchanged"),
            revision = obj.optString("revision").ifEmpty { null },
            name = obj.optString("name").ifEmpty { null },
            description = obj.optString("description").ifEmpty { null },
            imageUrl = SpotifyIds.imageUrlFromImageUri(obj.optString("image_url").ifEmpty { null }),
            itemUris = uris,
            owner = parseOwner(obj),
            collaborative = obj.optBoolean("collaborative"),
        )
    }

    /** `getCollectionTracksJSON`: Liked Songs URIs, newest first. */
    fun parseCollectionTracks(json: String): List<String> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return objects(array).map { it.optString("uri") }.filter { SpotifyIds.isTrackUri(it) }
    }

    /** `owner` is documented without a shape; accept a username, a user URI or an object. */
    private fun parseOwner(obj: JSONObject): String? {
        val raw = obj.optJSONObject("owner")?.let { o ->
            o.optString("username").ifEmpty { o.optString("id") }.ifEmpty { o.optString("uri") }
        } ?: obj.optString("owner")
        return raw.removePrefix("spotify:user:").ifEmpty { null }?.takeUnless { it == "null" }
    }

    private fun parseAlbumHeader(obj: JSONObject): SpotifyAlbumEntity? {
        val uri = obj.optString("uri").ifEmpty { return null }
        val artists = parseArtists(obj.optJSONArray("artists"))
        val year = obj.optInt("year").takeIf { it > 0 }
            ?: obj.optString("release_date").take(4).toIntOrNull()
            ?: 0
        return SpotifyAlbumEntity(
            uri = uri,
            localId = SpotifyIds.localId(uri),
            name = obj.optString("name"),
            imageUrl = SpotifyIds.imageUrlFromImageUri(obj.optString("image_url").ifEmpty { null }),
            year = year,
            artistUris = SpotifyIds.joinList(artists.map { it.uri }),
            artistNames = SpotifyIds.joinList(artists.map { it.name }),
        )
    }

    private fun parseArtists(array: JSONArray?): List<SpotifyArtistEntity> {
        if (array == null) return emptyList()
        return objects(array).mapNotNull { a ->
            val uri = a.optString("uri").ifEmpty { return@mapNotNull null }
            SpotifyArtistEntity(uri, SpotifyIds.localId(uri), a.optString("name"), null)
        }
    }

    private fun objects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
