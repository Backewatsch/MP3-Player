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
    /// Solange der Nutzer tippt, wird die Adresszeile nicht überschrieben.
    var isEditingAddress = false {
        didSet { if !isEditingAddress { syncState(updateAddress: true) } }
    }

    weak var downloads: DownloadManager?
    private var observations: [NSKeyValueObservation] = []

    /// Auf YouTube wird kein Download angeboten (siehe README).
    var isYouTube: Bool {
        guard let host = currentHost?.lowercased() else { return false }
        return host == "youtu.be" || host.hasSuffix("youtube.com") || host.hasSuffix("youtube-nocookie.com")
    }

    override init() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.websiteDataStore = .default()
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
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
    func startDownload(_ url: URL) {
        var request = URLRequest(url: url)
        if let referer = webView.url, referer.scheme?.hasPrefix("http") == true {
            request.setValue(referer.absoluteString, forHTTPHeaderField: "Referer")
        }
        webView.startDownload(using: request) { [weak self] download in
            self?.downloads?.track(download, source: url)
        }
    }

    /// Sucht Audio-Elemente und Links auf Audiodateien auf der aktuellen Seite.
    func scanForAudio() {
        let js = """
        (function() {
          var re = /\\.(mp3|m4a|aac|wav|flac|aiff?|caf)([?#]|$)/i;
          var out = [];
          var add = function(u) { if (u && /^https?:/i.test(u) && out.indexOf(u) < 0) out.push(u); };
          document.querySelectorAll('audio').forEach(function(a) { add(a.currentSrc || a.src); });
          document.querySelectorAll('audio source[src]').forEach(function(s) { add(s.src); });
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
                if urls.isEmpty {
                    self.notice = "Auf dieser Seite wurden keine Audiodateien gefunden."
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
           Storage.audioExtensions.contains(url.pathExtension.lowercased()) {
            // Link auf eine Audiodatei → herunterladen statt im Browser abspielen
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
        let isAudio = mime.hasPrefix("audio/")
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
      <p>Tippe auf einen Link zu einer MP3-Datei (oder drücke lange darauf → „Als Audio herunterladen“). Der Titel landet automatisch in deiner Playlist „Heruntergeladen“.</p>
      <a class="card" href="https://archive.org/details/audio"><b>Internet Archive</b><span>Millionen freie Aufnahmen, Live-Konzerte, Hörbücher</span></a>
      <a class="card" href="https://freemusicarchive.org/"><b>Free Music Archive</b><span>Kostenlose, legal nutzbare Musik</span></a>
      <a class="card" href="https://www.jamendo.com/"><b>Jamendo</b><span>Unabhängige Künstler unter Creative Commons</span></a>
      <a class="card" href="https://ccmixter.org/"><b>ccMixter</b><span>Remixe und Samples unter CC-Lizenz</span></a>
      <a class="card" href="https://incompetech.com/music/royalty-free/music.html"><b>Incompetech</b><span>Lizenzfreie Musik von Kevin MacLeod</span></a>
      <a class="card" href="https://librivox.org/"><b>LibriVox</b><span>Gemeinfreie Hörbücher als MP3</span></a>
      <div class="hint">Tipp: Über das Menü <b>⋯</b> oben kannst du eine Seite nach Audiodateien durchsuchen lassen.</div>
    </body></html>
    """
}
