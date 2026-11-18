package code.name.monkey.retromusic.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotifyDao {

    @Query("SELECT * FROM spotify_track")
    suspend fun tracks(): List<SpotifyTrackEntity>

    @Query("SELECT * FROM spotify_saved_track")
    suspend fun savedTracks(): List<SpotifySavedTrackEntity>

    @Upsert
    suspend fun upsertSavedTracks(tracks: List<SpotifySavedTrackEntity>)

    /** Callers must chunk [ids]; see [deleteSavedTracks]. */
    @Query("DELETE FROM spotify_saved_track WHERE id IN (:ids)")
    suspend fun deleteSavedTracksChunk(ids: List<String>)

    /** Callers must chunk [uris] below SQLite's bind-variable limit; see [tracksByUri]. */
    @Query("SELECT * FROM spotify_track WHERE uri IN (:uris)")
    suspend fun tracksByUriChunk(uris: List<String>): List<SpotifyTrackEntity>

    @Query("SELECT * FROM spotify_track WHERE local_id = :localId LIMIT 1")
    suspend fun trackByLocalId(localId: Long): SpotifyTrackEntity?

    @Query("SELECT uri FROM spotify_track")
    suspend fun trackUris(): List<String>

    @Upsert
    suspend fun upsertTracks(tracks: List<SpotifyTrackEntity>)

    @Query("SELECT * FROM spotify_album")
    suspend fun albums(): List<SpotifyAlbumEntity>

    @Query("SELECT uri FROM spotify_album")
    suspend fun albumUris(): List<String>

    @Upsert
    suspend fun upsertAlbums(albums: List<SpotifyAlbumEntity>)

    @Query("SELECT * FROM spotify_artist")
    suspend fun artists(): List<SpotifyArtistEntity>

    @Query("SELECT * FROM spotify_artist WHERE local_id = :localId LIMIT 1")
    suspend fun artistByLocalId(localId: Long): SpotifyArtistEntity?

    @Query("SELECT uri FROM spotify_artist WHERE image_url IS NULL")
    suspend fun artistUrisWithoutImage(): List<String>

    @Upsert
    suspend fun upsertArtists(artists: List<SpotifyArtistEntity>)

    @Query("UPDATE spotify_artist SET image_url = :imageUrl WHERE uri = :uri")
    suspend fun setArtistImage(uri: String, imageUrl: String)

    @Query("SELECT * FROM spotify_playlist ORDER BY position")
    suspend fun playlists(): List<SpotifyPlaylistEntity>

    @Query("SELECT * FROM spotify_playlist WHERE local_id = :localId LIMIT 1")
    suspend fun playlistByLocalId(localId: Long): SpotifyPlaylistEntity?

    @Upsert
    suspend fun upsertPlaylists(playlists: List<SpotifyPlaylistEntity>)

    @Query("DELETE FROM spotify_playlist WHERE uri = :uri")
    suspend fun deletePlaylist(uri: String)

    @Query("SELECT * FROM spotify_playlist WHERE local_id = :localId LIMIT 1")
    fun observePlaylist(localId: Long): Flow<SpotifyPlaylistEntity?>

    /** Re-emits whenever the playlist's entries change, e.g. after an edit or sync. */
    @Query(
        "SELECT track_uri FROM spotify_playlist_track WHERE playlist_uri IN " +
            "(SELECT uri FROM spotify_playlist WHERE local_id = :localId) ORDER BY position"
    )
    fun observePlaylistTrackUris(localId: Long): Flow<List<String>>

    @Query("DELETE FROM spotify_playlist WHERE uri NOT IN (:keepUris)")
    suspend fun deletePlaylistsNotIn(keepUris: List<String>)

    @Query("DELETE FROM spotify_playlist_track WHERE playlist_uri NOT IN (SELECT uri FROM spotify_playlist)")
    suspend fun deleteOrphanPlaylistTracks()

    @Query("SELECT track_uri FROM spotify_playlist_track WHERE playlist_uri = :playlistUri ORDER BY position")
    suspend fun playlistTrackUris(playlistUri: String): List<String>

    @Query("SELECT COUNT(*) FROM spotify_playlist_track WHERE playlist_uri = :playlistUri")
    suspend fun playlistTrackCount(playlistUri: String): Int

    @Query("SELECT DISTINCT track_uri FROM spotify_playlist_track")
    suspend fun allPlaylistTrackUris(): List<String>

    @Query("SELECT DISTINCT playlist_uri FROM spotify_playlist_track WHERE track_uri = :trackUri")
    suspend fun playlistsContaining(trackUri: String): List<String>

    @Query("DELETE FROM spotify_playlist_track WHERE playlist_uri = :playlistUri")
    suspend fun deletePlaylistTracks(playlistUri: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(tracks: List<SpotifyPlaylistTrackEntity>)

    @Transaction
    suspend fun replacePlaylistTracks(playlistUri: String, trackUris: List<String>) {
        deletePlaylistTracks(playlistUri)
        trackUris.chunked(500).forEachIndexed { chunkIndex, chunk ->
            insertPlaylistTracks(chunk.mapIndexed { i, uri ->
                SpotifyPlaylistTrackEntity(playlistUri, chunkIndex * 500 + i, uri)
            })
        }
    }

    @Query("DELETE FROM spotify_playlist_track")
    suspend fun clearPlaylistTracks()

    @Query("DELETE FROM spotify_playlist")
    suspend fun clearPlaylists()

    @Query("DELETE FROM spotify_track")
    suspend fun clearTracks()

    @Query("DELETE FROM spotify_album")
    suspend fun clearAlbums()

    @Query("DELETE FROM spotify_artist")
    suspend fun clearArtists()

    @Transaction
    suspend fun clearAll() {
        clearPlaylistTracks()
        clearPlaylists()
        clearTracks()
        clearAlbums()
        clearArtists()
    }
}

/** Deletes saved-track rows in chunks, like [tracksByUri]. */
suspend fun SpotifyDao.deleteSavedTracks(ids: Collection<String>) =
    ids.chunked(500).forEach { deleteSavedTracksChunk(it) }

/** Looks up tracks in chunks so large playlists stay under SQLite's bind-variable limit. */
suspend fun SpotifyDao.tracksByUri(uris: Collection<String>): List<SpotifyTrackEntity> =
    uris.distinct().chunked(500).flatMap { tracksByUriChunk(it) }
