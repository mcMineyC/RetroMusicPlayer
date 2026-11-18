package code.name.monkey.retromusic.spotify

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.IBinder
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteDiscoveryRequest
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouteProviderService
import androidx.mediarouter.media.MediaRouter
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SPOTIFY_MODE
import code.name.monkey.retromusic.util.PreferenceUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.koin.android.ext.android.get

/**
 * Hosts [SpotifyConnectRouteProvider]. Bound by the system media router (Android 11+, which
 * Spotify mode needs), so the routes show in Retro's Cast button and in the system output
 * switcher for Retro's media session.
 */
class SpotifyConnectRouteProviderService : MediaRouteProviderService() {

    private var provider: SpotifyConnectRouteProvider? = null

    override fun onCreateMediaRouteProvider(): MediaRouteProvider =
        SpotifyConnectRouteProvider(this, get()).also { provider = it }

    // Only accept the MediaRouter2 binding: its calls are checked to come from the system. The
    // legacy messenger protocol would let any app bind this service and move playback around.
    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == MEDIA_ROUTE2_PROVIDER_SERVICE) super.onBind(intent) else null

    override fun onDestroy() {
        provider?.release()
        provider = null
        super.onDestroy()
    }

    private companion object {
        const val MEDIA_ROUTE2_PROVIDER_SERVICE = "android.media.MediaRoute2ProviderService"
    }
}

/**
 * Publishes the user's other Spotify Connect devices as media routes while Spotify mode is on.
 * Selecting one transfers librespot's playback to that device; selecting this phone again
 * transfers it back.
 *
 * Routes only carry [CATEGORY_SPOTIFY_CONNECT], which nothing but Retro asks for, so other
 * apps never see them. `cast/MediaRouteController` ignores them (they aren't remote-playback
 * routes): librespot keeps owning the queue and simply goes inactive while another device plays.
 */
