package dev.kr1901.openvid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URLEncoder

/**
 * Owns the WebView and the browsing session.
 *
 * Hoisting this out of the composable means the page, cookies, history and the
 * list of detected media all survive switching between the Home / Browser /
 * Downloads tabs — the browser no longer reloads every time you come back to it.
 */
class BrowserController(private val context: Context) {

    var address by mutableStateOf(HOME)
        private set
    var currentUrl by mutableStateOf(HOME)
        private set
    var secure by mutableStateOf(true)
        private set
    var loading by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var canGoBack by mutableStateOf(false)
        private set

    val detected = mutableStateListOf<String>()

    var webView: WebView? = null
        private set

    private val mediaRegex =
        Regex("""(?i)\.(m3u8|mpd|mp4|webm|mkv|mov|m4v|ts|m4a|mp3|aac|ogg|opus)(\?|#|$)""")

    fun addDetected(url: String) {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return
        if (detected.none { it == url }) detected.add(url)
    }

    /** Navigate to [target], which may be a URL, a bare domain, or a search term. */
    fun go(target: String) {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return
        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(" ") || !trimmed.contains(".") ->
                "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
            else -> "https://$trimmed"
        }
        address = url
        webView?.loadUrl(url)
    }

    fun reload() {
        webView?.reload()
    }

    fun back(): Boolean {
        val view = webView ?: return false
        return if (view.canGoBack()) {
            view.goBack()
            true
        } else {
            false
        }
    }

    /** Ask the page for any media elements it exposes. */
    fun probe() {
        webView?.evaluateJavascript(PROBE_JS) { raw -> applyProbeResult(raw) }
    }

    private fun applyProbeResult(raw: String?) {
        if (raw.isNullOrBlank() || raw == "null") return
        // evaluateJavascript returns a JSON-encoded value; our script returns a
        // JSON string, so it is encoded twice.
        val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return
        runCatching {
            val array = JSONArray(inner)
            for (i in 0 until array.length()) addDetected(array.optString(i))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(): WebView {
        webView?.let { return it }

        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (mediaRegex.containsMatchIn(url)) addDetected(url)
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loading = true
                progress = 0
                address = url
                currentUrl = url
                secure = url.startsWith("https")
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading = false
                address = url
                currentUrl = url
                canGoBack = view.canGoBack()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                address = url
                currentUrl = url
                secure = url.startsWith("https")
                canGoBack = view.canGoBack()
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
            }
        }

        view.setDownloadListener { url, _, _, _, _ ->
            if (url != null) addDetected(url)
        }

        view.loadUrl(HOME)
        webView = view
        return view
    }

    companion object {
        const val HOME = "https://www.google.com"

        private const val PROBE_JS = """
            (function () {
              var out = [];
              function push(u) { if (u && out.indexOf(u) < 0) out.push(u); }
              document.querySelectorAll('video,audio,source').forEach(function (e) {
                if (e.src) push(e.src);
                if (e.currentSrc) push(e.currentSrc);
              });
              document.querySelectorAll('a[href]').forEach(function (a) {
                if (/\.(mp4|m3u8|webm|mkv|mov|m4a|mp3)(\?|#|${'$'})/i.test(a.href)) push(a.href);
              });
              return JSON.stringify(out);
            })();
        """
    }
}
