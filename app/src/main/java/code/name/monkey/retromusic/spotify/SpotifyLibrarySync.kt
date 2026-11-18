package code.name.monkey.retromusic.spotify

import android.content.Context
import android.util.Log
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.SpotifyAlbumEntity
import code.name.monkey.retromusic.db.SpotifyArtistEntity
import code.name.monkey.retromusic.db.SpotifyDao
import code.name.monkey.retromusic.db.SpotifyPlaylistEntity
import code.name.monkey.retromusic.db.SpotifyTrackEntity
import code.name.monkey.retromusic.db.tracksByUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

sealed interface SpotifySyncState {
    data object Idle : SpotifySyncState
    data class Running(val step: String, val done: Int, val total: Int) : SpotifySyncState
    data class Finished(val tracks: Int, val playlists: Int) : SpotifySyncState
    data class Failed(val reason: String) : SpotifySyncState
}

/**
 * Builds the Spotify catalogue cache from the user's library:
 *  1. the root library list (playlists, saved albums, artists; folders are flattened),
 *  2. each playlist's track list, skipped when its revision is unchanged,
 *  3. Liked Songs and saved albums,
 *  4. batched metadata for every track URI not cached yet,
 *  5. artist portraits, a bounded number per run.
 *
 * [startPlaylistsOnly] (used on app start) does 1, 2, Liked Songs and 4 only: it skips the
 * saved-albums list and artist portraits, which Glide fetches on demand anyway.
 */
