import Foundation

// „Importierte Lieder“: Lieder von einem anderen Handy landen zuerst in einem eigenen Ordner.
// Erst was man dort selbst aussucht, kommt in die Titel-Liste (und damit in Alben, Suche, Statistik …).

/// Ergebnis von „Lieder importieren“
struct SongImportResult: Equatable {
    /// Lieder, die jetzt im Ordner „Importierte Lieder“ liegen
    var staged = 0
    /// Lieder, die schon in der Titel-Liste oder schon importiert waren
    var alreadyThere = 0
    /// Lieder, die sich nicht lesen ließen
    var failed = 0

    /// Ergebnis in einem Satz (für die Anzeige)
    var summary: String {
        var parts: [String] = []
        switch staged {
        case 0: parts.append("Keine neuen Lieder importiert.")
        case 1: parts.append("✓ 1 Lied liegt jetzt in der Mediathek unter „Importierte Lieder“.")
        default: parts.append("✓ \(staged) Lieder liegen jetzt in der Mediathek unter „Importierte Lieder“.")
        }
        if alreadyThere == 1 { parts.append("1 war schon da.") }
        if alreadyThere > 1 { parts.append("\(alreadyThere) waren schon da.") }
        if failed == 1 { parts.append("1 ließ sich nicht lesen.") }
        if failed > 1 { parts.append("\(failed) ließen sich nicht lesen.") }
        return parts.joined(separator: " ")
    }
}

enum SongImportError: LocalizedError {
    case noSongs
    case notEnoughSpace(Int64)

    var errorDescription: String? {
        switch self {
        case .noSongs:
            return "Diese Sicherung enthält keine Lieder. Erstelle sie auf dem alten Handy mit „Alle Lieder exportieren“."
        case .notEnoughSpace(let bytes):
            let size = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
            return "Zu wenig freier Speicher: Die Lieder brauchen etwa \(size)."
        }
    }
}

/// Schnelles Nachschlagen „ist dieses Lied schon da?“ – auch bei tausenden Liedern
struct SongIndex {
    private var ids = Set<UUID>()
    /// Titel + Interpret (klein geschrieben) → Längen der Lieder mit diesem Namen
    private var lengths: [String: [Double]] = [:]

    init(_ tracks: [Track] = []) {
        for track in tracks { add(track) }
    }

    mutating func add(_ track: Track) {
        ids.insert(track.id)
        lengths[Self.key(track), default: []].append(track.duration)
    }

    func hasID(_ id: UUID) -> Bool { ids.contains(id) }

    /// Dasselbe Lied? Gleiche Kennung oder gleicher Titel und Interpret bei (fast) gleicher Länge.
    /// Der Dateiname allein zählt nicht – „Track 01.mp3“ gibt es in vielen Alben.
    func contains(_ track: Track) -> Bool {
        if ids.contains(track.id) { return true }
        return (lengths[Self.key(track)] ?? []).contains { abs($0 - track.duration) < 2 }
    }

    private static func key(_ track: Track) -> String {
        track.title.trimmingCharacters(in: .whitespaces).lowercased() + "\u{1}"
            + track.artist.trimmingCharacters(in: .whitespaces).lowercased()
    }
}

extension Library {
    /// Datei eines importierten Liedes (im Ordner „Importierte Lieder“)
    func importedFileURL(_ track: Track) -> URL {
        Storage.importedDirectory.appendingPathComponent(track.fileName)
    }

    /// Dateiname hinter „music/“ – nur reine Namen (kein Unterordner, kein „..“) und nur Musikdateien
    nonisolated static func plainMusicName(_ entryName: String) -> String? {
        guard entryName.hasPrefix(PortableBackup.music) else { return nil }
        let name = String(entryName.dropFirst(PortableBackup.music.count))
        guard !name.isEmpty, !name.contains("/"), !name.contains("\\"), name != "..", Storage.isAudioFile(name) else { return nil }
        return name
    }

