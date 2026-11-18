package code.name.monkey.retromusic.spotify

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.views.LollipopFixedWebView
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Spotify OAuth in a WebView. librespot's interactive flow redirects to http://127.0.0.1;
 * the redirect is intercepted and handed back to librespot instead of being loaded.
 */
class SpotifyAuthActivity : AppCompatActivity() {

    private val engine: LibrespotEngine by inject()
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var completing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.spotify_login)

        webView = LollipopFixedWebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith(REDIRECT_PREFIX)) {
                        complete(url)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!completing) this@SpotifyAuthActivity.progress.isVisible = false
                }
            }
        }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        setContentView(FrameLayout(this).apply {
            addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(
                progress,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            )
        })

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        lifecycleScope.launch {
            val url = engine.beginAuth()
            if (url.isNullOrEmpty()) {
                showToast(R.string.spotify_login_failed)
                finish()
            } else {
                webView.loadUrl(url)
            }
        }
    }

    private fun complete(redirectUrl: String) {
        if (completing) return
        completing = true
        progress.isVisible = true
        webView.isVisible = false
        lifecycleScope.launch {
            val ok = engine.completeAuth(redirectUrl)
            showToast(if (ok) R.string.spotify_login_success else R.string.spotify_login_failed)
            setResult(if (ok) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REDIRECT_PREFIX = "http://127.0.0.1"
    }
}
