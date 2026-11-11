import Foundation

/// Ein Ordner aus der Dateien-App, den die App nach Liedern durchsucht (einmal ausgewählt, dann bei jedem Start).
struct WatchedFolder: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    /// Dauerhafte Erlaubnis für den Ordner (Lesezeichen)
    var bookmark: Data
    /// Erledigte Dateien („/Unterordner/Lied.mp3|Größe“): übernommen oder schon in der App. Sie kommen nicht noch einmal –
    /// auch dann nicht, wenn das Lied in der App gelöscht wurde.
    var seen: Set<String> = []
    /// Fehlgeschlagene Versuche je Datei. Die nächste Suche versucht es erneut; nach drei Fehlschlägen gibt die App auf.
    var failures: [String: Int] = [:]

    init(name: String, bookmark: Data) {
        self.name = name
        self.bookmark = bookmark
    }

    enum CodingKeys: String, CodingKey {
        case id, name, bookmark, seen, failures
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        name = try c.decode(String.self, forKey: .name)
        bookmark = try c.decode(Data.self, forKey: .bookmark)
        seen = try c.decodeIfPresent(Set<String>.self, forKey: .seen) ?? []
        failures = try c.decodeIfPresent([String: Int].self, forKey: .failures) ?? [:]
    }
}

/// Durchsucht ausgewählte Ordner samt Unterordnern nach Liedern und übernimmt alle neuen in die Mediathek.
///
/// Das ganze iPhone auf einmal zu durchsuchen erlaubt iOS nicht: Jede App sieht nur Ordner, die man ihr freigibt.
/// Deshalb wählt man den Ordner einmal aus (z. B. „Auf meinem iPhone“); die Freigabe bleibt gespeichert.
@MainActor
final class FolderScanner: ObservableObject {
    /// Ergebnis einer Suche
    struct ScanResult: Equatable {
        var added = 0
        var failed = 0
    }

    /// Ergebnis von „Ordner hinzufügen“
    enum AddResult: Equatable {
        /// Ordner durchsucht
        case scanned(ScanResult)
        /// Es lief schon eine Suche – der neue Ordner kommt gleich mit dran
        case queued
        /// Ordner ließ sich nicht merken (mit Grund)
        case failed(String)
    }

    @Published private(set) var folders: [WatchedFolder] = []
    /// Fortschritt oder Ergebnis der letzten Suche (für die Anzeige)
    @Published private(set) var status: String?
    @Published private(set) var isScanning = false
    /// Gerade werden Lieder übernommen (nicht nur Ordner durchgesehen) – dann zeigt die Mediathek den Fortschritt
    @Published private(set) var isImporting = false

    weak var library: Library?
    private let storeURL: URL
    /// Schon übernommene Dateien aus allen Ordnern („Name|Größe“). Schützt vor doppelten Liedern, auch wenn ein Ordner
    /// entfernt und neu hinzugefügt wird, Ordner sich überschneiden oder die Datei beim Übernehmen umgewandelt wurde.
    private var imported: Set<String> = []
    private var lastScan = Date.distantPast
    /// Während einer Suche wurde ein Ordner hinzugefügt: danach noch eine Runde
    private var rescanRequested = false

    nonisolated static let storeName = "folders.json"

    private struct Store: Codable {
        var folders: [WatchedFolder]
        var imported: Set<String>
    }

    init(storeURL: URL = Storage.support.appendingPathComponent(FolderScanner.storeName)) {
        self.storeURL = storeURL
        guard let data = try? Data(contentsOf: storeURL) else { return }
        if let store = try? JSONDecoder().decode(Store.self, from: data) {
            folders = store.folders
            imported = store.imported
        } else if let saved = try? JSONDecoder().decode([WatchedFolder].self, from: data) {
            folders = saved
        }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(Store(folders: folders, imported: imported)) {
            try? data.write(to: storeURL, options: .atomic)
        }
    }

