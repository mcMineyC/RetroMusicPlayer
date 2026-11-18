package code.name.monkey.retromusic.service

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.service.playback.Playback
import code.name.monkey.retromusic.spotify.LibrespotEngine
import code.name.monkey.retromusic.spotify.LibrespotPlayback
import code.name.monkey.retromusic.util.PreferenceUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent


class PlaybackManager(val context: Context) {

    var playback: Playback? = null
    private var playbackLocation = PlaybackLocation.LOCAL

    val isLocalPlayback get() = playbackLocation == PlaybackLocation.LOCAL

    val audioSessionId: Int
        get() = if (playback != null) {
            playback!!.audioSessionId
        } else 0

    val songDurationMillis: Int
        get() = if (playback != null) {
            playback!!.duration()
        } else -1

    val songProgressMillis: Int
        get() = if (playback != null) {
            playback!!.position()
        } else -1

    val isPlaying: Boolean
        get() = playback != null && playback!!.isPlaying

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    /** Reads ReplayGain tags off the playback path; see [applyReplayGain]. */
    private val replayGainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var replayGainJob: Job? = null

    init {
        playback = createLocalPlayback()
    }

    fun setCallbacks(callbacks: Playback.PlaybackCallbacks) {
        playback?.callbacks = callbacks
    }

    fun play(onNotInitialized: () -> Unit) {
        if (PreferenceUtil.isSpeakerDisabled && speakerEnabled()) {
            context.showToast(R.string.speaker_disabled)
            return
        }
        if (playback != null && !playback!!.isPlaying) {
            if (!playback!!.isInitialized) {
                onNotInitialized()
            } else {
                openAudioEffectSession()
                if (playbackLocation == PlaybackLocation.LOCAL) {
                    if (playback is CrossFadePlayer) {
                        if (!(playback as CrossFadePlayer).isCrossFading) {
                            AudioFader.startFadeAnimator(playback!!, true)
                        }
                    } else {
                        AudioFader.startFadeAnimator(playback!!, true)
                    }
                }
                playback?.start()
            }
        }
    }

    fun pause(force: Boolean, onPause: () -> Unit) {
        if (playback != null && playback!!.isPlaying) {
            if (force) {
                playback?.pause()
                closeAudioEffectSession()
                onPause()
            } else {
                AudioFader.startFadeAnimator(playback!!, false) {
                    //Code to run when Animator Ends
                    playback?.pause()
                    closeAudioEffectSession()
                    onPause()
                }
            }
        }
    }

    fun seek(millis: Int, force: Boolean): Int = playback!!.seek(millis, force)

    fun setDataSource(
        song: Song,
        force: Boolean,
        completion: (success: Boolean) -> Unit,
    ) {
        playback?.setDataSource(song, force, completion)
        applyReplayGain(song)
    }

    /**
     * Applies [song]'s ReplayGain. Uses the cached value when there is one; otherwise, off the
     * main thread (the usual case: the service opens local tracks on a background dispatcher)
     * it reads the tags before playback starts, and on the main thread it reads them in the
     * background and applies the result as soon as it is ready.
     */
    fun applyReplayGain(song: Song) {
        replayGainJob?.cancel()
        val cached = ReplayGain.cachedFactor(song)
        when {
            cached != null -> playback?.setReplayGain(cached)
            Looper.myLooper() != Looper.getMainLooper() ->
                playback?.setReplayGain(ReplayGain.loadFactor(song))

            else -> replayGainJob = replayGainScope.launch {
                val factor = ReplayGain.loadFactor(song)
                withContext(Dispatchers.Main) { playback?.setReplayGain(factor) }
            }
        }
    }

    /** Reads [song]'s ReplayGain ahead of time so the switch to it (or a crossfade) has it ready. */
    fun prefetchReplayGain(song: Song?) {
        if (song == null || ReplayGain.cachedFactor(song) != null) return
        replayGainScope.launch { ReplayGain.loadFactor(song) }
    }

