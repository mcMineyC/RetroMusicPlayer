package code.name.monkey.retromusic.extensions

import android.content.Context
import android.view.Menu
import androidx.core.view.MenuItemCompat
import androidx.mediarouter.app.MediaRouteActionProvider
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.cast.MediaRouteController

/** Shows the Cast button for remote playback routes. It stays hidden while none are available. */
@Suppress("UnusedReceiverParameter")
fun Context.setUpMediaRouteButton(menu: Menu) {
    val item = menu.findItem(R.id.action_cast) ?: return
    val provider = MenuItemCompat.getActionProvider(item) as? MediaRouteActionProvider ?: return
    provider.routeSelector = MediaRouteController.REMOTE_PLAYBACK_SELECTOR
}
