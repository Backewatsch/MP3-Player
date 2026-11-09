import AVFoundation
import UIKit

/// Verwaltet alle Titel und Playlists und speichert sie dauerhaft.
@MainActor
final class Library: ObservableObject {
    /// Feste ID der automatischen Playlist für heruntergeladene Titel.
    static let downloadsPlaylistID = UUID(uuidString: "00000000-0000-0000-0000-00000000D001")!

    @Published private(set) var tracks: [Track] = []
    @Published private(set) var playlists: [Playlist] = []
    /// Angepinnte Titel („Kurzwahl“)
    @Published private(set) var pinnedIDs: [UUID] = []
    /// Lieblingssongs (Herz), zuletzt markiert zuerst
    @Published private(set) var favoriteIDs: [UUID] = []
    /// Wie oft jeder Titel gehört wurde
    @Published private(set) var playCounts: [UUID: Int] = [:]
    /// Verlauf (neueste zuerst)
    @Published private(set) var historyEvents: [PlayEvent] = []
    /// Gehörte Titel für die Statistik (neueste zuerst)
    @Published private(set) var plays: [PlayEvent] = []
    /// Hörzeit in Sekunden je Monat
    @Published private(set) var listenedByMonth: [String: Double] = [:]
    /// Lautstärke-Angleichung je Titel (dB)
    @Published private(set) var gains: [UUID: Float] = [:]
    /// Selbst eingetragene Songtexte
    @Published private(set) var lyricsByID: [UUID: String] = [:]
    /// Titel mit eigenem Cover (auch für die Cover-Anzeige ohne Zugriff auf die Bibliothek)
    @Published private(set) var customCovers: [UUID] = [] {
        didSet { Self.customCoverIDs = Set(customCovers) }
    }
    nonisolated(unsafe) static var customCoverIDs = Set<UUID>()
    /// Zählt hoch, wenn sich ein Cover geändert hat (Anzeige lädt neu)
    @Published private(set) var coverVersion = 0
    static let maxHistory = 300
    /// Fortschritt einer laufenden Umwandlung (für die Anzeige), sonst `nil`
    @Published private(set) var conversionStatus: String?

    /// Wird aufgerufen, sobald ein neuer Titel hinzugekommen ist (z. B. für die Warteschlange).
    var onTrackAdded: ((Track, _ playlistID: UUID?) -> Void)?

    private var isScanning = false
    /// Dateien, die gerade importiert werden (damit `rescan()` sie nicht doppelt aufnimmt).
    private var importing = Set<String>()

    private var needsMetadataUpgrade = false
    /// Neu gefundene Nicht-MP3-Dateien (z. B. per Finder kopiert) automatisch in MP3 umwandeln
    let autoConvertToMP3: Bool
    private var isConverting = false

    init(autoScan: Bool = true) {
        autoConvertToMP3 = autoScan
        load()
        ensureDownloadsPlaylist()
        if autoScan {
            Task {
                await rescan()
                await upgradeMetadataIfNeeded()
            }
        }
    }

    // MARK: Abfragen

    func track(id: UUID) -> Track? { tracks.first { $0.id == id } }

    func playlist(id: UUID) -> Playlist? { playlists.first { $0.id == id } }

    func tracks(in playlist: Playlist) -> [Track] {
        let byID = Dictionary(uniqueKeysWithValues: tracks.map { ($0.id, $0) })
        return playlist.trackIDs.compactMap { byID[$0] }
    }

    var pinnedTracks: [Track] { pinnedIDs.compactMap { track(id: $0) } }

    func isPinned(_ track: Track) -> Bool { pinnedIDs.contains(track.id) }

    func togglePin(_ track: Track) {
        if let i = pinnedIDs.firstIndex(of: track.id) {
            pinnedIDs.remove(at: i)
        } else {
            pinnedIDs.insert(track.id, at: 0)
        }
        save()
    }

    func tracks(byArtist artist: String) -> [Track] {
        sortedTracks.filter { $0.artist.localizedCaseInsensitiveCompare(artist) == .orderedSame }
    }

    func tracks(inAlbum album: String) -> [Track] {
        sortedTracks.filter { $0.album.localizedCaseInsensitiveCompare(album) == .orderedSame }
    }

    var sortedTracks: [Track] {
        tracks.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }

    func tracksSorted(by order: TrackSort) -> [Track] { order.apply(tracks, playCounts: playCounts) }

