package code.name.monkey.retromusic.spotify

import android.util.Log
import code.name.monkey.retromusic.db.SpotifyDao
import code.name.monkey.retromusic.db.SpotifyPlaylistEntity
import code.name.monkey.retromusic.db.tracksByUri
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * What Retro may do with each Spotify library item, keyed by its Long ID. Adapters and menus
 * check this on the main thread, so [code.name.monkey.retromusic.repository.SpotifyRepository]
 * fills it whenever it builds playlists. IDs that aren't Spotify library items (Retro's own
 * playlists) are always fully editable.
 */
object SpotifyPlaylistInfo {
    private class Info(val kind: String, val editable: Boolean)

    private val items = ConcurrentHashMap<Long, Info>()

    fun put(playlist: SpotifyPlaylistEntity) {
        items[playlist.localId] = Info(playlist.kind, playlist.editable)
    }

    fun remove(playlistId: Long) {
        items.remove(playlistId)
    }

    /** Spotify library items have negative IDs; Retro's Room playlists are positive. */
    fun isSpotifyPlaylist(playlistId: Long): Boolean = playlistId < 0 && PreferenceUtil.isSpotifyMode

    private fun info(playlistId: Long): Info? = if (isSpotifyPlaylist(playlistId)) items[playlistId] else null

    private fun ownPlaylist(info: Info) = info.kind == KIND_PLAYLIST && info.editable

    /** Add/remove songs. Liked Songs maps to like/unlike. */
    fun canEditSongs(playlistId: Long): Boolean {
        if (!isSpotifyPlaylist(playlistId)) return true
        val info = info(playlistId) ?: return false
        return info.kind == KIND_COLLECTION || ownPlaylist(info)
    }

    /** Liked Songs is ordered by date added, so only real playlists can be reordered. */
    fun canReorder(playlistId: Long): Boolean =
        !isSpotifyPlaylist(playlistId) || info(playlistId)?.let { ownPlaylist(it) } == true

    fun canRename(playlistId: Long): Boolean = canReorder(playlistId)

    /** Deleting someone else's playlist unfollows it; deleting a saved album un-saves it. */
    fun canDelete(playlistId: Long): Boolean {
        if (!isSpotifyPlaylist(playlistId)) return true
        val kind = info(playlistId)?.kind ?: return false
        return kind == KIND_PLAYLIST || kind == KIND_ALBUM
    }

    const val KIND_PLAYLIST = "playlist"
    const val KIND_ALBUM = "album"
    const val KIND_COLLECTION = "collection"
}

/**
 * Applies Retro playlist edits to Spotify library items through the bridge, then re-reads the
 * playlist so the cache matches Spotify exactly (positions, revision). All functions block on
 * the network; call them from IO. Each returns false if Spotify rejected the edit.
 */
