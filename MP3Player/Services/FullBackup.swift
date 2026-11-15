import Foundation

/// Komplett-Sicherung: eine ZIP-Datei mit „library.json“ (Playlists, Herzen, Statistik …), allen Liedern („music/…“)
/// und ihren Covern („artwork/…“).
///
/// Das Format ist das der Android-App. Eine Sicherung vom Android-Handy lässt sich deshalb auf dem iPhone
/// wiederherstellen und umgekehrt – so zieht die ganze Musik mit um.
enum PortableBackup {
    static let index = "library.json"
    static let music = "music/"
    static let artwork = "artwork/"

    struct Decoded {
        var state: LibraryState
        /// Kennung jedes Liedes so, wie sie in der Sicherung steht (so heißen dort auch die Cover-Dateien)
        var rawIDs: [UUID: String]
    }

    private static func text(_ id: UUID) -> String { id.uuidString.lowercased() }

    private static func milliseconds(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    /// Mediathek im Format der Android-App (Zeiten in Millisekunden, Kennungen als Text)
    static func encode(_ state: LibraryState) throws -> Data {
        func events(_ list: [PlayEvent]) -> [[String: Any]] {
            list.map { ["trackId": text($0.trackID), "at": milliseconds($0.at)] }
        }
        let tracks: [[String: Any]] = state.tracks.map { track in
            let duration = track.duration.isFinite ? max(0, track.duration) : 0
            return ["id": text(track.id), "fileName": track.fileName, "title": track.title, "artist": track.artist,
                    "album": track.album, "durationMs": Int64((duration * 1000).rounded()),
                    "addedAt": milliseconds(track.addedAt), "genre": track.genre]
        }
        let playlists: [[String: Any]] = state.playlists.map { playlist in
            ["id": text(playlist.id), "name": playlist.name, "trackIds": playlist.trackIDs.map(text)]
        }
        var playCounts: [String: Any] = [:]
        for (id, count) in state.playCounts { playCounts[text(id)] = count }
        var listened: [String: Any] = [:]
        for (month, seconds) in state.listenedByMonth { listened[month] = Int64((seconds * 1000).rounded()) }
        var gains: [String: Any] = [:]
        for (id, db) in state.gains { gains[text(id)] = Double(db) }
        var lyrics: [String: Any] = [:]
        for (id, words) in state.lyrics { lyrics[text(id)] = words }
        let root: [String: Any] = [
            "version": LibraryState.currentVersion,
            "tracks": tracks,
            "playlists": playlists,
            "pinned": state.pinned.map(text),
            "playCounts": playCounts,
            "history": events(state.history),
            "favorites": state.favorites.map(text),
            "plays": events(state.plays),
            "listenedByMonth": listened,
            "gains": gains,
            "lyrics": lyrics,
            "customCovers": state.customCovers.map(text),
            "genresScanned": true,
        ]
        return try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    }

    /// Liest „library.json“ einer Komplett-Sicherung. `nil`, wenn es keine ist.
    static func decode(_ data: Data) -> Decoded? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawTracks = root["tracks"] as? [[String: Any]] else { return nil }
        var known: [String: UUID] = [:]
        // Kennungen, die keine UUID sind, bekommen eine neue – überall dieselbe
        func uuid(_ raw: String) -> UUID {
            if let id = known[raw] { return id }
            let id = UUID(uuidString: raw) ?? UUID()
            known[raw] = id
            return id
        }
        func uuids(_ value: Any?) -> [UUID] { ((value as? [String]) ?? []).map(uuid) }
        func number(_ value: Any?) -> Double { (value as? NSNumber)?.doubleValue ?? 0 }
        func date(_ value: Any?) -> Date { Date(timeIntervalSince1970: number(value) / 1000) }
        func events(_ value: Any?) -> [PlayEvent] {
            ((value as? [[String: Any]]) ?? []).compactMap { event -> PlayEvent? in
                guard let id = event["trackId"] as? String else { return nil }
                return PlayEvent(trackID: uuid(id), at: date(event["at"]))
            }
        }

        var tracks: [Track] = []
        var rawIDs: [UUID: String] = [:]
        for raw in rawTracks {
            guard let rawID = raw["id"] as? String, let fileName = raw["fileName"] as? String, !fileName.isEmpty else { continue }
            let id = uuid(rawID)
            rawIDs[id] = rawID
            let title = (raw["title"] as? String) ?? ""
            var track = Track(id: id, fileName: fileName,
                              title: title.isEmpty ? (fileName as NSString).deletingPathExtension : title,
                              artist: (raw["artist"] as? String) ?? "",
                              duration: number(raw["durationMs"]) / 1000,
                              addedAt: date(raw["addedAt"]))
            track.album = (raw["album"] as? String) ?? ""
            track.genre = (raw["genre"] as? String) ?? ""
            tracks.append(track)
        }

        var playlists: [Playlist] = []
        for raw in (root["playlists"] as? [[String: Any]]) ?? [] {
            guard let rawID = raw["id"] as? String, let name = raw["name"] as? String else { continue }
            playlists.append(Playlist(id: uuid(rawID), name: name, trackIDs: uuids(raw["trackIds"])))
        }
        var playCounts: [UUID: Int] = [:]
        for (id, count) in (root["playCounts"] as? [String: Any]) ?? [:] { playCounts[uuid(id)] = Int(number(count)) }
        var listened: [String: Double] = [:]
        for (month, ms) in (root["listenedByMonth"] as? [String: Any]) ?? [:] { listened[month] = number(ms) / 1000 }

        var state = LibraryState(tracks: tracks, playlists: playlists, pinned: uuids(root["pinned"]),
                                 favorites: uuids(root["favorites"]), playCounts: playCounts,
                                 history: events(root["history"]), plays: events(root["plays"]), listenedByMonth: listened)
        for (id, db) in (root["gains"] as? [String: Any]) ?? [:] { state.gains[uuid(id)] = Float(number(db)) }
        for (id, words) in (root["lyrics"] as? [String: Any]) ?? [:] {
            if let words = words as? String { state.lyrics[uuid(id)] = words }
        }
        state.customCovers = uuids(root["customCovers"])
        return Decoded(state: state, rawIDs: rawIDs)
    }

