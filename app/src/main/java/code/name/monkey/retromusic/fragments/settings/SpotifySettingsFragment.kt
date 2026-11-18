package code.name.monkey.retromusic.fragments.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SPOTIFY_ACCOUNT
import code.name.monkey.retromusic.SPOTIFY_BITRATE
import code.name.monkey.retromusic.SPOTIFY_DEVICE_NAME
import code.name.monkey.retromusic.SPOTIFY_FULL_RESCAN
import code.name.monkey.retromusic.SPOTIFY_MODE
import code.name.monkey.retromusic.SPOTIFY_NORMALISATION
import code.name.monkey.retromusic.SPOTIFY_OGG_DUMP
import code.name.monkey.retromusic.SPOTIFY_OGG_DUMP_DIR
import code.name.monkey.retromusic.SPOTIFY_SYNC
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.spotify.LibrespotEngine
import code.name.monkey.retromusic.spotify.SpotifyAuthActivity
import code.name.monkey.retromusic.spotify.SpotifyLibrarySync
import code.name.monkey.retromusic.spotify.SpotifySyncState
import code.name.monkey.retromusic.util.PreferenceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.io.File

class SpotifySettingsFragment : AbsSettingsFragment() {

    private val engine: LibrespotEngine by inject()
    private val sync: SpotifyLibrarySync by inject()
    private val libraryViewModel by activityViewModel<LibraryViewModel>()

    /** Login state last seen by this screen; null until first observed. */
    private var wasLoggedIn: Boolean? = null