class SpotifyPlaylistEditor(
    private val engine: LibrespotEngine,
    private val dao: SpotifyDao,
    private val sync: SpotifyLibrarySync,
) {

    /** Creates a Spotify playlist holding [songs] (non-Spotify songs are skipped). */
    suspend fun create(name: String, songs: List<Song>): Boolean {
        val uri = engine.createPlaylist(name) ?: return false
        val tracks = trackUris(songs)
        if (tracks.isNotEmpty() && engine.addToPlaylist(uri, tracks, -1) == null) {
            Log.w(TAG, "Created $uri but could not add its songs")
        }
        // createPlaylist puts it at the top of the library; Liked Songs stays above it.
        val top = dao.playlists().filter { it.kind != SpotifyPlaylistInfo.KIND_COLLECTION }
            .minOfOrNull { it.position } ?: 0
        val entity = SpotifyPlaylistEntity(
            uri = uri,
            localId = SpotifyIds.localId(uri),
            name = name,
            description = null,
            imageUrl = null,
            kind = SpotifyPlaylistInfo.KIND_PLAYLIST,
            position = top - 1,
            revision = null,
            editable = true,
        )
        dao.upsertPlaylists(listOf(entity))
        SpotifyPlaylistInfo.put(entity)
        refresh(entity)
        return true
    }

    suspend fun addSongs(playlistId: Long, songs: List<Song>): Boolean {
        val playlist = dao.playlistByLocalId(playlistId) ?: return false
        val tracks = trackUris(songs)
        if (tracks.isEmpty()) return true
        return when (playlist.kind) {
            SpotifyPlaylistInfo.KIND_COLLECTION -> sync.setLiked(tracks, true)
            SpotifyPlaylistInfo.KIND_PLAYLIST -> {
                val ok = engine.addToPlaylist(playlist.uri, tracks, -1) != null
                refresh(playlist)
                ok
            }
            else -> false
        }
    }

    /** Removes every occurrence of [uris], matching how Retro removes songs from a playlist. */
    suspend fun removeSongs(playlistId: Long, uris: Collection<String>): Boolean {
        val playlist = dao.playlistByLocalId(playlistId) ?: return false
        val targets = uris.toSet()
        return when (playlist.kind) {
            SpotifyPlaylistInfo.KIND_COLLECTION -> sync.setLiked(targets.toList(), false)
            SpotifyPlaylistInfo.KIND_PLAYLIST -> {
                val stored = dao.playlistTrackUris(playlist.uri)
                // Highest index first, so earlier indices stay valid.
                var ok = true
                for (index in stored.indices.reversed()) {
                    if (stored[index] !in targets) continue
                    if (engine.removeFromPlaylist(playlist.uri, index, stored[index]) == null) {
                        ok = false
                        break
                    }
                }
                refresh(playlist)
                ok
            }
            else -> false
        }
    }

    /**
     * Moves the song shown at [from] to [to]. Positions are in Retro's list, which hides
     * entries without cached metadata (episodes, local files), so they are mapped to Spotify
     * indices first. [expectedAfter] is Retro's list after the move; if the cache disagrees
     * with it, nothing is changed and the playlist is re-read.
     */
    suspend fun move(playlistId: Long, from: Int, to: Int, expectedAfter: List<String>): Boolean {
        val playlist = dao.playlistByLocalId(playlistId) ?: return false
        if (playlist.kind != SpotifyPlaylistInfo.KIND_PLAYLIST) return false
        val stored = dao.playlistTrackUris(playlist.uri)
        val cached = dao.tracksByUri(stored.filter { SpotifyIds.isTrackUri(it) }).mapTo(HashSet()) { it.uri }
        val shown = stored.indices.filter { stored[it] in cached }
        val before = shown.map { stored[it] }
        val after = before.toMutableList().apply { if (from in indices && to in indices) add(to, removeAt(from)) }
        if (from !in shown.indices || to !in shown.indices || after != expectedAfter) {
            refresh(playlist)
            return false
        }
        val fromIndex = shown[from]
        val uri = stored[fromIndex]
        // Inserting at the target's original index lands before it when moving up and, once
        // the removal has shifted it down by one, after it when moving down.
        val ok = engine.removeFromPlaylist(playlist.uri, fromIndex, uri) != null &&
            engine.addToPlaylist(playlist.uri, listOf(uri), shown[to]) != null
        refresh(playlist)
        return ok
    }

    suspend fun rename(playlistId: Long, name: String): Boolean {
        val playlist = dao.playlistByLocalId(playlistId) ?: return false
        if (playlist.kind != SpotifyPlaylistInfo.KIND_PLAYLIST) return false
        if (engine.renamePlaylist(playlist.uri, name) == null) return false
        dao.upsertPlaylists(listOf(playlist.copy(name = name)))
        sync.onCacheChanged?.invoke()
        return true
    }

    /** Deletes (or unfollows) a playlist, or removes a saved album from the library. */
    suspend fun delete(playlistId: Long): Boolean {
        val playlist = dao.playlistByLocalId(playlistId) ?: return false
        val ok = when (playlist.kind) {
            SpotifyPlaylistInfo.KIND_PLAYLIST -> engine.deletePlaylist(playlist.uri)
            SpotifyPlaylistInfo.KIND_ALBUM -> engine.setLiked(listOf(playlist.uri), false)
            else -> false
        }
        if (!ok) return false
        dao.deletePlaylist(playlist.uri)
        dao.deleteOrphanPlaylistTracks()
        SpotifyPlaylistInfo.remove(playlistId)
        sync.onCacheChanged?.invoke()
        return true
    }

    /** Re-reads the playlist from Spotify and replaces the cached entries and revision. */
    private suspend fun refresh(playlist: SpotifyPlaylistEntity) {
        val parsed = engine.playlistJson(playlist.uri, null)
            ?.let { SpotifyMetadataParser.parsePlaylist(it) }
        if (parsed != null) {
            // Cache new tracks before publishing the list, so observers see them complete.
            sync.ensureTracksCached(parsed.itemUris)
            dao.replacePlaylistTracks(playlist.uri, parsed.itemUris)
            val updated = playlist.copy(
                revision = parsed.revision,
                imageUrl = playlist.imageUrl ?: parsed.imageUrl,
                editable = sync.isEditable(parsed),
            )
            dao.upsertPlaylists(listOf(updated))
            SpotifyPlaylistInfo.put(updated)
        }
        sync.onCacheChanged?.invoke()
    }

    private fun trackUris(songs: List<Song>): List<String> =
        songs.map { it.data }.filter { SpotifyIds.isTrackUri(it) }

    companion object {
        private const val TAG = "SpotifyPlaylistEditor"
    }
}
