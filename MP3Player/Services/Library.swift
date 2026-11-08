import AVFoundation
import UIKit

/// Verwaltet alle Titel und Playlists und speichert sie dauerhaft.
@MainActor
final class Library: ObservableObject {
    /// Feste ID der automatischen Playlist für heruntergeladene Titel.
    static let downloadsPlaylistID = UUID(uuidString: "00000000-0000-0000-0000-00000000D001")!

    @Published private(set) var tracks: [Track] = []
    @Published private(set) var playlists: [Playlist] = []

    /// Wird aufgerufen, sobald ein neuer Titel hinzugekommen ist (z. B. für die Warteschlange).
    var onTrackAdded: ((Track, _ playlistID: UUID?) -> Void)?

    private var isScanning = false

    init() {
        load()
        ensureDownloadsPlaylist()
        Task { await rescan() }
    }

    // MARK: Abfragen

    func track(id: UUID) -> Track? { tracks.first { $0.id == id } }

    func playlist(id: UUID) -> Playlist? { playlists.first { $0.id == id } }

    func tracks(in playlist: Playlist) -> [Track] {
        let byID = Dictionary(uniqueKeysWithValues: tracks.map { ($0.id, $0) })
        return playlist.trackIDs.compactMap { byID[$0] }
    }

    var sortedTracks: [Track] {
        tracks.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }

    // MARK: Import

    /// Übernimmt eine Audiodatei in die Bibliothek.
    /// - Parameters:
    ///   - move: `true` verschiebt die Datei (temporäre Downloads), `false` kopiert sie (Dateien-App).
    ///   - addToDownloads: fügt den Titel zusätzlich der Playlist „Heruntergeladen“ hinzu.
    @discardableResult
    func importAudio(from source: URL, preferredName: String? = nil, move: Bool, addToDownloads: Bool = false) async throws -> Track {
        let name = Self.sanitize(preferredName ?? source.lastPathComponent)
        guard Storage.isAudioFile(name) else { throw LibraryError.unsupportedFormat(name) }

        let accessing = source.startAccessingSecurityScopedResource()
        defer { if accessing { source.stopAccessingSecurityScopedResource() } }

        let destination = Self.uniqueDestination(for: name)
        let fm = FileManager.default
        if move {
            try fm.moveItem(at: source, to: destination)
        } else {
            try fm.copyItem(at: source, to: destination)
        }

        let track = await Self.makeTrack(for: destination)
        tracks.append(track)
        var targetPlaylist: UUID?
        if addToDownloads, let index = playlists.firstIndex(where: { $0.id == Self.downloadsPlaylistID }) {
            playlists[index].trackIDs.append(track.id)
            targetPlaylist = Self.downloadsPlaylistID
        }
        save()
        onTrackAdded?(track, targetPlaylist)
        return track
    }

    /// Gleicht die Bibliothek mit dem Dokumente-Ordner ab (z. B. Dateien, die per Finder/Dateien-App kopiert wurden).
    func rescan() async {
        guard !isScanning else { return }
        isScanning = true
        defer { isScanning = false }

        let fm = FileManager.default
        let files = (try? fm.contentsOfDirectory(at: Storage.documents, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])) ?? []
        let audioFiles = files.filter { Storage.isAudioFile($0.lastPathComponent) }
        let present = Set(audioFiles.map(\.lastPathComponent))
        let known = Set(tracks.map(\.fileName))

