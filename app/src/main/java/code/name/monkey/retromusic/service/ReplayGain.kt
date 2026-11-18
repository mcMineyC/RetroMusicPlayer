package code.name.monkey.retromusic.service

import android.util.LruCache
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.spotify.SpotifyIds
import code.name.monkey.retromusic.util.AudioTags
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.REPLAY_GAIN_ALBUM
import code.name.monkey.retromusic.util.REPLAY_GAIN_OFF
import java.io.File

/**
 * ReplayGain volume factors for local songs. Reading tags is disk I/O, so players only use
 * [cachedFactor] (never blocking playback); [loadFactor] fills the cache from a background thread.
 */
object ReplayGain {
    /** Path → tag values; a file without ReplayGain caches an empty value, not a miss. */
    private val cache = LruCache<String, AudioTags.ReplayGain>(128)
    private val none = AudioTags.ReplayGain(null, null, null, null)

    private fun appliesTo(song: Song?): Boolean =
        song != null && song.data.isNotEmpty() && !SpotifyIds.isSpotifyUri(song.data) &&
                PreferenceUtil.replayGainMode != REPLAY_GAIN_OFF

    private fun factorOf(values: AudioTags.ReplayGain): Float =
        values.volumeFactor(preferAlbum = PreferenceUtil.replayGainMode == REPLAY_GAIN_ALBUM)

    /** The factor if already known: 1 when ReplayGain is off or doesn't apply, null if not loaded. */
    fun cachedFactor(song: Song?): Float? {
        if (!appliesTo(song)) return 1f
        return cache.get(song!!.data)?.let(::factorOf)
    }

    /** Reads the file if needed. Call from a background thread. */
    fun loadFactor(song: Song?): Float {
        if (!appliesTo(song)) return 1f
        val path = song!!.data
        val values = cache.get(path)
            ?: (AudioTags.readReplayGain(File(path)) ?: none).also { cache.put(path, it) }
        return factorOf(values)
    }
}
