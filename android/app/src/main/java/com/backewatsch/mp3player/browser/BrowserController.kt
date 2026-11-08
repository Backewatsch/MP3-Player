package com.backewatsch.mp3player.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import com.backewatsch.mp3player.data.FileNames
import com.backewatsch.mp3player.download.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Interner Browser: erkennt Audio/Video, durchsucht Seiten und leitet Downloads weiter. */
class BrowserController(context: Context, private val downloads: DownloadRepository) {
    sealed interface Detected {
        data class Downloadable(val url: String) : Detected
        data object Stream : Detected
    }

    data class UiState(
        val url: String = "",
        val host: String? = null,
        val title: String = "",
        val loading: Boolean = false,
        val progress: Int = 0,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val found: List<String> = emptyList(),
        val detected: Detected? = null,
        val notice: String? = null,
        /** Letzter Seitenaufruf scheiterte, weil kein Internet da war */
        val offline: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportMultipleWindows(false)
        downloads.userAgent = settings.userAgentString
        addJavascriptInterface(Bridge(), "MP3PlayerBridge")
        webViewClient = Client()
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _state.value = _state.value.copy(progress = newProgress, loading = newProgress < 100)
            }
            override fun onReceivedTitle(view: WebView, title: String?) {
                _state.value = _state.value.copy(title = title.orEmpty())
            }
        }
        // Dateien, die der Browser nicht anzeigen kann (Content-Disposition: attachment, Audio, Video …)
        setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            if (FileNames.mediaFileName(name, mimetype, url) != null) {
                startDownload(url, suggestedName = FileNames.mediaFileName(name, mimetype, url), mime = mimetype)
            } else {
                notify(FileNames.notMediaMessage(mimetype))
            }
        }
    }

    init {
        loadStartPage()
    }

    // region Navigation

    fun submit(input: String) {
        if (input.isBlank()) return
        webView.loadUrl(FileNames.urlFromInput(input))
    }

    fun back() { if (webView.canGoBack()) webView.goBack() }
    fun forward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() { if (_state.value.loading) webView.stopLoading() else webView.reload() }

    fun loadStartPage() {
        webView.loadDataWithBaseURL("https://start.mp3player.local/", StartPage.HTML, "text/html", "utf-8", null)
    }

    // endregion

    fun startDownload(url: String, playlistId: String? = null, suggestedName: String? = null, mime: String? = null) {
        downloads.download(url, playlistId, suggestedName, mime)
        notify("Download gestartet – wird zu MP3 und landet in „Heruntergeladen“ und in der Wiedergabeliste.")
    }

    fun downloadDetected() {
        val d = _state.value.detected as? Detected.Downloadable ?: return
        startDownload(d.url)
        _state.value = _state.value.copy(detected = null)
    }

    fun dismissDetected() { _state.value = _state.value.copy(detected = null) }
    fun notify(text: String?) { _state.value = _state.value.copy(notice = text) }

    /** Seite nach Audio-/Videodateien durchsuchen */
    fun scan(onResult: ((List<String>) -> Unit)? = null) {
        webView.evaluateJavascript(SCAN_SCRIPT) { result ->
            val urls = runCatching {
                val arr = JSONArray(result ?: "[]")
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList())
            _state.value = _state.value.copy(found = urls)
            onResult?.invoke(urls)
        }
    }

    /** Wird aufgerufen, wenn auf der Seite ein Medium startet */
    fun mediaStartedPlaying(src: String) {
        val detected = if (src.startsWith("http://") || src.startsWith("https://")) Detected.Downloadable(src) else Detected.Stream
        _state.value = _state.value.copy(detected = detected)
    }

    private fun syncNavigation(url: String?) {
        val shown = url?.takeIf { it.startsWith("http") && !it.contains("start.mp3player.local") }.orEmpty()
        _state.value = _state.value.copy(
            url = shown,
            host = android.net.Uri.parse(shown).host,
            canGoBack = webView.canGoBack(),
            canGoForward = webView.canGoForward(),
        )
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val path = request.url.path.orEmpty()
            if (request.isForMainFrame && FileNames.isImportable(path.substringAfterLast('/'))) {
                // Link auf eine Audio-/Videodatei → herunterladen und umwandeln
                startDownload(url)
                return true
            }
            if (!url.startsWith("http")) return true // keine fremden App-Links öffnen
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            val offlinePage = url?.contains("start.mp3player.local/offline") == true
            _state.value = _state.value.copy(loading = true, found = emptyList(), detected = null,
                offline = offlinePage && _state.value.offline)
            syncNavigation(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            _state.value = _state.value.copy(loading = false)
            syncNavigation(url)
            view.evaluateJavascript(WATCH_SCRIPT, null)
            scan()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = syncNavigation(url)

        /** Ohne Internet: freundliche Offline-Seite statt Fehlerseite */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (!request.isForMainFrame) return
            val offline = error.errorCode in setOf(ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO)
            val html = if (offline) StartPage.OFFLINE else StartPage.error(error.description?.toString().orEmpty())
            view.loadDataWithBaseURL("https://start.mp3player.local/offline", html, "text/html", "utf-8", request.url.toString())
            _state.value = _state.value.copy(offline = offline)
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun mediaPlaying(src: String?) {
            main.post { mediaStartedPlaying(src.orEmpty()) }
        }
    }

    companion object {
        private const val WATCH_SCRIPT = """
            (function() {
              if (window.__mp3PlayerWatcher) return;
              window.__mp3PlayerWatcher = true;
              document.addEventListener('play', function(e) {
                var el = e.target;
                if (!el || (el.tagName !== 'AUDIO' && el.tagName !== 'VIDEO')) return;
                try { MP3PlayerBridge.mediaPlaying(el.currentSrc || el.src || ''); } catch (err) {}
              }, true);
            })();
        """

        private const val SCAN_SCRIPT = """
            (function() {
              var re = /\.(mp3|m4a|aac|wav|flac|ogg|oga|opus|mp4|m4v|mov|webm|mkv)([?#]|$)/i;
              var out = [];
              var add = function(u) { if (u && /^https?:/i.test(u) && out.indexOf(u) < 0) out.push(u); };
              document.querySelectorAll('audio, video').forEach(function(m) { add(m.currentSrc || m.src); });
              document.querySelectorAll('audio source[src], video source[src]').forEach(function(s) { add(s.src); });
              document.querySelectorAll('a[href]').forEach(function(a) { if (re.test(a.href)) add(a.href); });
              return out.slice(0, 200);
            })();
        """
    }
}

