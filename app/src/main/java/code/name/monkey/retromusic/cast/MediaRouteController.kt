package code.name.monkey.retromusic.cast

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouterParams
import code.name.monkey.retromusic.service.playback.Playback

/**
 * Connects the app's media session to AndroidX MediaRouter so remote routes can be picked
 * from the in-app Cast button and from the system output switcher (the devices button in the
 * media notification), and switches playback between this device and the selected route.
 *
 * Owned by MusicService. All calls must be made on the main thread.
 */
class MediaRouteController(
    context: Context,
    private val mediaSession: MediaSessionCompat,
    private val remotePlaybackFactory: RemotePlaybackFactory,
    private val onRemotePlayback: (Playback) -> Unit,
    private val onLocalPlayback: () -> Unit,
) {
    private val router = MediaRouter.getInstance(context)
    private var remoteRoute: MediaRouter.RouteInfo? = null

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteSelected(
            router: MediaRouter,
            route: MediaRouter.RouteInfo,
            reason: Int,
        ) {
            onRouteChosen(route)
        }

        override fun onRouteUnselected(
            router: MediaRouter,
            route: MediaRouter.RouteInfo,
            reason: Int,
        ) {
            if (route.id == remoteRoute?.id) {
                remoteRoute = null
                onLocalPlayback()
            }
        }
    }

    fun start() {
        router.setRouterParams(
            MediaRouterParams.Builder()
                .setOutputSwitcherEnabled(true)
                .setTransferToLocalEnabled(true)
                .build()
        )
        router.setMediaSessionCompat(mediaSession)
        router.addCallback(REMOTE_PLAYBACK_SELECTOR, callback)
        // Pick up a remote route that was already selected before the service started.
        onRouteChosen(router.selectedRoute)
    }

    fun stop() {
        router.removeCallback(callback)
        router.setMediaSessionCompat(null)
        remoteRoute = null
    }

    private fun onRouteChosen(route: MediaRouter.RouteInfo) {
        if (route.id == remoteRoute?.id || !isRemote(route)) return
        val playback = remotePlaybackFactory.create(route)
        if (playback == null) {
            // No backend can play to this route; go back to playing on this device.
            router.defaultRoute.select()
            return
        }
        remoteRoute = route
        onRemotePlayback(playback)
    }

    private fun isRemote(route: MediaRouter.RouteInfo): Boolean =
        !route.isDefault && !route.isBluetooth &&
            route.supportsControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)

    companion object {
        /** Routes that can play media remotely; used by the service and the Cast button. */
        val REMOTE_PLAYBACK_SELECTOR: MediaRouteSelector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
    }
}
