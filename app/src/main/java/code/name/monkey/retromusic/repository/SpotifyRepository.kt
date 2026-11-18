package code.name.monkey.retromusic.repository

import android.os.SystemClock
import android.util.Log
import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.PlaylistWithSongs
import code.name.monkey.retromusic.db.SpotifyAlbumEntity
import code.name.monkey.retromusic.db.SpotifyArtistEntity
import code.name.monkey.retromusic.db.SpotifyDao
import code.name.monkey.retromusic.db.SpotifyPlaylistEntity
import code.name.monkey.retromusic.db.SpotifyTrackEntity
import code.name.monkey.retromusic.db.toSongsEntity
import code.name.monkey.retromusic.db.tracksByUri
import code.name.monkey.retromusic.helper.SortOrder
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.spotify.SpotifyArtwork
import code.name.monkey.retromusic.spotify.LibrespotEngine
import code.name.monkey.retromusic.spotify.SpotifyIds
import code.name.monkey.retromusic.spotify.SpotifyMetadataParser
import code.name.monkey.retromusic.spotify.SpotifyNowPlaying
import code.name.monkey.retromusic.spotify.SpotifyPlaylistInfo
import code.name.monkey.retromusic.spotify.SpotifyQueueItem
import code.name.monkey.retromusic.spotify.SpotifyRemoteCatalog
import code.name.monkey.retromusic.spotify.SpotifySavedTrackIndex
import code.name.monkey.retromusic.spotify.SpotifySavedTracks
import code.name.monkey.retromusic.util.PreferenceUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Collator

/**
 * Serves the Spotify catalogue cache as Retro models. A Spotify [Song] has
 * `data = "spotify:track:<id>"` and negative IDs from [SpotifyIds.localId].
 */
