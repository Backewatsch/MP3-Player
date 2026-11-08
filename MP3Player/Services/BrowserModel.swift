import Foundation
import UIKit
import WebKit

/// Steuert den internen Browser und leitet Audio-Downloads an den `DownloadManager` weiter.
@MainActor
final class BrowserModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    let webView: WKWebView

    @Published var address = ""
    @Published private(set) var canGoBack = false
    @Published private(set) var canGoForward = false
    @Published private(set) var isLoading = false
    @Published private(set) var progress: Double = 0
    @Published private(set) var currentHost: String?
    @Published var notice: String?
    @Published var foundAudio: [URL] = []
    @Published var showFoundAudio = false

    /// Medium, das gerade auf der Seite abgespielt wird
    enum DetectedMedia: Equatable {
        case downloadable(URL)
        /// Stream (blob:/MediaSource, z. B. YouTube) – lässt sich nicht als Datei laden
        case stream
    }
    @Published var detectedMedia: DetectedMedia?
    /// Solange der Nutzer tippt, wird die Adresszeile nicht überschrieben.
    var isEditingAddress = false {
        didSet { if !isEditingAddress { syncState(updateAddress: true) } }
    }

    weak var downloads: DownloadManager?
    private var observations: [NSKeyValueObservation] = []

    /// Auf YouTube wird kein Download angeboten (siehe README).
    /// Seitentitel als Vorschlag für den Playlist-Namen
    var pageTitle: String {
        let title = webView.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return title.isEmpty ? (currentHost ?? "Neue Playlist") : String(title.prefix(60))
    }

    var isYouTube: Bool {
        guard let host = currentHost?.lowercased() else { return false }
        return host == "youtu.be" || host.hasSuffix("youtube.com") || host.hasSuffix("youtube-nocookie.com")
    }

    override init() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.websiteDataStore = .default()
        // Meldet, wenn auf einer Seite (auch in eingebetteten Frames) ein Audio/Video startet
        let handler = MediaMessageHandler()
        config.userContentController.add(handler, name: "mediaPlaying")
        config.userContentController.addUserScript(WKUserScript(source: Self.mediaWatcherScript,
                                                                injectionTime: .atDocumentEnd,
                                                                forMainFrameOnly: false))
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        handler.browser = self
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true

        observations = [
            webView.observe(\.canGoBack, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in self?.syncState() }
            },
            webView.observe(\.canGoForward, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in self?.syncState() }
            },
            webView.observe(\.isLoading, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in self?.syncState() }
            },
            webView.observe(\.estimatedProgress, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in self?.syncState() }
            },
            webView.observe(\.url, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in self?.syncState(updateAddress: true) }
            }
        ]
        loadStartPage()
    }

    // MARK: Navigation

    func submitAddress() {
        let text = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        if let url = Self.url(fromInput: text) {
            webView.load(URLRequest(url: url))
        }
    }

    func goBack() { webView.goBack() }
    func goForward() { webView.goForward() }
    func reload() {
        if isLoading { webView.stopLoading() } else { webView.reload() }
    }

    func loadStartPage() {
        webView.loadHTMLString(StartPage.html, baseURL: URL(string: "about:blank"))
        address = ""
    }

    /// Die aktuell geöffnete Adresse direkt als Audiodatei laden.
    func downloadCurrentPage() {
        guard let url = webView.url, url.scheme?.hasPrefix("http") == true else {
            notice = "Öffne zuerst eine Seite bzw. einen Link zu einer Audiodatei."
            return
        }
        startDownload(url)
    }

    /// Lädt über die Browser-Sitzung herunter (Cookies/Anmeldung der Seite bleiben erhalten).
    func startDownload(_ url: URL, into playlistID: UUID? = nil) {
        var request = URLRequest(url: url)
        if let referer = webView.url, referer.scheme?.hasPrefix("http") == true {
            request.setValue(referer.absoluteString, forHTTPHeaderField: "Referer")
        }
        webView.startDownload(using: request) { [weak self] download in
            self?.downloads?.track(download, source: url, into: playlistID)
        }
    }

    /// Wird vom Seitenskript aufgerufen, wenn ein Medium startet.
    func mediaStartedPlaying(source: String) {
        if let url = URL(string: source), let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
            detectedMedia = .downloadable(url)
        } else {
            detectedMedia = .stream
        }
    }

    /// Lädt das erkannte Medium herunter (wird umgewandelt und landet in der Wiedergabeliste).
    func downloadDetectedMedia() {
        guard case .downloadable(let url) = detectedMedia else { return }
        startDownload(url)
        detectedMedia = nil
    }

    private static let mediaWatcherScript = """
    (function() {
      if (window.__mp3PlayerWatcher) return;
      window.__mp3PlayerWatcher = true;
      document.addEventListener('play', function(e) {
        var el = e.target;
        if (!el || (el.tagName !== 'AUDIO' && el.tagName !== 'VIDEO')) return;
        try {
          window.webkit.messageHandlers.mediaPlaying.postMessage({ src: el.currentSrc || el.src || '' });
        } catch (err) {}
      }, true);
    })();
    """

    /// Sucht Audio-Elemente und Links auf Audiodateien auf der aktuellen Seite.
    /// - Parameter silent: ohne Meldung/Dialog (automatische Suche nach dem Laden einer Seite)
    func scanForAudio(silent: Bool = false) {
        let js = """
        (function() {
          var re = /\\.(mp3|m4a|aac|wav|flac|aiff?|caf|mp4|mov|m4v)([?#]|$)/i;
          var out = [];
          var add = function(u) { if (u && /^https?:/i.test(u) && out.indexOf(u) < 0) out.push(u); };
          document.querySelectorAll('audio').forEach(function(a) { add(a.currentSrc || a.src); });
          document.querySelectorAll('audio source[src], video source[src]').forEach(function(s) { add(s.src); });
          document.querySelectorAll('video').forEach(function(v) { add(v.currentSrc || v.src); });
          document.querySelectorAll('a[href]').forEach(function(a) { if (re.test(a.href)) add(a.href); });
          return out.slice(0, 200);
        })();
        """
        webView.evaluateJavaScript(js) { [weak self] result, _ in
            let strings = (result as? [String]) ?? []
            let urls = strings.compactMap(URL.init(string:))
            Task { @MainActor in
                guard let self else { return }
                self.foundAudio = urls
                guard !silent else { return }
                if urls.isEmpty {
                    self.notice = "Auf dieser Seite wurden keine herunterladbaren Audio- oder Videodateien gefunden."
                } else {
                    self.showFoundAudio = true
                }
            }
        }
    }

    private func syncState(updateAddress: Bool = false) {
        canGoBack = webView.canGoBack
        canGoForward = webView.canGoForward
        isLoading = webView.isLoading
        progress = webView.estimatedProgress
        let url = webView.url
        currentHost = url?.host
        if updateAddress && !isEditingAddress {
            if let url, url.scheme?.hasPrefix("http") == true {
                address = url.absoluteString
            } else {
                address = ""
            }
        }
    }

    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if navigationAction.shouldPerformDownload {
            decisionHandler(.download)
            return
        }
        if let url = navigationAction.request.url,
           navigationAction.targetFrame?.isMainFrame != false,
           Storage.isImportable(url.lastPathComponent) {
            // Link auf eine Audio-/Videodatei → herunterladen (Videos werden umgewandelt)
            decisionHandler(.download)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard navigationResponse.isForMainFrame else {
            decisionHandler(.allow)
            return
        }
        let response = navigationResponse.response
        let mime = response.mimeType?.lowercased() ?? ""
        let disposition = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Disposition")?.lowercased() ?? ""
        let isAudio = mime.hasPrefix("audio/") || mime.hasPrefix("video/")
            || DownloadManager.audioFileName(suggested: response.suggestedFilename, mime: mime, url: response.url) != nil
                && !mime.hasPrefix("text/")
        if isAudio || disposition.hasPrefix("attachment") || !navigationResponse.canShowMIMEType {
            decisionHandler(.download)
        } else {
            decisionHandler(.allow)
        }
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        downloads?.track(download, source: navigationAction.request.url)
        notice = "Download gestartet – der Titel landet automatisch in der Playlist „Heruntergeladen“."
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        downloads?.track(download, source: navigationResponse.response.url)
        notice = "Download gestartet – der Titel landet automatisch in der Playlist „Heruntergeladen“."
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        detectedMedia = nil
        foundAudio = []
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        // Seite automatisch nach Audio-/Videodateien durchsuchen (Zähler am Wellen-Symbol)
        scanForAudio(silent: true)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        handle(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        handle(error)
    }

    private func handle(_ error: Error) {
        let nsError = error as NSError
        // -999 = abgebrochen, 102 = „Frame load interrupted“ (passiert beim Umwandeln in einen Download)
        if nsError.code == NSURLErrorCancelled || (nsError.domain == "WebKitErrorDomain" && nsError.code == 102) { return }
        notice = error.localizedDescription
    }

    // MARK: WKUIDelegate

    /// Links mit target="_blank" im selben Fenster öffnen.
    func webView(_ webView: WKWebView,
                 createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil {
            webView.load(navigationAction.request)
        }
        return nil
    }

    /// Lange auf einen Link drücken → „Als Audio herunterladen“.
    func webView(_ webView: WKWebView,
                 contextMenuConfigurationForElement elementInfo: WKContextMenuElementInfo,
                 completionHandler: @escaping (UIContextMenuConfiguration?) -> Void) {
        guard let url = elementInfo.linkURL, url.scheme?.hasPrefix("http") == true else {
            completionHandler(nil)
            return
        }
        let configuration = UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] suggested in
            let action = UIAction(title: "Als Audio herunterladen", image: UIImage(systemName: "arrow.down.circle")) { _ in
                Task { @MainActor in self?.startDownload(url) }
            }
            return UIMenu(title: "", children: [action] + suggested)
        }
        completionHandler(configuration)
    }

    // MARK: Hilfsfunktionen

    static func url(fromInput text: String) -> URL? {
        if let url = URL(string: text), let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme) {
            return url
        }
        if !text.contains(" "), text.contains("."), let url = URL(string: "https://" + text) {
            return url
        }
        var components = URLComponents(string: "https://duckduckgo.com/")!
        components.queryItems = [URLQueryItem(name: "q", value: text)]
        return components.url
    }
}