    // MARK: Lieblingssongs

    func isFavorite(_ track: Track) -> Bool { favoriteIDs.contains(track.id) }

    var favoriteTracks: [Track] {
        let byID = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        return favoriteIDs.compactMap { byID[$0] }
    }

    func toggleFavorite(_ track: Track) {
        if let i = favoriteIDs.firstIndex(of: track.id) { favoriteIDs.remove(at: i) } else { favoriteIDs.insert(track.id, at: 0) }
        save()
    }

    func addFavorites(_ list: [Track]) {
        let new = list.map(\.id).filter { !favoriteIDs.contains($0) }
        favoriteIDs = new + favoriteIDs
        save()
    }

    // MARK: Oft gespielt, Verlauf, Statistik

    /// Titel gestartet → Verlauf (gleicher Titel innerhalb einer Minute nur einmal)
    func recordStart(_ id: UUID, at now: Date = Date()) {
        guard track(id: id) != nil else { return }
        if let recent = historyEvents.first, recent.trackID == id, now.timeIntervalSince(recent.at) < 60 { return }
        historyEvents.insert(PlayEvent(trackID: id, at: now), at: 0)
        if historyEvents.count > Self.maxHistory { historyEvents.removeLast(historyEvents.count - Self.maxHistory) }
        save()
    }

    /// Titel richtig gehört → „Oft gespielt“ und Statistik
    func recordPlay(_ id: UUID, at now: Date = Date()) {
        guard track(id: id) != nil else { return }
        playCounts[id, default: 0] += 1
        plays.insert(PlayEvent(trackID: id, at: now), at: 0)
        if plays.count > StatsSummary.maxPlays { plays.removeLast(plays.count - StatsSummary.maxPlays) }
        save()
    }

    func addListened(_ seconds: Double, at now: Date = Date()) {
        guard seconds > 0 else { return }
        listenedByMonth[StatsSummary.monthKey(now), default: 0] += seconds
        save()
    }

    func playCount(_ track: Track) -> Int { playCounts[track.id] ?? 0 }

    func mostPlayed(limit: Int = 50) -> [(Track, Int)] {
        let byID = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var items: [(Track, Int)] = []
        for (id, n) in playCounts where n > 0 { if let t = byID[id] { items.append((t, n)) } }
        items.sort { a, b in a.1 > b.1 }
        return Array(items.prefix(limit))
    }

    func history(limit: Int = Library.maxHistory) -> [(Track, Date)] {
        let byID = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var items: [(Track, Date)] = []
        for e in historyEvents { if let t = byID[e.trackID] { items.append((t, e.at)) } }
        return Array(items.prefix(limit))
    }

    func recentlyPlayed(limit: Int = 20) -> [Track] {
        var seen = Set<UUID>()
        var result: [Track] = []
        for (track, _) in history() where seen.insert(track.id).inserted {
            result.append(track)
            if result.count >= limit { break }
        }
        return result
    }

    func clearHistory() {
        historyEvents = []
        playCounts = [:]
        save()
    }

    func clearStats() {
        plays = []
        listenedByMonth = [:]
        save()
    }

    var stateSnapshot: LibraryState {
        var s = LibraryState(tracks: tracks, playlists: playlists, pinned: pinnedIDs, favorites: favoriteIDs, playCounts: playCounts,
                             history: historyEvents, plays: plays, listenedByMonth: listenedByMonth)
        s.gains = gains
        s.lyrics = lyricsByID
        s.customCovers = customCovers
        return s
    }

    // MARK: Lautstärke, Songtexte, Cover, Doppelte, Mixe

    func gain(of id: UUID) -> Float? { gains[id] }
    var tracksWithoutGain: [Track] { tracks.filter { gains[$0.id] == nil } }

    func setGain(_ id: UUID, _ db: Float) {
        guard track(id: id) != nil else { return }
        gains[id] = db
        save()
    }

    func storedLyrics(_ track: Track) -> String? { lyricsByID[track.id] }

    /// Songtext: selbst eingetragen oder aus der Datei
    func lyrics(for track: Track) async -> String? {
        if let s = lyricsByID[track.id] { return s }
        let asset = AVURLAsset(url: track.fileURL)
        let text = try? await asset.load(.lyrics)
        return text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    func setLyrics(_ track: Track, _ text: String) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        lyricsByID[track.id] = clean.isEmpty ? nil : clean
        save()
        writeTags(track, Id3Writer.Update(lyrics: clean))
    }