class SpotifyConnectRouteProvider(
    context: Context,
    private val engine: LibrespotEngine,
) : MediaRouteProvider(context) {

    private data class ConnectDevice(
        val id: String,
        val name: String,
        val type: String,
        val isActive: Boolean,
        val volume: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private var pollJob: Job? = null
    private var pollIntervalMs = PASSIVE_POLL_MS
    private var transferHomeJob: Job? = null
    private var devices: List<ConnectDevice>? = null

    /** The device the user last picked from a route, until they pick this phone again. */
    private var selectedDeviceId: String? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SPOTIFY_MODE) updatePolling()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        scope.cancel()
    }

    override fun onDiscoveryRequestChanged(request: MediaRouteDiscoveryRequest?) {
        updatePolling()
    }

    override fun onCreateRouteController(routeId: String): RouteController? {
        if (!routeId.startsWith(ROUTE_ID_PREFIX)) return null
        return ConnectRouteController(routeId.removePrefix(ROUTE_ID_PREFIX))
    }

    /**
     * Polls the cluster while Spotify mode is on and someone is looking for our routes.
     * `listDevicesJSON` reads local cluster state, so polling is cheap; it just has no event.
     */
    private fun updatePolling() {
        val request = discoveryRequest
        val wanted = PreferenceUtil.isSpotifyMode &&
            request != null && request.selector.hasControlCategory(CATEGORY_SPOTIFY_CONNECT)
        if (!wanted) {
            pollJob?.cancel()
            pollJob = null
            if (!PreferenceUtil.isSpotifyMode) publish(emptyList())
            return
        }
        val interval = if (request!!.isActiveScan) ACTIVE_POLL_MS else PASSIVE_POLL_MS
        if (pollJob?.isActive == true && interval == pollIntervalMs) return
        pollIntervalMs = interval
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // Retry soon while librespot is still starting; then settle on the interval.
                delay(if (refresh()) pollIntervalMs else STARTUP_RETRY_MS)
            }
        }
    }

    /** Returns false while librespot isn't running yet. */
    private suspend fun refresh(): Boolean {
        // Null while librespot isn't running (starting, restarting): keep the last list rather
        // than dropping routes, which would unselect the one in use.
        val json = engine.devicesJson() ?: return false
        if (PreferenceUtil.isSpotifyMode) publish(parseDevices(json))
        return true
    }

    private fun parseDevices(json: String): List<ConnectDevice> {
        val ownId = engine.deviceId
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty() || id.equals(ownId, ignoreCase = true)) return@mapNotNull null
            ConnectDevice(
                id = id,
                name = o.optString("name").ifBlank { id },
                type = o.optString("type"),
                isActive = o.optBoolean("is_active"),
                volume = o.optInt("volume").coerceIn(0, 100),
            )
        }
    }

    private fun publish(newDevices: List<ConnectDevice>) {
        if (newDevices == devices) return
        devices = newDevices
        val routes = newDevices.map { device ->
            MediaRouteDescriptor.Builder(ROUTE_ID_PREFIX + device.id, device.name)
                .setDescription(
                    context.getString(
                        if (device.isActive) R.string.spotify_connect_route_playing
                        else R.string.spotify_connect_route
                    )
                )
                .addControlFilter(IntentFilter().apply { addCategory(CATEGORY_SPOTIFY_CONNECT) })
                .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setDeviceType(deviceType(device.type))
                // The bridge can only set this device's volume, not another device's.
                .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED)
                .setVolumeMax(100)
                .setVolume(device.volume)
                .build()
        }
        descriptor = MediaRouteProviderDescriptor.Builder().addRoutes(routes).build()
    }

    private fun onDeviceSelected(deviceId: String) {
        transferHomeJob?.cancel()
        transferHomeJob = null
        selectedDeviceId = deviceId
        if (!PreferenceUtil.isSpotifyMode) return
        if (devices?.firstOrNull { it.id == deviceId }?.isActive == true) return
        // play = false keeps the current play/pause state ("restore paused").
        scope.launch { engine.transferPlayback(deviceId, play = false) }
    }

    private fun onDeviceUnselected(deviceId: String) {
        // Switching straight to another Spotify device: that route's select does the transfer.
        if (selectedDeviceId != deviceId) return
        transferHomeJob?.cancel()
        // The other route's select may follow this unselect; give it a moment to cancel us.
        transferHomeJob = scope.launch {
            delay(TRANSFER_HOME_DELAY_MS)
            selectedDeviceId = null
            if (!PreferenceUtil.isSpotifyMode) return@launch
            // Only take playback back if that device still has it: if it vanished or the user
            // moved playback elsewhere from another app, leave it alone.
            val current = engine.devicesJson()?.let(::parseDevices) ?: devices.orEmpty()
            if (current.firstOrNull { it.id == deviceId }?.isActive != true) return@launch
            engine.transferPlayback(engine.deviceId, play = false)
        }
    }

    private inner class ConnectRouteController(private val deviceId: String) : RouteController() {
        override fun onSelect() {
            scope.launch { onDeviceSelected(deviceId) }
        }

        override fun onUnselect(reason: Int) {
            scope.launch { onDeviceUnselected(deviceId) }
        }
    }

    companion object {
        /** Control category of Spotify Connect routes; add it to a selector to show them. */
        const val CATEGORY_SPOTIFY_CONNECT = "code.name.monkey.retromusic.CATEGORY_SPOTIFY_CONNECT"

        private const val ROUTE_ID_PREFIX = "spotify-connect:"
        private const val ACTIVE_POLL_MS = 3_000L
        private const val PASSIVE_POLL_MS = 20_000L
        private const val STARTUP_RETRY_MS = 2_000L
        private const val TRANSFER_HOME_DELAY_MS = 750L

        /** Maps the Connect `DeviceType` enum name to a MediaRouter device type for the icon. */
        private fun deviceType(type: String): Int = when (type.uppercase()) {
            "TV", "STB", "CAST_VIDEO" -> MediaRouter.RouteInfo.DEVICE_TYPE_TV
            "SPEAKER", "AUDIO_DONGLE", "CAST_AUDIO", "HOME_THING" ->
                MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER
            "AVR" -> MediaRouter.RouteInfo.DEVICE_TYPE_AUDIO_VIDEO_RECEIVER
            "COMPUTER", "CHROMEBOOK" -> MediaRouter.RouteInfo.DEVICE_TYPE_COMPUTER
            "TABLET" -> MediaRouter.RouteInfo.DEVICE_TYPE_TABLET
            "SMARTPHONE" -> MediaRouter.RouteInfo.DEVICE_TYPE_SMARTPHONE
            "GAME_CONSOLE" -> MediaRouter.RouteInfo.DEVICE_TYPE_GAME_CONSOLE
            "AUTOMOBILE", "CAR_THING" -> MediaRouter.RouteInfo.DEVICE_TYPE_CAR
            "SMARTWATCH" -> MediaRouter.RouteInfo.DEVICE_TYPE_SMARTWATCH
            else -> MediaRouter.RouteInfo.DEVICE_TYPE_UNKNOWN
        }
    }
}