object StartPage {
    const val OFFLINE = """<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{font-family:monospace;margin:0;padding:48px 24px;background:#07060b;color:#cfc9de;text-align:center}
h1{font-size:22px;letter-spacing:2px;text-transform:uppercase;text-shadow:-1px 0 rgba(79,175,187,.5),1px 0 rgba(194,80,159,.5)}
p{color:#7e7898;line-height:1.5} .err{color:#d9566b;letter-spacing:2px}
.ok{margin-top:28px;padding:14px;background:#0e0c15;border:1px solid rgba(79,175,187,.28);color:#4fafbb}</style>
</head><body><div class="err">&gt; ERROR 0x00 // NO SIGNAL</div><h1>Keine Internetverbindung</h1>
<p>Zum Herunterladen brauchst du Internet. Sobald du wieder online bist, tippe auf „Neu laden“.</p>
<div class="ok">🎵 Deine Musik in der <b>Mediathek</b> funktioniert auch offline.</div></body></html>"""

    fun error(message: String) = """<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{font-family:sans-serif;margin:0;padding:48px 24px;background:#000;color:#fff;text-align:center} p{color:#aaa}</style>
</head><body><h1>Seite nicht erreichbar</h1><p>${android.text.TextUtils.htmlEncode(message)}</p></body></html>"""

    const val HTML = """<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
 body{font-family:monospace;margin:0;padding:22px 16px 40px;color:#cfc9de;
   background:#07060b linear-gradient(rgba(79,175,187,.03) 1px,transparent 1px) 0 0/40px 40px,
   linear-gradient(90deg,rgba(79,175,187,.03) 1px,transparent 1px) 0 0/40px 40px}
 .sys{color:#c8b85a;font-size:13px;letter-spacing:1px}
 h1{font-size:28px;margin:6px 0 4px;letter-spacing:3px;text-transform:uppercase;color:#dcd6ea;
   text-shadow:-1px 0 rgba(79,175,187,.5),1px 0 rgba(194,80,159,.5)}
 p{color:#7e7898;line-height:1.5;margin:6px 0 18px}
 a.card{display:block;padding:14px 16px;margin:12px 0;text-decoration:none;color:inherit;background:#0e0c15;
   border:1px solid rgba(79,175,187,.28);clip-path:polygon(14px 0,100% 0,100% calc(100% - 14px),calc(100% - 14px) 100%,0 100%,0 14px)}
 a.card b{display:block;font-size:16px;color:#4fafbb;letter-spacing:1px;text-transform:uppercase}
 a.card span{font-size:13px;color:#7e7898}
 .hint{font-size:13px;padding:12px 14px;border-left:3px solid #c2509f;background:rgba(194,80,159,.07);margin-top:22px}
</style></head><body>
<div class="sys">&gt; SYSTEM ONLINE_ // NETRUNNER-MODUS</div>
<h1>Musik finden</h1>
<p>Tippe auf einen Link zu einer Audio- oder Videodatei – oder starte ein Audio/Video auf einer Seite und tippe auf <b>„Als Audio laden“</b>. Alles wird automatisch in MP3 umgewandelt und landet in „Heruntergeladen“ und in deiner Wiedergabeliste.</p>
<a class="card" href="https://archive.org/details/audio"><b>Internet Archive</b><span>Millionen freie Aufnahmen, Live-Konzerte, Hörbücher</span></a>
<a class="card" href="https://freemusicarchive.org/"><b>Free Music Archive</b><span>Kostenlose, legal nutzbare Musik</span></a>
<a class="card" href="https://www.jamendo.com/"><b>Jamendo</b><span>Unabhängige Künstler unter Creative Commons</span></a>
<a class="card" href="https://ccmixter.org/"><b>ccMixter</b><span>Remixe und Samples unter CC-Lizenz</span></a>
<a class="card" href="https://incompetech.com/music/royalty-free/music.html"><b>Incompetech</b><span>Lizenzfreie Musik von Kevin MacLeod</span></a>
<a class="card" href="https://librivox.org/"><b>LibriVox</b><span>Gemeinfreie Hörbücher als MP3</span></a>
<div class="hint">Tipp: Das Wellen-Symbol unten zeigt, wie viele Dateien auf einer Seite gefunden wurden – mit „Alle als neue Playlist laden“ holst du sie auf einmal.</div>
</body></html>"""
}