    func hasCustomCover(_ track: Track) -> Bool { customCovers.contains(track.id) }

    func setCustomCover(_ track: Track, image: UIImage) {
        let thumb = image.preparingThumbnail(of: CGSize(width: 800, height: 800)) ?? image
        let jpeg = thumb.jpegData(compressionQuality: 0.85)
        try? jpeg?.write(to: track.artworkURL, options: .atomic)
        ArtworkCache.shared.remove(track.id)
        // Cover auch in die MP3 schreiben
        if let jpeg { writeTags(track, Id3Writer.Update(cover: jpeg)) }
        if !customCovers.contains(track.id) { customCovers.insert(track.id, at: 0) }
        coverVersion += 1
        save()
    }

    func removeCustomCover(_ track: Track) async {
        try? FileManager.default.removeItem(at: track.artworkURL)
        ArtworkCache.shared.remove(track.id)
        // Eingebettetes Cover wiederherstellen
        _ = await Self.makeTrack(for: track.fileURL, artworkTo: track.artworkURL)
        customCovers.removeAll { $0 == track.id }
        coverVersion += 1
        save()
    }

    func playlistCoverURL(_ playlist: Playlist) -> URL { Storage.artwork.appendingPathComponent("playlist-\(playlist.id.uuidString).jpg") }

    func setPlaylistCover(_ playlist: Playlist, image: UIImage) {
        let thumb = image.preparingThumbnail(of: CGSize(width: 800, height: 800)) ?? image
        try? thumb.jpegData(compressionQuality: 0.85)?.write(to: playlistCoverURL(playlist), options: .atomic)
        coverVersion += 1
    }

    var duplicateGroups: [[Track]] { Duplicates.groups(tracks) }

    private var analyzing = false

    /// Lautheit aller noch nicht gemessenen Lieder im Hintergrund messen
    func analyzeLoudness() async {
        guard !analyzing else { return }
        analyzing = true
        defer { analyzing = false }
        while let next = tracksWithoutGain.first {
            let url = next.fileURL
            let gain = await Task.detached(priority: .utility) { LoudnessAnalyzer.gain(of: url) }.value
            setGain(next.id, gain ?? 0)
            onGainMeasured?(next.id, gain ?? 0)
        }
    }

    /// Meldet eine neu gemessene Verstärkung (für den laufenden Titel)
    var onGainMeasured: ((UUID, Float) -> Void)?

    /// Behält ein Lied der Gruppe, übernimmt Playlists, Herzen, Zähler und Verlauf der anderen und löscht diese
    @discardableResult
    func mergeDuplicates(_ group: [Track]) -> Track {
        let keep = Duplicates.keeper(group, playCounts: playCounts,
                                     playlistCount: { t in self.playlists.filter { $0.trackIDs.contains(t.id) }.count },
                                     favorites: Set(favoriteIDs))
        let others = group.filter { $0.id != keep.id }
        guard !others.isEmpty else { return keep }
        let gone = Set(others.map(\.id))
        func swap(_ id: UUID) -> UUID { gone.contains(id) ? keep.id : id }
        func unique(_ ids: [UUID]) -> [UUID] {
            var seen = Set<UUID>(); var out: [UUID] = []
            for id in ids where seen.insert(id).inserted { out.append(id) }
            return out
        }
        for i in playlists.indices { playlists[i].trackIDs = unique(playlists[i].trackIDs.map(swap)) }
        pinnedIDs = unique(pinnedIDs.map(swap))
        favoriteIDs = unique(favoriteIDs.map(swap))
        var total = 0
        for t in group { total += playCounts[t.id] ?? 0 }
        for id in gone { playCounts[id] = nil }
        playCounts[keep.id] = total
        historyEvents = historyEvents.map { PlayEvent(trackID: swap($0.trackID), at: $0.at) }
        plays = plays.map { PlayEvent(trackID: swap($0.trackID), at: $0.at) }
        if lyricsByID[keep.id] == nil, let text = others.compactMap({ lyricsByID[$0.id] }).first { lyricsByID[keep.id] = text }
        save()
        deleteAll(others)
        return keep
    }

    func smartTracks(_ kind: SmartPlaylist) -> [Track] { kind.tracks(stateSnapshot) }

    var stats: StatsSummary { StatsSummary.make(stateSnapshot) }