    /// Schreibt die Komplett-Sicherung. `progress` bekommt (fertig, gesamt).
    static func write(state: LibraryState, to url: URL, progress: (Int, Int) -> Void) throws {
        let zip = try ZipWriter(url: url)
        try zip.add(name: index, data: try encode(state))
        let fm = FileManager.default
        for (n, track) in state.tracks.enumerated() {
            progress(n, state.tracks.count)
            if fm.fileExists(atPath: track.fileURL.path) {
                try zip.add(name: music + track.fileName, fileAt: track.fileURL)
            }
            if fm.fileExists(atPath: track.artworkURL.path) {
                try zip.add(name: artwork + text(track.id) + ".jpg", fileAt: track.artworkURL)
            }
        }
        progress(state.tracks.count, state.tracks.count)
        try zip.finish()
    }
}

struct FullRestoreResult {
    /// Lieder, die aus der Sicherung zurückgeholt wurden
    var restored: Int
    /// Lieder, die schon auf dem iPhone waren
    var matched: Int
    /// Lieder, die weder vorhanden noch in der Sicherung enthalten sind
    var missing: Int
    var playlists: Int
    /// Lieder, die unter „Importierte Lieder“ warteten und von dort in die Titel-Liste übernommen wurden
    var fromImported = 0
}

/// Ergebnis von „Playlists und Herzen übernehmen“
struct InfoRestoreResult: Equatable {
    /// Lieder der Datei, die in der Titel-Liste sind
    var matched = 0
    /// Lieder der Datei, die nicht in der Titel-Liste sind
    var missing = 0
    /// Playlists, in die Lieder einsortiert wurden
    var playlists = 0
    /// Übernommene Herzen
    var favorites = 0