    fun setNextDataSource(trackUri: Uri?) {
        playback?.setNextDataSource(trackUri)
    }

    fun setCrossFadeDuration(duration: Int) {
        playback?.setCrossFadeDuration(duration)
    }

    /**
     * @param crossFadeDuration CrossFade duration
     * @return Whether switched playback
     */
    fun maybeSwitchToCrossFade(crossFadeDuration: Int): Boolean {
        // librespot produces its own audio; crossfade does not apply in Spotify mode.
        if (playback is LibrespotPlayback) return false
        /* Switch to RetroExoPlayer if CrossFade duration is 0 and
                Playback is not an instance of RetroExoPlayer */
        if (playback !is RetroExoPlayer && crossFadeDuration == 0) {
            if (playback != null) {
                playback?.release()
            }
            playback = null
            playback = RetroExoPlayer(context)
            return true
        } else if (playback !is CrossFadePlayer && crossFadeDuration > 0) {
            if (playback != null) {
                playback?.release()
            }
            playback = null
            playback = CrossFadePlayer(context)
            return true
        }
        return false
    }

    fun release() {
        replayGainJob?.cancel()
        playback?.release()
        playback = null
        closeAudioEffectSession()
    }

    private fun openAudioEffectSession() {
        // Session 0 is the global output mix; never hand that to an equalizer app.
        if (audioSessionId == 0) return
        val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        context.sendBroadcast(intent)
    }

    private fun closeAudioEffectSession() {
        if (audioSessionId == 0) return
        val audioEffectsIntent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        if (playback != null) {
            audioEffectsIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION,
                playback!!.audioSessionId)
        }
        audioEffectsIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        context.sendBroadcast(audioEffectsIntent)
    }

    fun switchToLocalPlayback(onChange: (wasPlaying: Boolean, progress: Int) -> Unit) {
        playbackLocation = PlaybackLocation.LOCAL
        switchToPlayback(createLocalPlayback(), onChange)
    }

    fun switchToRemotePlayback(
        remotePlayback: Playback,
        onChange: (wasPlaying: Boolean, progress: Int) -> Unit,
    ) {
        playbackLocation = PlaybackLocation.REMOTE
        switchToPlayback(remotePlayback, onChange)
    }

    private fun switchToPlayback(
        playback: Playback,
        onChange: (wasPlaying: Boolean, progress: Int) -> Unit,
    ) {
        val oldPlayback = this.playback
        val wasPlaying: Boolean = oldPlayback?.isPlaying == true
        val progress: Int = oldPlayback?.position() ?: 0
        this.playback = playback
        oldPlayback?.stop()
        onChange(wasPlaying, progress)
    }

    /** Re-creates the on-device playback, e.g. after switching between Local and Spotify mode. */
    fun resetLocalPlayback(callbacks: Playback.PlaybackCallbacks) {
        playback?.release()
        playbackLocation = PlaybackLocation.LOCAL
        playback = createLocalPlayback()
        playback?.callbacks = callbacks
    }

    private fun createLocalPlayback(): Playback {
        if (PreferenceUtil.isSpotifyMode) {
            return LibrespotPlayback(context, KoinJavaComponent.get(LibrespotEngine::class.java))
        }
        // Set RetroExoPlayer when crossfade duration is 0 i.e. off
        return if (PreferenceUtil.crossFadeDuration == 0) {
            RetroExoPlayer(context)
        } else {
            CrossFadePlayer(context)
        }
    }

    fun setPlaybackSpeedPitch(playbackSpeed: Float, playbackPitch: Float) {
        playback?.setPlaybackSpeedPitch(playbackSpeed, playbackPitch)
    }

    private fun speakerEnabled(): Boolean {
        val headsetTypes = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE
        )
        if (VersionUtils.hasS()) {
            headsetTypes.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            headsetTypes.add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        if (VersionUtils.hasOreo()) {
            headsetTypes.add(AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            if (device.type in headsetTypes) {
                return false
            }
        }
        return true
    }
}

enum class PlaybackLocation {
    LOCAL,
    REMOTE
}