    // MARK: Import

    /// Übernimmt eine Audiodatei in die Bibliothek.
    /// - Parameters:
    ///   - move: `true` verschiebt die Datei (temporäre Downloads), `false` kopiert sie (Dateien-App).
    ///   - addToDownloads: fügt den Titel zusätzlich der Playlist „Heruntergeladen“ hinzu.
    @discardableResult
    func importAudio(from source: URL, preferredName: String? = nil, move: Bool, addToDownloads: Bool = false,
                     alsoAddTo extraPlaylistID: UUID? = nil) async throws -> Track {
        let name = Self.sanitize(preferredName ?? source.lastPathComponent)
        guard Storage.isImportable(name) else { throw LibraryError.unsupportedFormat(name) }

        // Alles, was nicht MP3 ist (Videos, M4A, WAV, FLAC …), wird in MP3 umgewandelt
        if !Storage.isMP3(name) {
            let accessing = source.startAccessingSecurityScopedResource()
            defer { if accessing { source.stopAccessingSecurityScopedResource() } }
            let baseName = (name as NSString).deletingPathExtension
            let converted = try await AudioConverter.convertToMP3(source, baseName: baseName)
            defer { try? FileManager.default.removeItem(at: converted.deletingLastPathComponent()) }
            if move { try? FileManager.default.removeItem(at: source) }
            return try await importAudio(from: converted, move: true, addToDownloads: addToDownloads,
                                         alsoAddTo: extraPlaylistID)
        }

        // Datei liegt schon im Musikordner (z. B. in der Dateien-App angetippt) → nicht kopieren
        if source.deletingLastPathComponent().standardizedFileURL.path == Storage.documents.standardizedFileURL.path {
            if let existing = tracks.first(where: { $0.fileName == source.lastPathComponent }) { return existing }
            let track = await Self.makeTrack(for: source)
            if let existing = tracks.first(where: { $0.fileName == track.fileName }) { return existing }
            tracks.append(track)
            save()
            onTrackAdded?(track, nil)
            return track
        }

        let accessing = source.startAccessingSecurityScopedResource()
        defer { if accessing { source.stopAccessingSecurityScopedResource() } }

        let destination = Self.uniqueDestination(for: name)
        importing.insert(destination.lastPathComponent)
        defer { importing.remove(destination.lastPathComponent) }
        let fm = FileManager.default
        if move {
            try fm.moveItem(at: source, to: destination)
        } else {
            try fm.copyItem(at: source, to: destination)
        }

        let track: Track
        if let existing = tracks.first(where: { $0.fileName == destination.lastPathComponent }) {
            track = existing
        } else {
            track = await Self.makeTrack(for: destination)
            tracks.append(track)
        }
        var targetPlaylist: UUID?
        if addToDownloads, let index = playlists.firstIndex(where: { $0.id == Self.downloadsPlaylistID }),
           !playlists[index].trackIDs.contains(track.id) {
            playlists[index].trackIDs.append(track.id)
            targetPlaylist = Self.downloadsPlaylistID
        }
        if let extraPlaylistID, let index = playlists.firstIndex(where: { $0.id == extraPlaylistID }),
           !playlists[index].trackIDs.contains(track.id) {
            playlists[index].trackIDs.append(track.id)
            targetPlaylist = extraPlaylistID
        }
        save()
        onTrackAdded?(track, targetPlaylist)
        return track
    }

    // MARK: Umwandeln in MP3

    /// Titel, die noch nicht im MP3-Format vorliegen (z. B. per Finder kopierte M4A/WAV)
    var convertibleTracks: [Track] {
        tracks.filter { !Storage.isMP3($0.fileName) }
    }