    /// Freier Dateiname in einem Ordner („Lied.mp3“, „Lied (2).mp3“ …)
    nonisolated static func uniqueURL(in folder: URL, name: String) -> URL {
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var candidate = folder.appendingPathComponent(name)
        var n = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = folder.appendingPathComponent(ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)")
            n += 1
        }
        return candidate
    }

    /// „Lieder importieren“: liest eine Sicherung mit Musik (auch aus der Android-App) und legt ihre Lieder im Ordner
    /// „Importierte Lieder“ ab – nicht in der Titel-Liste. Lieder, die schon da sind, werden übersprungen.
    /// Playlists, Herzen und Statistik der Sicherung bleiben unberührt (dafür gibt es „Sicherung wiederherstellen“).
    /// Jedes Lied wird direkt in den Ordner entpackt (keine zweite Kopie).
    func importSongs(from url: URL, progress: (Int, Int) -> Void = { _, _ in }) async throws -> SongImportResult {
        let zip: ZipReader
        do {
            zip = try ZipReader(url: url)
        } catch {
            throw BackupError.notABackup
        }
        let musicEntries = zip.entries.filter { Self.plainMusicName($0.name) != nil }
        var decoded: PortableBackup.Decoded?
        if let indexEntry = zip.entry(named: PortableBackup.index), let data = try? zip.data(of: indexEntry) {
            decoded = PortableBackup.decode(data)
        }
        guard !musicEntries.isEmpty else {
            if decoded == nil { throw BackupError.notABackup }
            throw SongImportError.noSongs
        }

        // Zu welchem Lied der Sicherung gehört welcher Eintrag? Haben zwei Lieder denselben Dateinamen,
        // schreibt die Android-App beim zweiten die Kennung davor („<Kennung>__Name.mp3“).
        var savedByEntry: [String: Track] = [:]
        var rawIDByEntry: [String: String] = [:]
        if let decoded {
            for saved in decoded.state.tracks {
                let raw = decoded.rawIDs[saved.id] ?? saved.id.uuidString.lowercased()
                let withID = PortableBackup.music + raw + "__" + saved.fileName
                let plain = PortableBackup.music + saved.fileName
                if zip.entry(named: withID) != nil {
                    savedByEntry[withID] = saved
                    rawIDByEntry[withID] = raw
                } else if savedByEntry[plain] == nil, zip.entry(named: plain) != nil {
                    savedByEntry[plain] = saved
                    rawIDByEntry[plain] = raw
                }
            }
        }

        var index = SongIndex(tracks + importedTracks)

        // Reicht der Platz für alle Lieder, die neu dazukommen? (0 heißt „unbekannt“ – dann nicht abbrechen)
        var needed: Int64 = 0
        for entry in musicEntries {
            if let saved = savedByEntry[entry.name], index.contains(saved) { continue }
            needed += Int64(clamping: entry.size)
        }
        let values = try? Storage.documents.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        if let free = values?.volumeAvailableCapacityForImportantUsage, free > 0, needed + 50_000_000 > free {
            throw SongImportError.notEnoughSpace(needed)
        }

        isImportingSongs = true
        defer { isImportingSongs = false }
        let folder = Storage.importedDirectory
        var result = SongImportResult()

        for (n, entry) in musicEntries.enumerated() {
            progress(n + 1, musicEntries.count)
            let saved = savedByEntry[entry.name]
            if let saved, index.contains(saved) {
                result.alreadyThere += 1
                continue
            }
            guard let plainName = Self.plainMusicName(entry.name) else {
                result.failed += 1
                continue
            }
            let destination = Self.uniqueURL(in: folder, name: Self.sanitize(saved?.fileName ?? plainName))
            do {
                // Entpacken abseits der Oberfläche, damit sie bedienbar bleibt
                try await Task.detached(priority: .userInitiated) { try zip.extract(entry, to: destination) }.value
            } catch {
                result.failed += 1
                continue
            }

            // Kennung aus der Sicherung behalten, solange sie hier noch frei ist
            var id = UUID()
            if let saved, !index.hasID(saved.id) { id = saved.id }
            let artworkURL = Storage.artwork.appendingPathComponent("\(id.uuidString).jpg")
            let made = await Self.makeTrack(for: destination, artworkTo: artworkURL)
            var track = Track(id: id, fileName: destination.lastPathComponent, title: made.title, artist: made.artist,
                              duration: made.duration, addedAt: made.addedAt)
            track.album = made.album
            track.genre = made.genre
            if let saved {
                // Angaben aus der Sicherung gehen vor (dort können sie von Hand geändert worden sein)
                if !saved.title.isEmpty { track.title = saved.title }
                if !saved.artist.isEmpty { track.artist = saved.artist }
                if !saved.album.isEmpty { track.album = saved.album }
                if !saved.genre.isEmpty { track.genre = saved.genre }
                if saved.duration > 0 { track.duration = saved.duration }
                if saved.addedAt.timeIntervalSince1970 > 0 { track.addedAt = saved.addedAt }
            } else if index.contains(track) {
                // Ohne Angaben in der Sicherung zeigt erst die Datei selbst, dass das Lied schon da ist
                try? FileManager.default.removeItem(at: destination)
                try? FileManager.default.removeItem(at: artworkURL)
                result.alreadyThere += 1
                continue
            }
            // Cover aus der Sicherung (z. B. ein selbst gewähltes) übernehmen
            if let raw = rawIDByEntry[entry.name], let art = zip.entry(named: PortableBackup.artwork + raw + ".jpg") {
                try? zip.extract(art, to: artworkURL)
            }
            ArtworkCache.shared.remove(id)
            let stagedTrack = track
            updateImported { $0.append(stagedTrack) }
            index.add(stagedTrack)
            result.staged += 1
        }
        return result
    }

    /// Gleicht die Liste mit dem Ordner „Importierte Lieder“ ab: Dateien ohne Eintrag (z. B. über die Dateien-App
    /// hineinkopiert) kommen dazu, Einträge ohne Datei verschwinden. Ruht, solange ein Import läuft.
    func syncImportedFolder() async {
        guard !isImportingSongs, !isSyncingImported else { return }
        isSyncingImported = true
        defer { isSyncingImported = false }
        // Ordner nicht lesbar → nichts anfassen
        guard let files = try? FileManager.default.contentsOfDirectory(at: Storage.importedDirectory, includingPropertiesForKeys: nil,
                                                                       options: [.skipsHiddenFiles]) else { return }
        let audio = files.filter { Storage.isAudioFile($0.lastPathComponent) }
        let present = Set(audio.map(\.lastPathComponent))
        let known = Set(importedTracks.map(\.fileName))
        guard present != known else { return }

        var fresh: [Track] = []
        for url in audio where !known.contains(url.lastPathComponent) {
            fresh.append(await Self.makeTrack(for: url))
        }
        let fm = FileManager.default
        // Inzwischen hat ein Import begonnen: Der trägt seine Lieder selbst ein
        guard !isImportingSongs else {
            for track in fresh { try? fm.removeItem(at: track.artworkURL) }
            return
        }
        let libraryIDs = Set(tracks.map(\.id))
        // Der Ordner kann sich während des Lesens geändert haben (z. B. ein inzwischen abgeschlossener Import):
        // nur Einträge entfernen, deren Datei jetzt wirklich fehlt
        let folder = Storage.importedDirectory
        let gone = Set(importedTracks.filter {
            !present.contains($0.fileName) && !fm.fileExists(atPath: folder.appendingPathComponent($0.fileName).path)
        }.map(\.id))
        for track in importedTracks where gone.contains(track.id) && !libraryIDs.contains(track.id) {
            try? fm.removeItem(at: track.artworkURL)
            ArtworkCache.shared.remove(track.id)
        }
        updateImported { list in
            list.removeAll { gone.contains($0.id) }
            for track in fresh where !list.contains(where: { $0.fileName == track.fileName }) {
                list.append(track)
            }
        }
    }

    /// Übernimmt mehrere importierte Lieder in die Titel-Liste. Liefert, wie viele übernommen wurden.
    func adoptImported(_ ids: [UUID]) async -> Int {
        var count = 0
        for (n, id) in ids.enumerated() {
            if adoptImported(id) { count += 1 }
            // Bei sehr vielen Liedern zwischendurch Luft lassen, damit die Oberfläche bedienbar bleibt
            if n % 50 == 49 { await Task.yield() }
        }
        return count
    }

    /// Löscht importierte Lieder samt Datei. Liefert, wie viele gelöscht wurden.
    @discardableResult
    func deleteImported(_ ids: [UUID]) -> Int {
        let chosen = Set(ids)
        let doomed = importedTracks.filter { chosen.contains($0.id) }
        guard !doomed.isEmpty else { return 0 }
        let libraryIDs = Set(tracks.map(\.id))
        let fm = FileManager.default
        for track in doomed {
            try? fm.removeItem(at: importedFileURL(track))
            if !libraryIDs.contains(track.id) {
                try? fm.removeItem(at: track.artworkURL)
                ArtworkCache.shared.remove(track.id)
            }
        }
        updateImported { list in list.removeAll { chosen.contains($0.id) } }
        return doomed.count
    }
}
