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
        var idMap: [UUID: UUID] = [:]
        var restored = 0
        var missing = 0
        let folder = Storage.makeIncomingDirectory()
        defer { try? FileManager.default.removeItem(at: folder) }

        for (n, saved) in backup.tracks.enumerated() {
            progress(n + 1, backup.tracks.count)
            if let existing = matchingTrack(for: saved) {
                idMap[saved.id] = existing.id
                continue
            }
            let raw = decoded.rawIDs[saved.id] ?? saved.id.uuidString.lowercased()
            // Bei gleichem Dateinamen zweimal hängt die Android-App die Kennung davor
            guard let entry = zip.entry(named: PortableBackup.music + saved.fileName)
                    ?? zip.entry(named: PortableBackup.music + raw + "__" + saved.fileName) else {
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
                if let art = zip.entry(named: PortableBackup.artwork + raw + ".jpg"),
                   backup.customCovers.contains(saved.id) || !FileManager.default.fileExists(atPath: track.artworkURL.path) {
                    try? zip.extract(art, to: track.artworkURL)
                    ArtworkCache.shared.remove(track.id)
                }
                adoptBackupInfo(track.id, from: saved)
            } catch {
                missing += 1
                try? FileManager.default.removeItem(at: temp)
            }
        }

        applyRestore(backup: backup, map: { ids in ids.compactMap { idMap[$0] } })
        applyRestoreExtras(lyrics: backup.lyrics, customCovers: backup.customCovers, map: { idMap[$0] })
        return FullRestoreResult(restored: restored, matched: idMap.count - restored, missing: missing,
                                 playlists: backup.playlists.count)
    }
}
