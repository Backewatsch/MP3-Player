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
        try? fm.removeItem(at: indexFile)
    }

    static func isAudioFile(_ name: String) -> Bool {
        audioExtensions.contains((name as NSString).pathExtension.lowercased())
    }

    /// Videos, deren Tonspur der Konverter in M4A umwandelt.
    static let videoExtensions: Set<String> = ["mp4", "mov", "m4v", "3gp"]

    static func isVideoFile(_ name: String) -> Bool {
        videoExtensions.contains((name as NSString).pathExtension.lowercased())
    }

    /// Audio direkt oder Video zum Umwandeln
    static func isImportable(_ name: String) -> Bool {
        isAudioFile(name) || isVideoFile(name)
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

    var fileURL: URL { Storage.documents.appendingPathComponent(fileName) }
    var artworkURL: URL { Storage.artwork.appendingPathComponent("\(id.uuidString).jpg") }
}

extension Track {
    enum CodingKeys: String, CodingKey {
        case id, fileName, title, artist, duration, addedAt, album
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
    }
}

struct Playlist: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    var trackIDs: [UUID]
}

struct LibraryState: Codable {
    /// 2 = Alben werden gespeichert
    static let currentVersion = 2

    var version: Int
    var tracks: [Track]
    var playlists: [Playlist]
    /// Titel in der „Kurzwahl“ (angepinnt)
    var pinned: [UUID]

    init(tracks: [Track], playlists: [Playlist], pinned: [UUID]) {
        version = Self.currentVersion
        self.tracks = tracks
        self.playlists = playlists
        self.pinned = pinned
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 1
        tracks = try c.decode([Track].self, forKey: .tracks)
        playlists = try c.decode([Playlist].self, forKey: .playlists)
        pinned = try c.decodeIfPresent([UUID].self, forKey: .pinned) ?? []
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
