package code.name.monkey.retromusic.spotify

import android.util.Log
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.SpotifyRepository
import code.name.monkey.retromusic.service.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In Spotify mode librespot owns the play queue. This bridge:
 *  - mirrors librespot's queue (prev + current + next) into MusicService's playingQueue, and
 *  - turns Retro queue actions (open queue, skip to, enqueue, insert, move, remove, shuffle,
 *    repeat) into librespot commands.
 */
class SpotifyQueueBridge(
    private val engine: LibrespotEngine,
    private val repository: SpotifyRepository,
    private val sync: SpotifyLibrarySync,
    private val host: Host,
) {
    /** Implemented by MusicService; always called on the main thread. */
    interface Host {
        val currentQueue: List<Song>
        val currentPosition: Int
        val currentShuffleMode: Int
        fun onSpotifyQueue(queue: List<Song>, position: Int, trackChanged: Boolean)
        fun onSpotifyPlayStateChanged()
        fun onSpotifySeek()
        fun onSpotifyModesChanged(shuffleMode: Int, repeatMode: Int)
    }

    private var scope: CoroutineScope? = null
    private var refreshJob: Job? = null
    private var lastTrackUri: String? = null

    /** The librespot entries behind the last mirrored queue, index-aligned with it. */
    private var lastItems: List<SpotifyQueueItem> = emptyList()

    fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        newScope.launch {
            engine.ensureStarted()
            refreshQueue()
            // Pick up playlist changes made elsewhere; only changed playlists and new tracks are
            // fetched, and artists/saved albums are left to the manual sync.
            if (engine.isLoggedIn.value) sync.startPlaylistsOnly()
        }
        newScope.launch {
            engine.events.collect { event ->
                when (event) {
                    LibrespotEvent.TrackChanged,
                    LibrespotEvent.QueueMaybeChanged -> scheduleRefresh()

                    LibrespotEvent.PlayStateChanged,
                    LibrespotEvent.Stopped -> host.onSpotifyPlayStateChanged()

                    LibrespotEvent.Seeked -> host.onSpotifySeek()
                    LibrespotEvent.ModesChanged -> publishModes()
                }
            }
        }
        publishModes()
    }

    fun stop() {
        scope?.cancel()
        scope = null
        refreshJob = null
        lastTrackUri = null
        lastItems = emptyList()
    }

    // ---------------------------------------------------------------------------------------
    // Retro -> librespot
    // ---------------------------------------------------------------------------------------

    /**
     * Plays [songs] starting at [startPosition]. If the list is exactly a cached Spotify
     * playlist or saved album, that context is played so other Connect devices see it.
     * Anything else (All songs, search results, Liked Songs, Retro playlists) is played as an
     * explicit track list with `playTracks`.
     */
    fun openQueue(songs: List<Song>, startPosition: Int, startPlaying: Boolean) {
        val target = songs.getOrNull(startPosition) ?: return
        if (!SpotifyIds.isTrackUri(target.data)) return
        launch {
            val shuffle = host.currentShuffleMode == MusicService.SHUFFLE_MODE_SHUFFLE
            val contextUri = repository.contextFor(songs)
            val started = if (contextUri != null && contextUri != SpotifyIds.COLLECTION_URI) {
                engine.playUri(contextUri, target.data, paused = !startPlaying)
            } else {
                // playUri("spotify:collection") is untested in the bridge; a track list is not.
                val startIndex = songs.take(startPosition).count { SpotifyIds.isTrackUri(it.data) }
                val uris = songs.map { it.data }.filter { SpotifyIds.isTrackUri(it) }
                engine.playTracks(uris, startIndex, 0, paused = !startPlaying)
            }
            if (started && shuffle != engine.state.value.shuffle) engine.setShuffle(shuffle)
            scheduleRefresh()
        }
    }

    /** Jumps to [position] in the mirrored queue. */
    fun playAt(position: Int) {
        val queue = host.currentQueue
        val target = queue.getOrNull(position) ?: return
        val current = host.currentPosition
        when {
            position > current -> engine.skipToNext(target.data)
            position < current -> engine.skipToPrev(target.data)
            else -> {
                engine.seek(0)
                engine.play()
            }
        }
    }

    fun next() = engine.next()

    fun previous() = engine.previous()

    /** Appends to librespot's user queue, which plays before the rest of the context. */
    fun enqueue(songs: List<Song>) {
        launch {
            songs.map { it.data }.filter { SpotifyIds.isTrackUri(it) }.forEach { engine.addToQueue(it) }
            scheduleRefresh()
        }
    }

    /**
     * Inserts [songs] so they play at [position] of the mirrored queue ("play next" passes the
     * slot after the current track). The user queue sits at the front of the upcoming tracks,
     * and librespot clamps the index into it.
     */
    fun insertAt(position: Int, songs: List<Song>) {
        launch {
            val index = (position - host.currentPosition - 1).coerceAtLeast(0)
            songs.map { it.data }.filter { SpotifyIds.isTrackUri(it) }.forEachIndexed { i, uri ->
                engine.insertIntoQueue(uri, index + i)
            }
            scheduleRefresh()
        }
    }

    /**
     * Moves an upcoming user-queued track. Returns false when the move can't be applied
     * (it involves context tracks, the current track or history), so the UI can snap back.
     *
     * Context tracks are deliberately not reordered with `setQueueJSON`: it replaces
     * everything after the current track, and the mirrored list may only hold the first page
     * of a long playlist, which would silently drop the rest.
     */
    fun move(from: Int, to: Int): Boolean {
        val current = host.currentPosition
        val items = lastItems
        if (items.size != host.currentQueue.size || from <= current || to <= current) return false
        val upcoming = items.drop(current + 1)
        val userQueued = upcoming.takeWhile { it.isUserQueued }
        val fromIndex = from - current - 1
        val toIndex = to - current - 1
        if (fromIndex !in userQueued.indices || toIndex !in userQueued.indices) return false
        val uri = userQueued[fromIndex].uri
        launch {
            if (engine.removeFromQueueNow(uri)) engine.insertIntoQueue(uri, toIndex)
            scheduleRefresh()
        }
        return true
    }

    /** Removes the first upcoming entry with each song's URI. */
    fun remove(songs: List<Song>) {
        songs.forEach { engine.removeFromQueue(it.data) }
    }

    fun clear() = engine.clearQueue()

    fun setShuffle(enabled: Boolean) = engine.setShuffle(enabled)

    fun toggleShuffle() = engine.setShuffle(!engine.state.value.shuffle)

    fun setRepeatMode(repeatMode: Int) {
        engine.setRepeat(
            when (repeatMode) {
                MusicService.REPEAT_MODE_ALL -> "context"
                MusicService.REPEAT_MODE_THIS -> "track"
                else -> "off"
            }
        )
    }

    /** Re-reads librespot's queue, e.g. to undo a drag in the queue UI that librespot cannot apply. */
    fun requestRefresh() = scheduleRefresh()

    // ---------------------------------------------------------------------------------------
    // librespot -> Retro
    // ---------------------------------------------------------------------------------------

    private fun publishModes() {
        val state = engine.state.value
        val shuffle = if (state.shuffle) MusicService.SHUFFLE_MODE_SHUFFLE else MusicService.SHUFFLE_MODE_NONE
        val repeat = when {
            state.repeatTrack -> MusicService.REPEAT_MODE_THIS
            state.repeatContext -> MusicService.REPEAT_MODE_ALL
            else -> MusicService.REPEAT_MODE_NONE
        }
        host.onSpotifyModesChanged(shuffle, repeat)
    }

    /** Coalesces bursts of events (metadata + queue + shuffle) into one queue read. */
    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = launch {
            delay(REFRESH_DEBOUNCE_MS)
            refreshQueue()
        }
    }

    private suspend fun refreshQueue() {
        val queue = engine.queue()
        val nowPlaying = engine.state.value.track
        val items = buildList {
            queue?.prev?.let { addAll(it) }
            queue?.current?.let { add(it) }
                ?: nowPlaying?.let { add(SpotifyQueueItem(it.uri, it.name)) }
            queue?.next?.let { addAll(it) }
        }
        if (items.isEmpty()) return
        val position = (queue?.prev?.size ?: 0).coerceAtMost(items.lastIndex)

        val uris = items.map { it.uri }
        val cached = withContext(Dispatchers.IO) {
            repository.songsForUris(uris.filter { SpotifyIds.isTrackUri(it) })
        }.associateBy { it.data }

        // Library songs keep their cached metadata; anything else (autoplay, search results)
        // is built from the metadata librespot sends with the queue.
        val songs = items.mapIndexed { index, item ->
            cached[item.uri]
                ?: if (index == position && nowPlaying != null && nowPlaying.uri == item.uri) {
                    SpotifyRepository.toSong(nowPlaying)
                } else {
                    SpotifyRepository.toSong(item)
                }
        }
        // librespot's metadata event has the freshest cover URL for the current track.
        nowPlaying?.coverUrl?.let { SpotifyArtwork.putAlbum(songs[position].albumId, it) }

        val currentUri = items[position].uri
        val trackChanged = currentUri != lastTrackUri
        lastTrackUri = currentUri
        lastItems = items
        host.onSpotifyQueue(songs, position, trackChanged)
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit): Job? =
        scope?.launch(block = block).also {
            if (it == null) Log.w(TAG, "Spotify bridge is not running")
        }

    companion object {
        private const val TAG = "SpotifyQueueBridge"
        private const val REFRESH_DEBOUNCE_MS = 250L
    }
}