        var changed = false
        // Entfernte Dateien aus der Bibliothek löschen
        let missing = tracks.filter { !present.contains($0.fileName) }
        if !missing.isEmpty {
            let ids = Set(missing.map(\.id))
            tracks.removeAll { ids.contains($0.id) }
            for i in playlists.indices { playlists[i].trackIDs.removeAll { ids.contains($0) } }
            changed = true
        }
        // Neue Dateien aufnehmen
        for url in audioFiles where !known.contains(url.lastPathComponent) {
            let track = await Self.makeTrack(for: url)
            if !tracks.contains(where: { $0.fileName == track.fileName }) {
                tracks.append(track)
                changed = true
            }
        }
        if changed { save() }
    }

    // MARK: Bearbeiten

    func delete(_ track: Track) {
        try? FileManager.default.removeItem(at: track.fileURL)
        try? FileManager.default.removeItem(at: track.artworkURL)
        tracks.removeAll { $0.id == track.id }
        for i in playlists.indices { playlists[i].trackIDs.removeAll { $0 == track.id } }
        ArtworkCache.shared.remove(track.id)
        save()
    }

    func rename(_ track: Track, title: String, artist: String) {
        guard let i = tracks.firstIndex(where: { $0.id == track.id }) else { return }
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        tracks[i].title = t.isEmpty ? tracks[i].title : t
        tracks[i].artist = artist.trimmingCharacters(in: .whitespacesAndNewlines)
        save()
    }

    @discardableResult
    func createPlaylist(named name: String) -> Playlist {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let playlist = Playlist(id: UUID(), name: trimmed.isEmpty ? "Neue Playlist" : trimmed, trackIDs: [])
        playlists.append(playlist)
        save()
        return playlist
    }

    func renamePlaylist(_ playlist: Playlist, to name: String) {
        guard let i = playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        playlists[i].name = trimmed
        save()
    }

    func deletePlaylist(_ playlist: Playlist) {
        guard playlist.id != Self.downloadsPlaylistID else { return }
        playlists.removeAll { $0.id == playlist.id }
        save()
    }

    func add(_ track: Track, to playlist: Playlist) {
        guard let i = playlists.firstIndex(where: { $0.id == playlist.id }),
              !playlists[i].trackIDs.contains(track.id) else { return }
        playlists[i].trackIDs.append(track.id)
        save()
    }

    func removeTracks(at offsets: IndexSet, from playlist: Playlist) {
        guard let i = playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        // Offsets beziehen sich auf die angezeigten (existierenden) Titel
        let visible = tracks(in: playlists[i]).map(\.id)
        let ids = Set(offsets.compactMap { visible.indices.contains($0) ? visible[$0] : nil })
        playlists[i].trackIDs.removeAll { ids.contains($0) }
        save()
    }

    func moveTracks(from source: IndexSet, to destination: Int, in playlist: Playlist) {
        guard let i = playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        var visible = tracks(in: playlists[i]).map(\.id)
        visible.move(fromOffsets: source, toOffset: destination)
        playlists[i].trackIDs = visible
        save()
    }

    // MARK: Persistenz

    private func load() {
        guard let data = try? Data(contentsOf: Storage.indexFile),
              let state = try? JSONDecoder().decode(LibraryState.self, from: data) else { return }
        tracks = state.tracks
        playlists = state.playlists
    }

    private func save() {
        let state = LibraryState(tracks: tracks, playlists: playlists)
        if let data = try? JSONEncoder().encode(state) {
            try? data.write(to: Storage.indexFile, options: .atomic)
        }
    }

    private func ensureDownloadsPlaylist() {
        if !playlists.contains(where: { $0.id == Self.downloadsPlaylistID }) {
            playlists.insert(Playlist(id: Self.downloadsPlaylistID, name: "Heruntergeladen", trackIDs: []), at: 0)
            save()
        }
    }

    // MARK: Hilfsfunktionen

    nonisolated static func sanitize(_ name: String) -> String {
        let invalid = CharacterSet(charactersIn: "/\\:?%*|\"<>").union(.newlines).union(.controlCharacters)
        let cleaned = name.components(separatedBy: invalid).joined(separator: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "Titel.mp3" : cleaned
    }

    nonisolated static func uniqueDestination(for name: String) -> URL {
        let fm = FileManager.default
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var candidate = Storage.documents.appendingPathComponent(name)
        var n = 2
        while fm.fileExists(atPath: candidate.path) {
            candidate = Storage.documents.appendingPathComponent("\(base) (\(n)).\(ext)")
            n += 1
        }
        return candidate
    }

    /// Liest ID3-/iTunes-Metadaten (Titel, Interpret, Dauer, Cover).
    nonisolated static func makeTrack(for url: URL) async -> Track {
        let asset = AVURLAsset(url: url)
        var title: String?
        var artist: String?
        var artworkData: Data?
        var duration: Double = 0

        if let d = try? await asset.load(.duration), d.seconds.isFinite {
            duration = d.seconds
        }
        if let items = try? await asset.load(.commonMetadata) {
            for item in items {
                if item.commonKey == .commonKeyTitle {
                    title = try? await item.load(.stringValue)
                } else if item.commonKey == .commonKeyArtist {
                    artist = try? await item.load(.stringValue)
                } else if item.commonKey == .commonKeyArtwork, artworkData == nil {
                    artworkData = try? await item.load(.dataValue)
                }
            }
        }

        let fallbackTitle = (url.lastPathComponent as NSString).deletingPathExtension
        let track = Track(
            id: UUID(),
            fileName: url.lastPathComponent,
            title: (title?.isEmpty == false ? title! : fallbackTitle),
            artist: artist ?? "",
            duration: duration,
            addedAt: Date()
        )
        if let artworkData, let image = UIImage(data: artworkData) {
            let thumb = image.preparingThumbnail(of: CGSize(width: 600, height: 600)) ?? image
            try? thumb.jpegData(compressionQuality: 0.85)?.write(to: track.artworkURL, options: .atomic)
        }
        return track
    }
}

/// Kleiner Zwischenspeicher für Cover-Bilder.
final class ArtworkCache {
    static let shared = ArtworkCache()
    private let cache = NSCache<NSUUID, UIImage>()
    private var missing = Set<UUID>()
    private let lock = NSLock()

    func image(for track: Track) -> UIImage? {
        if let img = cache.object(forKey: track.id as NSUUID) { return img }
        lock.lock(); let isMissing = missing.contains(track.id); lock.unlock()
        if isMissing { return nil }
        if let img = UIImage(contentsOfFile: track.artworkURL.path) {
            cache.setObject(img, forKey: track.id as NSUUID)
            return img
        }
        lock.lock(); missing.insert(track.id); lock.unlock()
        return nil
    }

    func remove(_ id: UUID) {
        cache.removeObject(forKey: id as NSUUID)
        lock.lock(); missing.remove(id); lock.unlock()
    }
}
