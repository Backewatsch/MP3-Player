import Foundation

/// Gemeinsame Speicherorte der App.
enum Storage {
    /// Unterstützte Audio-Dateiendungen.
    static let audioExtensions: Set<String> = ["mp3", "m4a", "aac", "wav", "aif", "aiff", "caf", "flac"]

    /// Musikdateien liegen direkt im Dokumente-Ordner (sichtbar in der Dateien-App und im Finder).
    static let documents: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]

    static let support: URL = {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    static let artwork: URL = {
        let url = support.appendingPathComponent("Artwork", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    static let indexFile: URL = support.appendingPathComponent("library.json")

    /// Neuer, leerer temporärer Ordner für eine eingehende Datei.
    static let incomingRoot: URL = FileManager.default.temporaryDirectory.appendingPathComponent("Incoming", isDirectory: true)

    static func makeIncomingDirectory() -> URL {
        let url = incomingRoot.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Löscht Musik, Cover und Bibliotheksindex (nur für automatische Tests).
    static func resetAll() {
        let fm = FileManager.default
        for url in (try? fm.contentsOfDirectory(at: documents, includingPropertiesForKeys: nil)) ?? [] {
            try? fm.removeItem(at: url)
        }
        try? fm.removeItem(at: artwork)
        try? fm.createDirectory(at: artwork, withIntermediateDirectories: true)
        LibrarySaver.shared.discard()
        try? fm.removeItem(at: indexFile)
        try? fm.removeItem(at: support.appendingPathComponent(FolderScanner.storeName))
    }

    static func isAudioFile(_ name: String) -> Bool {
        audioExtensions.contains((name as NSString).pathExtension.lowercased())
    }

    /// Videos, deren Tonspur der Konverter in MP3 umwandelt.
    static let videoExtensions: Set<String> = ["mp4", "mov", "m4v", "3gp"]

    static func isVideoFile(_ name: String) -> Bool {
        videoExtensions.contains((name as NSString).pathExtension.lowercased())
    }

    /// Audio direkt oder Video zum Umwandeln
    static func isImportable(_ name: String) -> Bool {
        isAudioFile(name) || isVideoFile(name)
    }

    /// Bereits im Zielformat MP3?
    static func isMP3(_ name: String) -> Bool {
        (name as NSString).pathExtension.lowercased() == "mp3"
    }
}

struct Track: Identifiable, Codable, Hashable {
    let id: UUID
    var fileName: String
    var title: String
    var artist: String
    var duration: Double
    var addedAt: Date
    var album: String = ""
    /// Genre (z. B. „Hip-Hop“), aus der Datei gelesen oder selbst eingetragen
    var genre: String = ""

    var fileURL: URL { Storage.documents.appendingPathComponent(fileName) }
    var artworkURL: URL { Storage.artwork.appendingPathComponent("\(id.uuidString).jpg") }
}

extension Track {
    enum CodingKeys: String, CodingKey {
        case id, fileName, title, artist, duration, addedAt, album, genre
    }

    /// Ältere Bibliotheken (vor Version 1.0.9) haben noch kein Album gespeichert.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        fileName = try c.decode(String.self, forKey: .fileName)
        title = try c.decode(String.self, forKey: .title)
        artist = try c.decode(String.self, forKey: .artist)
        duration = try c.decode(Double.self, forKey: .duration)
        addedAt = try c.decode(Date.self, forKey: .addedAt)
        album = try c.decodeIfPresent(String.self, forKey: .album) ?? ""
        genre = try c.decodeIfPresent(String.self, forKey: .genre) ?? ""
    }
}

struct Playlist: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    var trackIDs: [UUID]
}

/// Ein Eintrag im Verlauf bzw. in der Statistik: welcher Titel wann
struct PlayEvent: Codable, Hashable {
    var trackID: UUID
    var at: Date
}

struct LibraryState: Codable {
    /// 2 = Alben werden gespeichert, 3 = Herzen, Verlauf, Statistik
    static let currentVersion = 4

    var version: Int
    var tracks: [Track]
    var playlists: [Playlist]
    /// Titel in der „Kurzwahl“ (angepinnt)
    var pinned: [UUID]
    /// Lieblingssongs (zuletzt markiert zuerst)
    var favorites: [UUID] = []
    /// Wie oft jeder Titel gehört wurde („Oft gespielt“)
    var playCounts: [UUID: Int] = [:]
    /// Zuletzt gestartete Titel, neueste zuerst („Verlauf“)
    var history: [PlayEvent] = []
    /// Richtig gehörte Titel (für die Statistik), neueste zuerst
    var plays: [PlayEvent] = []
    /// Hörzeit in Sekunden je Monat („2026-11“)
    var listenedByMonth: [String: Double] = [:]
    /// Gemessene Verstärkung je Titel in dB („Lautstärke angleichen“)
    var gains: [UUID: Float] = [:]
    /// Selbst eingetragene Songtexte
    var lyrics: [UUID: String] = [:]
    /// Titel mit selbst gewähltem Cover
    var customCovers: [UUID] = []

    init(tracks: [Track], playlists: [Playlist], pinned: [UUID], favorites: [UUID] = [], playCounts: [UUID: Int] = [:],
         history: [PlayEvent] = [], plays: [PlayEvent] = [], listenedByMonth: [String: Double] = [:]) {
        version = Self.currentVersion
        self.tracks = tracks
        self.playlists = playlists
        self.pinned = pinned
        self.favorites = favorites
        self.playCounts = playCounts
        self.history = history
        self.plays = plays
        self.listenedByMonth = listenedByMonth
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 1
        tracks = try c.decode([Track].self, forKey: .tracks)
        playlists = try c.decode([Playlist].self, forKey: .playlists)
        pinned = try c.decodeIfPresent([UUID].self, forKey: .pinned) ?? []
        favorites = try c.decodeIfPresent([UUID].self, forKey: .favorites) ?? []
        playCounts = try c.decodeIfPresent([UUID: Int].self, forKey: .playCounts) ?? [:]
        history = try c.decodeIfPresent([PlayEvent].self, forKey: .history) ?? []
        plays = try c.decodeIfPresent([PlayEvent].self, forKey: .plays) ?? []
        listenedByMonth = try c.decodeIfPresent([String: Double].self, forKey: .listenedByMonth) ?? [:]
        gains = try c.decodeIfPresent([UUID: Float].self, forKey: .gains) ?? [:]
        lyrics = try c.decodeIfPresent([UUID: String].self, forKey: .lyrics) ?? [:]
        customCovers = try c.decodeIfPresent([UUID].self, forKey: .customCovers) ?? []
    }
}

/// Sortierung der Titelliste
enum TrackSort: String, CaseIterable, Identifiable {
    case title, artist, newest, mostPlayed, longest

    var id: String { rawValue }

    var label: String {
        switch self {
        case .title: return "Titel (A–Z)"
        case .artist: return "Interpret (A–Z)"
        case .newest: return "Zuletzt hinzugefügt"
        case .mostPlayed: return "Am meisten gespielt"
        case .longest: return "Länge"
        }
    }

    func apply(_ tracks: [Track], playCounts: [UUID: Int] = [:]) -> [Track] {
        func byTitle(_ a: Track, _ b: Track) -> Bool { a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending }
        switch self {
        case .title:
            return tracks.sorted(by: byTitle)
        case .artist:
            return tracks.sorted { a, b in
                if a.artist.isEmpty != b.artist.isEmpty { return !a.artist.isEmpty } // ohne Interpret ans Ende
                let c = a.artist.localizedCaseInsensitiveCompare(b.artist)
                if c != .orderedSame { return c == .orderedAscending }
                let d = a.album.localizedCaseInsensitiveCompare(b.album)
                if d != .orderedSame { return d == .orderedAscending }
                return byTitle(a, b)
            }
        case .newest:
            return tracks.sorted { (a: Track, b: Track) -> Bool in
                if a.addedAt != b.addedAt { return a.addedAt > b.addedAt }
                return byTitle(a, b)
            }
        case .mostPlayed:
            return tracks.sorted {
                let x = playCounts[$0.id] ?? 0, y = playCounts[$1.id] ?? 0
                return x != y ? x > y : byTitle($0, $1)
            }
        case .longest:
            return tracks.sorted { (a: Track, b: Track) -> Bool in
                if a.duration != b.duration { return a.duration > b.duration }
                return byTitle(a, b)
            }
        }
    }
}

/// Hör-Statistik
struct StatsSummary {
    var listenedTotal: Double
    var listenedThisMonth: Double
    var playsThisMonth: Int
    var playsTotal: Int
    var topThisMonth: [(Track, Int)]
    var topAllTime: [(Track, Int)]
    var topArtists: [(String, Int)]
    var trackCount: Int
    var libraryDuration: Double
    var favoriteCount: Int

    static let maxPlays = 15000

    static func monthKey(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month], from: date)
        return String(format: "%04d-%02d", c.year ?? 0, c.month ?? 0)
    }

    static func make(_ state: LibraryState, now: Date = Date(), calendar: Calendar = .current) -> StatsSummary {
        let byID = Dictionary(state.tracks.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let month = monthKey(now, calendar: calendar)
        let plays = state.plays.filter { byID[$0.trackID] != nil }
        let monthPlays = plays.filter { monthKey($0.at, calendar: calendar) == month }
        func top(_ events: [PlayEvent]) -> [(Track, Int)] {
            var counts: [UUID: Int] = [:]
            for e in events { counts[e.trackID, default: 0] += 1 }
            var items: [(Track, Int)] = []
            for (id, n) in counts { if let t = byID[id] { items.append((t, n)) } }
            items.sort(by: Self.byCountThenTitle)
            return Array(items.prefix(10))
        }
        var artists: [String: Int] = [:]
        for e in plays { if let a = byID[e.trackID]?.artist, !a.isEmpty { artists[a, default: 0] += 1 } }
        var artistList: [(String, Int)] = artists.map { ($0.key, $0.value) }
        artistList.sort { a, b in
            if a.1 != b.1 { return a.1 > b.1 }
            return a.0.lowercased() < b.0.lowercased()
        }
        let topArtists = Array(artistList.prefix(5))
        return StatsSummary(
            listenedTotal: state.listenedByMonth.values.reduce(0, +),
            listenedThisMonth: state.listenedByMonth[month] ?? 0,
            playsThisMonth: monthPlays.count,
            playsTotal: plays.count,
            topThisMonth: top(monthPlays),
            topAllTime: top(plays),
            topArtists: topArtists,
            trackCount: state.tracks.count,
            libraryDuration: state.tracks.reduce(0) { $0 + $1.duration },
            favoriteCount: state.favorites.filter { byID[$0] != nil }.count
        )
    }

    private static func byCountThenTitle(_ a: (Track, Int), _ b: (Track, Int)) -> Bool {
        if a.1 != b.1 { return a.1 > b.1 }
        return a.0.title.localizedCaseInsensitiveCompare(b.0.title) == .orderedAscending
    }

    /// „1 Std. 5 Min.“ bzw. „7 Min.“
    static func formatListening(_ seconds: Double) -> String {
        let minutes = Int(seconds / 60)
        let h = minutes / 60, m = minutes % 60
        return h > 0 ? "\(h) Std. \(m) Min." : "\(m) Min."
    }
}

enum LibraryError: LocalizedError {
    case unsupportedFormat(String)

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat(let name):
            return "„\(name)“ ist keine unterstützte Audiodatei (MP3, M4A, AAC, WAV, AIFF, FLAC) und kein umwandelbares Video (MP4, MOV, M4V)."
        }
    }
}

extension TimeInterval {
    /// 3:07 bzw. 1:02:03
    var formattedTime: String {
        guard isFinite, self >= 0 else { return "0:00" }
        let total = Int(self.rounded(.down))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

extension String {
    /// `nil` statt leerem Text
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