    private val pickOggDumpDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onOggDumpTreePicked(it) }
    }

    /** Returning from the All files access screen: continue to the picker if it was granted. */
    private val allFilesAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasAllFilesAccess()) pickOggDumpDir.launch(null)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_spotify)
    }

    override fun invalidateSettings() {
        findPreference<Preference>(SPOTIFY_ACCOUNT)?.setOnPreferenceClickListener {
            if (engine.isLoggedIn.value) confirmLogout() else login()
            true
        }

        findPreference<TwoStatePreference>(SPOTIFY_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            val enable = newValue as Boolean
            if (enable && !engine.isSupported) {
                showToast(R.string.spotify_requires_android_11)
                return@setOnPreferenceChangeListener false
            }
            // Let the preference persist first; MusicService and the tabs read it.
            view?.post {
                reloadLibrary()
                if (enable && engine.isLoggedIn.value) sync.start()
            }
            true
        }

        findPreference<Preference>(SPOTIFY_SYNC)?.setOnPreferenceClickListener {
            startSync(fullRescan = false)
            true
        }
        findPreference<Preference>(SPOTIFY_FULL_RESCAN)?.setOnPreferenceClickListener {
            startSync(fullRescan = true)
            true
        }

        findPreference<TwoStatePreference>(SPOTIFY_OGG_DUMP)?.setOnPreferenceChangeListener { _, newValue ->
            engine.setOggDumpEnabled(newValue as Boolean)
            true
        }

        findPreference<Preference>(SPOTIFY_OGG_DUMP_DIR)?.setOnPreferenceClickListener {
            onOggDumpDirClicked()
            true
        }

        // These are read when librespot starts, so restart it if it is running.
        listOf(SPOTIFY_DEVICE_NAME, SPOTIFY_BITRATE, SPOTIFY_NORMALISATION).forEach { key ->
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                if (engine.isRunning) {
                    view?.post { lifecycleScope.launch { engine.restart() } }
                }
                true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    engine.isLoggedIn.collect { loggedIn ->
                        findPreference<Preference>(SPOTIFY_ACCOUNT)?.setSummary(
                            if (loggedIn) R.string.spotify_logged_in else R.string.spotify_logged_out
                        )
                        // First sync right after logging in (not every time this screen opens;
                        // the player already does a playlists-only sync on start).
                        if (loggedIn && wasLoggedIn == false && PreferenceUtil.isSpotifyMode) {
                            sync.start()
                        }
                        wasLoggedIn = loggedIn
                    }
                }
                launch {
                    sync.state.collect { state -> renderSyncState(state) }
                }
                // Also toggled from the player menu and playlist screen.
                launch {
                    engine.oggDumpEnabled.collect { enabled ->
                        findPreference<TwoStatePreference>(SPOTIFY_OGG_DUMP)?.isChecked = enabled
                    }
                }
                launch { renderOggDumpDir() }
            }
        }
    }

    private fun renderSyncState(state: SpotifySyncState) {
        val pref = findPreference<Preference>(SPOTIFY_SYNC) ?: return
        pref.summary = when (state) {
            SpotifySyncState.Idle -> getString(R.string.spotify_sync_idle)
            is SpotifySyncState.Running -> {
                val step = when (state.step) {
                    "playlists" -> R.string.spotify_sync_step_playlists
                    "tracks" -> R.string.spotify_sync_step_tracks
                    "artists" -> R.string.spotify_sync_step_artists
                    "albums" -> R.string.spotify_sync_step_albums
                    "liked" -> R.string.spotify_sync_step_liked
                    else -> R.string.spotify_sync_step_library
                }
                getString(R.string.spotify_sync_running, getString(step), state.done, state.total)
            }

            is SpotifySyncState.Finished -> {
                reloadLibrary()
                getString(R.string.spotify_sync_finished, state.tracks, state.playlists)
            }

            is SpotifySyncState.Failed -> getString(R.string.spotify_sync_failed, state.reason)
        }
    }

    private suspend fun renderOggDumpDir() {
        val custom = engine.customOggDumpDir
        val usable = custom != null && withContext(Dispatchers.IO) { engine.isUsableDir(custom) }
        findPreference<Preference>(SPOTIFY_OGG_DUMP_DIR)?.summary = when {
            custom == null -> getString(R.string.spotify_ogg_dump_dir_default)
            usable -> custom.path
            else -> getString(R.string.spotify_ogg_dump_dir_unavailable, custom.path)
        }
    }

    private fun onOggDumpDirClicked() {
        if (engine.customOggDumpDir == null) {
            chooseOggDumpDir()
            return
        }
        materialDialog(R.string.pref_title_spotify_ogg_dump_dir)
            .setPositiveButton(R.string.spotify_ogg_dump_choose_folder) { _, _ -> chooseOggDumpDir() }
            .setNeutralButton(R.string.spotify_ogg_dump_use_app_storage) { _, _ -> applyOggDumpDir(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseOggDumpDir() {
        if (!engine.isSupported) {
            showToast(R.string.spotify_requires_android_11)
            return
        }
        if (hasAllFilesAccess()) {
            pickOggDumpDir.launch(null)
            return
        }
        materialDialog(R.string.spotify_ogg_dump_access_title)
            .setMessage(R.string.spotify_ogg_dump_access_message)
            .setPositiveButton(R.string.grant_access) { _, _ -> requestAllFilesAccess() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appScreen = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", requireContext().packageName, null)
        )
        try {
            allFilesAccess.launch(appScreen)
        } catch (e: Exception) {
            // Some devices only have the list screen.
            allFilesAccess.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun onOggDumpTreePicked(uri: Uri) {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            treeUriToDir(requireContext(), uri)
        } else {
            null
        }
        if (dir == null) {
            showToast(R.string.spotify_ogg_dump_folder_unsupported)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            if (!withContext(Dispatchers.IO) { engine.isUsableDir(dir) }) {
                showToast(R.string.spotify_ogg_dump_folder_not_writable)
                return@launch
            }
            applyOggDumpDir(dir)
        }
    }

    /** Switches the folder, turns saving on, then offers to bring already-saved tracks along. */
    private fun applyOggDumpDir(dir: File?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val oldDir = engine.oggDumpDir
            engine.setOggDumpDir(dir)
            if (dir != null && !engine.oggDumpEnabled.value) engine.setOggDumpEnabled(true)
            renderOggDumpDir()
            val newDir = engine.oggDumpDir
            val count = withContext(Dispatchers.IO) {
                if (oldDir.canonicalPath == newDir.canonicalPath) 0 else engine.savedTracks(oldDir).size
            }
            if (count > 0) confirmMoveSavedTracks(oldDir, newDir, count)
        }
    }

    private fun confirmMoveSavedTracks(from: File, to: File, count: Int) {
        materialDialog(R.string.spotify_ogg_dump_move_title)
            .setMessage(resources.getQuantityString(R.plurals.spotify_ogg_dump_move_message, count, count))
            .setPositiveButton(R.string.spotify_ogg_dump_move) { _, _ ->
                // Not tied to this screen: the move should finish even if the user leaves.
                val appContext = requireContext().applicationContext
                engine.launchIo {
                    val moved = engine.moveSavedTracks(from, to)
                    withContext(Dispatchers.Main) {
                        appContext.showToast(
                            appContext.resources.getQuantityString(R.plurals.spotify_ogg_dump_moved, moved, moved)
                        )
                    }
                }
            }
            .setNegativeButton(R.string.spotify_ogg_dump_keep, null)
            .show()
    }

    private fun startSync(fullRescan: Boolean) {
        if (!engine.isSupported) {
            showToast(R.string.spotify_requires_android_11)
            return
        }
        if (!engine.isLoggedIn.value) {
            showToast(R.string.spotify_sync_needs_login)
            return
        }
        sync.start(fullRescan)
    }

    private fun login() {
        if (!engine.isSupported) {
            showToast(R.string.spotify_requires_android_11)
            return
        }
        startActivity(Intent(requireContext(), SpotifyAuthActivity::class.java))
    }

    private fun confirmLogout() {
        materialDialog(R.string.spotify)
            .setMessage(R.string.spotify_logout_confirm)
            .setPositiveButton(R.string.spotify_logout) { _, _ ->
                lifecycleScope.launch {
                    engine.logout()
                    sync.clear()
                    reloadLibrary()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reloadLibrary() {
        listOf(
            ReloadType.Songs,
            ReloadType.Albums,
            ReloadType.Artists,
            ReloadType.Playlists,
            ReloadType.Genres,
            ReloadType.HomeSections,
            ReloadType.Suggestions,
        ).forEach { libraryViewModel.forceReload(it) }
    }
}

/**
 * Maps an external-storage document tree (e.g. `primary:Music/Spotify`) to its file path.
 * Returns null for other providers (Downloads, cloud), which have no plain path the bridge
 * could use.
 */
@RequiresApi(Build.VERSION_CODES.R)
private fun treeUriToDir(context: Context, uri: Uri): File? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val volumeId = docId.substringBefore(':')
    val relative = docId.substringAfter(':', "")
    val root = if (volumeId.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory()
    } else {
        context.getSystemService(StorageManager::class.java)?.storageVolumes
            ?.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }?.directory
    } ?: return null
    return if (relative.isEmpty()) root else File(root, relative)
}
