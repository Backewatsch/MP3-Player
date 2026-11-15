import XCTest
@testable import MP3Player

// „Importierte Lieder“: Lieder von einem anderen Handy landen in einem eigenen Ordner und kommen erst
// nach eigener Auswahl in die Titel-Liste.

@MainActor
final class ImportedSongsTests: XCTestCase {
    private var library: Library!
    private var tracksBefore = Set<UUID>()
    private var importedBefore = Set<UUID>()
    private var playlistsBefore = Set<UUID>()
    private var folder: URL!
    /// Eigene Kennzeichnung je Testlauf: Im Simulator können Lieder anderer Tests liegen
    private var tag = ""

    /// Ein Lied in der Export-Datei
    private struct Song {
        let track: Track
        /// Testdatei mit dem Ton
        let fixture: String
        /// Name des Eintrags hinter „music/“, falls er vom Dateinamen abweicht
        var entry: String? = nil
    }

    override func setUp() async throws {
        library = Library(autoScan: false)
        tracksBefore = Set(library.tracks.map(\.id))
        importedBefore = Set(library.importedTracks.map(\.id))
        playlistsBefore = Set(library.playlists.map(\.id))
        tag = String(UUID().uuidString.prefix(6))
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("Import-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        library.deleteImported(library.importedTracks.filter { !importedBefore.contains($0.id) }.map(\.id))
        library.deleteAll(library.tracks.filter { !tracksBefore.contains($0.id) })
        for playlist in library.playlists where !playlistsBefore.contains(playlist.id) { library.deletePlaylist(playlist) }
        try? FileManager.default.removeItem(at: folder)
        library = nil
    }

    private func song(_ name: String, artist: String, duration: Double) -> Track {
        Track(id: UUID(), fileName: "\(name)-\(tag).mp3", title: "\(name) \(tag)", artist: artist, duration: duration,
              addedAt: Date(timeIntervalSince1970: 1_700_000_000))
    }

    /// Export-Datei wie vom alten Handy: library.json, Lieder und Cover
    private func archive(_ songs: [Song], artworkFor: [UUID] = [], playlists: [Playlist] = []) throws -> URL {
        let url = folder.appendingPathComponent("lieder-\(UUID().uuidString).zip")
        let zip = try ZipWriter(url: url)
        // Herzen stehen mit in der Datei – sie dürfen beim Importieren nicht mitkommen
        let state = LibraryState(tracks: songs.map(\.track), playlists: playlists, pinned: [], favorites: songs.map { $0.track.id })
        try zip.add(name: "library.json", data: try PortableBackup.encode(state))
        for song in songs {
            try zip.add(name: "music/" + (song.entry ?? song.track.fileName), fileAt: Fixture.url(song.fixture))
        }
        for id in artworkFor {
            try zip.add(name: "artwork/" + id.uuidString.lowercased() + ".jpg", data: Data([1, 2, 3]))
        }
        try zip.finish()
        return url
    }

    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    func testSongsLandInTheirOwnFolderAndOnlyChosenOnesJoinTheLibrary() async throws {
        let a = song("Sommer", artist: "Band A", duration: 3.03)
        let b = song("Winter", artist: "Band B", duration: 2.04)
        let url = try archive([Song(track: a, fixture: "tagged.mp3"), Song(track: b, fixture: "untagged.mp3")], artworkFor: [b.id])
        let titlesBefore = library.tracks.map(\.title)
        let favoritesBefore = library.favoriteTracks.count

        let result = try await library.importSongs(from: url)
        XCTAssertEqual(result, SongImportResult(staged: 2, alreadyThere: 0, failed: 0))
        XCTAssertEqual(library.tracks.map(\.title), titlesBefore, "die Titel-Liste bleibt unverändert")
        XCTAssertEqual(library.favoriteTracks.count, favoritesBefore, "Herzen kommen auf diesem Weg nicht mit")
        let stagedA = try XCTUnwrap(library.importedTracks.first { $0.id == a.id })
        let stagedB = try XCTUnwrap(library.importedTracks.first { $0.id == b.id })
        XCTAssertEqual(stagedA.title, a.title)
        XCTAssertEqual(stagedA.artist, "Band A", "Angaben aus der Export-Datei, nicht aus der MP3")
        XCTAssertEqual(stagedA.addedAt, a.addedAt)
        XCTAssertEqual(stagedA.duration, 3.03, accuracy: 0.001)
        XCTAssertEqual(try Data(contentsOf: library.importedFileURL(stagedA)), try Data(contentsOf: Fixture.url("tagged.mp3")))
        XCTAssertEqual(library.importedFileURL(stagedB).deletingLastPathComponent().lastPathComponent, "Importierte Lieder")
        XCTAssertFalse(exists(Storage.documents.appendingPathComponent(stagedA.fileName)), "nicht im Musikordner selbst")
        XCTAssertEqual(try Data(contentsOf: stagedB.artworkURL), Data([1, 2, 3]), "Cover aus der Export-Datei")

        // Noch einmal importieren: nichts kommt doppelt
        let again = try await library.importSongs(from: url)
        XCTAssertEqual(again, SongImportResult(staged: 0, alreadyThere: 2, failed: 0))

        // Der Abgleich des Musikordners lässt importierte Lieder in Ruhe
        await library.rescan()
        XCTAssertNil(library.track(id: a.id))
        XCTAssertEqual(library.importedTracks.filter { $0.id == a.id || $0.id == b.id }.count, 2)

        // Eins übernehmen: wandert in die Titel-Liste und in den Musikordner
        XCTAssertTrue(library.adoptImported(a.id))
        let adopted = try XCTUnwrap(library.track(id: a.id))
        XCTAssertEqual(adopted.title, a.title)
        XCTAssertEqual(try Data(contentsOf: adopted.fileURL), try Data(contentsOf: Fixture.url("tagged.mp3")))
        XCTAssertFalse(exists(library.importedFileURL(stagedA)))
        XCTAssertNil(library.importedTracks.first { $0.id == a.id })
        await library.rescan()
        XCTAssertEqual(library.tracks.filter { $0.title == a.title }.count, 1, "der Abgleich nimmt das Lied nicht noch einmal auf")
        let third = try await library.importSongs(from: url)
        XCTAssertEqual(third, SongImportResult(staged: 0, alreadyThere: 2, failed: 0))

        // Das andere löschen: Eintrag, Datei und Cover weg – die Titel-Liste bleibt
        XCTAssertEqual(library.deleteImported([b.id]), 1)
        XCTAssertNil(library.importedTracks.first { $0.id == b.id })
        XCTAssertFalse(exists(library.importedFileURL(stagedB)))
        XCTAssertFalse(exists(stagedB.artworkURL))
        XCTAssertNotNil(library.track(id: a.id))
    }

    func testPlaylistsAndHeartsFollowTheChosenSongs() async throws {
        let a = song("Sommer", artist: "Band A", duration: 3.03)
        let b = song("Winter", artist: "Band B", duration: 2.04)
        let mix = Playlist(id: UUID(), name: "Mix \(tag)", trackIDs: [a.id, b.id])
        let onlyWinter = Playlist(id: UUID(), name: "Nur Winter \(tag)", trackIDs: [b.id])
        let url = try archive([Song(track: a, fixture: "tagged.mp3"), Song(track: b, fixture: "untagged.mp3")],
                              playlists: [mix, onlyWinter])
        _ = try await library.importSongs(from: url)

        // Noch nichts übernommen: Es wird nichts angelegt
        let nothing = try library.restoreInfo(from: url)
        XCTAssertEqual(nothing, InfoRestoreResult(matched: 0, missing: 2, playlists: 0, favorites: 0))
        XCTAssertNil(library.playlists.first { $0.name == mix.name })

        // Nur „Sommer“ übernehmen: Playlist und Herz kommen nur für dieses Lied
        XCTAssertTrue(library.adoptImported(a.id))
        let first = try library.restoreInfo(from: url)
        XCTAssertEqual(first, InfoRestoreResult(matched: 1, missing: 1, playlists: 1, favorites: 1))
        XCTAssertEqual(library.playlists.first { $0.name == mix.name }?.trackIDs, [a.id])
        XCTAssertNil(library.playlists.first { $0.name == onlyWinter.name }, "keine leere Playlist")
        XCTAssertTrue(library.favoriteIDs.contains(a.id))
        XCTAssertFalse(library.favoriteIDs.contains(b.id))
        XCTAssertNil(library.track(id: b.id), "es kommt kein Lied dazu")
        XCTAssertNotNil(library.importedTracks.first { $0.id == b.id }, "das nicht ausgesuchte Lied wartet weiter")

        // Später auch „Winter“ übernehmen und wiederholen: nichts doppelt, der Rest kommt dazu
        XCTAssertTrue(library.adoptImported(b.id))
        let second = try library.restoreInfo(from: url)
        XCTAssertEqual(second, InfoRestoreResult(matched: 2, missing: 0, playlists: 2, favorites: 2))
        XCTAssertEqual(library.playlists.first { $0.name == mix.name }?.trackIDs, [a.id, b.id])
        XCTAssertEqual(library.playlists.first { $0.name == onlyWinter.name }?.trackIDs, [b.id])
        XCTAssertEqual(library.favoriteIDs.filter { $0 == a.id || $0 == b.id }.count, 2)
    }

    func testFullRestoreTakesWaitingSongsFromTheImportedFolder() async throws {
        let a = song("Sommer", artist: "Band A", duration: 3.03)
        let b = song("Winter", artist: "Band B", duration: 2.04)
        let mix = Playlist(id: UUID(), name: "Mix \(tag)", trackIDs: [a.id, b.id])
        let url = try archive([Song(track: a, fixture: "tagged.mp3"), Song(track: b, fixture: "untagged.mp3")], playlists: [mix])
        _ = try await library.importSongs(from: url)

        // „Sicherung wiederherstellen“ mit derselben Datei: Die wartenden Lieder ziehen um, nichts liegt doppelt da
        let result = try await library.restoreFull(from: url)
        XCTAssertEqual(result.fromImported, 2)
        XCTAssertEqual(result.restored, 0)
        XCTAssertEqual(result.missing, 0)
        XCTAssertNotNil(library.track(id: a.id))
        XCTAssertNotNil(library.track(id: b.id))
        XCTAssertEqual(library.tracks.filter { $0.title == a.title || $0.title == b.title }.count, 2)
        XCTAssertNil(library.importedTracks.first { $0.id == a.id || $0.id == b.id })
        XCTAssertEqual(Set(library.playlists.first { $0.name == mix.name }?.trackIDs ?? []), [a.id, b.id])
    }

    func testFolderIsKeptInSyncWithTheList() async throws {
        let a = song("Eins", artist: "Band", duration: 2.04)
        _ = try await library.importSongs(from: try archive([Song(track: a, fixture: "untagged.mp3")]))
        let staged = try XCTUnwrap(library.importedTracks.first { $0.id == a.id })

        // Über die Dateien-App in den Ordner kopiert → erscheint in der Liste
        let copied = "Kopiert-\(tag).mp3"
        try FileManager.default.copyItem(at: Fixture.url("tagged.mp3"), to: Storage.importedDirectory.appendingPathComponent(copied))
        await library.syncImportedFolder()
        let added = try XCTUnwrap(library.importedTracks.first { $0.fileName == copied })
        XCTAssertEqual(added.title, "Testlied", "Angaben aus der Datei")
        XCTAssertNil(library.tracks.first { $0.fileName == copied }, "nicht in der Titel-Liste")

        // Datei gelöscht → der Eintrag verschwindet
        try FileManager.default.removeItem(at: library.importedFileURL(staged))
        await library.syncImportedFolder()
        XCTAssertNil(library.importedTracks.first { $0.id == a.id })
        XCTAssertNotNil(library.importedTracks.first { $0.fileName == copied })

        // Übernehmen eines Liedes, dessen Datei fehlt: Eintrag weg, nichts übernommen
        try FileManager.default.removeItem(at: library.importedFileURL(added))
        XCTAssertFalse(library.adoptImported(added.id))
        XCTAssertNil(library.importedTracks.first { $0.id == added.id })
        XCTAssertNil(library.track(id: added.id))
    }

    func testImportedSongsSurviveARestart() async throws {
        let a = song("Bleibt", artist: "Band", duration: 2.04)
        _ = try await library.importSongs(from: try archive([Song(track: a, fixture: "untagged.mp3")]))
        // Eine neue Mediathek lädt den gespeicherten Stand (wie nach einem Neustart der App)
        let restarted = Library(autoScan: false)
        XCTAssertEqual(restarted.importedTracks.first { $0.id == a.id }?.title, a.title)
        XCTAssertNil(restarted.track(id: a.id))
    }

    func testBackupsNeverContainImportedSongs() async throws {
        let a = song("Geheim", artist: "Band", duration: 2.04)
        _ = try await library.importSongs(from: try archive([Song(track: a, fixture: "untagged.mp3")]))

        // Kleine Sicherung (ohne Musik)
        let small = try BackupDocument(state: library.stateSnapshot)
        XCTAssertTrue(try Library.decodeBackup(small.data).imported.isEmpty)
        XCTAssertFalse(String(decoding: small.data, as: UTF8.self).contains(a.title))

        // Komplett-Sicherung (mit Musik)
        let url = try await library.makeFullBackup()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let zip = try ZipReader(url: url)
        XCTAssertNil(zip.entry(named: "music/" + a.fileName))
        let json = try zip.data(of: try XCTUnwrap(zip.entry(named: "library.json")))
        XCTAssertFalse(String(decoding: json, as: UTF8.self).contains(a.title))
    }

    /// Mit Java geschrieben wie von der Android-App: gepackte Einträge, zwei Lieder, Cover für das zweite
    func testArchiveFromTheAndroidAppIsAccepted() async throws {
        let result = try await library.importSongs(from: Fixture.url("android-backup.zip"))
        XCTAssertEqual(result.staged + result.alreadyThere, 2)
        XCTAssertEqual(result.failed, 0)
        // „Über den Wolken“ liegt jetzt bei den importierten Liedern – außer ein solches Lied war schon da
        if let wolken = library.importedTracks.first(where: { $0.title == "Über den Wolken" && !importedBefore.contains($0.id) }) {
            XCTAssertEqual(wolken.artist, "Reinhard")
            XCTAssertEqual(try Data(contentsOf: library.importedFileURL(wolken)), try Data(contentsOf: Fixture.url("untagged.mp3")))
            XCTAssertTrue(exists(wolken.artworkURL), "Cover aus der Sicherung")
            XCTAssertNil(library.track(id: wolken.id), "nicht in der Titel-Liste")
        } else {
            XCTAssertGreaterThanOrEqual(result.alreadyThere, 1)
        }
    }

    /// Die Android-App schreibt bei doppeltem Dateinamen die Kennung vor den zweiten Eintrag
    func testTwoSongsWithTheSameFileNameAreBothImported() async throws {
        var a = song("Intro A", artist: "Band A", duration: 3.03)
        var b = song("Intro B", artist: "Band B", duration: 30)
        a.fileName = "Intro-\(tag).mp3"
        b.fileName = a.fileName
        let url = try archive([Song(track: a, fixture: "tagged.mp3"),
                               Song(track: b, fixture: "long.mp3", entry: b.id.uuidString.lowercased() + "__" + b.fileName)])

        let result = try await library.importSongs(from: url)
        XCTAssertEqual(result, SongImportResult(staged: 2, alreadyThere: 0, failed: 0))
        let stagedA = try XCTUnwrap(library.importedTracks.first { $0.id == a.id })
        let stagedB = try XCTUnwrap(library.importedTracks.first { $0.id == b.id })
        XCTAssertNotEqual(stagedA.fileName, stagedB.fileName)
        XCTAssertEqual(stagedB.title, b.title)
        XCTAssertEqual(try Data(contentsOf: library.importedFileURL(stagedA)), try Data(contentsOf: Fixture.url("tagged.mp3")))
        XCTAssertEqual(try Data(contentsOf: library.importedFileURL(stagedB)), try Data(contentsOf: Fixture.url("long.mp3")))

        // Beide auf einmal übernehmen
        let count = await library.adoptImported([a.id, b.id])
        XCTAssertEqual(count, 2)
        let adoptedB = try XCTUnwrap(library.track(id: b.id))
        XCTAssertEqual(try Data(contentsOf: adoptedB.fileURL), try Data(contentsOf: Fixture.url("long.mp3")))
        XCTAssertNotEqual(adoptedB.fileName, try XCTUnwrap(library.track(id: a.id)).fileName)
    }

    func testWrongFilesGiveClearMessages() async throws {
        do {
            _ = try await library.importSongs(from: Fixture.url("tagged.mp3"))
            XCTFail("eine MP3 ist keine Sicherung")
        } catch {
            XCTAssertEqual(error.localizedDescription, "Das ist keine Sicherung von ILLEGAL FREEDOM MUSIC.")
        }

        // Sicherung ohne Musik
        let url = folder.appendingPathComponent("ohne-musik.zip")
        let zip = try ZipWriter(url: url)
        let state = LibraryState(tracks: [song("Ohne", artist: "Band", duration: 1)], playlists: [], pinned: [])
        try zip.add(name: "library.json", data: try PortableBackup.encode(state))
        try zip.finish()
        do {
            _ = try await library.importSongs(from: url)
            XCTFail("ohne Lieder gibt es nichts zu importieren")
        } catch {
            XCTAssertTrue(error.localizedDescription.hasPrefix("Diese Sicherung enthält keine Lieder."), error.localizedDescription)
        }
    }

    func testRecognisesTheSameSong() {
        func track(_ title: String, _ artist: String, _ duration: Double, file: String = "a.mp3") -> Track {
            Track(id: UUID(), fileName: file, title: title, artist: artist, duration: duration, addedAt: Date())
        }
        let known = track("Sommernacht", "Bella", 180)
        var index = SongIndex([known])
        XCTAssertTrue(index.contains(known), "gleiche Kennung")
        XCTAssertTrue(index.hasID(known.id))
        XCTAssertTrue(index.contains(track("SOMMERNACHT", "bella", 181.5)), "gleicher Titel und Interpret, fast gleiche Länge")
        XCTAssertFalse(index.contains(track("Sommernacht", "Bella", 240)), "andere Fassung")
        XCTAssertFalse(index.contains(track("Sommernacht", "Band", 180)), "anderer Interpret")
        XCTAssertFalse(index.contains(track("Intro", "Wer", 180, file: "a.mp3")), "der Dateiname allein zählt nicht")
        let later = track("Regen", "Band", 200)
        XCTAssertFalse(index.contains(later))
        index.add(later)
        XCTAssertTrue(index.contains(track("regen", "BAND", 200.9)))
    }

    func testSummaryTexts() {
        XCTAssertEqual(SongImportResult(staged: 118, alreadyThere: 2, failed: 0).summary,
                       "✓ 118 Lieder liegen jetzt in der Mediathek unter „Importierte Lieder“. 2 waren schon da.")
        XCTAssertEqual(SongImportResult(staged: 1, alreadyThere: 1, failed: 3).summary,
                       "✓ 1 Lied liegt jetzt in der Mediathek unter „Importierte Lieder“. 1 war schon da. 3 ließen sich nicht lesen.")
        XCTAssertEqual(SongImportResult(staged: 0, alreadyThere: 5, failed: 0).summary, "Keine neuen Lieder importiert. 5 waren schon da.")
    }
}