class SpotifyLibrarySync(
    context: Context,
    private val engine: LibrespotEngine,
    private val dao: SpotifyDao,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null

    private val artistsWithoutImage: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val _state = MutableStateFlow<SpotifySyncState>(SpotifySyncState.Idle)
    val state: StateFlow<SpotifySyncState> = _state.asStateFlow()

    /** Listeners notified (on IO) when the cache contents change, e.g. to reload library tabs. */
    var onCacheChanged: (() -> Unit)? = null

    /** Full sync, for the Settings buttons and the first sync after logging in. */
    fun start(fullRescan: Boolean = false) = launchSync(fullRescan, playlistsOnly = false)

    /** Picks up playlist changes and their new songs; cheap when nothing changed. */
    fun startPlaylistsOnly() = launchSync(fullRescan = false, playlistsOnly = true)

    private fun launchSync(fullRescan: Boolean, playlistsOnly: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            mutex.withLock {
                try {
                    sync(fullRescan, playlistsOnly)
                } catch (e: Exception) {
                    Log.e(TAG, "Spotify sync failed", e)
                    _state.value = SpotifySyncState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    suspend fun clear() = mutex.withLock {
        dao.clearAll()
        _state.value = SpotifySyncState.Idle
        onCacheChanged?.invoke()
    }

    private suspend fun sync(fullRescan: Boolean, playlistsOnly: Boolean) {
        _state.value = SpotifySyncState.Running(STEP_LIBRARY, 0, 0)
        val rootJson = retry { engine.rootLibraryItemsJson() }
            ?: throw IllegalStateException("Could not read the Spotify library")
        val rootItems = parseRootItems(rootJson)
        val existing = dao.playlists().associateBy { it.uri }
        val entities = LinkedHashMap<String, SpotifyPlaylistEntity>()

        fun entity(item: RootItem, position: Int, revision: String?) = SpotifyPlaylistEntity(
            uri = item.uri,
            localId = SpotifyIds.localId(item.uri),
            name = item.name,
            description = item.description,
            imageUrl = item.imageUrl,
            kind = item.kind,
            position = position,
            revision = revision,
        )

        // 1 + 2. Root items and playlist contents.
        val playlists = rootItems.filter { it.kind == "playlist" }
        var playlistIndex = 0
        rootItems.forEachIndexed { position, item ->
            val stored = existing[item.uri]
            when (item.kind) {
                "playlist" -> {
                    _state.value = SpotifySyncState.Running(STEP_PLAYLISTS, playlistIndex++, playlists.size)
                    // Older builds stored revisions without fetching tracks, so a playlist with
                    // no cached tracks is always fetched in full (cheap for a truly empty one).
                    val hasTracks = dao.playlistTrackCount(item.uri) > 0
                    val knownRevision = stored?.revision.takeUnless { fullRescan || !hasTracks }
                    if (knownRevision != null && knownRevision == item.revision) {
                        // Unchanged since the last sync; no request needed.
                        entities[item.uri] = entity(item, position, knownRevision)
                            .copy(editable = stored?.editable == true)
                    } else {
                        val parsed = engine.playlistJson(item.uri, knownRevision)
                            ?.let { SpotifyMetadataParser.parsePlaylist(it) }
                        if (parsed != null && !parsed.unchanged) {
                            dao.replacePlaylistTracks(item.uri, parsed.itemUris)
                        }
                        // On failure keep the old revision so the next sync retries.
                        val revision = when {
                            parsed == null -> stored?.revision
                            else -> parsed.revision ?: item.revision
                        }
                        entities[item.uri] = entity(item, position, revision).copy(
                            imageUrl = item.imageUrl ?: parsed?.imageUrl ?: stored?.imageUrl,
                            description = item.description ?: parsed?.description,
                            // An "unchanged" reply may omit the owner, so keep what we knew.
                            editable = parsed?.takeUnless { it.unchanged }?.let { isEditable(it) }
                                ?: (stored?.editable == true),
                        )
                    }
                }

                "album" -> {
                    syncAlbum(item.uri, fullRescan)
                    entities[item.uri] = entity(item, position, item.revision)
                }

                "collection" -> Unit // Added below with its real contents.

                else -> entities[item.uri] = entity(item, position, item.revision)
            }
        }

        // 3a. Liked Songs.
        _state.value = SpotifySyncState.Running(STEP_LIKED, 0, 0)
        val likedJson = engine.collectionTracksJson()
        if (likedJson != null) {
            dao.replacePlaylistTracks(SpotifyIds.COLLECTION_URI, SpotifyMetadataParser.parseCollectionTracks(likedJson))
        }
        if (likedJson != null || existing.containsKey(SpotifyIds.COLLECTION_URI)) {
            entities[SpotifyIds.COLLECTION_URI] = SpotifyPlaylistEntity(
                uri = SpotifyIds.COLLECTION_URI,
                localId = SpotifyIds.localId(SpotifyIds.COLLECTION_URI),
                name = appContext.getString(R.string.spotify_liked_songs),
                description = null,
                imageUrl = null,
                kind = "collection",
                position = Int.MIN_VALUE, // Always first, like Spotify's own library.
                revision = null,
            )
        }

        // 3b. Saved albums that the root list does not already include. A playlists-only sync
        // keeps the ones stored by the last full sync instead of asking again.
        if (playlistsOnly) {
            existing.values
                .filter { it.kind == "album" && !entities.containsKey(it.uri) }
                .forEach { entities[it.uri] = it }
        }
        val savedAlbums = if (playlistsOnly) emptyList() else
            engine.savedAlbumsJson()?.let { SpotifyMetadataParser.parseAlbumSummaries(it) }.orEmpty()
                .filterNot { entities.containsKey(it.uri) }
        savedAlbums.forEachIndexed { index, album ->
            _state.value = SpotifySyncState.Running(STEP_ALBUMS, index, savedAlbums.size)
            syncAlbum(album.uri, fullRescan)
            entities[album.uri] = SpotifyPlaylistEntity(
                uri = album.uri,
                localId = album.localId,
                name = album.name,
                description = SpotifyIds.splitList(album.artistNames).joinToString(", ").ifEmpty { null },
                imageUrl = album.imageUrl,
                kind = "album",
                position = rootItems.size + index,
                revision = null,
            )
        }

        val all = entities.values.toList()
        dao.upsertPlaylists(all)
        if (all.isNotEmpty()) {
            // Also drops folder rows and the start-group/end-group rows older builds stored.
            dao.deletePlaylistsNotIn(all.map { it.uri })
        }
        dao.deleteOrphanPlaylistTracks()
        onCacheChanged?.invoke()

        // 4. Track metadata for everything referenced and not cached yet.
        // Playlists also hold episodes and local files; only tracks have metadata to cache.
        val referenced = dao.allPlaylistTrackUris().filter { SpotifyIds.isTrackUri(it) }
        val toResolve = if (fullRescan) referenced else referenced.minus(dao.trackUris().toSet())
        resolveTracks(toResolve.toList()) { done ->
            _state.value = SpotifySyncState.Running(STEP_TRACKS, done, toResolve.size)
        }

        // 5. Artist portraits (one request per artist, so bounded per run). Skipped on app
        // start: artist screens fetch missing portraits on demand (artistImageUrl).
        val artistUris = if (playlistsOnly) emptyList() else
            dao.artistUrisWithoutImage().take(MAX_ARTIST_IMAGES_PER_SYNC)
        artistUris.forEachIndexed { index, uri ->
            _state.value = SpotifySyncState.Running(STEP_ARTISTS, index, artistUris.size)
            engine.artistJson(uri)?.let { SpotifyMetadataParser.parseArtistImage(it) }
                ?.let { dao.setArtistImage(uri, it) }
            if (index % FLUSH_EVERY == FLUSH_EVERY - 1) onCacheChanged?.invoke()
        }
        if (artistUris.isNotEmpty()) onCacheChanged?.invoke()

        _state.value = SpotifySyncState.Finished(
            tracks = dao.trackUris().size,
            playlists = all.size
        )
    }

    /**
     * Whether Retro may edit a playlist: the user owns it or it is collaborative. With no
     * owner in the response, allow it and let Spotify reject the edit.
     */
    fun isEditable(playlist: SpotifyMetadataParser.ParsedPlaylist): Boolean {
        if (playlist.collaborative) return true
        val owner = playlist.owner ?: return true
        val username = engine.username ?: return false
        return owner.equals(username, ignoreCase = true)
    }

    /** Caches an album and its tracks. Albums don't change, so cached ones are skipped. */
    private suspend fun syncAlbum(uri: String, fullRescan: Boolean) {
        if (!fullRescan && dao.playlistTrackUris(uri).isNotEmpty()) return
        val parsed = engine.albumJson(uri)?.let { SpotifyMetadataParser.parseAlbum(it) } ?: return
        val artists = (parsed.artists + parsed.tracks.flatMap { it.artists }).distinctBy { it.uri }
        dao.upsertAlbums(listOf(parsed.album))
        dao.upsertArtists(artists.withoutClobberingImages())
        dao.upsertTracks(parsed.tracks.map { it.track })
        dao.replacePlaylistTracks(uri, parsed.tracks.map { it.track.uri })
    }

    /**
     * Fetches and caches metadata for [uris] in batches, flushing each batch to the database
     * so the library tabs fill in while a large sync runs.
     *
     * The bridge returns what it managed to resolve and drops the rest, both for unknown URIs
     * and when a request times out. URIs missing from a reply are asked for once more after a
     * short pause; what is still missing then is left for the next sync.
     */
    suspend fun resolveTracks(uris: List<String>, onProgress: (Int) -> Unit = {}) {
        uris.chunked(METADATA_BATCH).forEachIndexed { batchIndex, batch ->
            val resolved = fetchAndStoreTracks(batch)
            val missing = batch.filterNot { it in resolved }
            if (missing.isNotEmpty()) {
                delay(RETRY_DELAY_MS)
                val retried = fetchAndStoreTracks(missing)
                val stillMissing = missing.size - retried.size
                if (stillMissing > 0) Log.i(TAG, "$stillMissing of ${batch.size} tracks unresolved after retry")
            }
            onProgress(((batchIndex + 1) * METADATA_BATCH).coerceAtMost(uris.size))
        }
    }

    /** One `getTracksMetadataJSON` call; returns the URIs that came back and were stored. */
    private suspend fun fetchAndStoreTracks(uris: List<String>): Set<String> {
        val parsed = engine.tracksMetadataJson(uris)?.let { SpotifyMetadataParser.parseTracks(it) }
        if (parsed.isNullOrEmpty()) return emptySet()
        val albums = parsed.mapNotNull { it.album }.distinctBy { it.uri }
        val artists = parsed.flatMap { it.artists }.distinctBy { it.uri }
        dao.upsertAlbums(albums.withoutClobberingAlbumImages())
        dao.upsertArtists(artists.withoutClobberingImages())
        dao.upsertTracks(parsed.map { it.track })
        onCacheChanged?.invoke()
        return parsed.mapTo(HashSet()) { it.track.uri }
    }

    /** Resolves only the URIs missing from the cache. */
    suspend fun ensureTracksCached(uris: Collection<String>) {
        val trackUris = uris.filter { SpotifyIds.isTrackUri(it) }
        if (trackUris.isEmpty()) return
        val known = dao.tracksByUri(trackUris).mapTo(HashSet()) { it.uri }
        val missing = trackUris.filterNot { it in known }.distinct()
        if (missing.isNotEmpty()) resolveTracks(missing)
    }

    /** Whether [uri] is in Liked Songs; asks Spotify, falling back to the cached list. */
    suspend fun isLiked(uri: String): Boolean =
        engine.isLiked(listOf(uri))?.get(uri)
            ?: (uri in dao.playlistTrackUris(SpotifyIds.COLLECTION_URI))

    /** Likes or unlikes [uris] on Spotify and mirrors the change into the cached Liked Songs. */
    suspend fun setLiked(uris: List<String>, liked: Boolean): Boolean {
        if (uris.isEmpty()) return true
        if (!engine.setLiked(uris, liked)) return false
        // Not under [mutex]: a running sync can take minutes, and the heart button must not wait.
        // A sync that races this re-reads Liked Songs from Spotify anyway.
        val current = dao.playlistTrackUris(SpotifyIds.COLLECTION_URI)
        val updated = if (liked) uris.distinct() + (current - uris.toSet()) else current - uris.toSet()
        if (liked) ensureTracksCached(uris)
        dao.replacePlaylistTracks(SpotifyIds.COLLECTION_URI, updated)
        onCacheChanged?.invoke()
        return true
    }

    /**
     * The artist's Spotify portrait, fetched on demand for artists the sync hasn't reached yet
     * (it only fetches a bounded number per run). Used by Glide, off the main thread. Artists
     * Spotify has no portrait for are remembered for this process so they aren't re-requested.
     */
    suspend fun artistImageUrl(artistId: Long): String? {
        SpotifyArtwork.artistUrl(artistId)?.let { return it }
        if (artistId in artistsWithoutImage) return null
        val artist = dao.artistByLocalId(artistId)
        if (artist == null) {
            artistsWithoutImage.add(artistId)
            return null
        }
        artist.imageUrl?.let {
            SpotifyArtwork.putArtist(artistId, it)
            return it
        }
        // A failed request (null JSON) is not remembered, so it is retried next time.
        val json = engine.artistJson(artist.uri) ?: return null
        val url = SpotifyMetadataParser.parseArtistImage(json)
        if (url == null) {
            artistsWithoutImage.add(artistId)
            return null
        }
        dao.setArtistImage(artist.uri, url)
        SpotifyArtwork.putArtist(artistId, url)
        return url
    }

    /** Upserting an artist from track metadata (no image) must not erase a fetched portrait. */
    private suspend fun List<SpotifyArtistEntity>.withoutClobberingImages(): List<SpotifyArtistEntity> {
        if (isEmpty()) return this
        val existing = dao.artists().associateBy { it.uri }
        return map { artist -> artist.copy(imageUrl = artist.imageUrl ?: existing[artist.uri]?.imageUrl) }
    }

    private suspend fun List<SpotifyAlbumEntity>.withoutClobberingAlbumImages(): List<SpotifyAlbumEntity> {
        if (isEmpty()) return this
        val existing = dao.albums().associateBy { it.uri }
        return map { album -> album.copy(imageUrl = album.imageUrl ?: existing[album.uri]?.imageUrl) }
    }

    private suspend fun <T> retry(times: Int = 5, block: suspend () -> T?): T? {
        repeat(times) { attempt ->
            block()?.let { return it }
            delay(1000L * (attempt + 1))
        }
        return null
    }

    private class RootItem(
        val uri: String,
        val name: String,
        val description: String?,
        val imageUrl: String?,
        val kind: String,
        val revision: String?,
    )

    /**
     * Folders arrive as `{kind: "folder"}` items, and their children carry a `folder` field.
     * Retro has no playlist folders, so folders are dropped and their children kept in order.
     */
    private fun parseRootItems(json: String): List<RootItem> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri").ifEmpty { return@mapNotNull null }
            val kind = o.optString("kind").ifEmpty { "unknown" }
            if (kind == "folder" || uri.startsWith("spotify:folder:") ||
                uri.startsWith("spotify:start-group:") || uri.startsWith("spotify:end-group:")
            ) return@mapNotNull null
            RootItem(
                uri = uri,
                name = o.optString("name").ifEmpty { uri },
                description = o.optString("description").ifEmpty { null },
                imageUrl = SpotifyIds.imageUrlFromImageUri(o.optString("image_url").ifEmpty { null }),
                kind = kind,
                revision = o.optString("revision").ifEmpty { null },
            )
        }
    }

    companion object {
        private const val TAG = "SpotifyLibrarySync"
        private const val FLUSH_EVERY = 25
        private const val METADATA_BATCH = 200
        private const val RETRY_DELAY_MS = 2_000L
        private const val MAX_ARTIST_IMAGES_PER_SYNC = 150

        const val STEP_LIBRARY = "library"
        const val STEP_PLAYLISTS = "playlists"
        const val STEP_LIKED = "liked"
        const val STEP_ALBUMS = "albums"
        const val STEP_TRACKS = "tracks"
        const val STEP_ARTISTS = "artists"
    }
}