    /// Wandelt die Datei eines Titels in MP3 um. Der Titel behält ID, Playlists, Kurzwahl und Cover.
    @discardableResult
    func convertToMP3(_ track: Track) async throws -> Track {
        guard let current = self.track(id: track.id) else { return track }
        guard !Storage.isMP3(current.fileName) else { return current }
        let oldURL = current.fileURL
        let baseName = (current.fileName as NSString).deletingPathExtension
        let converted = try await AudioConverter.convertToMP3(oldURL, baseName: baseName)
        defer { try? FileManager.default.removeItem(at: converted.deletingLastPathComponent()) }

        let destination = Self.uniqueDestination(for: "\(baseName).mp3")
        importing.insert(destination.lastPathComponent)
        defer { importing.remove(destination.lastPathComponent) }
        try FileManager.default.moveItem(at: converted, to: destination)

        guard let i = tracks.firstIndex(where: { $0.id == current.id }) else {
            try? FileManager.default.removeItem(at: destination)
            return current
        }
        // Erst den neuen Dateinamen eintragen, dann die alte Datei löschen (kein Zwischenstand ohne Datei)
        tracks[i].fileName = destination.lastPathComponent
        try? FileManager.default.removeItem(at: oldURL)
        let fresh = await Self.makeTrack(for: destination)
        if let j = tracks.firstIndex(where: { $0.id == current.id }), fresh.duration > 0 {
            tracks[j].duration = fresh.duration
        }
        save()
        return self.track(id: current.id) ?? current
    }

    /// Wandelt alle Titel, die nicht MP3 sind, nacheinander um.
    /// - Parameter skipping: z. B. der gerade laufende Titel
    func convertAllToMP3(skipping skipID: UUID? = nil) async -> (converted: Int, failed: Int) {
        guard !isConverting else { return (0, 0) }
        isConverting = true
        defer { isConverting = false }
        let todo = convertibleTracks.filter { $0.id != skipID }
        var converted = 0, failed = 0
        for (n, track) in todo.enumerated() {
            conversionStatus = "Wandle um: \(n + 1) von \(todo.count) – „\(track.title)“"
            do {
                try await convertToMP3(track)
                converted += 1
            } catch {
                failed += 1
            }
        }
        conversionStatus = nil
        return (converted, failed)
    }

    func setConversionStatus(_ text: String?) {
        conversionStatus = text
    }

    /// Liest bei Bibliotheken aus älteren Versionen einmalig die Alben nach.
    func upgradeMetadataIfNeeded() async {
        guard needsMetadataUpgrade else { return }
        needsMetadataUpgrade = false
        for track in tracks where track.album.isEmpty {
            let fresh = await Self.makeTrack(for: track.fileURL)
            if let i = tracks.firstIndex(where: { $0.id == track.id }), !fresh.album.isEmpty {
                tracks[i].album = fresh.album
            }
        }
        save()
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
        let missing = tracks.filter { !present.contains($0.fileName) && !importing.contains($0.fileName) }
        if !missing.isEmpty {
            let ids = Set(missing.map(\.id))
            tracks.removeAll { ids.contains($0.id) }
            for i in playlists.indices { playlists[i].trackIDs.removeAll { ids.contains($0) } }
            pinnedIDs.removeAll { ids.contains($0) }
            favoriteIDs.removeAll { ids.contains($0) }
            changed = true
        }
        // Neue Dateien aufnehmen
        for url in audioFiles where !known.contains(url.lastPathComponent) && !importing.contains(url.lastPathComponent) {
            let track = await Self.makeTrack(for: url)
            if !tracks.contains(where: { $0.fileName == track.fileName }) && !importing.contains(track.fileName) {
                tracks.append(track)
                changed = true
            }
        }
        if changed { save() }
        if autoConvertToMP3 && !convertibleTracks.isEmpty {
            Task { _ = await convertAllToMP3() }
        }
    }

    // MARK: Bearbeiten

    func delete(_ track: Track) { deleteAll([track]) }

    /// Löscht mehrere Titel vom iPhone und aus allen Playlists.
    func deleteAll(_ list: [Track]) {
        guard !list.isEmpty else { return }
        let ids = Set(list.map(\.id))
        for track in list {
            try? FileManager.default.removeItem(at: track.fileURL)
            try? FileManager.default.removeItem(at: track.artworkURL)
            ArtworkCache.shared.remove(track.id)
        }
        tracks.removeAll { ids.contains($0.id) }
        for i in playlists.indices { playlists[i].trackIDs.removeAll { ids.contains($0) } }
        pinnedIDs.removeAll { ids.contains($0) }
        favoriteIDs.removeAll { ids.contains($0) }
        for id in ids { playCounts[id] = nil; gains[id] = nil; lyricsByID[id] = nil }
        customCovers.removeAll { ids.contains($0) }
        historyEvents.removeAll { ids.contains($0.trackID) }
        plays.removeAll { ids.contains($0.trackID) }
        save()
    }

    /// Mehrere Titel (ohne Doppelte) an eine Playlist anhängen
    func addAll(_ list: [Track], to playlist: Playlist) {
        guard let i = playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        for t in list where !playlists[i].trackIDs.contains(t.id) { playlists[i].trackIDs.append(t.id) }
        save()
    }

