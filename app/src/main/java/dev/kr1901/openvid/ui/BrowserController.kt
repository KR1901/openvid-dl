package dev.kr1901.openvid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
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

const val HOME_URL = "https://www.google.com"

internal fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull() ?: url

internal fun fileNameOf(url: String): String {
    val name = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: url
}

/** One open tab: its own WebView, history and list of detected videos. */
class BrowserTab(val id: Int, startUrl: String) {
    var url by mutableStateOf(startUrl)
        internal set
    var title by mutableStateOf("")
        internal set
    var loading by mutableStateOf(false)
        internal set
    var progress by mutableIntStateOf(0)
        internal set
    var canGoBack by mutableStateOf(false)
        internal set
    var secure by mutableStateOf(startUrl.startsWith("https"))
        internal set

    val detected = mutableStateListOf<String>()

    internal var webView: WebView? = null

    val label: String
        get() = if (title.isNotBlank()) title else hostOf(url)
}

/**
 * Owns every tab's WebView and the shared browsing session. Hoisted out of the
 * composable so pages, cookies and history survive switching between app tabs.
 */
class BrowserController(private val context: Context) {

    val tabs = mutableStateListOf<BrowserTab>()

    var activeIndex by mutableIntStateOf(0)
        private set

    private var nextId = 1

    init {
        openTab(HOME_URL)
    }

    val active: BrowserTab
        get() = tabs.getOrElse(activeIndex) { tabs.first() }

    fun openTab(url: String = HOME_URL, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextId++, url)
        tabs.add(tab)
        if (select) activeIndex = tabs.lastIndex
        return tab
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        tab.webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        tab.webView = null
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            openTab(HOME_URL)
            return
        }
        if (activeIndex >= tabs.size) activeIndex = tabs.lastIndex
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices) activeIndex = index
    }

    /** Navigate the active tab to [target]: a URL, a bare domain, or a search. */
    fun go(target: String) {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return
        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(" ") || !trimmed.contains(".") ->
                "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
            else -> "https://$trimmed"
        }
        val tab = active
        tab.url = url
        ensureWebView(tab).loadUrl(url)
    }

    fun reload() {
        active.webView?.reload()
    }

    fun back(): Boolean {
        val view = active.webView ?: return false
        return if (view.canGoBack()) {
            view.goBack()
            true
        } else {
            false
        }
    }

    /** Ask the active page for every media element it exposes. */
    fun probe() {
        val tab = active
        tab.webView?.evaluateJavascript(PROBE_JS) { raw -> applyProbeResult(tab, raw) }
    }

    private fun applyProbeResult(tab: BrowserTab, raw: String?) {
        if (raw.isNullOrBlank() || raw == "null") return
        val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return
        runCatching {
            val array = JSONArray(inner)
            for (i in 0 until array.length()) addDetected(tab, array.optString(i))
        }
    }

    private fun addDetected(tab: BrowserTab, url: String) {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return
        if (tab.detected.none { it == url }) tab.detected.add(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(tab: BrowserTab): WebView {
        tab.webView?.let { return it }

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
                if (MEDIA_REGEX.containsMatchIn(url)) addDetected(tab, url)
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.loading = true
                tab.progress = 0
                tab.url = url
                tab.secure = url.startsWith("https")
                // A fresh page starts with a fresh list of videos.
                tab.detected.clear()
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.loading = false
                tab.url = url
                tab.canGoBack = view.canGoBack()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                tab.url = url
                tab.secure = url.startsWith("https")
                tab.canGoBack = view.canGoBack()
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) tab.title = title
            }
        }

        view.setDownloadListener { url, _, _, _, _ ->
            if (url != null) addDetected(tab, url)
        }

        view.loadUrl(tab.url)
        tab.webView = view
        return view
    }

    companion object {
        private val MEDIA_REGEX = Regex(
            """(?i)(\.(m3u8|mpd|mp4|webm|mkv|mov|m4v|m4s|ts|3gp|m4a|mp3|aac|ogg|opus)(\?|#|$))|(videoplayback)|(mime=video)"""
        )

        private const val PROBE_JS = """
            (function () {
              var out = [];
              function push(u) { if (u && u.indexOf('blob:') !== 0 && out.indexOf(u) < 0) out.push(u); }
              document.querySelectorAll('video,audio,source').forEach(function (e) {
                if (e.src) push(e.src);
                if (e.currentSrc) push(e.currentSrc);
                if (e.getAttribute) {
                  var d = e.getAttribute('data-src');
                  if (d) push(d);
                }
              });
              document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]').forEach(function (m) {
                var c = m.getAttribute('content');
                if (c) push(c);
              });
              document.querySelectorAll('a[href]').forEach(function (a) {
                if (/\.(mp4|m3u8|webm|mkv|mov|m4a|mp3)(\?|#|${'$'})/i.test(a.href)) push(a.href);
              });
              return JSON.stringify(out);
            })();
        """
    }
}
