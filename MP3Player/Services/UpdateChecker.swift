import Foundation

/// Prüft über GitHub-Releases, ob es eine neuere Version der App gibt.
///
/// Neue Versionen veröffentlicht der GitHub-Workflow automatisch. Maßgeblich ist die AltStore-Quelle am neuesten
/// Release: Sie nennt die neueste iPhone-Version – auch wenn das Release selbst eine ältere Nummer trägt
/// (solange Android pausiert, wird die iPhone-App ans neueste Release angehängt, damit Android kein Update sieht).
/// Installiert werden Updates über AltStore/SideStore (die App kann sich unter iOS nicht selbst ersetzen).
@MainActor
final class UpdateChecker: ObservableObject {
    nonisolated static let repository = "Backewatsch/MP3-Player"
    /// Dauerhafte Adresse der AltStore-/SideStore-Quelle (zeigt immer auf das neueste Release).
    nonisolated static let sourceURL = URL(string: "https://github.com/\(repository)/releases/latest/download/altstore-source.json")!
    static let releasesPage = URL(string: "https://github.com/\(repository)/releases")!

    struct Release: Equatable {
        let version: String
        let build: Int
        let notes: String
        let ipaURL: URL?
        let pageURL: URL
        let date: Date?
    }

    enum State: Equatable {
        case idle
        case checking
        case upToDate
        case available(Release)
        case noReleases
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var lastChecked: Date?

    private let session: URLSession
    private let defaults: UserDefaults
    private static let lastCheckKey = "updateLastCheck"

    init(session: URLSession = .shared, defaults: UserDefaults = .standard) {
        self.session = session
        self.defaults = defaults
        lastChecked = defaults.object(forKey: Self.lastCheckKey) as? Date
    }

    var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }

    var currentBuild: Int {
        Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
    }

    var availableRelease: Release? {
        if case .available(let release) = state { return release }
        return nil
    }

    /// Beim Start höchstens alle 6 Stunden automatisch prüfen.
    func checkIfDue() async {
        if let lastChecked, Date().timeIntervalSince(lastChecked) < 6 * 3600, state != .idle { return }
        await check()
    }

    func check() async {
        guard state != .checking else { return }
        state = .checking
        do {
            let release = try await latestRelease()
            markChecked()
            guard let release else {
                state = .noReleases
                return
            }
            state = Self.isNewer(release, thanBuild: currentBuild) ? .available(release) : .upToDate
        } catch let error as CheckError {
            markChecked()
            state = .failed(error.message)
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    private struct CheckError: Error {
        let message: String
    }

    private func markChecked() {
        lastChecked = Date()
        defaults.set(lastChecked, forKey: Self.lastCheckKey)
    }

    /// Neueste iPhone-Version; `nil`, wenn es noch kein Release gibt
    private func latestRelease() async throws -> Release? {
        // 1. Die AltStore-Quelle am neuesten Release nennt die neueste iPhone-Version
        if let data = try await load(Self.sourceURL), let release = Self.parseSource(data) {
            return release
        }
        // 2. Keine Quelle am Release: wie bisher über die Nummer des neuesten Release
        let api = URL(string: "https://api.github.com/repos/\(Self.repository)/releases/latest")!
        guard let data = try await load(api, accept: "application/vnd.github+json") else { return nil }
        guard let release = Self.parseRelease(data) else {
            throw CheckError(message: "Die Release-Daten konnten nicht gelesen werden.")
        }
        return release
    }

    /// Lädt eine Adresse; `nil` bei „nicht gefunden“ (404)
    private func load(_ url: URL, accept: String? = nil) async throws -> Data? {
        var request = URLRequest(url: url)
        if let accept { request.setValue(accept, forHTTPHeaderField: "Accept") }
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 20
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 404 { return nil }
        guard (200..<300).contains(status) else {
            throw CheckError(message: "GitHub antwortet mit Fehler \(status).")
        }
        return data
    }

    // MARK: Reine Logik (getestet)

    /// Liest die Antwort von `GET /repos/{owner}/{repo}/releases/latest`.
    nonisolated static func parseRelease(_ data: Data) -> Release? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tag = json["tag_name"] as? String,
              let build = buildNumber(fromTag: tag),
              let page = (json["html_url"] as? String).flatMap(URL.init(string:)) else { return nil }
        let assets = json["assets"] as? [[String: Any]] ?? []
        let ipa = assets.first { ($0["name"] as? String)?.lowercased().hasSuffix(".ipa") == true }
        let ipaURL = (ipa?["browser_download_url"] as? String).flatMap(URL.init(string:))
        let date = (json["published_at"] as? String).flatMap { ISO8601DateFormatter().date(from: $0) }
        let version = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
        return Release(version: version, build: build, notes: (json["body"] as? String) ?? "",
                       ipaURL: ipaURL, pageURL: page, date: date)
    }

    /// Liest die AltStore-/SideStore-Quelle (`altstore-source.json`): die neueste Version der ersten App.
    nonisolated static func parseSource(_ data: Data) -> Release? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let app = (json["apps"] as? [[String: Any]])?.first,
              let latest = (app["versions"] as? [[String: Any]])?.first,
              let version = latest["version"] as? String,
              let build = (latest["buildVersion"] as? String).flatMap({ Int($0) }) ?? buildNumber(fromTag: version),
              let page = URL(string: "https://github.com/\(repository)/releases") else { return nil }
        let ipaURL = (latest["downloadURL"] as? String).flatMap(URL.init(string:))
        let date = (latest["date"] as? String).flatMap { ISO8601DateFormatter().date(from: $0) }
        return Release(version: version, build: build, notes: (latest["localizedDescription"] as? String) ?? "",
                       ipaURL: ipaURL, pageURL: page, date: date)
    }

    /// `v1.0.42` → 42
    nonisolated static func buildNumber(fromTag tag: String) -> Int? {
        tag.split(separator: ".").last.flatMap { Int($0) }
    }

    nonisolated static func isNewer(_ release: Release, thanBuild build: Int) -> Bool {
        release.build > build
    }

    /// Deep-Link: Update direkt in AltStore installieren.
    nonisolated static func altStoreInstallURL(for ipa: URL) -> URL? {
        var components = URLComponents()
        components.scheme = "altstore"
        components.host = "install"
        components.queryItems = [URLQueryItem(name: "url", value: ipa.absoluteString)]
        return components.url
    }

    /// Deep-Link: diese App als Quelle in AltStore hinzufügen (danach meldet AltStore Updates selbst).
    nonisolated static func altStoreAddSourceURL() -> URL? {
        var components = URLComponents()
        components.scheme = "altstore"
        components.host = "source"
        components.queryItems = [URLQueryItem(name: "url", value: sourceURL.absoluteString)]
        return components.url
    }
}