    /// Ergebnis in einem Satz (für die Anzeige)
    var summary: String {
        guard matched > 0 else {
            return "In deiner Titel-Liste ist noch kein Lied aus dieser Datei. Übernimm zuerst Lieder aus „Importierte Lieder“."
        }
        let songs = matched == 1 ? "1 Lied" : "\(matched) Lieder"
        let lists = playlists == 1 ? "1 Playlist" : "\(playlists) Playlists"
        let hearts = favorites == 1 ? "1 Herz" : "\(favorites) Herzen"
        var text = "✓ Für \(songs) übernommen: \(lists), \(hearts) und die Statistik."
        if missing == 1 { text += " 1 Lied der Datei ist nicht in deiner Titel-Liste." }
        if missing > 1 { text += " \(missing) Lieder der Datei sind nicht in deiner Titel-Liste." }
        return text
    }
}

extension Library {
    /// Erstellt die Komplett-Sicherung in einem temporären Ordner und liefert die Datei.
    func makeFullBackup(progress: @escaping @MainActor @Sendable (Int, Int) -> Void = { _, _ in }) async throws -> URL {
        // Noch laufende Änderungen an den MP3s (Infos, Cover) erst fertig schreiben
        await Self.awaitFileWrites()
        let state = stateSnapshot
        var needed: Int64 = 0
        for track in state.tracks {
            let attributes = try? FileManager.default.attributesOfItem(atPath: track.fileURL.path)
            needed += (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        }
        let folder = Storage.makeIncomingDirectory()
        let values = try? folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        let free = values?.volumeAvailableCapacityForImportantUsage
        // 0 heißt „unbekannt“ (kommt im Simulator vor) – dann nicht abbrechen
        if let free, free > 0, needed + 50_000_000 > free {
            try? FileManager.default.removeItem(at: folder)
            throw BackupError.notEnoughSpace(needed)
        }
        let url = folder.appendingPathComponent("ILLEGAL-FREEDOM-MUSIC-Sicherung.zip")
        do {
            try await Task.detached(priority: .userInitiated) {
                try PortableBackup.write(state: state, to: url) { done, total in
                    Task { @MainActor in progress(done, total) }
                }
            }.value
        } catch {
            try? FileManager.default.removeItem(at: folder)
            throw error
        }
        return url
    }

    /// Stellt eine Komplett-Sicherung wieder her (auch eine von der Android-App): Lieder, die auf dem iPhone fehlen,
    /// kommen aus der Sicherung; danach folgen Playlists, Herzen, Kurzwahl und Statistik. Vorhandenes bleibt erhalten.
    func restoreFull(from url: URL, progress: (Int, Int) -> Void = { _, _ in }) async throws -> FullRestoreResult {
        let zip = try ZipReader(url: url)
        guard let indexEntry = zip.entry(named: PortableBackup.index),
              let decoded = PortableBackup.decode(try zip.data(of: indexEntry)) else { throw BackupError.notABackup }
        let backup = decoded.state
        // Nur Lieder, die schon vor dem Wiederherstellen da waren, gelten als „vorhanden“ –
        // sonst würde ein gerade zurückgeholtes Lied ein zweites mit gleichem Dateinamen verschlucken
        var matcher = RestoreMatcher(existing: tracks)
        // Lieder der Sicherung, die schon unter „Importierte Lieder“ warten: von dort übernehmen statt doppelt anlegen
        var waiting = WaitingSongs(importedTracks)
        var idMap: [UUID: UUID] = [:]
        var restoredCovers: [UUID] = []
        var restored = 0
        var missing = 0
        var fromImported = 0
        let folder = Storage.makeIncomingDirectory()
        defer { try? FileManager.default.removeItem(at: folder) }

        for (n, saved) in backup.tracks.enumerated() {
            progress(n + 1, backup.tracks.count)
            let raw = decoded.rawIDs[saved.id] ?? saved.id.uuidString.lowercased()
            // Gibt es einen Dateinamen zweimal, schreibt die Android-App die Kennung davor („<id>__Name.mp3“) –
            // dieser Eintrag gehört eindeutig zu diesem Lied und wird deshalb zuerst gesucht
            let entry = zip.entry(named: PortableBackup.music + raw + "__" + saved.fileName)
                ?? zip.entry(named: PortableBackup.music + saved.fileName)
            if let existing = matcher.match(saved, archivedSize: entry?.size) {
                idMap[saved.id] = existing.id
                // Bei großen Mediatheken ab und zu Luft lassen, damit die Fortschrittsanzeige weiterläuft
                if n % 200 == 199 { try? await Task.sleep(nanoseconds: 1_000_000) }
                continue
            }
            if let staged = waiting.take(saved), let moved = adoptImportedTrack(staged.id) {
                matcher.add(moved)
                idMap[saved.id] = moved.id
                fromImported += 1
                if backup.customCovers.contains(saved.id), FileManager.default.fileExists(atPath: moved.artworkURL.path) {
                    restoredCovers.append(moved.id)
                }
                continue
            }
            guard let entry else {
                missing += 1
                continue
            }
            let temp = folder.appendingPathComponent(Self.sanitize(saved.fileName))
            do {
                // Entpacken abseits der Oberfläche, damit sie bedienbar bleibt
                try await Task.detached(priority: .userInitiated) { try zip.extract(entry, to: temp) }.value
                let track = try await importAudio(from: temp, move: true)
                idMap[saved.id] = track.id
                restored += 1
                let isCustom = backup.customCovers.contains(saved.id)
                if let art = zip.entry(named: PortableBackup.artwork + raw + ".jpg"),
                   isCustom || !FileManager.default.fileExists(atPath: track.artworkURL.path),
                   (try? zip.extract(art, to: track.artworkURL)) != nil {
                    ArtworkCache.shared.remove(track.id)
                    if isCustom { restoredCovers.append(track.id) }
                }
                adoptBackupInfo(track.id, from: saved)
            } catch {
                missing += 1
                try? FileManager.default.removeItem(at: temp)
            }
        }

        applyRestore(backup: backup, map: { ids in ids.compactMap { idMap[$0] } })
        applyRestoreExtras(lyrics: backup.lyrics, map: { idMap[$0] }, restoredCovers: restoredCovers)
        return FullRestoreResult(restored: restored, matched: idMap.count - restored - fromImported, missing: missing,
                                 playlists: backup.playlists.count, fromImported: fromImported)
    }

    /// „Playlists und Herzen übernehmen“: liest nur die Mediathek einer Sicherung (ZIP mit oder ohne Musik, auch aus der
    /// Android-App, oder die kleine Sicherung) und übernimmt Playlists, Herzen, Kurzwahl, Statistik, Songtexte und eigene
    /// Cover für die Lieder, die schon in der Titel-Liste sind. Es kommt kein Lied dazu – gedacht für danach, wenn man
    /// unter „Importierte Lieder“ ausgesucht hat. Lässt sich beliebig oft wiederholen.
    func restoreInfo(from url: URL) throws -> InfoRestoreResult {
        let backup: LibraryState
        var rawIDs: [UUID: String] = [:]
        var zip: ZipReader?
        if ZipReader.isZip(url) {
            let reader = try ZipReader(url: url)
            guard let indexEntry = reader.entry(named: PortableBackup.index),
                  let decoded = PortableBackup.decode(try reader.data(of: indexEntry)) else { throw BackupError.notABackup }
            backup = decoded.state
            rawIDs = decoded.rawIDs
            zip = reader
        } else {
            backup = try Self.decodeBackup(try Data(contentsOf: url))
        }

        var matcher = RestoreMatcher(existing: tracks)
        var idMap: [UUID: UUID] = [:]
        var result = InfoRestoreResult()
        for saved in backup.tracks {
            // Mit Musik in der Datei: Ein Treffer nur über den Dateinamen zählt erst, wenn auch die Größe stimmt
            var archivedSize: UInt64?
            if let zip {
                let raw = rawIDs[saved.id] ?? saved.id.uuidString.lowercased()
                archivedSize = (zip.entry(named: PortableBackup.music + raw + "__" + saved.fileName)
                    ?? zip.entry(named: PortableBackup.music + saved.fileName))?.size
            }
            if let existing = matcher.match(saved, archivedSize: archivedSize) {
                idMap[saved.id] = existing.id
            } else {
                result.missing += 1
            }
        }
        result.matched = idMap.count
        // Playlists, zu denen (noch) kein Lied in der Titel-Liste ist, werden nicht leer angelegt
        var chosen = backup
        chosen.playlists = backup.playlists.filter { list in list.trackIDs.contains { idMap[$0] != nil } }
        result.playlists = chosen.playlists.count
        result.favorites = Set(backup.favorites.compactMap { idMap[$0] }).count
        guard result.matched > 0 else { return result }

        applyRestore(backup: chosen, map: { ids in ids.compactMap { idMap[$0] } })
        let fm = FileManager.default
        let covers = backup.customCovers.compactMap { idMap[$0] }.filter { id in
            guard let song = self.track(id: id) else { return false }
            return fm.fileExists(atPath: song.artworkURL.path)
        }
        applyRestoreExtras(lyrics: backup.lyrics, map: { idMap[$0] }, restoredCovers: covers)
        return result
    }
}

/// Lieder unter „Importierte Lieder“, nachschlagbar über die Kennung oder über Titel + Interpret + Länge
private struct WaitingSongs {
    private var byID: [UUID: Track] = [:]
    private var byTitle: [String: [Track]] = [:]

