package code.name.monkey.retromusic.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Cache of the Spotify catalogue seen inside the user's library (playlists, Liked Songs,
 * saved albums). librespot has no "all songs/albums/artists" API, so these tables are what
 * Retro's Songs, Albums and Artists tabs read in Spotify mode.
 *
 * `local_id` is a stable Long derived from the URI (see SpotifyIds) so Spotify items fit
 * Retro's Long-keyed models, history, play counts and Room playlists.
 *
 * Keep these definitions in sync with MIGRATION_24_25 and MIGRATION_25_26.
 */

@Entity(
    tableName = "spotify_track",
    indices = [Index(value = ["local_id"], unique = true), Index(value = ["album_uri"])]
)
data class SpotifyTrackEntity(
    @PrimaryKey
    val uri: String,
    @ColumnInfo(name = "local_id")
    val localId: Long,
    val name: String,
    @ColumnInfo(name = "album_uri")
    val albumUri: String?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "track_number")
    val trackNumber: Int,
    @ColumnInfo(name = "disc_number")
    val discNumber: Int,
    val isrc: String?,
    /** Artist URIs in credit order, joined with [SpotifyIds.LIST_SEPARATOR]. */
    @ColumnInfo(name = "artist_uris")
    val artistUris: String,
    /** Artist names in credit order, joined with [SpotifyIds.LIST_SEPARATOR]. */
    @ColumnInfo(name = "artist_names")
    val artistNames: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "spotify_album",
    indices = [Index(value = ["local_id"], unique = true)]
)
data class SpotifyAlbumEntity(
    @PrimaryKey
    val uri: String,
    @ColumnInfo(name = "local_id")
    val localId: Long,
    val name: String,
    @ColumnInfo(name = "image_url")
    val imageUrl: String?,
    val year: Int,
    /** Album artist URIs in credit order, joined with [SpotifyIds.LIST_SEPARATOR]. */
    @ColumnInfo(name = "artist_uris")
    val artistUris: String,
    @ColumnInfo(name = "artist_names")
    val artistNames: String,
)

@Entity(
    tableName = "spotify_artist",
    indices = [Index(value = ["local_id"], unique = true)]
)
data class SpotifyArtistEntity(
    @PrimaryKey
    val uri: String,
    @ColumnInfo(name = "local_id")
    val localId: Long,
    val name: String,
    @ColumnInfo(name = "image_url")
    val imageUrl: String?,
)

/** A top-level item of the user's Spotify library (playlist, saved album, artist, folder...). */
@Entity(
    tableName = "spotify_playlist",
    indices = [Index(value = ["local_id"], unique = true)]
)
data class SpotifyPlaylistEntity(
    @PrimaryKey
    val uri: String,
    @ColumnInfo(name = "local_id")
    val localId: Long,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "image_url")
    val imageUrl: String?,
    /** "playlist", "album", "artist", "collection", "folder", "show" or "unknown". */
    val kind: String,
    /** Order in the user's library root list. */
    val position: Int,
    /** Opaque revision of the playlist contents; sync skips playlists whose revision is unchanged. */
    val revision: String?,
    /** True when the user owns the playlist or it is collaborative, so Retro may edit it. */
    @ColumnInfo(defaultValue = "0")
    val editable: Boolean = false,
)

@Entity(
    tableName = "spotify_playlist_track",
    primaryKeys = ["playlist_uri", "position"],
    indices = [Index(value = ["track_uri"])]
)
data class SpotifyPlaylistTrackEntity(
    @ColumnInfo(name = "playlist_uri")
    val playlistUri: String,
    val position: Int,
    @ColumnInfo(name = "track_uri")
    val trackUri: String,
)

/**
 * A track saved by the bridge's Ogg dump (`<dir>/<id>.ogg`), with the tags read from the file.
 * [size] and [modified] tell whether the file changed since it was read.
 */
@Entity(tableName = "spotify_saved_track")
data class SpotifySavedTrackEntity(
    /** Base62 id from the file name, i.e. the requested track URI's last segment. */
    @PrimaryKey
    val id: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val title: String,
    /** Joined with [code.name.monkey.retromusic.spotify.SpotifyIds.LIST_SEPARATOR]. */
    val artists: String,
    val album: String,
    @ColumnInfo(name = "album_artist")
    val albumArtist: String,
    val year: Int,
    @ColumnInfo(name = "track_number")
    val trackNumber: Int,
    @ColumnInfo(name = "disc_number")
    val discNumber: Int,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
)
