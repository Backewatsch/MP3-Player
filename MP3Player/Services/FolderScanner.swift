import Foundation

/// Ein Ordner aus der Dateien-App, den die App nach Liedern durchsucht (einmal ausgewählt, dann bei jedem Start).
struct WatchedFolder: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    /// Dauerhafte Erlaubnis für den Ordner (Lesezeichen)
    var bookmark: Data
    /// Schon übernommene Dateien („/Unterordner/Lied.mp3|Größe“). Sie kommen nicht noch einmal –
    /// auch dann nicht, wenn das Lied in der App gelöscht wurde.
    var seen: Set<String> = []
}

/// Durchsucht ausgewählte Ordner samt Unterordnern nach Liedern und übernimmt alle neuen in die Mediathek.
///
/// Das ganze iPhone auf einmal zu durchsuchen erlaubt iOS nicht: Jede App sieht nur Ordner, die man ihr freigibt.
/// Deshalb wählt man den Ordner einmal aus (z. B. „Auf meinem iPhone“); die Freigabe bleibt gespeichert.
@MainActor
final class FolderScanner: ObservableObject {
    @Published private(set) var folders: [WatchedFolder] = []
    /// Fortschritt oder Ergebnis der letzten Suche (für die Anzeige)
    @Published private(set) var status: String?
    @Published private(set) var isScanning = false

    weak var library: Library?
    private let storeURL: URL
    private var lastScan = Date.distantPast

    nonisolated static let storeName = "folders.json"

    init(storeURL: URL = Storage.support.appendingPathComponent(FolderScanner.storeName)) {
        self.storeURL = storeURL
        if let data = try? Data(contentsOf: storeURL), let saved = try? JSONDecoder().decode([WatchedFolder].self, from: data) {
            folders = saved
        }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(folders) { try? data.write(to: storeURL, options: .atomic) }
    }

    /// Eine Datei im Ordner
    struct FoundFile: Sendable {
        let url: URL
        /// Erkennungszeichen: Pfad im Ordner und Größe
        let key: String
        let size: Int
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

    /// Pfad ohne Verknüpfungen und ohne Schrägstrich am Ende – zum Vergleichen zweier Ordner
    nonisolated static func canonicalPath(_ url: URL) -> String {
        url.resolvingSymlinksInPath().standardizedFileURL.path
    }

    /// Merkt sich einen neuen Ordner und durchsucht ihn sofort.
    @discardableResult
    func add(_ url: URL) async -> Int {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        guard let bookmark = try? url.bookmarkData() else {
            status = "Der Ordner „\(url.lastPathComponent)“ lässt sich nicht merken."
            return 0
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
        return await scan(force: true)
    }

    func remove(_ folder: WatchedFolder) {
        folders.removeAll { $0.id == folder.id }
        persist()
        if folders.isEmpty { status = nil }
    }

    /// Durchsucht alle gemerkten Ordner und übernimmt neue Lieder. Liefert, wie viele dazugekommen sind.
    /// - Parameter force: `false` = höchstens einmal pro Minute (Start der App, Rückkehr in die App)
    @discardableResult
    func scan(force: Bool = false) async -> Int {
        guard !isScanning, let library, !folders.isEmpty else { return 0 }
        if !force, Date().timeIntervalSince(lastScan) < 60 { return 0 }
        isScanning = true
        lastScan = Date()
        defer { isScanning = false }

        var added = 0
        var failed = 0
        var unreachable: [String] = []
        for folder in folders {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: folder.bookmark, bookmarkDataIsStale: &stale) else {
                unreachable.append(folder.name)
                continue
            }
            let accessing = url.startAccessingSecurityScopedResource()
            if stale, let fresh = try? url.bookmarkData() { update(folder.id) { $0.bookmark = fresh } }
            let found = await Task.detached(priority: .utility) { FolderScanner.audioFiles(in: url) }.value
            let new = found.filter { !folder.seen.contains($0.key) }
            for (n, file) in new.enumerated() {
                status = "Übernehme Lied \(n + 1) von \(new.count) aus „\(folder.name)“ …"
                // Liegt genau diese Datei schon in der App, nur merken
                if !library.hasFile(named: file.url.lastPathComponent, size: file.size) {
                    do {
                        try await library.importAudio(from: file.url, move: false)
                        added += 1
                    } catch {
                        failed += 1
                    }
                }
                update(folder.id) { $0.seen.insert(file.key) }
                if n % 20 == 19 { persist() }
            }
            if accessing { url.stopAccessingSecurityScopedResource() }
            persist()
        }

        var parts: [String] = []
        parts.append(added == 0 ? "Keine neuen Lieder gefunden." : "✓ \(added) neue Lieder übernommen.")
        if failed > 0 { parts.append("\(failed) Dateien ließen sich nicht lesen.") }
        if !unreachable.isEmpty { parts.append("Nicht erreichbar: \(unreachable.joined(separator: ", ")) – bitte neu hinzufügen.") }
        status = parts.joined(separator: " ")
        return added
    }

    private func update(_ id: UUID, _ change: (inout WatchedFolder) -> Void) {
        guard let index = folders.firstIndex(where: { $0.id == id }) else { return }
        change(&folders[index])
    }
}
