package code.name.monkey.retromusic.spotify

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.SPOTIFY_BITRATE
import code.name.monkey.retromusic.SPOTIFY_DEVICE_ID
import code.name.monkey.retromusic.SPOTIFY_DEVICE_NAME
import code.name.monkey.retromusic.SPOTIFY_NORMALISATION
import code.name.monkey.retromusic.SPOTIFY_OGG_DUMP
import code.name.monkey.retromusic.SPOTIFY_OGG_DUMP_DIR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mobilebind.CredentialCallback
import mobilebind.EventCallback
import mobilebind.Librespot
import mobilebind.OggDumpCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** The track librespot reports as current, from its `metadata` event. */
data class SpotifyNowPlaying(
    val uri: String,
    val name: String,
    val artistNames: List<String>,
    val albumName: String,
    val coverUrl: String?,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
)

data class SpotifyPlayerState(
    val track: SpotifyNowPlaying? = null,
    val isPlaying: Boolean = false,
    /** Position at [positionTimestamp] (elapsedRealtime). */
    val positionMs: Long = 0,
    val positionTimestamp: Long = 0,
    val shuffle: Boolean = false,
    val repeatTrack: Boolean = false,
    val repeatContext: Boolean = false,
    val contextUri: String? = null,
) {
    /** Extrapolated playback position; librespot only reports position on metadata/seek events. */
    fun currentPositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val base = if (isPlaying) positionMs + (now - positionTimestamp) else positionMs
        val duration = track?.durationMs ?: 0
        return if (duration > 0) base.coerceIn(0, duration) else base.coerceAtLeast(0)
    }
}

data class SpotifyQueueItem(
    val uri: String,
    val name: String,
    /** "context", "queue" (user-queued) or "autoplay". */
    val provider: String = "context",
    val durationMs: Long = 0,
    val albumUri: String? = null,
    val albumName: String = "",
    val imageUrl: String? = null,
    val artistUris: List<String> = emptyList(),
    val artistNames: List<String> = emptyList(),
) {
    val isUserQueued: Boolean get() = provider == "queue"
}

data class SpotifyQueue(
    val current: SpotifyQueueItem?,
    val prev: List<SpotifyQueueItem>,
    val next: List<SpotifyQueueItem>,
)

/** Changes to the saved-track folder, for [SpotifySavedTrackIndex]. */
sealed interface SavedTrackEvent {
    /** The bridge finished writing [path]. */
    data class Written(val path: String) : SavedTrackEvent

    /** The folder changed or files were moved: rescan [LibrespotEngine.oggDumpDir]. */
    data object FolderChanged : SavedTrackEvent
}

sealed interface LibrespotEvent {
    data object TrackChanged : LibrespotEvent
    data object PlayStateChanged : LibrespotEvent
    data object Seeked : LibrespotEvent
    data object ModesChanged : LibrespotEvent
    data object Stopped : LibrespotEvent
    data object QueueMaybeChanged : LibrespotEvent
}

/**
 * Owns the single go-librespot instance. All calls into Go run on [Dispatchers.IO]; the Go
 * event callback only parses JSON and updates flows, so it never blocks the Go side.
 */
class LibrespotEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val credentialsFile = File(context.filesDir, CREDENTIALS_FILE)

    @Volatile
    private var librespot: Librespot? = null

    private val _state = MutableStateFlow(SpotifyPlayerState())
    val state: StateFlow<SpotifyPlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LibrespotEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<LibrespotEvent> = _events.asSharedFlow()

    private val _isLoggedIn = MutableStateFlow(credentialsFile.exists())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _savedTrackEvents = MutableSharedFlow<SavedTrackEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val savedTrackEvents: SharedFlow<SavedTrackEvent> = _savedTrackEvents.asSharedFlow()

    private val _oggDumpEnabled = MutableStateFlow(prefs.getBoolean(SPOTIFY_OGG_DUMP, false))

    /** Whether played tracks are saved to [oggDumpDir] and replayed from there (bridge §5.6). */
    val oggDumpEnabled: StateFlow<Boolean> = _oggDumpEnabled.asStateFlow()

    /** App-specific folder used when no folder was chosen; needs no extra permission. */
    val defaultOggDumpDir: File by lazy {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        File(base, OGG_DUMP_DIR)
    }

    /** The folder the user chose for saved tracks, or null for [defaultOggDumpDir]. */
    val customOggDumpDir: File?
        get() = prefs.getString(SPOTIFY_OGG_DUMP_DIR, null)?.ifBlank { null }?.let(::File)

    /**
     * Where the bridge saves and reads tracks. The bridge uses plain file I/O (no SAF), so a
     * chosen folder outside app storage needs All files access; if it isn't usable (access
     * revoked, SD card removed), fall back to app storage rather than failing playback.
     */
    val oggDumpDir: File
        get() = customOggDumpDir?.takeIf { isUsableDir(it) } ?: defaultOggDumpDir

    /** True if the bridge can create and write files in [dir]. */
    fun isUsableDir(dir: File): Boolean =
        try {
            (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
        } catch (e: SecurityException) {
            false
        }

    /** Latest local output gain (0..1) for ducking/fades; applied on IO, conflated. */
    private val localGain = MutableStateFlow(1f)

    @Volatile
    private var restartAttempts = 0

    @Volatile
    private var restartPending = false

    init {
        scope.launch {
            localGain.collect { gain -> librespot?.let { runCatching { it.setLocalGain(gain) } } }
        }
    }

    val isRunning: Boolean get() = librespot != null

    /** The AAR is currently built with minSdk 30; see AndroidManifest overrideLibrary. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val deviceId: String
        get() = prefs.getString(SPOTIFY_DEVICE_ID, null) ?: generateDeviceId().also {
            prefs.edit { putString(SPOTIFY_DEVICE_ID, it) }
        }

    /** Starts librespot if it is not already running. Returns null if unsupported or failed. */
    suspend fun ensureStarted(): Librespot? = lifecycleMutex.withLock {
        librespot?.let { return it }
        if (!isSupported) return null
        withContext(Dispatchers.IO) { startLocked() }
    }

    private fun startLocked(): Librespot? {
        val instance = Librespot()
        instance.setCredentialCallback(object : CredentialCallback {
            override fun onCredentialsPersist(credentialsJson: String) {
                val obj = JSONObject(credentialsJson)
                writeCredentials(obj.optString("username"), obj.optString("data"))
                _isLoggedIn.value = true
            }
        })
        instance.setOggDumpCallback(oggDumpCallback)
        return try {
            instance.startWithConfigJSON(buildConfig().toString(), eventCallback)
            librespot = instance
            instance.setLocalGain(localGain.value)
            instance
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start librespot", e)
            null
        }
    }

    /**
     * The bridge does not retry a failed player setup (e.g. a Spotify 5xx on the client token):
     * every later call throws "not started". Restart with backoff until it comes up.
     */
    private fun scheduleRestart(reason: String) {
        if (restartPending || !_isLoggedIn.value) return
        restartPending = true
        val attempt = restartAttempts++
        val delayMs = (RESTART_BASE_DELAY_MS shl attempt.coerceAtMost(5)).coerceAtMost(RESTART_MAX_DELAY_MS)
        Log.w(TAG, "Restarting librespot in ${delayMs}ms ($reason)")
        scope.launch {
            delay(delayMs)
            try {
                if (_isLoggedIn.value) restart()
            } finally {
                restartPending = false
            }
        }
    }

    /** Re-reads the authoritative player state, e.g. after a transfer or reconnect. */
    suspend fun refreshStatus() {
        val json = call { it.getStatusJSON() } ?: return
        if (!json.trim().startsWith("{") || json.trim() == "{}") return
        val o = JSONObject(json)
        val now = SystemClock.elapsedRealtime()
        val playing = o.optBoolean("playing") && !o.optBoolean("paused")
        var position = o.optLong("position_ms")
        if (playing) {
            val timestamp = o.optLong("timestamp_ms")
            if (timestamp > 0) position += (System.currentTimeMillis() - timestamp).coerceAtLeast(0)
        }
        val durationMs = o.optLong("duration_ms")
        _state.update { s ->
            s.copy(
                isPlaying = playing,
                positionMs = position,
                positionTimestamp = now,
                shuffle = o.optBoolean("shuffle", s.shuffle),
                repeatTrack = o.optBoolean("repeat_track", s.repeatTrack),
                repeatContext = o.optBoolean("repeat_context", s.repeatContext),
                contextUri = o.optString("context_uri").ifEmpty { null } ?: s.contextUri,
                track = s.track?.let { t ->
                    if (durationMs > 0 && t.uri == o.optString("track_uri")) t.copy(durationMs = durationMs) else t
                },
            )
        }
        _events.tryEmit(LibrespotEvent.PlayStateChanged)
        _events.tryEmit(LibrespotEvent.ModesChanged)
    }

    suspend fun stop() = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                librespot?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping librespot", e)
            }
            librespot = null
            _state.value = SpotifyPlayerState()
            _events.tryEmit(LibrespotEvent.Stopped)
        }
    }

    /** Restarts with the current settings (device name, bitrate...). */
    suspend fun restart() {
        stop()
        ensureStarted()
    }

    /**
     * Turns saving played tracks on or off. Persists the choice (read again at start) and applies
     * it to the running instance; it affects tracks that start loading after this call.
     */
    fun setOggDumpEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(SPOTIFY_OGG_DUMP, enabled) }
        _oggDumpEnabled.value = enabled
        val instance = librespot ?: return
        scope.launch {
            try {
                if (enabled) instance.enableOggDump(oggDumpDir.path) else instance.disableOggDump()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to ${if (enabled) "enable" else "disable"} Ogg dump: ${e.message}")
            }
        }
    }

    /**
     * Saves tracks to [dir] (null = app storage) and plays saved tracks from there from now on.
     * Call off the main thread: it checks the folder on disk.
     */
    suspend fun setOggDumpDir(dir: File?) = withContext(Dispatchers.IO) {
        prefs.edit { putString(SPOTIFY_OGG_DUMP_DIR, dir?.path) }
        _savedTrackEvents.tryEmit(SavedTrackEvent.FolderChanged)
        val instance = librespot ?: return@withContext
        if (!_oggDumpEnabled.value) return@withContext
        try {
            // Re-enabling with the new folder switches tracks that start loading after this.
            instance.enableOggDump(oggDumpDir.path)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch Ogg dump folder: ${e.message}")
        }
    }

    /** Runs [block] in the engine's process-wide scope, for work that must outlive a screen. */
    fun launchIo(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** Saved tracks (`<id>.ogg`, not partial downloads) directly inside [dir]. */
    fun savedTracks(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.name.endsWith(".ogg") }
            ?.toList().orEmpty()

    /**
     * Moves saved tracks from [from] to [to], skipping ones [to] already has. Returns how many
     * were moved. Runs on IO; a cross-volume move falls back to copy + delete.
     */
    suspend fun moveSavedTracks(from: File, to: File): Int = withContext(Dispatchers.IO) {
        if (from.canonicalPath == to.canonicalPath || !isUsableDir(to)) return@withContext 0
        var moved = 0
        val movedPaths = ArrayList<String>()
        for (file in savedTracks(from)) {
            val target = File(to, file.name)
            if (target.exists()) continue
            try {
                if (!file.renameTo(target)) {
                    // Copy under a hidden name first so the bridge never sees a half-written .ogg.
                    val part = File(to, ".${file.name}.move.part")
                    file.copyTo(part, overwrite = true)
                    if (!part.renameTo(target)) {
                        part.delete()
                        continue
                    }
                    file.delete()
                }
                moved++
                movedPaths += target.path
            } catch (e: Exception) {
                Log.w(TAG, "Failed to move ${file.name}: ${e.message}")
            }
        }
        if (moved > 0) {
            _savedTrackEvents.tryEmit(SavedTrackEvent.FolderChanged)
            scanIfShared(movedPaths)
        }
        moved
    }

    /**
     * Saved tracks in a shared folder (e.g. Music/) belong in MediaStore so local mode lists them
     * with their tags; app storage is never indexed, so skip it.
     */
    private fun scanIfShared(paths: List<String>) {
        val appDir = defaultOggDumpDir.parentFile?.parentFile?.path ?: return
        val shared = paths.filterNot { it.startsWith(appDir) }
        if (shared.isNotEmpty()) MediaScannerConnection.scanFile(context, shared.toTypedArray(), null, null)
    }

    // ---------------------------------------------------------------------------------------
    // Auth
    // ---------------------------------------------------------------------------------------

    /** Returns the OAuth URL to open; the redirect goes to http://127.0.0.1. */
    suspend fun beginAuth(): String? = call { it.beginInteractiveAuth() }

    /** Completes OAuth with the intercepted redirect URL. Returns true on success. */
    suspend fun completeAuth(redirectUrl: String): Boolean =
        call { it.completeInteractiveAuth(redirectUrl); true } == true

    suspend fun logout() {
        stop()
        credentialsFile.delete()
        _isLoggedIn.value = false
    }

    // ---------------------------------------------------------------------------------------
    // Playback commands (fire-and-forget; state arrives through events)
    // ---------------------------------------------------------------------------------------

    fun play() = launchCall { it.play() }

    fun pause() = launchCall { it.pause() }

    fun next() = launchCall { it.next() }

    fun previous() = launchCall { it.previous() }

    fun seek(positionMs: Long) {
        _state.update {
            it.copy(positionMs = positionMs, positionTimestamp = SystemClock.elapsedRealtime())
        }
        launchCall { it.seek(positionMs) }
    }

    fun setShuffle(enabled: Boolean) {
        _state.update { it.copy(shuffle = enabled) }
        launchCall { it.setShuffle(enabled) }
    }

    /** [mode] is "off", "context" or "track". */
    fun setRepeat(mode: String) {
        _state.update { it.copy(repeatTrack = mode == "track", repeatContext = mode == "context") }
        launchCall { it.setRepeat(mode) }
    }

    fun skipToNext(uri: String) = launchCall { it.skipToNextTrack(uri) }

    fun skipToPrev(uri: String) = launchCall { it.skipToPrevTrack(uri) }

    suspend fun playUri(contextUri: String, skipToUri: String?, paused: Boolean): Boolean =
        call { it.playUri(contextUri, skipToUri.orEmpty(), paused); true } == true

    /** Plays an explicit list of URIs as one librespot-owned context. */
    suspend fun playTracks(uris: List<String>, startIndex: Int, positionMs: Long, paused: Boolean): Boolean =
        call { it.playTracks(jsonArray(uris), startIndex.toLong(), positionMs, paused); true } == true

    /**
     * Local output gain (0..1) for ducking and fades. Not sent to Spotify Connect, so other
     * devices keep showing the real volume. Cheap and conflated, so it can be called per frame.
     */
    fun setLocalGain(gain: Float) {
        localGain.value = gain.coerceIn(0f, 1f)
    }

    suspend fun addToQueue(uri: String): Boolean = call { it.addToQueue(uri); true } == true

    /** Inserts into the user queue; 0 = play next. The index is clamped by librespot. */
    suspend fun insertIntoQueue(uri: String, index: Int): Boolean =
        call { it.insertIntoQueue(uri, index.toLong()); true } == true

    suspend fun removeFromQueueNow(uri: String): Boolean = call { it.removeFromQueue(uri); true } == true

    fun removeFromQueue(uri: String) = launchCall {
        it.removeFromQueue(uri)
        _events.tryEmit(LibrespotEvent.QueueMaybeChanged)
    }

    fun clearQueue() = launchCall {
        it.clearQueue()
        _events.tryEmit(LibrespotEvent.QueueMaybeChanged)
    }

    suspend fun queue(): SpotifyQueue? = call { parseQueue(it.getQueueJSON()) }

    // ---------------------------------------------------------------------------------------
    // Library / metadata (blocking network calls; callers are already on IO)
    // ---------------------------------------------------------------------------------------

    suspend fun rootLibraryItemsJson(): String? = call { it.apiGetRootLibraryItems() }

    /** Batch track metadata; see LIBRESPOT_BRIDGE.md §5.3. */
    suspend fun tracksMetadataJson(uris: List<String>): String? =
        call { it.getTracksMetadataJSON(jsonArray(uris)) }

    suspend fun albumJson(uri: String): String? = call { it.getAlbumJSON(uri) }

    suspend fun artistJson(uri: String): String? = call { it.getArtistJSON(uri) }

    /** Returns `unchanged: true` and no tracks when [knownRevision] is still current. */
    suspend fun playlistJson(uri: String, knownRevision: String?): String? =
        call { it.getPlaylistJSON(uri, knownRevision.orEmpty()) }

    /** Liked Songs, newest first. */
    suspend fun collectionTracksJson(): String? = call { it.getCollectionTracksJSON() }

    suspend fun savedAlbumsJson(): String? = call { it.getSavedAlbumsJSON() }

    /** Map of URI to liked state, or null on failure. */
    suspend fun isLiked(uris: List<String>): Map<String, Boolean>? = call {
        val o = JSONObject(it.isLikedJSON(jsonArray(uris)))
        uris.associateWith { uri -> o.optBoolean(uri) }
    }

    suspend fun setLiked(uris: List<String>, liked: Boolean): Boolean = call {
        if (liked) it.like(jsonArray(uris)) else it.unlike(jsonArray(uris))
        true
    } == true

    // Playlist edits. Each returns null on failure; the add/remove/rename calls return the new
    // revision, which callers ignore because they re-read the playlist afterwards.

    /** Creates a playlist at the top of the library and returns its URI. */
    suspend fun createPlaylist(name: String): String? = call { it.createPlaylist(name) }

    suspend fun renamePlaylist(uri: String, name: String): String? = call { it.renamePlaylist(uri, name) }

    /** A negative [position] appends. */
    suspend fun addToPlaylist(uri: String, trackUris: List<String>, position: Int): String? =
        call { it.addToPlaylist(uri, jsonArray(trackUris), position.toLong()) }

    /** Fails safely (null) if the entry at [index] is no longer [expectedUri]. */
    suspend fun removeFromPlaylist(uri: String, index: Int, expectedUri: String): String? =
        call { it.removeFromPlaylist(uri, index.toLong(), expectedUri) }

    /** Removes the playlist from the user's library (Spotify's delete / unfollow). */
    suspend fun deletePlaylist(uri: String): Boolean = call { it.deletePlaylist(uri); true } == true

    /** The logged-in Spotify username, used to tell which playlists the user owns. */
    val username: String? get() = readCredentials()?.first

    suspend fun searchJson(query: String, limit: Long): String? =
        call { it.apiSearch(query, limit) }

    /**
     * Spotify Connect devices from the cluster state (local, no network). Unlike [call], this
     * never starts librespot: returns null while it isn't running.
     */
    suspend fun devicesJson(): String? = withContext(Dispatchers.IO) {
        val instance = librespot ?: return@withContext null
        try {
            instance.listDevicesJSON()
        } catch (e: Exception) {
            Log.w(TAG, "listDevicesJSON failed: ${e.message}")
            null
        }
    }

    suspend fun transferPlayback(deviceId: String, play: Boolean): Boolean =
        call { it.transferPlayback(deviceId, play); true } == true

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    /** Runs [block] against the running instance on IO, returning null if not running or on error. */
    suspend fun <T> call(block: (Librespot) -> T): T? = withContext(Dispatchers.IO) {
        val instance = librespot ?: ensureStarted() ?: return@withContext null
        try {
            block(instance)
        } catch (e: Exception) {
            Log.w(TAG, "librespot call failed: ${e.message}")
            if (e.message?.contains("not started", ignoreCase = true) == true) {
                scheduleRestart("call failed: not started")
            }
            null
        }
    }

    private fun jsonArray(values: List<String>): String = JSONArray(values).toString()

    private fun launchCall(block: (Librespot) -> Unit) {
        scope.launch { call(block) }
    }

    private val eventCallback = object : EventCallback {
        override fun onEvent(event: String, payloadJson: String) {
            try {
                handleEvent(event, payloadJson)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle librespot event $event", e)
            }
        }

        override fun onLog(level: String, message: String) {
            when (level) {
                "error", "fatal" -> Log.e(TAG, message)
                "warning", "warn" -> Log.w(TAG, message)
                "info" -> Log.i(TAG, message)
                else -> Log.d(TAG, message)
            }
            // Never call back into the bridge from here; scheduleRestart hops to a coroutine.
            if (message.contains("player initialization skipped")) {
                scheduleRestart("player initialization skipped")
            }
        }
    }

    private val oggDumpCallback = object : OggDumpCallback {
        override fun onOggWritten(uri: String, path: String, infoJson: String) {
            Log.d(TAG, "Saved $uri to $path")
            _savedTrackEvents.tryEmit(SavedTrackEvent.Written(path))
            scanIfShared(listOf(path))
        }

        override fun onOggDumpError(uri: String, message: String) {
            Log.w(TAG, "Could not save $uri: $message")
        }
    }

    private fun handleEvent(event: String, payload: String) {
        val now = SystemClock.elapsedRealtime()
        val data = payload.takeIf { it.startsWith("{") }?.let { JSONObject(it) }
        when (event) {
            "metadata" -> {
                if (data == null) return
                val track = parseNowPlaying(data)
                _isLoggedIn.value = true
                _state.update {
                    it.copy(
                        track = track,
                        positionMs = data.optLong("position"),
                        positionTimestamp = now,
                    )
                }
                _events.tryEmit(LibrespotEvent.TrackChanged)
                _events.tryEmit(LibrespotEvent.QueueMaybeChanged)
            }

            "will_play" -> {
                val contextUri = data?.optString("context_uri")?.ifEmpty { null }
                _state.update { it.copy(contextUri = contextUri ?: it.contextUri) }
            }

            "playing" -> {
                _state.update {
                    it.copy(
                        isPlaying = true,
                        positionMs = it.currentPositionMs(now),
                        positionTimestamp = now,
                        contextUri = data?.optString("context_uri")?.ifEmpty { null } ?: it.contextUri,
                    )
                }
                _events.tryEmit(LibrespotEvent.PlayStateChanged)
            }

            "paused" -> {
                _state.update {
                    it.copy(isPlaying = false, positionMs = it.currentPositionMs(now), positionTimestamp = now)
                }
                _events.tryEmit(LibrespotEvent.PlayStateChanged)
            }

            "stopped", "inactive", "not_playing" -> {
                _state.update {
                    it.copy(isPlaying = false, positionMs = it.currentPositionMs(now), positionTimestamp = now)
                }
                _events.tryEmit(LibrespotEvent.PlayStateChanged)
            }

            "active" -> scope.launch { refreshStatus() }

            "online" -> {
                _isOnline.value = true
                restartAttempts = 0
                scope.launch { refreshStatus() }
            }

            "offline" -> _isOnline.value = false

            "queue_changed" -> {
                val contextUri = data?.optString("context_uri")?.ifEmpty { null }
                if (contextUri != null) _state.update { it.copy(contextUri = contextUri) }
                _events.tryEmit(LibrespotEvent.QueueMaybeChanged)
            }

            "error" -> Log.w(
                TAG,
                "librespot error (${data?.optString("source")}): ${data?.optString("message")} ${data?.optString("uri").orEmpty()}"
            )

            "seek" -> {
                if (data == null) return
                _state.update { it.copy(positionMs = data.optLong("position"), positionTimestamp = now) }
                _events.tryEmit(LibrespotEvent.Seeked)
            }

            "shuffle_context" -> {
                _state.update { it.copy(shuffle = data?.optBoolean("value") ?: it.shuffle) }
                _events.tryEmit(LibrespotEvent.ModesChanged)
                _events.tryEmit(LibrespotEvent.QueueMaybeChanged)
            }

            "repeat_track" -> {
                _state.update { it.copy(repeatTrack = data?.optBoolean("value") ?: it.repeatTrack) }
                _events.tryEmit(LibrespotEvent.ModesChanged)
            }

            "repeat_context" -> {
                _state.update { it.copy(repeatContext = data?.optBoolean("value") ?: it.repeatContext) }
                _events.tryEmit(LibrespotEvent.ModesChanged)
            }
        }
    }

    private fun parseNowPlaying(data: JSONObject): SpotifyNowPlaying {
        val artists = data.optJSONArray("artist_names")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()
        val releaseDate = data.optString("release_date")
        return SpotifyNowPlaying(
            uri = data.optString("uri"),
            name = data.optString("name"),
            artistNames = artists,
            albumName = data.optString("album_name"),
            coverUrl = data.optString("album_cover_url").ifEmpty { null }
                ?.takeUnless { it == "null" },
            durationMs = data.optLong("duration"),
            trackNumber = data.optInt("track_number"),
            discNumber = data.optInt("disc_number"),
            year = releaseDate.take(4).toIntOrNull() ?: 0,
        )
    }

    private fun parseQueue(json: String?): SpotifyQueue? {
        if (json.isNullOrEmpty() || !json.trim().startsWith("{")) return null
        val obj = JSONObject(json)
        fun item(o: JSONObject?): SpotifyQueueItem? {
            val uri = o?.optString("uri")?.takeIf { it.isNotEmpty() } ?: return null
            val album = o.optJSONObject("album")
            val artists = o.optJSONArray("artists")
            val artistObjs = if (artists == null) emptyList()
            else (0 until artists.length()).mapNotNull { artists.optJSONObject(it) }
            return SpotifyQueueItem(
                uri = uri,
                name = o.optString("name"),
                provider = o.optString("provider").ifEmpty { "context" },
                durationMs = o.optLong("duration_ms"),
                albumUri = album?.optString("uri")?.ifEmpty { null },
                albumName = album?.optString("name").orEmpty(),
                imageUrl = SpotifyIds.imageUrlFromImageUri(album?.optString("image_url")?.ifEmpty { null }),
                artistUris = artistObjs.map { it.optString("uri") },
                artistNames = artistObjs.map { it.optString("name") },
            )
        }

        fun list(a: JSONArray?) =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { item(a.optJSONObject(it)) }
        return SpotifyQueue(
            current = item(obj.optJSONObject("current")),
            prev = list(obj.optJSONArray("prev")),
            next = list(obj.optJSONArray("next")),
        )
    }

    private fun buildConfig(): JSONObject {
        val credentials = readCredentials()
        return JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", prefs.getString(SPOTIFY_DEVICE_NAME, null)?.ifBlank { null } ?: DEFAULT_DEVICE_NAME)
            put("device_type", "smartphone")
            put("audio_backend", "oto")
            put("bitrate", prefs.getString(SPOTIFY_BITRATE, "160")?.toIntOrNull() ?: 160)
            put("volume_steps", 100)
            put("initial_volume", 100)
            put("normalisation_disabled", !prefs.getBoolean(SPOTIFY_NORMALISATION, true))
            put("flac_enabled", false)
            put("zeroconf_enabled", false)
            put("server", JSONObject().put("enabled", false))
            put("ogg_dump", JSONObject().apply {
                put("enabled", _oggDumpEnabled.value)
                put("dir", oggDumpDir.path)
            })
            put("credentials", JSONObject().apply {
                put("type", "interactive")
                put("spotify_token", JSONObject().apply {
                    put("username", credentials?.first.orEmpty())
                    put("data", credentials?.second.orEmpty())
                })
            })
        }
    }

    private fun readCredentials(): Pair<String, String>? {
        if (!credentialsFile.exists()) return null
        return try {
            val obj = JSONObject(credentialsFile.readText())
            val user = obj.optString("username")
            val data = obj.optString("data")
            if (user.isNotEmpty() && data.isNotEmpty()) user to data else null
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCredentials(username: String, data: String) {
        try {
            credentialsFile.writeText(JSONObject().put("username", username).put("data", data).toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist Spotify credentials", e)
        }
    }

    private fun generateDeviceId(): String {
        val bytes = ByteArray(20)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "LibrespotEngine"
        private const val CREDENTIALS_FILE = "spotify_credentials.json"
        private const val OGG_DUMP_DIR = "spotify"
        private const val DEFAULT_DEVICE_NAME = "Retro Music"
        private const val RESTART_BASE_DELAY_MS = 2_000L
        private const val RESTART_MAX_DELAY_MS = 60_000L
    }
}
