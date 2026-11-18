package code.name.monkey.retromusic.glide.artistimage

import code.name.monkey.retromusic.spotify.SpotifyArtwork
import code.name.monkey.retromusic.spotify.SpotifyLibrarySync
import com.bumptech.glide.Priority
import com.bumptech.glide.integration.okhttp3.OkHttpStreamFetcher
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.koin.java.KoinJavaComponent
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Artist image for a Spotify-mode artist whose portrait URL isn't known yet. The portrait is
 * looked up through librespot (never Deezer); without one, the artist's first album cover is
 * used.
 */
data class SpotifyArtistImage(val artistId: Long, val fallbackAlbumId: Long)

class SpotifyArtistImageLoader(
    private val sync: SpotifyLibrarySync,
    private val okhttp: OkHttpClient,
) : ModelLoader<SpotifyArtistImage, InputStream> {

    override fun buildLoadData(
        model: SpotifyArtistImage,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> =
        ModelLoader.LoadData(ObjectKey(model), SpotifyArtistImageFetcher(sync, okhttp, model))

    override fun handles(model: SpotifyArtistImage): Boolean = true

    class Factory : ModelLoaderFactory<SpotifyArtistImage, InputStream> {
        private val okhttp = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<SpotifyArtistImage, InputStream> =
            SpotifyArtistImageLoader(KoinJavaComponent.get(SpotifyLibrarySync::class.java), okhttp)

        override fun teardown() {}
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
    }
}

class SpotifyArtistImageFetcher(
    private val sync: SpotifyLibrarySync,
    private val okhttp: OkHttpClient,
    private val model: SpotifyArtistImage,
) : DataFetcher<InputStream> {

    @Volatile
    private var isCancelled = false
    private var streamFetcher: OkHttpStreamFetcher? = null

    // Glide calls this on its source executor, so blocking on the lookup is fine here.
    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        val url = try {
            runBlocking { sync.artistImageUrl(model.artistId) }
        } catch (e: Exception) {
            null
        } ?: SpotifyArtwork.albumUrl(model.fallbackAlbumId)
        if (isCancelled) {
            callback.onDataReady(null)
            return
        }
        if (url == null) {
            callback.onLoadFailed(IOException("No Spotify image for artist ${model.artistId}"))
            return
        }
        streamFetcher = OkHttpStreamFetcher(okhttp, GlideUrl(url)).also { it.loadData(priority, callback) }
    }

    override fun cleanup() {
        streamFetcher?.cleanup()
    }

    override fun cancel() {
        isCancelled = true
        streamFetcher?.cancel()
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.REMOTE
}