class SpotifyRepository(
    private val dao: SpotifyDao,
    private val albumRepository: RealAlbumRepository,
    private val artistRepository: RealArtistRepository,
    private val engine: LibrespotEngine,
    savedTrackIndex: SpotifySavedTrackIndex,
) {
    private class Snapshot(
        val songs: List<Song>,
        val songsByUri: Map<String, Song>,
    )

    private val snapshotMutex = Mutex()

    @Volatile
    private var snapshot: Snapshot? = null

    init {
        // Saved Ogg files override cached metadata, so rebuild Songs when they change.
        savedTrackIndex.onChanged = { invalidate() }
        savedTrackIndex.start()
    }

    /** Drops the in-memory copy; call when the cache changes. */
    fun invalidate() {
        snapshot = null
    }

    private suspend fun snapshot(): Snapshot = snapshotMutex.withLock {
        snapshot ?: run {
            val albums = dao.albums().associateBy { it.uri }
            val artists = dao.artists().associateBy { it.uri }
            val songs = dao.tracks().map { toSong(it, albums, artists) }
            Snapshot(songs, songs.associateBy { it.data }).also { snapshot = it }
        }
    }

    suspend fun songs(): List<Song> = sortSongs(snapshot().songs)

    suspend fun songs(query: String): List<Song> {
        val q = query.lowercase()
        return snapshot().songs.filter { it.title.lowercase().contains(q) }
    }

    suspend fun albums(): List<Album> = albumRepository.splitIntoAlbums(snapshot().songs)

    suspend fun albums(query: String): List<Album> {
        val q = query.lowercase()
        return albumRepository.splitIntoAlbums(snapshot().songs.filter { it.albumName.lowercase().contains(q) })
    }

    /** A library album, or one from Spotify (e.g. opened from search) if it isn't cached. */
    suspend fun album(albumId: Long): Album {
        val songs = snapshot().songs.filter { it.albumId == albumId }
            .ifEmpty { remoteAlbumSongs(albumId) }
            .sortedWith(compareBy({ it.trackNumber }, { it.title }))
        return Album(albumId, songs)
    }

    suspend fun artists(): List<Artist> =
        artistRepository.artists(albumRepository.splitIntoAlbums(snapshot().songs), false)

    suspend fun albumArtists(): List<Artist> =
        artistRepository.artists(albumRepository.splitIntoAlbums(snapshot().songs), true)

    suspend fun artists(query: String): List<Artist> {
        val q = query.lowercase()
        return artistRepository.artists(
            albumRepository.splitIntoAlbums(snapshot().songs.filter { it.artistName.lowercase().contains(q) }),
            false
        )
    }

    /** A library artist, or the artist's Spotify top tracks if none of their songs are cached. */
    suspend fun artist(artistId: Long): Artist {
        val songs = snapshot().songs.filter { it.artistId == artistId }
            .ifEmpty { remoteArtistSongs(artistId) }
        return Artist(artistId, albumRepository.splitIntoAlbums(songs))
    }

    // ---------------------------------------------------------------------------------------
    // Spotify catalogue (search, and albums/artists outside the library)
    // ---------------------------------------------------------------------------------------

    class SearchResults(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>)

    /** Albums/artists fetched for detail screens; small, since screens re-query on rotation. */
    private val remoteCache = object : LinkedHashMap<Long, List<Song>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Song>>?) = size > REMOTE_CACHE_SIZE
    }

    /**
     * Searches Spotify itself. Tracks are resolved with one batched metadata call so they carry
     * album, artists and duration like library songs. Albums and artists are lightweight
     * entries; their screens load from Spotify when opened. Returns null if the search fails
     * (offline, not logged in), so callers can fall back to the library.
     */
    suspend fun searchSpotify(query: String): SearchResults? {
        val started = SystemClock.elapsedRealtime()
        val json = engine.searchJson(query, SEARCH_LIMIT)
        if (json == null) {
            Log.w(TAG, "Spotify search failed for \"$query\"; showing library results")
            return null
        }
        val items = SpotifyMetadataParser.parseSearch(json)
        Log.i(
            TAG,
            "Spotify search \"$query\": ${items.size} results " +
                "(${items.groupingBy { it.kind }.eachCount()}) in ${SystemClock.elapsedRealtime() - started}ms"
        )
        if (items.isEmpty() && json.trim() != "[]") {
            Log.w(TAG, "Unparseable search reply: ${json.take(200)}")
            return null
        }

        val trackUris = items.filter { it.kind == "track" && SpotifyIds.isTrackUri(it.uri) }.map { it.uri }
        val songs = if (trackUris.isEmpty()) emptyList() else {
            val parsed = engine.tracksMetadataJson(trackUris)?.let { SpotifyMetadataParser.parseTracks(it) }
            if (parsed != null) toSongs(parsed) else items.filter { it.uri in trackUris }.map { searchTrackSong(it) }
        }
        val albums = items.filter { it.kind == "album" }.map { item ->
            val id = SpotifyRemoteCatalog.put(item.uri, item.description)
            SpotifyArtwork.putAlbum(id, item.imageUrl)
            Album(id, listOf(stubSong(item, albumId = id, artistId = Artist.empty.id)))
        }
        val artists = items.filter { it.kind == "artist" }.map { item ->
            val id = SpotifyRemoteCatalog.put(item.uri, item.description)
            SpotifyArtwork.putArtist(id, item.imageUrl)
            val stub = stubSong(item, albumId = SpotifyIds.localId("spotify:album-of:${item.uri}"), artistId = id)
                .copy(artistName = item.title, albumName = "")
            Artist(id, listOf(Album(stub.albumId, listOf(stub))))
        }
        return SearchResults(songs, albums, artists)
    }

    private suspend fun remoteAlbumSongs(albumId: Long): List<Song> {
        synchronized(remoteCache) { remoteCache[albumId] }?.let { return it }
        val uri = SpotifyRemoteCatalog.uri(albumId) ?: return emptyList()
        val parsed = engine.albumJson(uri)?.let { SpotifyMetadataParser.parseAlbum(it) } ?: return emptyList()
        val albumArtists = parsed.artists.associateBy { it.uri }
        val songs = toSongs(parsed.tracks).map { song ->
            // Album tracks list their own artists; keep the album's credit for "album artist".
            song.copy(albumArtist = parsed.album.artistNames.let { SpotifyIds.splitList(it).firstOrNull() })
        }
        albumArtists.keys.forEach { SpotifyRemoteCatalog.put(it) }
        synchronized(remoteCache) { remoteCache[albumId] = songs }
        return songs
    }

    private suspend fun remoteArtistSongs(artistId: Long): List<Song> {
        synchronized(remoteCache) { remoteCache[artistId] }?.let { return it }
        val uri = SpotifyRemoteCatalog.uri(artistId) ?: return emptyList()
        val json = engine.artistJson(uri) ?: return emptyList()
        SpotifyMetadataParser.parseArtistImage(json)?.let { SpotifyArtwork.putArtist(artistId, it) }
        // Credit every top track to this artist so the artist screen keeps them together.
        val songs = toSongs(SpotifyMetadataParser.parseArtistTopTracks(json)).map { it.copy(artistId = artistId) }
        synchronized(remoteCache) { remoteCache[artistId] = songs }
        return songs
    }

    /** Builds Songs from resolved tracks, registering their albums and artists for navigation. */
    private fun toSongs(parsed: List<SpotifyMetadataParser.ParsedTrack>): List<Song> {
        val albums = parsed.mapNotNull { it.album }.associateBy { it.uri }
        val artists = parsed.flatMap { it.artists }.associateBy { it.uri }
        albums.keys.forEach { SpotifyRemoteCatalog.put(it) }
        artists.keys.forEach { SpotifyRemoteCatalog.put(it) }
        return parsed.map { toSong(it.track, albums, artists) }
    }

    /** A track result when metadata could not be resolved: title and Spotify's subtitle only. */
    private fun searchTrackSong(item: SpotifyMetadataParser.SearchItem): Song {
        val albumId = SpotifyIds.localId("spotify:album-of:${item.uri}")
        SpotifyArtwork.putAlbum(albumId, item.imageUrl)
        return placeholderSong(item.uri, item.title).copy(albumId = albumId, artistName = item.description.orEmpty())
    }

    /** The one Song an album/artist search entry carries, so Retro's models can show it. */
    private fun stubSong(item: SpotifyMetadataParser.SearchItem, albumId: Long, artistId: Long): Song =
        Song(
            id = SpotifyIds.localId("spotify:search-entry:${item.uri}"),
            title = item.title,
            trackNumber = 0,
            year = 0,
            duration = 0,
            data = item.uri,
            dateModified = 0,
            albumId = albumId,
            albumName = item.title,
            artistId = artistId,
            artistName = item.description.orEmpty(),
            composer = null,
            albumArtist = null,
        )

    suspend fun albumArtist(name: String): Artist {
        val songs = snapshot().songs.filter { it.albumArtist == name }
        val albums = albumRepository.splitIntoAlbums(songs)
        return if (albums.isEmpty()) Artist.empty else Artist(name, albums, true)
    }

    /** Songs for [uris] in the given order; URIs not in the cache are skipped. */
    suspend fun songsForUris(uris: List<String>): List<Song> {
        val byUri = snapshot().songsByUri
        val missing = uris.filter { it !in byUri }
        if (missing.isEmpty()) return uris.mapNotNull { byUri[it] }
        // The snapshot may be stale (e.g. queue items resolved after it was built).
        val albums = dao.albums().associateBy { it.uri }
        val artists = dao.artists().associateBy { it.uri }
        val fresh = dao.tracksByUri(missing).associate { it.uri to toSong(it, albums, artists) }
        return uris.mapNotNull { byUri[it] ?: fresh[it] }
    }

    suspend fun songForUri(uri: String): Song? = songsForUris(listOf(uri)).firstOrNull()

    /** Liked Songs, Spotify playlists and saved albums from the library, as Retro playlists. */
    suspend fun playlistsWithSongs(): List<PlaylistWithSongs> =
        dao.playlists()
            .filter { it.kind in LISTED_KINDS }
            .map { playlist -> toPlaylistWithSongs(playlist) }

    suspend fun playlistWithSongs(localId: Long): PlaylistWithSongs? =
        dao.playlistByLocalId(localId)?.let { toPlaylistWithSongs(it) }

    /** Emits the playlist again whenever it or its entries change; null once it is deleted. */
    fun observePlaylistWithSongs(localId: Long): Flow<PlaylistWithSongs?> =
        combine(dao.observePlaylist(localId), dao.observePlaylistTrackUris(localId)) { playlist, uris ->
            playlist?.let { toPlaylistWithSongs(it, uris) }
        }

    /** Liked Songs and the playlists the user can edit, for the "Add to playlist" dialog. */
    suspend fun editablePlaylists(): List<PlaylistEntity> =
        dao.playlists()
            .onEach { SpotifyPlaylistInfo.put(it) }
            .filter { it.kind == SpotifyPlaylistInfo.KIND_COLLECTION || (it.kind == SpotifyPlaylistInfo.KIND_PLAYLIST && it.editable) }
            .map { PlaylistEntity(playListId = it.localId, playlistName = it.name) }

    private suspend fun toPlaylistWithSongs(
        playlist: SpotifyPlaylistEntity,
        uris: List<String>? = null,
    ): PlaylistWithSongs {
        // Glide and the playlist menus run on the main thread, so publish what they need.
        SpotifyArtwork.putPlaylist(playlist.localId, playlist.imageUrl)
        SpotifyPlaylistInfo.put(playlist)
        // Entries can include episodes and local files; songsForUris skips what isn't cached.
        val songs = songsForUris(uris ?: dao.playlistTrackUris(playlist.uri))
        return PlaylistWithSongs(
            PlaylistEntity(playListId = playlist.localId, playlistName = playlist.name),
            songs.toSongsEntity(playlist.localId)
        )
    }

    /**
     * The Spotify context (playlist or saved album URI) whose tracks are exactly [songs] in
     * order, or null. This lets "play this playlist" play the real Spotify context without
     * every caller having to pass a context URI.
     */
    suspend fun contextFor(songs: List<Song>): String? {
        val uris = songs.map { it.data }
        val first = uris.firstOrNull() ?: return null
        if (uris.size < 2 || !uris.all { SpotifyIds.isTrackUri(it) }) return null
        return dao.playlistsContaining(first).firstOrNull { playlistUri ->
            dao.playlistTrackUris(playlistUri).filter { SpotifyIds.isTrackUri(it) } == uris
        }
    }

    /** The Spotify URI behind a playlist ID, used as the playback context. */
    suspend fun playlistUri(localId: Long): String? = dao.playlistByLocalId(localId)?.uri

    /** The Spotify URI behind an album ID, if the album is in the cache. */
    suspend fun albumUri(albumId: Long): String? =
        dao.albums().firstOrNull { it.localId == albumId }?.uri

    /** The Spotify URI behind an artist ID, if the artist is in the cache. */
    suspend fun artistUri(artistId: Long): String? =
        dao.artists().firstOrNull { it.localId == artistId }?.uri

    private fun sortSongs(songs: List<Song>): List<Song> {
        val collator = Collator.getInstance()
        return when (PreferenceUtil.songSortOrder) {
            SortOrder.SongSortOrder.SONG_Z_A -> songs.sortedWith { a, b -> collator.compare(b.title, a.title) }
            SortOrder.SongSortOrder.SONG_ARTIST -> songs.sortedWith { a, b -> collator.compare(a.artistName, b.artistName) }
            SortOrder.SongSortOrder.SONG_ALBUM_ARTIST -> songs.sortedWith { a, b ->
                collator.compare(a.albumArtist.orEmpty(), b.albumArtist.orEmpty())
            }
            SortOrder.SongSortOrder.SONG_ALBUM -> songs.sortedWith { a, b -> collator.compare(a.albumName, b.albumName) }
            SortOrder.SongSortOrder.SONG_YEAR -> songs.sortedByDescending { it.year }
            SortOrder.SongSortOrder.SONG_DURATION -> songs.sortedByDescending { it.duration }
            else -> songs.sortedWith { a, b -> collator.compare(a.title, b.title) }
        }
    }

    companion object {
        private val LISTED_KINDS = setOf("collection", "playlist", "album")
        private const val TAG = "SpotifyRepository"
        private const val SEARCH_LIMIT = 30L
        private const val REMOTE_CACHE_SIZE = 20

        fun toSong(
            track: SpotifyTrackEntity,
            albums: Map<String, SpotifyAlbumEntity>,
            artists: Map<String, SpotifyArtistEntity>,
        ): Song {
            val album = track.albumUri?.let { albums[it] }
            val artistUris = SpotifyIds.splitList(track.artistUris)
            val artistNames = SpotifyIds.splitList(track.artistNames)
            val albumId = album?.localId
                ?: track.albumUri?.let { SpotifyIds.localId(it) }
                ?: SpotifyIds.localId("spotify:album-of:${track.uri}")
            val primaryArtistUri = artistUris.firstOrNull()
            val artistId = primaryArtistUri?.let { SpotifyIds.localId(it) } ?: Artist.empty.id
            SpotifyArtwork.putAlbum(albumId, album?.imageUrl)
            primaryArtistUri?.let { SpotifyArtwork.putArtist(artistId, artists[it]?.imageUrl) }
            return SpotifySavedTracks.applyTo(Song(
                id = track.localId,
                title = track.name,
                trackNumber = if (track.discNumber > 1) track.discNumber * 1000 + track.trackNumber else track.trackNumber,
                year = album?.year ?: 0,
                duration = track.durationMs,
                data = track.uri,
                dateModified = track.updatedAt / 1000,
                albumId = albumId,
                albumName = album?.name.orEmpty(),
                artistId = artistId,
                artistName = artistNames.joinToString(", "),
                composer = null,
                albumArtist = album?.artistNames?.let { SpotifyIds.splitList(it).firstOrNull() },
            ))
        }

        /** A Song for the track librespot is playing that is not in the cache yet. */
        fun toSong(nowPlaying: SpotifyNowPlaying): Song {
            val albumId = SpotifyIds.localId("spotify:album-name:${nowPlaying.albumName}")
            SpotifyArtwork.putAlbum(albumId, nowPlaying.coverUrl)
            return SpotifySavedTracks.applyTo(Song(
                id = SpotifyIds.localId(nowPlaying.uri),
                title = nowPlaying.name,
                trackNumber = nowPlaying.trackNumber,
                year = nowPlaying.year,
                duration = nowPlaying.durationMs,
                data = nowPlaying.uri,
                dateModified = 0,
                albumId = albumId,
                albumName = nowPlaying.albumName,
                artistId = SpotifyIds.localId("spotify:artist-name:${nowPlaying.artistNames.firstOrNull().orEmpty()}"),
                artistName = nowPlaying.artistNames.joinToString(", "),
                composer = null,
                albumArtist = nowPlaying.artistNames.firstOrNull(),
            ))
        }

        /**
         * A Song for a queue entry that is not in the cache, built from the metadata librespot
         * includes in its queue. IDs follow the same scheme as cached songs.
         */
        fun toSong(item: SpotifyQueueItem): Song {
            if (item.albumUri == null && item.artistUris.isEmpty()) return placeholderSong(item.uri, item.name)
            val albumId = SpotifyIds.localId(item.albumUri ?: "spotify:album-of:${item.uri}")
            val artistId = item.artistUris.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { SpotifyIds.localId(it) } ?: Artist.empty.id
            SpotifyArtwork.putAlbum(albumId, item.imageUrl)
            return SpotifySavedTracks.applyTo(Song(
                id = SpotifyIds.localId(item.uri),
                title = item.name.ifEmpty { item.uri },
                trackNumber = 0,
                year = 0,
                duration = item.durationMs,
                data = item.uri,
                dateModified = 0,
                albumId = albumId,
                albumName = item.albumName,
                artistId = artistId,
                artistName = item.artistNames.joinToString(", "),
                composer = null,
                albumArtist = item.artistNames.firstOrNull(),
            ))
        }

        /** A placeholder for a queued track with only a name (metadata not fetched yet). */
        fun placeholderSong(uri: String, name: String): Song = SpotifySavedTracks.applyTo(Song(
            id = SpotifyIds.localId(uri),
            title = name.ifEmpty { uri },
            trackNumber = 0,
            year = 0,
            duration = 0,
            data = uri,
            dateModified = 0,
            albumId = SpotifyIds.localId("spotify:album-of:$uri"),
            albumName = "",
            artistId = Artist.empty.id,
            artistName = "",
            composer = null,
            albumArtist = null,
        ))
    }
}

/** [Song] is not a data class; this copies the fields Spotify search and detail screens adjust. */
private fun Song.copy(
    albumId: Long = this.albumId,
    albumName: String = this.albumName,
    artistId: Long = this.artistId,
    artistName: String = this.artistName,
    albumArtist: String? = this.albumArtist,
): Song = Song(
    id = id,
    title = title,
    trackNumber = trackNumber,
    year = year,
    duration = duration,
    data = data,
    dateModified = dateModified,
    albumId = albumId,
    albumName = albumName,
    artistId = artistId,
    artistName = artistName,
    composer = composer,
    albumArtist = albumArtist,
)
