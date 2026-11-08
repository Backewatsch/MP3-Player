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
    static func makeIncomingDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("Incoming", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func isAudioFile(_ name: String) -> Bool {
        audioExtensions.contains((name as NSString).pathExtension.lowercased())
    }
}

struct Track: Identifiable, Codable, Hashable {
    let id: UUID
    var fileName: String
    var title: String
    var artist: String
    var duration: Double
    var addedAt: Date

    var fileURL: URL { Storage.documents.appendingPathComponent(fileName) }
    var artworkURL: URL { Storage.artwork.appendingPathComponent("\(id.uuidString).jpg") }
}

struct Playlist: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    var trackIDs: [UUID]
}

struct LibraryState: Codable {
    var tracks: [Track]
    var playlists: [Playlist]
}

enum LibraryError: LocalizedError {
    case unsupportedFormat(String)

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat(let name):
            return "„\(name)“ ist keine unterstützte Audiodatei (MP3, M4A, AAC, WAV, AIFF, FLAC)."
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