/// Leitet Nachrichten des Seitenskripts weiter, ohne den Browser festzuhalten (vermeidet einen Speicherzyklus).
@MainActor
private final class MediaMessageHandler: NSObject, WKScriptMessageHandler {
    weak var browser: BrowserModel?

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        let source = (message.body as? [String: Any])?["src"] as? String ?? ""
        browser?.mediaStartedPlaying(source: source)
    }
}

enum StartPage {
    static let html = """
    <!doctype html>
    <html lang="de"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      :root { color-scheme: light dark; }
      body { font: -apple-system-body; font-family: -apple-system, sans-serif; margin: 0; padding: 20px 16px 40px;
             background: Canvas; color: CanvasText; }
      h1 { font-size: 26px; margin: 8px 0 4px; }
      p { color: GrayText; line-height: 1.4; margin: 6px 0 18px; }
      a.card { display: block; padding: 14px 16px; margin: 10px 0; border-radius: 14px; text-decoration: none;
               color: inherit; background: rgba(127,127,127,.12); }
      a.card b { display: block; font-size: 17px; }
      a.card span { font-size: 14px; color: GrayText; }
      .hint { font-size: 14px; padding: 12px 14px; border-radius: 12px; background: rgba(250,46,102,.12); margin-top: 22px; }
    </style></head>
    <body>
      <h1>🎵 Musik finden</h1>
      <p>Tippe auf einen Link zu einer Audio- oder Videodatei – oder starte ein Audio/Video auf einer Seite und tippe auf <b>„Als Audio laden“</b>. Alles wird automatisch in MP3 umgewandelt. Jeder Titel landet in der Playlist „Heruntergeladen“ und in deiner Wiedergabeliste.</p>
      <a class="card" href="https://archive.org/details/audio"><b>Internet Archive</b><span>Millionen freie Aufnahmen, Live-Konzerte, Hörbücher</span></a>
      <a class="card" href="https://freemusicarchive.org/"><b>Free Music Archive</b><span>Kostenlose, legal nutzbare Musik</span></a>
      <a class="card" href="https://www.jamendo.com/"><b>Jamendo</b><span>Unabhängige Künstler unter Creative Commons</span></a>
      <a class="card" href="https://ccmixter.org/"><b>ccMixter</b><span>Remixe und Samples unter CC-Lizenz</span></a>
      <a class="card" href="https://incompetech.com/music/royalty-free/music.html"><b>Incompetech</b><span>Lizenzfreie Musik von Kevin MacLeod</span></a>
      <a class="card" href="https://librivox.org/"><b>LibriVox</b><span>Gemeinfreie Hörbücher als MP3</span></a>
      <div class="hint">Tipp: Das Wellen-Symbol unten zeigt, wie viele Dateien auf einer Seite gefunden wurden – mit „Alle als neue Playlist laden“ holst du sie auf einmal.</div>
    </body></html>
    """
}
