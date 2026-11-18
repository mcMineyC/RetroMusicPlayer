package code.name.monkey.retromusic.repository

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.SPOTIFY_OGG_DUMP_DIR
import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.toSongEntity
import code.name.monkey.retromusic.model.Song
import org.json.JSONObject
import java.io.File

/**
 * Keeps local-mode Retro playlists in step with the `.m3u` files in the saved tracks folder
 * (written by "Save as M3U for local mode", or copied over from another phone). Entries may be
 * relative to the `.m3u` (the normal case) or absolute.
 *
 * Only playlists this class created are updated, so a user's own playlist with the same name
 * is never overwritten. A playlist is created once at least one entry resolves to a song in
 * the media library, and is rewritten only when its songs change.
 */
class OfflinePlaylistImporter(
    context: Context,
    private val songRepository: SongRepository,
    private val roomRepository: RoomRepository,
) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    /** Disk, MediaStore and Room work: call from a background coroutine. */
    suspend fun sync() {
        val dir = prefs.getString(SPOTIFY_OGG_DUMP_DIR, null)?.ifBlank { null }?.let(::File) ?: return
        val m3us = try {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".m3u", ignoreCase = true) }.orEmpty()
        } catch (e: SecurityException) {
            return
        }
        if (m3us.isEmpty()) return

        val songsByPath: Map<String, Song> by lazy {
            songRepository.songsUnderFolder(dir.path).associateBy { File(it.data).path }
        }
        val owned = ownedPlaylists()
        val existing = roomRepository.playlistWithSongs().associateBy { it.playlistEntity.playListId }

        for (m3u in m3us) {
            try {
                val songs = parse(m3u).mapNotNull { songsByPath[it] }
                val name = m3u.nameWithoutExtension
                val current = owned[name]?.let { existing[it] }
                if (current == null) {
                    if (songs.isEmpty()) continue
                    val id = roomRepository.createPlaylist(PlaylistEntity(playlistName = name))
                    roomRepository.insertSongs(songs.map { it.toSongEntity(id) })
                    owned[name] = id
                } else {
                    val currentIds = current.songs.sortedBy { it.songPrimaryKey }.map { it.id }
                    if (currentIds == songs.map { it.id }) continue
                    roomRepository.deletePlaylistSongs(listOf(current.playlistEntity))
                    roomRepository.insertSongs(songs.map { it.toSongEntity(current.playlistEntity.playListId) })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not import ${m3u.name}: ${e.message}")
            }
        }
        saveOwnedPlaylists(owned)
    }

    /** Absolute paths of the entries, in order. */
    private fun parse(m3u: File): List<String> =
        m3u.readLines()
            .map { it.trim().removePrefix("﻿") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { entry ->
                val file = File(entry.replace('\\', '/'))
                (if (file.isAbsolute) file else File(m3u.parentFile, file.path)).normalize().path
            }

    /** M3U name → id of the Retro playlist this importer made for it. */
    private fun ownedPlaylists(): MutableMap<String, Long> {
        val json = prefs.getString(PREF_OWNED, null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().associateWithTo(mutableMapOf()) { o.getLong(it) }
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveOwnedPlaylists(owned: Map<String, Long>) {
        prefs.edit { putString(PREF_OWNED, JSONObject(owned as Map<*, *>).toString()) }
    }

    companion object {
        private const val TAG = "OfflinePlaylistImporter"
        private const val PREF_OWNED = "offline_m3u_playlists"
    }
}
