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
import dev.kr1901.openvid.engine.NativeEngine
import org.json.JSONArray
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URLEncoder

const val HOME_URL = "https://www.google.com"

internal fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull() ?: url

internal fun fileNameOf(url: String): String {
    val name = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: url
}

// Media detection is deliberately generous: a URL counts as media if it carries
// a recognisable token anywhere (many CDNs sign URLs with no file extension),
// unless it is clearly an image, stylesheet or script.
private val MEDIA_STRONG = Regex(
    """(?i)(\.(m3u8|mpd|mp4|webm|mkv|m4s|m4v|mov|3gp)\b|videoplayback|mime=video|mime%3dvideo)"""
)
private val MEDIA_WEAK = Regex("""(?i)\.(ts|m4a|mp3|aac|ogg|opus)(\?|#|$)""")
private val NON_MEDIA = Regex(
    """(?i)\.(jpe?g|png|gif|webp|svg|css|js|mjs|woff2?|ttf|otf|ico|json|xml)(\?|#|$)"""
)

internal fun looksLikeMedia(url: String): Boolean =
    (MEDIA_STRONG.containsMatchIn(url) || MEDIA_WEAK.containsMatchIn(url)) &&
        !NON_MEDIA.containsMatchIn(url)

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

    /** When true, pages are requested with a desktop user agent. */
    var desktopMode by mutableStateOf(false)
        private set

    /** Set when the page itself triggers a download, so the UI can surface it. */
    var interceptNotice by mutableStateOf<String?>(null)
        private set

    fun consumeInterceptNotice(): String? {
        val notice = interceptNotice
        interceptNotice = null
        return notice
    }

    /** Map a WebView request onto the resource types the filter engine knows. */
    private fun requestKind(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "document"
        val url = request.url.toString().lowercase()
        val path = url.substringBefore('?')
        return when {
            path.endsWith(".js") -> "script"
            path.endsWith(".css") -> "stylesheet"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") -> "image"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") -> "font"
            looksLikeMedia(url) -> "media"
            else -> "xmlhttprequest"
        }
    }

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

    fun openInNewTab(url: String) {
        openTab(url)
    }

    private fun destroy(tab: BrowserTab) {
        tab.webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        tab.webView = null
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        destroy(tabs[index])
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            openTab(HOME_URL)
            return
        }
        if (activeIndex >= tabs.size) activeIndex = tabs.lastIndex
    }

    fun closeAllTabs() {
        tabs.forEach { destroy(it) }
        tabs.clear()
        openTab(HOME_URL)
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices) activeIndex = index
    }

    /** Flip between mobile and desktop user agents and reload the active page. */
    fun toggleDesktopMode() {
        desktopMode = !desktopMode
        val ua = if (desktopMode) DESKTOP_UA else null
        tabs.forEach { it.webView?.settings?.userAgentString = ua }
        active.webView?.reload()
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
            if (desktopMode) userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
                // Never block the page itself, only its sub-resources.
                if (!request.isForMainFrame &&
                    NativeEngine.isBlocked(url, tab.url, requestKind(request))
                ) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
                if (looksLikeMedia(url)) addDetected(tab, url)
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
            if (url != null) {
                addDetected(tab, url)
                interceptNotice = url
            }
        }

        view.loadUrl(tab.url)
        tab.webView = view
        return view
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val PROBE_JS = """
            (function () {
              var out = [];
              function push(u) {
                if (u && u.indexOf('blob:') !== 0 && u.indexOf('data:') !== 0 && out.indexOf(u) < 0) out.push(u);
              }
              // Scan the page source itself. Many players (VK included) put the
              // stream URLs in an inline <script> rather than in a DOM element,
              // and the page reaches them fine because the browser is logged in
              // and not being reset — unlike a server-side fetch.
              function scan(text) {
                if (!text) return;
                var t = text.replace(/\\\//g, '/').replace(/\\u0026/gi, '&').replace(/&amp;/g, '&');
                if (t.length > 3000000) t = t.slice(0, 3000000);
                var re = /https?:\/\/[^\s"'<>\\]+?\.(?:mp4|m3u8|webm|m4v|mov)(?:\?[^\s"'<>\\]*)?/gi;
                var m;
                while ((m = re.exec(t)) !== null) push(m[0]);
              }
              try { scan(document.documentElement.innerHTML); } catch (e) {}

              document.querySelectorAll('video,audio,source,iframe,embed').forEach(function (el) {
                ['src', 'data-src', 'currentSrc'].forEach(function (attr) {
                  try { var v = el[attr]; if (v) push(v); } catch (e) {}
                });
              });
              document.querySelectorAll('meta[property^="og:video"]').forEach(function (m) {
                var c = m.getAttribute('content');
                if (c) push(c);
              });
              document.querySelectorAll('a[href]').forEach(function (a) {
                if (/\.(mp4|m3u8|webm|mkv|mov|m4a|mp3)(\?|#)/i.test(a.href)) push(a.href);
              });
              return JSON.stringify(out);
            })();
        """
    }
}
