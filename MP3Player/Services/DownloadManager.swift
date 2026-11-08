import Foundation
import WebKit

/// Lädt Audiodateien herunter (aus dem internen Browser oder per Link) und übernimmt sie in die Bibliothek.
@MainActor
final class DownloadManager: NSObject, ObservableObject, WKDownloadDelegate {
    enum State: Equatable {
        case running
        case importing
        case finished
        case failed(String)
    }

    struct Item: Identifiable {
        let id: UUID
        var name: String
        var source: URL?
        var progress: Double
        var state: State
        let startedAt: Date
    }

    @Published private(set) var items: [Item] = []
    /// Zähler für neue, abgeschlossene Downloads (Badge im Tab).
    @Published var unseenCount = 0

    weak var library: Library?

    private final class Pending {
        let id: UUID
        var destination: URL?
        var failed = false
        var observation: NSKeyValueObservation?
        init(id: UUID) { self.id = id }
    }

    private var pendingWK: [ObjectIdentifier: Pending] = [:]
    private var pendingTasks: [UUID: NSKeyValueObservation] = [:]

    var activeCount: Int {
        items.filter { $0.state == .running || $0.state == .importing }.count
    }

    // MARK: Direkter Download per Link

    func download(from url: URL) {
        let id = UUID()
        items.insert(Item(id: id, name: url.lastPathComponent.isEmpty ? url.absoluteString : url.lastPathComponent,
                          source: url, progress: 0, state: .running, startedAt: Date()), at: 0)

        let task = URLSession.shared.downloadTask(with: url) { [weak self] tempURL, response, error in
            // Die temporäre Datei wird nach diesem Block gelöscht → hier sofort verschieben.
            var result: Result<URL, Error>
            if let error {
                result = .failure(error)
            } else if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                result = .failure(DownloadError.http(http.statusCode))
            } else if let tempURL,
                      let name = DownloadManager.audioFileName(suggested: response?.suggestedFilename,
                                                               mime: response?.mimeType,
                                                               url: response?.url ?? url) {
                let dest = Storage.makeIncomingDirectory().appendingPathComponent(name)
                do {
                    try FileManager.default.moveItem(at: tempURL, to: dest)
                    result = .success(dest)
                } catch {
                    result = .failure(error)
                }
            } else {
                result = .failure(DownloadError.notAudio(response?.mimeType))
            }
            let final = result
            Task { @MainActor in self?.finishDownload(id: id, result: final) }
        }
        pendingTasks[id] = task.progress.observe(\.fractionCompleted) { [weak self] progress, _ in
            let value = progress.fractionCompleted
            Task { @MainActor in self?.update(id) { $0.progress = value } }
        }
        task.resume()
    }

    // MARK: Downloads aus dem Browser (WKDownload)

    func track(_ download: WKDownload, source: URL?) {
        let id = UUID()
        let pending = Pending(id: id)
        pending.observation = download.progress.observe(\.fractionCompleted) { [weak self] progress, _ in
            let value = progress.fractionCompleted
            Task { @MainActor in self?.update(id) { $0.progress = value } }
        }
        pendingWK[ObjectIdentifier(download)] = pending
        download.delegate = self
        let name = source?.lastPathComponent ?? "Download"
        items.insert(Item(id: id, name: name.isEmpty ? "Download" : name, source: source,
                          progress: 0, state: .running, startedAt: Date()), at: 0)
    }

    func download(_ download: WKDownload,
                  decideDestinationUsing response: URLResponse,
                  suggestedFilename: String,
                  completionHandler: @escaping (URL?) -> Void) {
        guard let pending = pendingWK[ObjectIdentifier(download)] else {
            completionHandler(nil)
            return
        }
        guard let name = Self.audioFileName(suggested: suggestedFilename, mime: response.mimeType, url: response.url) else {
            pending.failed = true
            update(pending.id) { $0.state = .failed(DownloadError.notAudio(response.mimeType).localizedDescription) }
            cleanup(download)
            completionHandler(nil)
            return
        }
        let dest = Storage.makeIncomingDirectory().appendingPathComponent(name)
        pending.destination = dest
        update(pending.id) { $0.name = name }
        completionHandler(dest)
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let pending = pendingWK[ObjectIdentifier(download)] else { return }
        cleanup(download)
        if let dest = pending.destination {
            finishDownload(id: pending.id, result: .success(dest))
        }
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        guard let pending = pendingWK[ObjectIdentifier(download)] else { return }
        cleanup(download)
        if !pending.failed {
            finishDownload(id: pending.id, result: .failure(error))
        }
    }

    // MARK: Verwaltung

    func clearFinished() {
        items.removeAll { item in
            if case .running = item.state { return false }
            if case .importing = item.state { return false }
            return true
        }
    }

    func retry(_ item: Item) {
        guard let source = item.source else { return }
        items.removeAll { $0.id == item.id }
        download(from: source)
    }

    private func cleanup(_ download: WKDownload) {
        pendingWK[ObjectIdentifier(download)]?.observation?.invalidate()
        pendingWK[ObjectIdentifier(download)] = nil
    }

    private func finishDownload(id: UUID, result: Result<URL, Error>) {
        pendingTasks[id]?.invalidate()
        pendingTasks[id] = nil
        switch result {
        case .failure(let error):
            update(id) { $0.state = .failed(error.localizedDescription) }
        case .success(let fileURL):
            update(id) { $0.state = .importing; $0.progress = 1 }
            Task {
                do {
                    guard let library else { throw DownloadError.noLibrary }
                    let track = try await library.importAudio(from: fileURL, move: true, addToDownloads: true)
                    update(id) { $0.state = .finished; $0.name = track.title }
                    unseenCount += 1
                } catch {
                    update(id) { $0.state = .failed(error.localizedDescription) }
                }
                try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent())
            }
        }
    }

    private func update(_ id: UUID, _ change: (inout Item) -> Void) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        change(&items[i])
    }

    // MARK: Dateiname / Typ bestimmen

    private nonisolated static let mimeExtensions: [String: String] = [
        "audio/mpeg": "mp3", "audio/mp3": "mp3", "audio/mpeg3": "mp3", "audio/x-mpeg": "mp3", "audio/x-mp3": "mp3",
        "audio/mp4": "m4a", "audio/x-m4a": "m4a", "audio/m4a": "m4a", "audio/aac": "aac", "audio/aacp": "aac",
        "audio/wav": "wav", "audio/x-wav": "wav", "audio/wave": "wav", "audio/vnd.wave": "wav",
        "audio/aiff": "aiff", "audio/x-aiff": "aiff", "audio/flac": "flac", "audio/x-flac": "flac",
        "audio/x-caf": "caf"
    ]

    /// Liefert einen passenden Dateinamen mit Audio-Endung – oder `nil`, wenn es keine Audiodatei ist.
    nonisolated static func audioFileName(suggested: String?, mime: String?, url: URL?) -> String? {
        var name = suggested?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if name.isEmpty { name = url?.lastPathComponent ?? "" }
        if name.isEmpty || name == "/" { name = "Download" }
        name = Library.sanitize(name)

        if Storage.isAudioFile(name) { return name }

        let urlExt = url?.pathExtension.lowercased() ?? ""
        let base = (name as NSString).deletingPathExtension.isEmpty ? "Download" : (name as NSString).deletingPathExtension
        if let mime = mime?.lowercased(), let ext = mimeExtensions[mime] {
            return "\(base).\(ext)"
        }
        if Storage.audioExtensions.contains(urlExt), mime?.lowercased().hasPrefix("text/") != true {
            return "\(base).\(urlExt)"
        }
        return nil
    }
}

enum DownloadError: LocalizedError {
    case notAudio(String?)
    case http(Int)
    case noLibrary

    var errorDescription: String? {
        switch self {
        case .notAudio(let mime):
            return "Keine Audiodatei" + (mime.map { " (\($0))" } ?? "") + "."
        case .http(let code):
            return "Server-Fehler \(code) – \(HTTPURLResponse.localizedString(forStatusCode: code))."
        case .noLibrary:
            return "Bibliothek nicht verfügbar."
        }
    }
}