    init(_ tracks: [Track]) {
        for track in tracks {
            byID[track.id] = track
            byTitle[Self.key(track), default: []].append(track)
        }
    }

    private static func key(_ track: Track) -> String {
        track.title.lowercased() + "\u{1}" + track.artist.lowercased()
    }

    /// Das wartende Lied zu einem Lied der Sicherung – jedes höchstens einmal
    mutating func take(_ saved: Track) -> Track? {
        let match = byID[saved.id] ?? byTitle[Self.key(saved)]?.first(where: { abs($0.duration - saved.duration) < 2 })
        guard let found = match else { return nil }
        byID[found.id] = nil
        byTitle[Self.key(found)]?.removeAll { $0.id == found.id }
        return found
    }
}

/// Findet Lieder aus einer Sicherung unter den Liedern, die vor dem Wiederherstellen schon in der App waren:
/// über die Kennung, über Titel + Interpret + Länge oder – nur wenn auch die Dateigröße stimmt – über den Dateinamen.
/// Nachschlagetabellen statt Suchen, damit auch Mediatheken mit tausenden Liedern flott gehen.
private struct RestoreMatcher {
    private var byID: [UUID: Track] = [:]
    private var byTitle: [String: [Track]] = [:]
    private var byFileName: [String: Track] = [:]
    /// Über den Dateinamen zugeordnete Lieder – jedes höchstens einmal
    private var claimedByName = Set<UUID>()

