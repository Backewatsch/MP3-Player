import SwiftUI
import UniformTypeIdentifiers

/// Kleine Sicherung von Playlists, Herzen, Kurzwahl, Verlauf und Statistik als JSON-Datei (ohne Musik).
/// Die Komplett-Sicherung samt Liedern steht in FullBackup.swift.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    var data: Data

    init(state: LibraryState) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        data = try encoder.encode(state)
    }

    init(configuration: ReadConfiguration) throws {
        guard let d = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        data = d
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

enum BackupError: LocalizedError {
    case notABackup
    case notEnoughSpace(Int64)

    var errorDescription: String? {
        switch self {
        case .notABackup:
            return "Das ist keine Sicherung von ILLEGAL FREEDOM MUSIC."
        case .notEnoughSpace(let bytes):
            let size = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
            return "Zu wenig freier Speicher: Die Sicherung braucht etwa \(size)."
        }
    }
}

struct RestoreResult {
    var matched: Int
    var missing: Int
    var playlists: Int
}

extension Library {
    /// Findet ein Lied aus einer Sicherung in der Mediathek: über die Kennung, über Titel + Interpret + Länge
    /// oder über den Dateinamen.
    func matchingTrack(for saved: Track) -> Track? {
        track(id: saved.id)
            ?? tracks.first(where: {
                $0.title.localizedCaseInsensitiveCompare(saved.title) == .orderedSame
                    && $0.artist.localizedCaseInsensitiveCompare(saved.artist) == .orderedSame
                    && abs($0.duration - saved.duration) < 2
            })
            ?? tracks.first(where: { $0.fileName == saved.fileName })
    }

    static func decodeBackup(_ data: Data) throws -> LibraryState {
        guard let state = try? JSONDecoder().decode(LibraryState.self, from: data) else { throw BackupError.notABackup }
        return state
    }

    /// Führt eine Sicherung mit der Mediathek zusammen – ohne Vorhandenes zu verlieren.
    /// Titel werden über ID oder Titel+Interpret+Länge wiedererkannt.
    @discardableResult
    func restore(from backup: LibraryState) -> RestoreResult {
        var idMap: [UUID: UUID] = [:]
        var missing = 0
        for bt in backup.tracks {
            if let existing = matchingTrack(for: bt) {
                idMap[bt.id] = existing.id
            } else {
                missing += 1
            }
        }
        func map(_ ids: [UUID]) -> [UUID] { ids.compactMap { idMap[$0] } }
        applyRestore(backup: backup, map: map)
        return RestoreResult(matched: idMap.count, missing: missing, playlists: backup.playlists.count)
    }
}
