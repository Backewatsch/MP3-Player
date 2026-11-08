import SwiftUI
import UniformTypeIdentifiers

/// Sicherung von Playlists, Herzen, Kurzwahl, Verlauf und Statistik als JSON-Datei.
/// Die Musikdateien selbst liegen sichtbar in der Dateien-App („Auf meinem iPhone › ILLEGAL FREEDOM MUSIC“).
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

    var errorDescription: String? { "Das ist keine Sicherung von ILLEGAL FREEDOM MUSIC." }
}

struct RestoreResult {
    var matched: Int
    var missing: Int
    var playlists: Int
}

extension Library {
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
            if let existing = track(id: bt.id)
                ?? tracks.first(where: {
                    $0.title.localizedCaseInsensitiveCompare(bt.title) == .orderedSame
                        && $0.artist.localizedCaseInsensitiveCompare(bt.artist) == .orderedSame
                        && abs($0.duration - bt.duration) < 2
                })
                ?? tracks.first(where: { $0.fileName == bt.fileName }) {
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