    func rename(_ track: Track, title: String, artist: String) {
        let current = tracks.first { $0.id == track.id } ?? track
        editInfo(track, title: title, artist: artist, album: current.album, genre: current.genre)
    }

    /// Infos ändern – in der Mediathek und direkt in der MP3 (dann stimmen sie auch in anderen Apps)
    func editInfo(_ track: Track, title: String, artist: String, album: String, genre: String) {
        guard let i = tracks.firstIndex(where: { $0.id == track.id }) else { return }
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        tracks[i].title = t.isEmpty ? tracks[i].title : t
        tracks[i].artist = artist.trimmingCharacters(in: .whitespacesAndNewlines)
        tracks[i].album = album.trimmingCharacters(in: .whitespacesAndNewlines)
        let g = Genres.normalize(genre)
        tracks[i].genre = g.isEmpty ? genre.trimmingCharacters(in: .whitespacesAndNewlines) : g
        save()
        let edited = tracks[i]
        writeTags(edited, Id3Writer.Update(title: edited.title, artist: edited.artist, album: edited.album, genre: edited.genre))
    }

    /// Änderungen in die MP3 schreiben (Fehler ändern nichts an der Mediathek)
    func writeTags(_ track: Track, _ update: Id3Writer.Update) {
        guard Storage.isMP3(track.fileName) else { return }
        let url = track.fileURL
        Task.detached(priority: .utility) { try? Id3Writer.write(url, update) }
    }

    // MARK: Genres

    /// Genres der vorhandenen Lieder einmal aus den Dateien lesen (ältere Bibliotheken)
    func scanGenresOnce() async {
        let key = "genresScanned"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        for t in tracks where t.genre.isEmpty {
            let g = await Self.readGenre(AVURLAsset(url: t.fileURL))
            if !g.isEmpty, let i = tracks.firstIndex(where: { $0.id == t.id }) { tracks[i].genre = g }
        }
        save()
        UserDefaults.standard.set(true, forKey: key)
    }

