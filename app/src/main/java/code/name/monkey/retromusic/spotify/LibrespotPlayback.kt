package code.name.monkey.retromusic.spotify

import android.content.Context
import android.net.Uri
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.service.AudioManagerPlayback
import code.name.monkey.retromusic.service.playback.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [Playback] backed by go-librespot. Audio is produced inside Go (oto), so there is no Android
 * audio session: the equalizer, crossfade and speed/pitch do not apply. Volume changes
 * (ducking, fades) go to librespot's local gain. librespot owns the
 * queue; [SpotifyQueueBridge] mirrors it into MusicService, so per-track data-source calls
 * only happen on fallback paths.
 */
class LibrespotPlayback(
    context: Context,
    private val engine: LibrespotEngine,
) : AudioManagerPlayback(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override var callbacks: Playback.PlaybackCallbacks? = null

    override val isInitialized: Boolean
        get() = engine.state.value.track != null

    override val isPlaying: Boolean
        get() = engine.state.value.isPlaying

    override val audioSessionId: Int = 0

    override fun setDataSource(song: Song, force: Boolean, completion: (success: Boolean) -> Unit) {
        if (!SpotifyIds.isTrackUri(song.data)) {
            completion(false)
            return
        }
        if (engine.state.value.track?.uri == song.data) {
            completion(true)
            return
        }
        scope.launch {
            completion(engine.playUri(song.data, null, paused = true))
        }
    }

    // librespot prefetches the next track itself.
    override fun setNextDataSource(path: Uri?) {}

    override fun start(): Boolean {
        super.start()
        engine.play()
        return true
    }

    override fun stop() {
        super.stop()
        engine.pause()
    }

    override fun release() {
        // The engine is app-wide and outlives this Playback; only drop focus.
        super.stop()
    }

    override fun pause(): Boolean {
        super.pause()
        engine.pause()
        return true
    }

    override fun duration(): Int = engine.state.value.track?.durationMs?.toInt() ?: 0

    override fun position(): Int = engine.state.value.currentPositionMs().toInt()

    override fun seek(whereto: Int, force: Boolean): Int {
        engine.seek(whereto.toLong())
        return whereto
    }

    // Ducking and fades use librespot's local output gain, not the Connect volume, so other
    // devices keep showing the user's real volume.
    override fun setVolume(vol: Float): Boolean {
        engine.setLocalGain(vol)
        return true
    }

    override fun setAudioSessionId(sessionId: Int): Boolean = false

    override fun setCrossFadeDuration(duration: Int) {}

    override fun setPlaybackSpeedPitch(speed: Float, pitch: Float) {}
}
