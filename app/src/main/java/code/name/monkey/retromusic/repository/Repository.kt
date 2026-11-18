/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package code.name.monkey.retromusic.repository

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import code.name.monkey.retromusic.*
import code.name.monkey.retromusic.db.*
import code.name.monkey.retromusic.fragments.search.Filter
import code.name.monkey.retromusic.model.*
import code.name.monkey.retromusic.model.smartplaylist.NotPlayedPlaylist
import code.name.monkey.retromusic.network.LastFMService
import code.name.monkey.retromusic.network.Result
import code.name.monkey.retromusic.network.Result.Error
import code.name.monkey.retromusic.network.Result.Success
import code.name.monkey.retromusic.network.model.LastFmAlbum
import code.name.monkey.retromusic.network.model.LastFmArtist
import code.name.monkey.retromusic.spotify.SpotifyIds
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.logE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

interface Repository {

    fun historySong(): List<HistoryEntity>
    fun favorites(): LiveData<List<SongEntity>>
    fun observableHistorySongs(): LiveData<List<Song>>
    fun albumById(albumId: Long): Album
    fun playlistSongs(playListId: Long): LiveData<List<SongEntity>>
    suspend fun fetchAlbums(): List<Album>
    suspend fun albumByIdAsync(albumId: Long): Album
    suspend fun allSongs(): List<Song>
    suspend fun fetchArtists(): List<Artist>
    suspend fun albumArtists(): List<Artist>
    suspend fun fetchLegacyPlaylist(): List<Playlist>
    suspend fun fetchGenres(): List<Genre>
    suspend fun search(query: String?, filter: Filter): MutableList<Any>
    suspend fun getPlaylistSongs(playlist: Playlist): List<Song>
    suspend fun getGenre(genreId: Long): List<Song>
    suspend fun artistInfo(name: String, lang: String?, cache: String?): Result<LastFmArtist>
    suspend fun albumInfo(artist: String, album: String): Result<LastFmAlbum>
    suspend fun artistById(artistId: Long): Artist
    suspend fun albumArtistByName(name: String): Artist
    suspend fun recentArtists(): List<Artist>
    suspend fun topArtists(): List<Artist>
    suspend fun topAlbums(): List<Album>
    suspend fun recentAlbums(): List<Album>
    suspend fun recentArtistsHome(): Home
    suspend fun topArtistsHome(): Home
    suspend fun topAlbumsHome(): Home
    suspend fun recentAlbumsHome(): Home
    suspend fun favoritePlaylistHome(): Home
    suspend fun suggestions(): List<Song>
    suspend fun genresHome(): Home
    suspend fun playlists(): Home
    suspend fun homeSections(): List<Home>
    suspend fun playlist(playlistId: Long): Playlist
    suspend fun fetchPlaylistWithSongs(): List<PlaylistWithSongs>
    suspend fun playlistSongs(playlistWithSongs: PlaylistWithSongs): List<Song>
    suspend fun insertSongs(songs: List<SongEntity>)
    suspend fun checkPlaylistExists(playlistName: String): List<PlaylistEntity>
    suspend fun createPlaylist(playlistEntity: PlaylistEntity): Long
    suspend fun fetchPlaylists(): List<PlaylistEntity>
    suspend fun deleteRoomPlaylist(playlists: List<PlaylistEntity>)
    suspend fun renameRoomPlaylist(playlistId: Long, name: String)
    suspend fun deleteSongsInPlaylist(songs: List<SongEntity>)
    suspend fun removeSongFromPlaylist(songEntity: SongEntity)
    suspend fun deletePlaylistSongs(playlists: List<PlaylistEntity>)
    suspend fun favoritePlaylist(): PlaylistEntity
    suspend fun isFavoriteSong(songEntity: SongEntity): List<SongEntity>
    suspend fun upsertSongInHistory(currentSong: Song)
    suspend fun favoritePlaylistSongs(): List<SongEntity>
    suspend fun recentSongs(): List<Song>
    suspend fun topPlayedSongs(): List<Song>
    suspend fun upsertSongInPlayCount(playCountEntity: PlayCountEntity)
    suspend fun deleteSongInPlayCount(playCountEntity: PlayCountEntity)
    suspend fun deleteSongInHistory(songId: Long)
    suspend fun clearSongHistory()
    suspend fun findSongExistInPlayCount(songId: Long): PlayCountEntity?
    suspend fun playCountSongs(): List<PlayCountEntity>
    suspend fun deleteSongs(songs: List<Song>)
    suspend fun contributor(): List<Contributor>
    suspend fun searchArtists(query: String): List<Artist>
    suspend fun searchSongs(query: String): List<Song>
    suspend fun searchAlbums(query: String): List<Album>
    suspend fun isSongFavorite(songId: Long): Boolean
    fun getSongByGenre(genreId: Long): Song
    fun checkPlaylistExists(playListId: Long): LiveData<Boolean>
    fun getPlaylist(playlistId: Long): LiveData<PlaylistWithSongs>
}