    var genreNames: [String] {
        var seen = Set<String>()
        return tracks.map(\.genre).filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    func tracks(inGenre genre: String) -> [Track] {
        tracks.filter { $0.genre.caseInsensitiveCompare(genre) == .orderedSame }
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }

    /// Korrigiert die Dauer, wenn der Player sie genauer kennt als die Metadaten.
    func updateDuration(of trackID: UUID, to duration: Double) {
        guard duration.isFinite, duration > 0,
              let i = tracks.firstIndex(where: { $0.id == trackID }),
              abs(tracks[i].duration - duration) > 1 else { return }
        tracks[i].duration = duration
        save()
    }

    /// Setzt die Dauer immer (z. B. nach dem Zuschneiden)
    func setExactDuration(of trackID: UUID, to duration: Double) {
        guard duration.isFinite, duration > 0, let i = tracks.firstIndex(where: { $0.id == trackID }) else { return }
        tracks[i].duration = duration
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

    func remove(_ track: Track, from playlist: Playlist) {
        guard let i = playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        playlists[i].trackIDs.removeAll { $0 == track.id }
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

    /// Übernimmt Playlists, Herzen, Kurzwahl, Zähler, Verlauf und Hörzeit aus einer Sicherung (IDs bereits zugeordnet).
    func applyRestore(backup: LibraryState, map: ([UUID]) -> [UUID]) {
        for bp in backup.playlists {
            let ids = map(bp.trackIDs)
            let target: Int?
            if bp.id == Self.downloadsPlaylistID {
                target = playlists.firstIndex { $0.id == Self.downloadsPlaylistID }
            } else {
                target = playlists.firstIndex {
                    $0.id != Self.downloadsPlaylistID && $0.name.localizedCaseInsensitiveCompare(bp.name) == .orderedSame
                }
            }
            if let i = target {
                for id in ids where !playlists[i].trackIDs.contains(id) { playlists[i].trackIDs.append(id) }
            } else {
                var unique: [UUID] = []
                for id in ids where !unique.contains(id) { unique.append(id) }
                let id = playlists.contains { $0.id == bp.id } ? UUID() : bp.id
                playlists.append(Playlist(id: id, name: bp.name, trackIDs: unique))
            }
        }
        for id in map(backup.pinned) where !pinnedIDs.contains(id) { pinnedIDs.append(id) }
        for id in map(backup.favorites) where !favoriteIDs.contains(id) { favoriteIDs.append(id) }
        for (old, n) in backup.playCounts {
            if let id = map([old]).first { playCounts[id] = max(playCounts[id] ?? 0, n) }
        }
        for (k, v) in backup.listenedByMonth { listenedByMonth[k] = max(listenedByMonth[k] ?? 0, v) }
        func merge(_ a: [PlayEvent], _ b: [PlayEvent], max limit: Int) -> [PlayEvent] {
            var seen = Set<String>()
            let mapped = b.compactMap { e in map([e.trackID]).first.map { PlayEvent(trackID: $0, at: e.at) } }
            var merged: [PlayEvent] = []
            for e in a + mapped where seen.insert("\(e.trackID)-\(e.at.timeIntervalSince1970)").inserted { merged.append(e) }
            merged.sort { x, y in x.at > y.at }
            return Array(merged.prefix(limit))
        }
        historyEvents = merge(historyEvents, backup.history, max: Self.maxHistory)
        plays = merge(plays, backup.plays, max: StatsSummary.maxPlays)
        save()
    }

    // MARK: Persistenz

    private func load() {
        guard let data = try? Data(contentsOf: Storage.indexFile),
              let state = try? JSONDecoder().decode(LibraryState.self, from: data) else { return }
        tracks = state.tracks
        playlists = state.playlists
        pinnedIDs = state.pinned.filter { id in state.tracks.contains { $0.id == id } }
        favoriteIDs = state.favorites
        playCounts = state.playCounts
        historyEvents = state.history
        plays = state.plays
        listenedByMonth = state.listenedByMonth
        gains = state.gains
        lyricsByID = state.lyrics
        customCovers = state.customCovers
        Self.customCoverIDs = Set(state.customCovers)
        // Alben wurden ab Version 2 gespeichert
        needsMetadataUpgrade = state.version < 2
    }

    private func save() {
        let state = stateSnapshot
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
        // Führende Punkte würden die Datei verstecken
        let visible = String(cleaned.drop(while: { $0 == "." }))
        guard !visible.isEmpty else { return "Titel.mp3" }
        guard visible.count > 180 else { return visible }
        // Zu lange Namen kürzen, Endung behalten
        let ext = (visible as NSString).pathExtension
        let base = String((visible as NSString).deletingPathExtension.prefix(170))
        return ext.isEmpty ? base : "\(base).\(ext)"

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
    /// Genre aus ID3 (TCON) bzw. iTunes-Angaben, vereinheitlicht
    nonisolated static func readGenre(_ asset: AVURLAsset) async -> String {
        var items: [AVMetadataItem] = []
        for format in (try? await asset.load(.availableMetadataFormats)) ?? [] {
            items += (try? await asset.loadMetadata(for: format)) ?? []
        }
        for item in items where item.identifier == .id3MetadataContentType || item.identifier == .iTunesMetadataUserGenre
            || item.identifier == .commonIdentifierType {
            if let value = try? await item.load(.stringValue) {
                let g = Genres.normalize(value)
                if !g.isEmpty { return g }
            }
        }
        return ""
    }

    nonisolated static func makeTrack(for url: URL, artworkTo: URL? = nil) async -> Track {
        let asset = AVURLAsset(url: url)
        var title: String?
        var artist: String?
        var album: String?
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
                } else if item.commonKey == .commonKeyAlbumName {
                    album = try? await item.load(.stringValue)
                } else if item.commonKey == .commonKeyArtwork, artworkData == nil {
                    artworkData = try? await item.load(.dataValue)
                }
            }
        }

        let genre = await readGenre(asset)
        let fallbackTitle = (url.lastPathComponent as NSString).deletingPathExtension
        var track = Track(
            id: UUID(),
            fileName: url.lastPathComponent,
            title: (title?.isEmpty == false ? title! : fallbackTitle),
            artist: artist ?? "",
            duration: duration,
            addedAt: Date(),
            album: album?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        )
        track.genre = genre
        if let artworkData, let image = UIImage(data: artworkData) {
            let thumb = image.preparingThumbnail(of: CGSize(width: 600, height: 600)) ?? image
            try? thumb.jpegData(compressionQuality: 0.85)?.write(to: artworkTo ?? track.artworkURL, options: .atomic)
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
