package code.name.monkey.retromusic.spotify

import android.util.Log
import code.name.monkey.retromusic.db.SpotifyDao
import code.name.monkey.retromusic.db.SpotifySavedTrackEntity
import code.name.monkey.retromusic.db.deleteSavedTracks
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.AudioTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Tags of a saved track file; see [SpotifySavedTracks]. */
data class SavedTrack(
    val path: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val albumArtist: String,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val durationMs: Long,
)

/**
 * In-memory view of the saved tracks, keyed by base62 id. Glide models and Songs are built on
 * threads that must not touch disk or Room, so they read this snapshot.
 */
object SpotifySavedTracks {
    @Volatile
    private var byId: Map<String, SavedTrack> = emptyMap()

    fun forUri(uri: String?): SavedTrack? =
        if (SpotifyIds.isSpotifyUri(uri)) byId[uri!!.substringAfterLast(':')] else null

    internal fun publish(tracks: Map<String, SavedTrack>) {
        byId = tracks
    }

    /**
     * [song] with the title, artists, album, numbering and duration from its saved file, if
     * there is one. IDs and the URI are kept, so library grouping doesn't change.
     */
    fun applyTo(song: Song): Song {
        val saved = forUri(song.data) ?: return song
        val trackNumber = when {
            saved.trackNumber <= 0 -> song.trackNumber
            saved.discNumber > 1 -> saved.discNumber * 1000 + saved.trackNumber
            else -> saved.trackNumber
        }
        return Song(
            id = song.id,
            title = saved.title.ifEmpty { song.title },
            trackNumber = trackNumber,
            year = saved.year.takeIf { it > 0 } ?: song.year,
            duration = saved.durationMs.takeIf { it > 0 } ?: song.duration,
            data = song.data,
            dateModified = song.dateModified,
            albumId = song.albumId,
            albumName = saved.album.ifEmpty { song.albumName },
            artistId = song.artistId,
            artistName = saved.artists.joinToString(", ").ifEmpty { song.artistName },
            composer = song.composer,
            albumArtist = saved.albumArtist.ifEmpty { null } ?: song.albumArtist,
        )
    }
}

/**
 * Keeps [SpotifySavedTracks] in sync with the files in [LibrespotEngine.oggDumpDir]. Tags are
 * read once per file and kept in Room (`spotify_saved_track`); a rescan only re-reads files
 * whose size or modification time changed.
 */
class SpotifySavedTrackIndex(
    private val dao: SpotifyDao,
    private val engine: LibrespotEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val tracks = HashMap<String, SavedTrack>()

    /** Called on a background thread after the index changes. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** Loads the stored index, rescans the folder, then follows new and moved files. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            engine.savedTrackEvents.collect { event ->
                when (event) {
                    is SavedTrackEvent.Written -> index(File(event.path))
                    SavedTrackEvent.FolderChanged -> rescan()
                }
            }
        }
        scope.launch {
            mutex.withLock {
                dao.savedTracks().forEach { tracks[it.id] = it.toSavedTrack() }
                SpotifySavedTracks.publish(HashMap(tracks))
            }
            rescan()
        }
    }

    private suspend fun rescan() = mutex.withLock {
        try {
            val files = engine.savedTracks(engine.oggDumpDir).associateBy { it.name.removeSuffix(".ogg") }
            val stored = dao.savedTracks().associateBy { it.id }
            val changed = files.mapNotNull { (id, file) ->
                val row = stored[id]
                if (row != null && row.path == file.path && row.size == file.length() &&
                    row.modified == file.lastModified()
                ) null else read(id, file)
            }
            val removed = stored.keys - files.keys
            if (changed.isNotEmpty()) dao.upsertSavedTracks(changed)
            if (removed.isNotEmpty()) dao.deleteSavedTracks(removed)

            tracks.clear()
            (stored.values.filter { it.id in files } + changed).forEach { tracks[it.id] = it.toSavedTrack() }
            SpotifySavedTracks.publish(HashMap(tracks))
            if (changed.isNotEmpty() || removed.isNotEmpty()) onChanged?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Saved track rescan failed: ${e.message}")
        }
    }

    private suspend fun index(file: File) = mutex.withLock {
        val entity = read(file.name.removeSuffix(".ogg"), file) ?: return@withLock
        try {
            dao.upsertSavedTracks(listOf(entity))
        } catch (e: Exception) {
            Log.w(TAG, "Could not store ${file.name}: ${e.message}")
        }
        tracks[entity.id] = entity.toSavedTrack()
        SpotifySavedTracks.publish(HashMap(tracks))
        onChanged?.invoke()
    }

    private fun read(id: String, file: File): SpotifySavedTrackEntity? {
        val info = AudioTags.read(file) ?: return null
        return SpotifySavedTrackEntity(
            id = id,
            path = file.path,
            size = file.length(),
            modified = file.lastModified(),
            title = info.title,
            artists = SpotifyIds.joinList(info.artists),
            album = info.album,
            albumArtist = info.albumArtist,
            year = info.year,
            trackNumber = info.trackNumber,
            discNumber = info.discNumber,
            durationMs = info.durationMs,
        )
    }

    private fun SpotifySavedTrackEntity.toSavedTrack() = SavedTrack(
        path = path,
        title = title,
        artists = SpotifyIds.splitList(artists),
        album = album,
        albumArtist = albumArtist,
        year = year,
        trackNumber = trackNumber,
        discNumber = discNumber,
        durationMs = durationMs,
    )

    companion object {
        private const val TAG = "SpotifySavedTrackIndex"
    }
}
