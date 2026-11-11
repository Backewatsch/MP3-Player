import XCTest
@testable import MP3Player

// MARK: - ZIP lesen und schreiben

@MainActor
final class ZipTests: XCTestCase {
    private func tempFile(_ name: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("Zip-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(name)
    }

    /// 70 000 Byte nach festem Muster (so steht es auch in der Testdatei zip64.zip)
    private var pattern: Data { Data((0..<70_000).map { UInt8(($0 * 31 + 7) % 251) }) }

    func testChecksumMatchesZlib() {
        // Vergleichswerte mit zlib berechnet
        XCTAssertEqual(CRC32.update(0, Data(#"{"version":4}"#.utf8)), 0x0C47_6E76)
        XCTAssertEqual(CRC32.update(0, Data()), 0)
        XCTAssertEqual(CRC32.update(0, pattern), 0x255E_C7FE)
        // In zwei Stücken gerechnet kommt dasselbe heraus
        let all = pattern
        let first = CRC32.update(0, all.prefix(33_333))
        XCTAssertEqual(CRC32.update(first, all.suffix(from: 33_333)), 0x255E_C7FE)
    }

    /// Mit Javas ZipOutputStream geschrieben – so schreibt die Android-App ihre Sicherung:
    /// alle Einträge gepackt, Größe und Prüfsumme stehen erst hinter den Daten.
    func testReadsArchiveWrittenLikeAndroid() throws {
        let zip = try ZipReader(url: Fixture.url("android-backup.zip"))
        XCTAssertEqual(zip.entries.map(\.name), ["library.json", "music/tagged.mp3", "music/Über den Wolken.mp3",
                                                  "artwork/7b1d6c1e-5a0f-4d6e-9c1a-0c3b5e2f1a02.jpg"])
        XCTAssertTrue(zip.entries.allSatisfy { $0.method == 8 }, "alle Einträge sind gepackt")
        let song = try XCTUnwrap(zip.entry(named: "music/tagged.mp3"))
        XCTAssertEqual(try zip.data(of: song), try Data(contentsOf: Fixture.url("tagged.mp3")))
        let target = tempFile("wolken.mp3")
        try zip.extract(try XCTUnwrap(zip.entry(named: "music/Über den Wolken.mp3")), to: target)
        XCTAssertEqual(try Data(contentsOf: target), try Data(contentsOf: Fixture.url("untagged.mp3")))
        XCTAssertNil(zip.entry(named: "music/fehlt.mp3"))
        XCTAssertTrue(ZipReader.isZip(Fixture.url("android-backup.zip")))
        XCTAssertFalse(ZipReader.isZip(Fixture.url("tagged.mp3")))
    }

    /// ZIP64: die Felder für Archive über 4 GB (in der Testdatei erzwungen, damit sie klein bleibt)
    func testReadsHugeArchiveFormat() throws {
        let zip = try ZipReader(url: Fixture.url("zip64.zip"))
        XCTAssertEqual(zip.entries.map(\.name), ["library.json", "music/Über den Wolken.mp3", "leer.txt"])
        XCTAssertEqual(try zip.data(of: zip.entries[0]), Data(#"{"version":4}"#.utf8))
        XCTAssertEqual(try zip.data(of: zip.entries[1]), pattern)
        XCTAssertEqual(try zip.data(of: zip.entries[2]), Data())
    }

    func testWriterAndReaderRoundTrip() throws {
        // größer als ein Lese-Block, damit auch das stückweise Lesen geprüft wird
        let big = Data((0..<600_000).map { UInt8(($0 * 7 + 3) % 256) })
        let source = tempFile("quelle.bin")
        try big.write(to: source)
        for force in [false, true] {
            let url = tempFile("test.zip")
            let writer = try ZipWriter(url: url, forceZip64: force)
            try writer.add(name: "library.json", data: Data("{}".utf8))
            try writer.add(name: "music/Größe & Länge.mp3", fileAt: source)
            try writer.add(name: "leer", data: Data())
            try writer.finish()
            let zip = try ZipReader(url: url)
            XCTAssertEqual(zip.entries.map(\.name), ["library.json", "music/Größe & Länge.mp3", "leer"], "ZIP64: \(force)")
            XCTAssertEqual(try zip.data(of: zip.entries[0]), Data("{}".utf8))
            XCTAssertEqual(try zip.data(of: zip.entries[1]), big)
            XCTAssertEqual(try zip.data(of: zip.entries[2]), Data())
            XCTAssertEqual(zip.entries[1].size, 600_000)
            XCTAssertEqual(zip.entries[1].method, 0, "ohne Kompression geschrieben")
        }
    }

    func testRejectsBrokenArchives() throws {
        XCTAssertThrowsError(try ZipReader(url: Fixture.url("tagged.mp3")), "eine MP3 ist kein Archiv")
        let data = try Data(contentsOf: Fixture.url("android-backup.zip"))
        // Abgeschnitten: das Inhaltsverzeichnis am Ende fehlt
        let cut = tempFile("abgeschnitten.zip")
        try data.prefix(data.count / 2).write(to: cut)
        XCTAssertThrowsError(try ZipReader(url: cut))
        // Ein Byte mitten im Lied verfälscht: Entpacken oder Prüfsumme schlägt an
        var bad = data
        bad[2_000] ^= 0xFF
        let badURL = tempFile("verfaelscht.zip")
        try bad.write(to: badURL)
        let zip = try ZipReader(url: badURL)
        XCTAssertThrowsError(try zip.data(of: try XCTUnwrap(zip.entry(named: "music/tagged.mp3"))))
    }
}

// MARK: - Format der Komplett-Sicherung (wie in der Android-App)

@MainActor
final class PortableBackupTests: XCTestCase {
    func testReadsLibraryOfAndroidApp() throws {
        let zip = try ZipReader(url: Fixture.url("android-backup.zip"))
        let json = try zip.data(of: try XCTUnwrap(zip.entry(named: PortableBackup.index)))
        let decoded = try XCTUnwrap(PortableBackup.decode(json))
        let state = decoded.state
        XCTAssertEqual(state.tracks.map(\.title), ["Testlied", "Über den Wolken"])
        XCTAssertEqual(state.tracks[0].duration, 3.03, accuracy: 0.001, "Millisekunden werden Sekunden")
        XCTAssertEqual(state.tracks[0].addedAt.timeIntervalSince1970, 1_762_700_000, accuracy: 0.5)
        XCTAssertEqual(state.tracks[0].genre, "Pop")
        XCTAssertEqual(state.tracks[1].artist, "Reinhard")
        let a = state.tracks[0].id
        let b = state.tracks[1].id
        XCTAssertEqual(decoded.rawIDs[b], "7b1d6c1e-5a0f-4d6e-9c1a-0c3b5e2f1a02")
        XCTAssertEqual(state.playlists.map(\.name), ["Heruntergeladen", "Test-Unterwegs"])
        XCTAssertEqual(state.playlists[0].id, Library.downloadsPlaylistID)
        XCTAssertEqual(state.playlists[1].trackIDs, [b, a])
        XCTAssertEqual(state.favorites, [b])
        XCTAssertEqual(state.pinned, [b])
        XCTAssertEqual(state.playCounts[a], 7)
        XCTAssertEqual(state.history.first?.trackID, a)
        XCTAssertEqual(state.plays.count, 1)
        XCTAssertEqual(state.listenedByMonth["2026-11"] ?? 0, 123, accuracy: 0.001)
        XCTAssertEqual(state.gains[a] ?? 0, -2.5, accuracy: 0.001)
        XCTAssertEqual(state.lyrics[b]?.hasPrefix("Über den Wolken"), true)
        XCTAssertEqual(state.customCovers, [b])
        XCTAssertNil(PortableBackup.decode(Data("kein json".utf8)))
        XCTAssertNil(PortableBackup.decode(Data("{}".utf8)))
    }

    func testWritesWhatTheAndroidAppExpects() throws {
        var track = Track(id: UUID(), fileName: "a.mp3", title: "Regen", artist: "Bella", duration: 185.4,
                          addedAt: Date(timeIntervalSince1970: 1_700_000_000))
        track.album = "Nachts"
        track.genre = "Pop"
        let playlist = Playlist(id: UUID(), name: "Mix", trackIDs: [track.id])
        var state = LibraryState(tracks: [track], playlists: [playlist], pinned: [track.id], favorites: [track.id],
                                 playCounts: [track.id: 3],
                                 history: [PlayEvent(trackID: track.id, at: Date(timeIntervalSince1970: 1_700_000_100))],
                                 plays: [], listenedByMonth: ["2026-11": 61.5])
        state.gains = [track.id: -1.5]
        state.lyrics = [track.id: "Text"]
        state.customCovers = [track.id]

        let data = try PortableBackup.encode(state)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let id = track.id.uuidString.lowercased()
        let first = try XCTUnwrap((root["tracks"] as? [[String: Any]])?.first)
        XCTAssertEqual(first["id"] as? String, id, "Kennungen klein geschrieben")
        XCTAssertEqual(first["durationMs"] as? Int, 185_400, "Dauer in Millisekunden")
        XCTAssertEqual(first["addedAt"] as? Int, 1_700_000_000_000, "Zeit in Millisekunden")
        XCTAssertEqual((root["playlists"] as? [[String: Any]])?.first?["trackIds"] as? [String], [id])
        XCTAssertEqual((root["listenedByMonth"] as? [String: Any])?["2026-11"] as? Int, 61_500)
        XCTAssertEqual(((root["history"] as? [[String: Any]])?.first)?["trackId"] as? String, id)

        // Und wieder zurück: nichts geht verloren
        let back = try XCTUnwrap(PortableBackup.decode(data)).state
        XCTAssertEqual(back.tracks, [track])
        XCTAssertEqual(back.playlists, [playlist])
        XCTAssertEqual(back.pinned, [track.id])
        XCTAssertEqual(back.favorites, [track.id])
        XCTAssertEqual(back.playCounts, [track.id: 3])
        XCTAssertEqual(back.history, state.history)
        XCTAssertEqual(back.listenedByMonth["2026-11"] ?? 0, 61.5, accuracy: 0.001)
        XCTAssertEqual(back.gains[track.id] ?? 0, -1.5, accuracy: 0.001)
        XCTAssertEqual(back.lyrics, [track.id: "Text"])
        XCTAssertEqual(back.customCovers, [track.id])
    }
}

// MARK: - Komplett-Sicherung erstellen und wiederherstellen

@MainActor
final class FullBackupTests: XCTestCase {
    private var library: Library!
    private var before = Set<UUID>()

    override func setUp() async throws {
        library = Library(autoScan: false)
        before = Set(library.tracks.map(\.id))
    }

    override func tearDown() async throws {
        library.deleteAll(library.tracks.filter { !before.contains($0.id) })
        for p in library.playlists where p.name.hasPrefix("Test-") { library.deletePlaylist(p) }
        library = nil
    }

    func testFullBackupBringsSongsBack() async throws {
        let tag = String(UUID().uuidString.prefix(6))
        let source = try Fixture.tempCopy("tagged.mp3", as: "Test-Sicherung-\(tag).mp3")
        let song = try await library.importAudio(from: source, move: true)
        // Eigener Titel, damit das Lied nicht mit anderen Testliedern verwechselt wird
        library.editInfo(song, title: "Sicherung \(tag)", artist: "Backup-Band", album: "Archiv", genre: "Pop")
        let playlist = library.createPlaylist(named: "Test-Komplett")
        library.addAll([song], to: playlist)
        library.toggleFavorite(song)
        library.recordPlay(song.id)
        library.setLyrics(song, "Zeile eins")
        await Library.awaitFileWrites()
        let fileName = song.fileName
        let original = try Data(contentsOf: song.fileURL)

        let backupURL = try await library.makeFullBackup()
        defer { try? FileManager.default.removeItem(at: backupURL.deletingLastPathComponent()) }
        XCTAssertTrue(ZipReader.isZip(backupURL))
        let zip = try ZipReader(url: backupURL)
        XCTAssertNotNil(zip.entry(named: "library.json"))
        XCTAssertEqual(try zip.data(of: try XCTUnwrap(zip.entry(named: "music/" + fileName))), original,
                       "das Lied steckt unverändert in der Sicherung")

        // Alles weg – wie auf einem neuen iPhone
        library.deletePlaylist(try XCTUnwrap(library.playlist(id: playlist.id)))
        library.delete(song)
        XCTAssertNil(library.track(id: song.id))
        XCTAssertFalse(FileManager.default.fileExists(atPath: song.fileURL.path))

        let result = try await library.restoreFull(from: backupURL)
        XCTAssertGreaterThanOrEqual(result.restored, 1)
        let back = try XCTUnwrap(library.tracks.first { $0.title == "Sicherung \(tag)" })
        XCTAssertEqual(try Data(contentsOf: back.fileURL), original)
        XCTAssertEqual(back.artist, "Backup-Band")
        XCTAssertTrue(library.isFavorite(back), "Herz ist wieder da")
        let restored = try XCTUnwrap(library.playlists.first { $0.name == "Test-Komplett" })
        XCTAssertEqual(restored.trackIDs, [back.id])
        XCTAssertEqual(library.playCount(back), 1)
        XCTAssertEqual(library.storedLyrics(back), "Zeile eins")

        // Noch einmal wiederherstellen: nichts kommt doppelt
        let again = try await library.restoreFull(from: backupURL)
        XCTAssertEqual(again.restored, 0)
        XCTAssertEqual(library.tracks.filter { $0.title == "Sicherung \(tag)" }.count, 1)
        XCTAssertEqual(library.playlists.filter { $0.name == "Test-Komplett" }.count, 1)
    }

    /// Eine Sicherung aus der Android-App (mit Musik) bringt Lieder, Playlists, Herzen und Cover aufs iPhone
    func testRestoresBackupFromAndroidApp() async throws {
        let result = try await library.restoreFull(from: Fixture.url("android-backup.zip"))
        XCTAssertEqual(result.restored + result.matched, 2)
        XCTAssertEqual(result.missing, 0)
        XCTAssertEqual(result.playlists, 2)

        let wolken = try XCTUnwrap(library.tracks.first { $0.title == "Über den Wolken" })
        XCTAssertEqual(try Data(contentsOf: wolken.fileURL), try Data(contentsOf: Fixture.url("untagged.mp3")))
        XCTAssertEqual(wolken.artist, "Reinhard", "Angaben aus der Sicherung – die Datei selbst hat keine")
        XCTAssertEqual(wolken.addedAt.timeIntervalSince1970, 1_762_700_100, accuracy: 0.5)
        XCTAssertTrue(library.isFavorite(wolken))
        XCTAssertTrue(library.isPinned(wolken))
        XCTAssertEqual(library.storedLyrics(wolken)?.hasPrefix("Über den Wolken"), true)
        XCTAssertTrue(library.hasCustomCover(wolken))
        XCTAssertTrue(FileManager.default.fileExists(atPath: wolken.artworkURL.path), "Cover aus der Sicherung")

        let playlist = try XCTUnwrap(library.playlists.first { $0.name == "Test-Unterwegs" })
        XCTAssertEqual(playlist.trackIDs.count, 2)
        XCTAssertEqual(playlist.trackIDs.first, wolken.id)
        let testlied = try XCTUnwrap(library.track(id: playlist.trackIDs[1]))
        XCTAssertEqual(testlied.title, "Testlied")
        XCTAssertGreaterThanOrEqual(library.playCount(testlied), 7)
    }

    /// Die Android-App sichert zwei Lieder mit gleichem Dateinamen (aus verschiedenen Ordnern) als „Name“ und „<id>__Name“.
    /// Beide müssen zurückkommen – auch wenn auf dem iPhone schon eine andere Datei mit diesem Namen liegt.
    func testRestoresTwoSongsWithTheSameFileName() async throws {
        let tag = String(UUID().uuidString.prefix(6))
        let name = "Test-\(tag) Intro.mp3"
        // Auf dem iPhone liegt schon ein anderes Lied mit demselben Dateinamen
        let local = try await library.importAudio(from: try Fixture.tempCopy("untagged.mp3", as: name), move: true)
        let localData = try Data(contentsOf: local.fileURL)

        let a = Track(id: UUID(), fileName: name, title: "Intro A \(tag)", artist: "Band A", duration: 3.03,
                      addedAt: Date(timeIntervalSince1970: 1_700_000_000))
        let b = Track(id: UUID(), fileName: name, title: "Intro B \(tag)", artist: "Band B", duration: 2.04,
                      addedAt: Date(timeIntervalSince1970: 1_700_000_100))
        let playlist = Playlist(id: UUID(), name: "Test-Intros", trackIDs: [b.id, a.id])
        let state = LibraryState(tracks: [a, b], playlists: [playlist], pinned: [], favorites: [b.id])
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("Doppelt-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("sicherung.zip")
        let zip = try ZipWriter(url: url)
        try zip.add(name: "library.json", data: try PortableBackup.encode(state))
        try zip.add(name: "music/" + name, fileAt: Fixture.url("tagged.mp3"))
        try zip.add(name: "music/" + b.id.uuidString.lowercased() + "__" + name, fileAt: Fixture.url("long.mp3"))
        try zip.finish()

        let result = try await library.restoreFull(from: url)
        XCTAssertEqual(result.restored, 2, "beide Lieder kommen aus der Sicherung")
        XCTAssertEqual(result.missing, 0)
        let backA = try XCTUnwrap(library.tracks.first { $0.title == "Intro A \(tag)" })
        let backB = try XCTUnwrap(library.tracks.first { $0.title == "Intro B \(tag)" })
        XCTAssertNotEqual(backA.id, local.id)
        XCTAssertNotEqual(backB.id, local.id)
        XCTAssertNotEqual(backA.id, backB.id)
        XCTAssertEqual(try Data(contentsOf: backA.fileURL), try Data(contentsOf: Fixture.url("tagged.mp3")))
        XCTAssertEqual(try Data(contentsOf: backB.fileURL), try Data(contentsOf: Fixture.url("long.mp3")))
        XCTAssertEqual(try Data(contentsOf: local.fileURL), localData, "das vorhandene Lied bleibt unverändert")
        XCTAssertTrue(library.isFavorite(backB))
        XCTAssertFalse(library.isFavorite(backA))
        XCTAssertEqual(library.playlists.first { $0.name == "Test-Intros" }?.trackIDs, [backB.id, backA.id])
    }

    /// Eine Sicherung auf demselben iPhone wiederherstellen: Verlauf und Statistik zählen nichts doppelt
    func testRestoringOnTheSamePhoneDoublesNothing() async throws {
        let tag = String(UUID().uuidString.prefix(6))
        let song = try await library.importAudio(from: try Fixture.tempCopy("tagged.mp3", as: "Test-Gleich-\(tag).mp3"), move: true)
        library.recordStart(song.id)
        library.recordPlay(song.id)
        let beforeState = library.stateSnapshot
        let url = try await library.makeFullBackup()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }

        let result = try await library.restoreFull(from: url)
        XCTAssertEqual(result.restored, 0, "alle Lieder sind schon da")
        let afterState = library.stateSnapshot
        XCTAssertEqual(afterState.tracks.count, beforeState.tracks.count)
        XCTAssertEqual(afterState.plays.filter { $0.trackID == song.id }.count, 1)
        XCTAssertEqual(afterState.history.filter { $0.trackID == song.id }.count,
                       beforeState.history.filter { $0.trackID == song.id }.count)
        XCTAssertEqual(afterState.plays.count, beforeState.plays.count)
        XCTAssertEqual(library.playCount(song), 1)
    }

    func testRefusesFilesThatAreNoBackup() async throws {
        do {
            _ = try await library.restoreFull(from: Fixture.url("zip64.zip"))
            XCTFail("ein Archiv ohne Mediathek ist keine Sicherung")
        } catch {
            XCTAssertNotNil(error.localizedDescription)
        }
    }
}

// MARK: - Ordner nach Liedern durchsuchen

@MainActor
final class FolderScannerTests: XCTestCase {
    private var library: Library!
    private var before = Set<UUID>()
    private var root: URL!
    private var store: URL!

    override func setUp() async throws {
        library = Library(autoScan: false)
        before = Set(library.tracks.map(\.id))
        let temp = FileManager.default.temporaryDirectory
        root = temp.appendingPathComponent("Ordner-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root.appendingPathComponent("Alben/Sommer"), withIntermediateDirectories: true)
        store = temp.appendingPathComponent("folders-\(UUID().uuidString).json")
    }

    override func tearDown() async throws {
        library.deleteAll(library.tracks.filter { !before.contains($0.id) })
        try? FileManager.default.removeItem(at: root)
        try? FileManager.default.removeItem(at: store)
        library = nil
    }

    private func put(_ fixture: String, at path: String) throws {
        try FileManager.default.copyItem(at: Fixture.url(fixture), to: root.appendingPathComponent(path))
    }

    func testFindsSongsInSubfolders() throws {
        let tag = String(UUID().uuidString.prefix(6))
        try put("tagged.mp3", at: "Scan-\(tag)-a.mp3")
        try put("untagged.mp3", at: "Alben/Sommer/Scan-\(tag)-b.mp3")
        try Data("kein Lied".utf8).write(to: root.appendingPathComponent("Notiz.txt"))
        try Data("versteckt".utf8).write(to: root.appendingPathComponent(".versteckt.mp3"))

        let found = FolderScanner.audioFiles(in: root)
        XCTAssertEqual(found.map { $0.url.lastPathComponent }.sorted(), ["Scan-\(tag)-a.mp3", "Scan-\(tag)-b.mp3"])
        XCTAssertTrue(found.contains { $0.key.contains("/Alben/Sommer/Scan-\(tag)-b.mp3|16300") },
                      "Erkennungszeichen aus Pfad und Größe: \(found.map(\.key))")
    }

    func testScanImportsOnceAndRespectsDeletion() async throws {
        let tag = String(UUID().uuidString.prefix(6))
        try put("tagged.mp3", at: "Scan-\(tag)-a.mp3")
        try put("untagged.mp3", at: "Alben/Sommer/Scan-\(tag)-b.mp3")
        func mine() -> [Track] { library.tracks.filter { $0.fileName.hasPrefix("Scan-\(tag)-") } }

        let scanner = FolderScanner(storeURL: store)
        scanner.library = library
        let added = await scanner.add(root)
        XCTAssertEqual(added, .scanned(.init(added: 2, failed: 0)), scanner.status ?? "")
        XCTAssertEqual(scanner.folders.count, 1)
        XCTAssertEqual(scanner.folders.first?.seen.count, 2)
        XCTAssertEqual(mine().count, 2)
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("Scan-\(tag)-a.mp3").path),
                      "die Originale bleiben, wo sie sind")

        // Zweite Suche: nichts doppelt. Denselben Ordner noch einmal hinzufügen: bleibt ein Eintrag.
        let second = await scanner.scan(force: true)
        XCTAssertEqual(second?.added, 0)
        _ = await scanner.add(root)
        XCTAssertEqual(scanner.folders.count, 1)
        XCTAssertEqual(mine().count, 2)

        // In der App gelöscht → kommt bei der nächsten Suche nicht zurück
        library.delete(mine()[0])
        let third = await scanner.scan(force: true)
        XCTAssertEqual(third?.added, 0)
        XCTAssertEqual(mine().count, 1)

        // Neues Lied im Ordner → wird übernommen, auch nach einem Neustart (die Ordnerliste ist gespeichert)
        try put("untagged.mp3", at: "Alben/Scan-\(tag)-c.mp3")
        let restarted = FolderScanner(storeURL: store)
        restarted.library = library
        XCTAssertEqual(restarted.folders.count, 1)
        let fourth = await restarted.scan(force: true)
        XCTAssertEqual(fourth?.added, 1, restarted.status ?? "")
        XCTAssertTrue(library.tracks.contains { $0.fileName == "Scan-\(tag)-c.mp3" })

        restarted.remove(restarted.folders[0])
        XCTAssertTrue(restarted.folders.isEmpty)
    }

    /// Ordner entfernt und neu hinzugefügt: Nichts kommt doppelt – auch kein Lied, das beim Übernehmen zu MP3 umgewandelt wurde
    func testReaddedFolderBringsNoDuplicates() async throws {
        let tag = String(UUID().uuidString.prefix(6))
        try put("tagged.mp3", at: "Scan-\(tag)-a.mp3")
        try put("short.m4a", at: "Alben/Scan-\(tag)-d.m4a")
        func mine() -> [Track] { library.tracks.filter { $0.fileName.hasPrefix("Scan-\(tag)-") } }

        let scanner = FolderScanner(storeURL: store)
        scanner.library = library
        let first = await scanner.add(root)
        XCTAssertEqual(first, .scanned(.init(added: 2, failed: 0)), scanner.status ?? "")
        XCTAssertTrue(mine().contains { $0.fileName == "Scan-\(tag)-d.mp3" }, "M4A wird als MP3 übernommen")

        scanner.remove(scanner.folders[0])
        let again = await scanner.add(root)
        XCTAssertEqual(again, .scanned(.init(added: 0, failed: 0)), scanner.status ?? "")
        XCTAssertEqual(mine().count, 2)
    }

    /// Eine Datei, die sich nicht übernehmen lässt, versucht die nächste Suche erneut – nach drei Fehlschlägen nicht mehr
    func testFailedFilesAreRetriedThreeTimes() async throws {
        let tag = String(UUID().uuidString.prefix(6))
        try Data(repeating: 0x42, count: 4096).write(to: root.appendingPathComponent("Scan-\(tag)-kaputt.m4a"))
        let scanner = FolderScanner(storeURL: store)
        scanner.library = library
        let first = await scanner.add(root)
        XCTAssertEqual(first, .scanned(.init(added: 0, failed: 1)), scanner.status ?? "")
        XCTAssertEqual(scanner.folders.first?.seen.count, 0, "nicht als erledigt vermerkt")
        let second = await scanner.scan(force: true)
        XCTAssertEqual(second?.failed, 1, "zweiter Versuch")
        let third = await scanner.scan(force: true)
        XCTAssertEqual(third?.failed, 1, "dritter Versuch")
        let fourth = await scanner.scan(force: true)
        XCTAssertEqual(fourth?.failed, 0, "nach drei Fehlschlägen gibt die App auf")
        XCTAssertEqual(scanner.folders.first?.seen.count, 1)
    }
}
