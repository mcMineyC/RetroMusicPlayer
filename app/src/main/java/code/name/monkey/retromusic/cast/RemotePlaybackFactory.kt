package code.name.monkey.retromusic.cast

import androidx.mediarouter.media.MediaRouter
import code.name.monkey.retromusic.service.playback.Playback

/**
 * Creates a [Playback] that plays on a remote media route, such as a device picked from
 * the Cast button or from the system output switcher in the media notification.
 *
 * Register an implementation in `MainModule.kt` to add a remote playback backend.
 * Return null for routes the backend cannot play to; playback then stays on this device.
 */
fun interface RemotePlaybackFactory {
    fun create(route: MediaRouter.RouteInfo): Playback?
}

/** Default backend: no remote playback, so every route falls back to local playback. */
object NoRemotePlaybackFactory : RemotePlaybackFactory {
    override fun create(route: MediaRouter.RouteInfo): Playback? = null
}
