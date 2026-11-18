package code.name.monkey.retromusic.spotify

import android.content.Context
import android.media.MediaScannerConnection
import code.name.monkey.retromusic.db.PlaylistWithSongs
import code.name.monkey.retromusic.db.toSongs
import java.io.File

/**
 * Writes a Spotify playlist as an `.m3u` next to the saved Ogg files, listing each track by the
 * file name the bridge saves it under (`<base62 id>.ogg`, bridge §5.6). Entries are relative, so
 * Android's media scanner resolves them against the folder and normal (non-Spotify) mode can
 * import the playlist. Tracks not saved yet are still listed and resolve once their file exists
 * and the playlist is written again.
 */
object SpotifyOfflinePlaylist {

    class Result(val file: File, val tracks: Int, val saved: Int, val visibleToMediaStore: Boolean)

    /** Disk I/O: call off the main thread. */
    fun write(context: Context, engine: LibrespotEngine, playlist: PlaylistWithSongs): Result {
        val dir = engine.oggDumpDir.apply { mkdirs() }
        val songs = playlist.songs.sortedBy { it.songPrimaryKey }.toSongs()
            .filter { SpotifyIds.isTrackUri(it.data) }
        val name = playlist.playlistEntity.playlistName.replace(UNSAFE, "_").trim().ifEmpty { "Spotify playlist" }
        val file = File(dir, "$name.m3u")

        var saved = 0
        file.bufferedWriter().use { out ->
            out.write("#EXTM3U")
            for (song in songs) {
                val fileName = song.data.substringAfterLast(':') + ".ogg"
                if (File(dir, fileName).exists()) saved++
                out.newLine()
                out.write("#EXTINF:${song.duration / 1000},${song.artistName} - ${song.title}")
                out.newLine()
                out.write(fileName)
            }
            out.newLine()
        }

        // App storage is never indexed, so only a chosen shared folder reaches normal mode.
        val visible = dir != engine.defaultOggDumpDir
        if (visible) MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
        return Result(file, songs.size, saved, visible)
    }

    private val UNSAFE = Regex("[\\\\/:*?\"<>|]")
}