class RealRepository(
    private val context: Context,
    private val lastFMService: LastFMService,
    private val songRepository: SongRepository,
    private val albumRepository: AlbumRepository,
    private val artistRepository: ArtistRepository,
    private val genreRepository: GenreRepository,
    private val lastAddedRepository: LastAddedRepository,
    private val playlistRepository: PlaylistRepository,
    private val searchRepository: RealSearchRepository,
    private val topPlayedRepository: TopPlayedRepository,
    private val roomRepository: RoomRepository,
    private val localDataRepository: LocalDataRepository,
    private val spotifyRepository: SpotifyRepository,
) : Repository {

    /** Spotify mode serves the library from the Spotify cache instead of MediaStore. */
    private val spotify: Boolean get() = PreferenceUtil.isSpotifyMode

    private val offlinePlaylists by lazy {
        OfflinePlaylistImporter(context, songRepository, roomRepository)
    }

    /** Spotify playlist IDs are negative (see SpotifyIds.localId); Room playlist IDs are positive. */
    private fun isSpotifyPlaylist(playlistId: Long) = spotify && playlistId < 0

    override suspend fun deleteSongs(songs: List<Song>) = roomRepository.deleteSongs(songs)

    override suspend fun contributor(): List<Contributor> = localDataRepository.contributors()

    override suspend fun searchSongs(query: String): List<Song> =
        if (spotify) spotifyRepository.songs(query) else songRepository.songs(query)

    override suspend fun searchAlbums(query: String): List<Album> =
        if (spotify) spotifyRepository.albums(query) else albumRepository.albums(query)

    override suspend fun isSongFavorite(songId: Long): Boolean =
        roomRepository.isSongFavorite(context, songId)

    override fun getSongByGenre(genreId: Long): Song = genreRepository.song(genreId)

    override suspend fun searchArtists(query: String): List<Artist> =
        if (spotify) spotifyRepository.artists(query) else artistRepository.artists(query)

    override suspend fun fetchAlbums(): List<Album> =
        if (spotify) spotifyRepository.albums() else albumRepository.albums()

    fun albumsForSongs(songs: List<Song>): List<Album> = albumRepository.albums(songs)

    override suspend fun albumByIdAsync(albumId: Long): Album =
        if (spotify) spotifyRepository.album(albumId) else albumRepository.album(albumId)

    override fun albumById(albumId: Long): Album =
        if (spotify) {
            // Rare synchronous path (shortcuts, tag editor); the cache read is a single query.
            runBlocking(Dispatchers.IO) { spotifyRepository.album(albumId) }
        } else {
            albumRepository.album(albumId)
        }

    override suspend fun fetchArtists(): List<Artist> =
        if (spotify) spotifyRepository.artists() else artistRepository.artists()

    fun artistsForAlbums(albums: List<Album>): List<Artist> =
        artistRepository.artists(albums, PreferenceUtil.albumArtistsOnly)

    override suspend fun albumArtists(): List<Artist> =
        if (spotify) spotifyRepository.albumArtists() else artistRepository.albumArtists()

    override suspend fun artistById(artistId: Long): Artist =
        if (spotify) spotifyRepository.artist(artistId) else artistRepository.artist(artistId)

    override suspend fun albumArtistByName(name: String): Artist =
        if (spotify) spotifyRepository.albumArtist(name) else artistRepository.albumArtist(name)

    override suspend fun recentArtists(): List<Artist> =
        if (spotify) {
            spotifyRepository.artists().sortedByDescending { it.safeGetFirstAlbum().dateModified }
        } else {
            lastAddedRepository.recentArtists()
        }

    override suspend fun recentAlbums(): List<Album> =
        if (spotify) {
            spotifyRepository.albums().sortedByDescending { it.dateModified }
        } else {
            lastAddedRepository.recentAlbums()
        }

    // Play-count based sections resolve IDs through MediaStore, so they are empty in Spotify mode.
    override suspend fun topArtists(): List<Artist> =
        if (spotify) emptyList() else topPlayedRepository.topArtists()

    override suspend fun topAlbums(): List<Album> =
        if (spotify) emptyList() else topPlayedRepository.topAlbums()

    override suspend fun fetchLegacyPlaylist(): List<Playlist> =
        if (spotify) emptyList() else playlistRepository.playlists()

    override suspend fun fetchGenres(): List<Genre> =
        if (spotify) emptyList() else genreRepository.genres()

    override suspend fun allSongs(): List<Song> =
        if (spotify) spotifyRepository.songs() else songRepository.songs()

    override suspend fun search(query: String?, filter: Filter): MutableList<Any> =
        if (spotify) spotifySearch(query, filter) else searchRepository.searchAll(context, query, filter)

    /** Same result shape as [RealSearchRepository.searchAll] (section title followed by items). */
    private suspend fun spotifySearch(query: String?, filter: Filter): MutableList<Any> {
        val results = mutableListOf<Any>()
        if (query.isNullOrEmpty()) return results
        fun section(title: Int, items: List<Any>) {
            if (items.isNotEmpty()) {
                results.add(context.getString(title))
                results.addAll(items)
            }
        }
        val wantsCatalog = filter == Filter.NO_FILTER || filter == Filter.SONGS ||
            filter == Filter.ARTISTS || filter == Filter.ALBUMS
        // Search Spotify itself; fall back to the cached library when that fails (offline).
        val remote = if (wantsCatalog) spotifyRepository.searchSpotify(query) else null
        if (filter == Filter.SONGS || filter == Filter.NO_FILTER) {
            section(R.string.songs, remote?.songs ?: spotifyRepository.songs(query))
        }
        if (filter == Filter.ARTISTS || filter == Filter.NO_FILTER) {
            section(R.string.artists, remote?.artists ?: spotifyRepository.artists(query))
        }
        if (filter == Filter.ALBUMS || filter == Filter.NO_FILTER) {
            section(R.string.albums, remote?.albums ?: spotifyRepository.albums(query))
        }
        if (filter == Filter.PLAYLISTS || filter == Filter.NO_FILTER) {
            section(R.string.playlists, fetchPlaylistWithSongs().filter {
                it.playlistEntity.playlistName.contains(query, ignoreCase = true)
            })
        }
        return results
    }

    override suspend fun getPlaylistSongs(playlist: Playlist): List<Song> =
        if (playlist is AbsCustomPlaylist) {
            playlist.songs()
        } else {
            PlaylistSongsLoader.getPlaylistSongList(context, playlist.id)
        }

    override suspend fun getGenre(genreId: Long): List<Song> =
        if (spotify) emptyList() else genreRepository.songs(genreId)

    override suspend fun artistInfo(
        name: String,
        lang: String?,
        cache: String?,
    ): Result<LastFmArtist> {
        return try {
            Success(lastFMService.artistInfo(name, lang, cache))
        } catch (e: Exception) {
            logE(e)
            Error(e)
        }
    }

    override suspend fun albumInfo(
        artist: String,
        album: String,
    ): Result<LastFmAlbum> {
        return try {
            val lastFmAlbum = lastFMService.albumInfo(artist, album)
            Success(lastFmAlbum)
        } catch (e: Exception) {
            logE(e)
            Error(e)
        }
    }

    override suspend fun homeSections(): List<Home> {
        val homeSections = mutableListOf<Home>()
        val sections: List<Home> = listOf(
            topArtistsHome(),
            topAlbumsHome(),
            recentArtistsHome(),
            recentAlbumsHome(),
            favoritePlaylistHome()
        )
        for (section in sections) {
            if (section.arrayList.isNotEmpty()) {
                homeSections.add(section)
            }
        }
        return homeSections
    }


    override suspend fun playlist(playlistId: Long) =
        playlistRepository.playlist(playlistId)

    override suspend fun fetchPlaylistWithSongs(): List<PlaylistWithSongs> =
        if (spotify) {
            // Spotify library playlists first, then the user's own Retro playlists, which can
            // also hold Spotify songs.
            spotifyRepository.playlistsWithSongs() + roomRepository.playlistWithSongs()
        } else {
            // Pick up .m3u playlists saved from Spotify mode (or copied from another phone).
            offlinePlaylists.sync()
            roomRepository.playlistWithSongs()
        }

    override fun getPlaylist(playlistId: Long): LiveData<PlaylistWithSongs> =
        if (isSpotifyPlaylist(playlistId)) {
            // Observed, so the detail screen follows Spotify playlist edits.
            spotifyRepository.observePlaylistWithSongs(playlistId).filterNotNull()
                .asLiveData(Dispatchers.IO)
        } else {
            roomRepository.getPlaylist(playlistId)
        }

    /** The Spotify context URI behind a Spotify playlist ID, for librespot to play. */
    suspend fun spotifyPlaylistUri(playlistId: Long): String? =
        if (isSpotifyPlaylist(playlistId)) spotifyRepository.playlistUri(playlistId) else null

    override suspend fun playlistSongs(playlistWithSongs: PlaylistWithSongs): List<Song> =
        playlistWithSongs.songs.map {
            it.toSong()
        }

    override fun playlistSongs(playListId: Long): LiveData<List<SongEntity>> =
        if (isSpotifyPlaylist(playListId)) {
            spotifyRepository.observePlaylistWithSongs(playListId).map { it?.songs.orEmpty() }
                .asLiveData(Dispatchers.IO)
        } else {
            roomRepository.getSongs(playListId)
        }

    override suspend fun insertSongs(songs: List<SongEntity>) =
        roomRepository.insertSongs(songs)

    override suspend fun checkPlaylistExists(playlistName: String): List<PlaylistEntity> =
        roomRepository.checkPlaylistExists(playlistName)

    override fun checkPlaylistExists(playListId: Long): LiveData<Boolean> =
        if (isSpotifyPlaylist(playListId)) {
            spotifyRepository.observePlaylistWithSongs(playListId).map { it != null }
                .distinctUntilChanged()
                .asLiveData(Dispatchers.IO)
        } else {
            roomRepository.checkPlaylistExists(playListId)
        }

    override suspend fun createPlaylist(playlistEntity: PlaylistEntity): Long =
        roomRepository.createPlaylist(playlistEntity)

    /** Playlists songs can be added to: Liked Songs and the user's own Spotify playlists first in Spotify mode. */
    override suspend fun fetchPlaylists(): List<PlaylistEntity> =
        if (spotify) spotifyRepository.editablePlaylists() + roomRepository.playlists()
        else roomRepository.playlists()

    override suspend fun deleteRoomPlaylist(playlists: List<PlaylistEntity>) =
        roomRepository.deletePlaylistEntities(playlists)

    override suspend fun renameRoomPlaylist(playlistId: Long, name: String) =
        roomRepository.renamePlaylistEntity(playlistId, name)

    override suspend fun deleteSongsInPlaylist(songs: List<SongEntity>) =
        roomRepository.deleteSongsInPlaylist(songs)

    override suspend fun removeSongFromPlaylist(songEntity: SongEntity) =
        roomRepository.removeSongFromPlaylist(songEntity)

    override suspend fun deletePlaylistSongs(playlists: List<PlaylistEntity>) =
        roomRepository.deletePlaylistSongs(playlists)

    override suspend fun favoritePlaylist(): PlaylistEntity =
        roomRepository.favoritePlaylist(context.getString(R.string.favorites))

    override suspend fun isFavoriteSong(songEntity: SongEntity): List<SongEntity> =
        roomRepository.isFavoriteSong(songEntity)

    override suspend fun upsertSongInHistory(currentSong: Song) =
        roomRepository.upsertSongInHistory(currentSong)

    override suspend fun favoritePlaylistSongs(): List<SongEntity> =
        roomRepository.favoritePlaylistSongs(context.getString(R.string.favorites))

    override suspend fun recentSongs(): List<Song> =
        if (spotify) {
            spotifyRepository.songs().sortedByDescending { it.dateModified }
        } else {
            lastAddedRepository.recentSongs()
        }

    override suspend fun topPlayedSongs(): List<Song> =
        if (spotify) emptyList() else topPlayedRepository.topTracks()

    override suspend fun upsertSongInPlayCount(playCountEntity: PlayCountEntity) =
        roomRepository.upsertSongInPlayCount(playCountEntity)

    override suspend fun deleteSongInPlayCount(playCountEntity: PlayCountEntity) =
        roomRepository.deleteSongInPlayCount(playCountEntity)

    override suspend fun deleteSongInHistory(songId: Long) =
        roomRepository.deleteSongInHistory(songId)

    override suspend fun clearSongHistory() {
        roomRepository.clearSongHistory()
    }

    override suspend fun findSongExistInPlayCount(songId: Long): PlayCountEntity? =
        roomRepository.findSongExistInPlayCount(songId)

    override suspend fun playCountSongs(): List<PlayCountEntity> =
        roomRepository.playCountSongs()

    override fun observableHistorySongs(): LiveData<List<Song>> =
        roomRepository.observableHistorySongs().map {
            it.fromHistoryToSongs()
        }

    override fun historySong(): List<HistoryEntity> =
        roomRepository.historySongs()

    override fun favorites(): LiveData<List<SongEntity>> =
        roomRepository.favoritePlaylistLiveData(context.getString(R.string.favorites))

    override suspend fun suggestions(): List<Song> {
        if (spotify) {
            return spotifyRepository.songs().shuffled().take(10).takeIf { it.size > 9 } ?: emptyList()
        }
        return NotPlayedPlaylist().songs().shuffled().takeIf {
            it.size > 9
        } ?: emptyList()
    }

    override suspend fun genresHome(): Home {
        val genres = fetchGenres().shuffled()
        return Home(genres, GENRES, R.string.genres)
    }

    override suspend fun playlists(): Home {
        val playlist = fetchLegacyPlaylist()
        return Home(playlist, PLAYLISTS, R.string.playlists)
    }

    override suspend fun recentArtistsHome(): Home {
        val artists = recentArtists().take(5)
        return Home(artists, RECENT_ARTISTS, R.string.recent_artists)
    }

    override suspend fun recentAlbumsHome(): Home {
        val albums = recentAlbums().take(5)
        return Home(albums, RECENT_ALBUMS, R.string.recent_albums)
    }

    override suspend fun topAlbumsHome(): Home {
        val albums = topAlbums().take(5)
        return Home(albums, TOP_ALBUMS, R.string.top_albums)
    }

    override suspend fun topArtistsHome(): Home {
        val artists = topArtists().take(5)
        return Home(artists, TOP_ARTISTS, R.string.top_artists)
    }

    override suspend fun favoritePlaylistHome(): Home {
        // Favorites can hold both local and Spotify songs; show the ones for the current source.
        val songs = favoritePlaylistSongs().map {
            it.toSong()
        }.filter { SpotifyIds.isSpotifyUri(it.data) == spotify }
        return Home(songs, FAVOURITES, R.string.favorites)
    }
}