    /// Eine Datei im Ordner
    struct FoundFile: Sendable {
        let url: URL
        /// Erkennungszeichen: Pfad im Ordner und Größe
        let key: String
        let size: Int
        /// Erkennungszeichen über alle Ordner hinweg: Dateiname und Größe
        var identity: String { "\(url.lastPathComponent)|\(size)" }
    }

    /// Pfad ohne Verknüpfungen und ohne Schrägstrich am Ende – zum Vergleichen zweier Ordner
    nonisolated static func canonicalPath(_ url: URL) -> String {
        url.resolvingSymlinksInPath().standardizedFileURL.path
    }

    /// Alle Musikdateien im Ordner und in seinen Unterordnern, nach Pfad sortiert.
    /// Versteckte Dateien, Pakete und der eigene Musikordner der App bleiben außen vor.
    nonisolated static func audioFiles(in folder: URL) -> [FoundFile] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        guard let enumerator = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: keys,
                                                              options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { return [] }
        let base = canonicalPath(folder)
        let own = canonicalPath(Storage.documents)
        var result: [FoundFile] = []
        for case let url as URL in enumerator {
            guard Storage.isAudioFile(url.lastPathComponent),
                  let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true,
                  let size = values.fileSize, size > 0 else { continue }
            // Die Lieder der App selbst nicht noch einmal übernehmen (z. B. wenn „Auf meinem iPhone“ gewählt wurde)
            var relative = canonicalPath(url)
            if relative.hasPrefix(own + "/") { continue }
            if relative.hasPrefix(base + "/") { relative = String(relative.dropFirst(base.count)) }
            result.append(FoundFile(url: url, key: "\(relative)|\(size)", size: size))
        }
        return result.sorted { $0.key.localizedStandardCompare($1.key) == .orderedAscending }
    }

    /// Merkt sich einen neuen Ordner und durchsucht ihn sofort.
    @discardableResult
    func add(_ url: URL) async -> AddResult {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        guard let bookmark = try? url.bookmarkData() else {
            let reason = "Der Ordner „\(url.lastPathComponent)“ lässt sich nicht merken."
            status = reason
            return .failed(reason)
        }
        let path = Self.canonicalPath(url)
        let known = folders.contains { folder in
            var stale = false
            let resolved = try? URL(resolvingBookmarkData: folder.bookmark, bookmarkDataIsStale: &stale)
            return resolved.map(Self.canonicalPath) == path
        }
        if !known {
            let name = url.lastPathComponent.isEmpty ? "Ordner" : url.lastPathComponent
            folders.append(WatchedFolder(name: name, bookmark: bookmark))
            persist()
        }
        guard let result = await scan(force: true) else { return .queued }
        return .scanned(result)
    }

    func remove(_ folder: WatchedFolder) {
        folders.removeAll { $0.id == folder.id }
        persist()
        if folders.isEmpty { status = nil }
    }

    /// Durchsucht alle gemerkten Ordner und übernimmt neue Lieder.
    /// - Parameter force: `false` = höchstens einmal pro Minute (Start der App, Rückkehr in die App)
    /// - Returns: `nil`, wenn schon eine Suche läuft (mit `force` nimmt sie danach alle Ordner noch einmal durch)
    @discardableResult
    func scan(force: Bool = false) async -> ScanResult? {
        if isScanning {
            if force { rescanRequested = true }
            return nil
        }
        guard let library, !folders.isEmpty else { return ScanResult() }
        if !force, Date().timeIntervalSince(lastScan) < 60 { return ScanResult() }
        isScanning = true
        status = nil
        defer {
            isScanning = false
            isImporting = false
        }

        var result = ScanResult()
        var unreachable: [String] = []
        repeat {
            rescanRequested = false
            lastScan = Date()
            for folder in folders {
                // Inzwischen entfernt?
                guard folders.contains(where: { $0.id == folder.id }) else { continue }
                var stale = false
                guard let url = try? URL(resolvingBookmarkData: folder.bookmark, bookmarkDataIsStale: &stale) else {
                    if !unreachable.contains(folder.name) { unreachable.append(folder.name) }
                    continue
                }
                let accessing = url.startAccessingSecurityScopedResource()
                if stale, let fresh = try? url.bookmarkData() { update(folder.id) { $0.bookmark = fresh } }
                await scan(folder, at: url, library: library, into: &result)
                if accessing { url.stopAccessingSecurityScopedResource() }
            }
        } while rescanRequested

        var parts: [String] = []
        parts.append(result.added == 0 ? "Keine neuen Lieder gefunden." : "✓ \(result.added) neue Lieder übernommen.")
        if result.failed > 0 { parts.append("\(result.failed) Dateien ließen sich nicht übernehmen.") }
        if !unreachable.isEmpty { parts.append("Nicht erreichbar: \(unreachable.joined(separator: ", ")) – bitte neu hinzufügen.") }
        status = parts.joined(separator: " ")
        return result
    }

    /// Übernimmt die neuen Lieder eines Ordners
    private func scan(_ folder: WatchedFolder, at url: URL, library: Library, into result: inout ScanResult) async {
        let found = await Task.detached(priority: .utility) { FolderScanner.audioFiles(in: url) }.value
        let new = found.filter { !folder.seen.contains($0.key) }
        guard !new.isEmpty else { return }
        // Dateinamen der App einmal nachschlagen statt bei jeder Datei die ganze Liste zu durchsuchen
        var namesInApp = Set(library.tracks.map(\.fileName))
        // Erledigte Dateien sammeln und gebündelt eintragen – das hält die Oberfläche auch bei tausenden Dateien flüssig
        var done: [String] = []
        var failures: [String: Int] = [:]
        func flush() {
            guard !done.isEmpty || !failures.isEmpty else { return }
            let finished = done
            let failed = failures
            update(folder.id) { entry in
                entry.seen.formUnion(finished)
                for key in finished { entry.failures[key] = nil }
                for (key, count) in failed { entry.failures[key] = count }
            }
            done.removeAll()
            failures.removeAll()
            persist()
        }

        for (n, file) in new.enumerated() {
            // Ordner während der Suche entfernt: nichts mehr daraus übernehmen
            guard let current = folders.first(where: { $0.id == folder.id }) else { break }
            if imported.contains(file.identity) || Self.isInApp(file, names: namesInApp) {
                done.append(file.key)
                if done.count >= 50 {
                    flush()
                    await Task.yield()
                }
                continue
            }
            isImporting = true
            status = "Übernehme Lied \(n + 1) von \(new.count) aus „\(folder.name)“ …"
            do {
                let track = try await library.importAudio(from: file.url, move: false)
                namesInApp.insert(track.fileName)
                imported.insert(file.identity)
                done.append(file.key)
                result.added += 1
            } catch {
                result.failed += 1
                let tries = (current.failures[file.key] ?? 0) + 1
                // Kaputte Dateien nicht bei jedem Start erneut versuchen
                if tries >= 3 { done.append(file.key) } else { failures[file.key] = tries }
            }
            // Gleich sichern: So kommt nach einem Abbruch der App nichts doppelt
            flush()
        }
        flush()
    }

    /// Liegt genau diese Datei (gleicher Name, gleiche Größe) schon im Musikordner der App?
    private static func isInApp(_ file: FoundFile, names: Set<String>) -> Bool {
        let name = Library.sanitize(file.url.lastPathComponent)
        guard names.contains(name) else { return false }
        let attributes = try? FileManager.default.attributesOfItem(atPath: Storage.documents.appendingPathComponent(name).path)
        return (attributes?[.size] as? NSNumber)?.intValue == file.size
    }

    private func update(_ id: UUID, _ change: (inout WatchedFolder) -> Void) {
        guard let index = folders.firstIndex(where: { $0.id == id }) else { return }
        change(&folders[index])
    }
}