    init(existing: [Track]) {
        for track in existing {
            byID[track.id] = track
            byTitle[Self.key(track), default: []].append(track)
            if byFileName[track.fileName] == nil { byFileName[track.fileName] = track }
        }
    }

    private static func key(_ track: Track) -> String {
        track.title.lowercased() + "\u{1}" + track.artist.lowercased()
    }

    /// Ein Lied, das während des Wiederherstellens in die Titel-Liste kam (z. B. aus „Importierte Lieder“)
    mutating func add(_ track: Track) {
        if byID[track.id] == nil { byID[track.id] = track }
        byTitle[Self.key(track), default: []].append(track)
    }

    /// - Parameter archivedSize: Größe der Datei in der Sicherung (`nil`, wenn sie dort fehlt)
    mutating func match(_ saved: Track, archivedSize: UInt64?) -> Track? {
        if let track = byID[saved.id] { return track }
        if let track = byTitle[Self.key(saved)]?.first(where: { abs($0.duration - saved.duration) < 2 }) { return track }
        guard let track = byFileName[saved.fileName], !claimedByName.contains(track.id) else { return nil }
        if let archivedSize {
            // Gleicher Name, aber andere Datei (z. B. „Track 01.mp3“ aus einem anderen Album): aus der Sicherung holen
            let attributes = try? FileManager.default.attributesOfItem(atPath: track.fileURL.path)
            guard (attributes?[.size] as? NSNumber)?.uint64Value == archivedSize else { return nil }
        }
        claimedByName.insert(track.id)
        return track
    }
}
