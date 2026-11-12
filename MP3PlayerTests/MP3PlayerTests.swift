import XCTest
import AVFoundation
import UIKit
@testable import MP3Player

/// Hilfsfunktionen für die Tests.
enum Fixture {
    static func url(_ name: String) -> URL {
        let bundle = Bundle(for: BundleToken.self)
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        guard let url = bundle.url(forResource: base, withExtension: ext) else {
            fatalError("Testdatei \(name) fehlt im Test-Bundle")
        }
        return url
    }

    /// Kopie der Testdatei in einem eigenen temporären Ordner.
    static func tempCopy(_ name: String, as newName: String? = nil) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("Fixture-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(newName ?? name)
        try FileManager.default.copyItem(at: url(name), to: dest)
        return dest
    }

    private final class BundleToken {}
}

/// Wartet (ohne den Main-Thread zu blockieren), bis eine Bedingung erfüllt ist.
@MainActor
func waitUntil(timeout: TimeInterval = 10, _ condition: () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if condition() { return true }
        try? await Task.sleep(nanoseconds: 50_000_000)
    }
    return condition()
}

// MARK: - Reine Hilfsfunktionen

final class HelperTests: XCTestCase {
    func testAudioFileNameKeepsAudioNames() {
        XCTAssertEqual(DownloadManager.audioFileName(suggested: "Lied.mp3", mime: "audio/mpeg", url: nil), "Lied.mp3")
        XCTAssertEqual(DownloadManager.audioFileName(suggested: "Song.M4A", mime: nil, url: nil), "Song.M4A")
    }

    func testAudioFileNameUsesMimeType() {
        XCTAssertEqual(DownloadManager.audioFileName(suggested: "download.php", mime: "audio/mpeg", url: nil), "download.mp3")
        XCTAssertEqual(DownloadManager.audioFileName(suggested: "videoplayback", mime: "audio/mp4", url: nil), "videoplayback.m4a")
        XCTAssertEqual(DownloadManager.audioFileName(suggested: nil, mime: "audio/x-wav",
                                                     url: URL(string: "https://example.com/get?id=3")), "get.wav")
    }

    func testAudioFileNameUsesURLExtension() {
        XCTAssertEqual(DownloadManager.audioFileName(suggested: "file", mime: "application/octet-stream",
                                                     url: URL(string: "https://example.com/a/b/track.flac")), "file.flac")
    }

    func testAudioFileNameRejectsNonAudio() {
        XCTAssertNil(DownloadManager.audioFileName(suggested: "index.html", mime: "text/html", url: URL(string: "https://example.com/")))
        XCTAssertNil(DownloadManager.audioFileName(suggested: "clip.ogg", mime: "audio/ogg", url: nil))
        XCTAssertNil(DownloadManager.audioFileName(suggested: "bild.jpg", mime: "image/jpeg", url: nil))
        // Webseite, deren URL zufällig auf .mp3 endet
        XCTAssertNil(DownloadManager.audioFileName(suggested: "x.html", mime: "text/html", url: URL(string: "https://example.com/x.mp3")))
    }

    func testNotAudioErrorMessages() {
        XCTAssertTrue(DownloadError.notAudio("audio/ogg").localizedDescription.contains("nicht unterstützt"))
        XCTAssertTrue(DownloadError.notAudio("text/html").localizedDescription.contains("Webseite"))
    }

    func testSanitize() {
        XCTAssertEqual(Library.sanitize("AC/DC: Live?.mp3"), "AC_DC_ Live_.mp3")
        XCTAssertEqual(Library.sanitize("  .versteckt.mp3 "), "versteckt.mp3")
        XCTAssertEqual(Library.sanitize(""), "Titel.mp3")
        let long = String(repeating: "a", count: 300) + ".mp3"
        let cleaned = Library.sanitize(long)
        XCTAssertTrue(cleaned.hasSuffix(".mp3"))
        XCTAssertLessThanOrEqual(cleaned.count, 180)
    }

    func testFormattedTime() {
        XCTAssertEqual(TimeInterval(0).formattedTime, "0:00")
        XCTAssertEqual(TimeInterval(187.9).formattedTime, "3:07")
        XCTAssertEqual(TimeInterval(3723).formattedTime, "1:02:03")
        XCTAssertEqual(TimeInterval.nan.formattedTime, "0:00")
        XCTAssertEqual(TimeInterval(-5).formattedTime, "0:00")
    }
}

// MARK: - Bibliothek

@MainActor
final class LibraryTests: XCTestCase {
    private var library: Library!
    private var created: [Track] = []

    override func setUp() async throws {
        library = Library(autoScan: false)
        created = []
    }

    override func tearDown() async throws {
        for track in created { library.delete(track) }
        library = nil
    }

    private func importFixture(_ name: String, as newName: String? = nil, downloads: Bool = false) async throws -> Track {
        let src = try Fixture.tempCopy(name, as: newName)
        let track = try await library.importAudio(from: src, move: true, addToDownloads: downloads)
        created.append(track)
        return track
    }

    func testImportReadsID3Metadata() async throws {
        let track = try await importFixture("tagged.mp3", as: "tagged-\(UUID().uuidString.prefix(6)).mp3")
        XCTAssertEqual(track.title, "Testlied")
        XCTAssertEqual(track.artist, "Testband")
        XCTAssertEqual(track.album, "Testalbum")
        XCTAssertEqual(track.duration, 3.0, accuracy: 0.3)
        XCTAssertTrue(FileManager.default.fileExists(atPath: track.fileURL.path), "Datei liegt im Musikordner")
        XCTAssertTrue(FileManager.default.fileExists(atPath: track.artworkURL.path), "Cover wurde gespeichert")
        XCTAssertNotNil(ArtworkCache.shared.image(for: track))
        XCTAssertTrue(library.tracks.contains(track))
    }

    func testImportWithoutTagsUsesFileName() async throws {
        let name = "Mein Lied \(UUID().uuidString.prefix(6)).mp3"
        let track = try await importFixture("untagged.mp3", as: name)
        XCTAssertEqual(track.title, (name as NSString).deletingPathExtension)
        XCTAssertEqual(track.artist, "")
        XCTAssertGreaterThan(track.duration, 1.5)
    }

    func testImportRejectsUnsupportedFile() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let txt = dir.appendingPathComponent("notiz.txt")
        try Data("hallo".utf8).write(to: txt)
        do {
            _ = try await library.importAudio(from: txt, move: false)
            XCTFail("Textdatei darf nicht importiert werden")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("keine unterstützte Audiodatei"))
        }
    }

    func testSameNameGetsUniqueFile() async throws {
        let name = "doppelt-\(UUID().uuidString.prefix(6)).mp3"
        let a = try await importFixture("untagged.mp3", as: name)
        let b = try await importFixture("untagged.mp3", as: name)
        XCTAssertNotEqual(a.fileName, b.fileName)
        XCTAssertTrue(b.fileName.contains("(2)"))
    }

    func testDownloadsPlaylistReceivesTrack() async throws {
        var added: (Track, UUID?)?
        library.onTrackAdded = { added = ($0, $1) }
        let track = try await importFixture("short.m4a", as: "dl-\(UUID().uuidString.prefix(6)).m4a", downloads: true)
        let playlist = try XCTUnwrap(library.playlist(id: Library.downloadsPlaylistID))
        XCTAssertEqual(library.tracks(in: playlist).last?.id, track.id)
        XCTAssertEqual(added?.0.id, track.id)
        XCTAssertEqual(added?.1, Library.downloadsPlaylistID)
    }

    func testRescanFindsCopiedFilesWithoutDuplicates() async throws {
        let name = "finder-\(UUID().uuidString.prefix(6)).mp3"
        let dest = Storage.documents.appendingPathComponent(name)
        try FileManager.default.copyItem(at: Fixture.url("tagged.mp3"), to: dest)
        await library.rescan()
        await library.rescan()
        let matches = library.tracks.filter { $0.fileName == name }
        XCTAssertEqual(matches.count, 1)
        created.append(contentsOf: matches)

        // Datei extern gelöscht → verschwindet aus der Bibliothek
        try FileManager.default.removeItem(at: dest)
        await library.rescan()
        XCTAssertFalse(library.tracks.contains { $0.fileName == name })
        created.removeAll { $0.fileName == name }
    }

    func testImportWhileRescanningCreatesNoDuplicate() async throws {
        let name = "parallel-\(UUID().uuidString.prefix(6)).mp3"
        let src = try Fixture.tempCopy("tagged.mp3", as: name)
        async let imported = library.importAudio(from: src, move: true)
        async let scan: Void = library.rescan()
        let track = try await imported
        await scan
        await library.rescan()
        created.append(track)
        XCTAssertEqual(library.tracks.filter { $0.fileName == track.fileName }.count, 1)
    }

    func testOpeningFileAlreadyInLibraryDoesNotCopy() async throws {
        let track = try await importFixture("untagged.mp3", as: "inplace-\(UUID().uuidString.prefix(6)).mp3")
        let again = try await library.importAudio(from: track.fileURL, move: false)
        XCTAssertEqual(again.id, track.id)
        XCTAssertEqual(library.tracks.filter { $0.fileName.hasPrefix((track.fileName as NSString).deletingPathExtension) }.count, 1)
    }

    func testPlaylistOperations() async throws {
        let a = try await importFixture("untagged.mp3", as: "pl-a-\(UUID().uuidString.prefix(6)).mp3")
        let b = try await importFixture("untagged.mp3", as: "pl-b-\(UUID().uuidString.prefix(6)).mp3")
        let c = try await importFixture("untagged.mp3", as: "pl-c-\(UUID().uuidString.prefix(6)).mp3")
        var playlist = library.createPlaylist(named: "  Party  ")
        XCTAssertEqual(playlist.name, "Party")
        library.add(a, to: playlist)
        library.add(b, to: playlist)
        library.add(c, to: playlist)
        library.add(a, to: playlist) // doppelt → ignoriert
        playlist = try XCTUnwrap(library.playlist(id: playlist.id))
        XCTAssertEqual(library.tracks(in: playlist).map(\.id), [a.id, b.id, c.id])

        library.moveTracks(from: IndexSet(integer: 2), to: 0, in: playlist)
        playlist = try XCTUnwrap(library.playlist(id: playlist.id))
        XCTAssertEqual(library.tracks(in: playlist).map(\.id), [c.id, a.id, b.id])

        library.removeTracks(at: IndexSet(integer: 1), from: playlist)
        playlist = try XCTUnwrap(library.playlist(id: playlist.id))
        XCTAssertEqual(library.tracks(in: playlist).map(\.id), [c.id, b.id])

        // Titel löschen entfernt ihn auch aus Playlists
        library.delete(c)
        created.removeAll { $0.id == c.id }
        playlist = try XCTUnwrap(library.playlist(id: playlist.id))
        XCTAssertEqual(library.tracks(in: playlist).map(\.id), [b.id])
        XCTAssertFalse(FileManager.default.fileExists(atPath: c.fileURL.path))

        library.renamePlaylist(playlist, to: "Chill")
        XCTAssertEqual(library.playlist(id: playlist.id)?.name, "Chill")
        library.deletePlaylist(playlist)
        XCTAssertNil(library.playlist(id: playlist.id))

        // Die Download-Playlist lässt sich nicht löschen
        let downloads = try XCTUnwrap(library.playlist(id: Library.downloadsPlaylistID))
        library.deletePlaylist(downloads)
        XCTAssertNotNil(library.playlist(id: Library.downloadsPlaylistID))
    }

    func testRenameTrack() async throws {
        let track = try await importFixture("tagged.mp3", as: "rename-\(UUID().uuidString.prefix(6)).mp3")
        library.rename(track, title: "  Neuer Name ", artist: " Band ")
        XCTAssertEqual(library.track(id: track.id)?.title, "Neuer Name")
        XCTAssertEqual(library.track(id: track.id)?.artist, "Band")
        library.rename(track, title: "   ", artist: "")
        XCTAssertEqual(library.track(id: track.id)?.title, "Neuer Name", "Leerer Titel wird ignoriert")
    }

    func testPersistence() async throws {
        let track = try await importFixture("untagged.mp3", as: "persist-\(UUID().uuidString.prefix(6)).mp3")
        let playlist = library.createPlaylist(named: "Gespeichert")
        library.add(track, to: playlist)
        let reloaded = Library(autoScan: false)
        XCTAssertEqual(reloaded.track(id: track.id)?.fileName, track.fileName)
        XCTAssertEqual(reloaded.playlist(id: playlist.id)?.trackIDs, [track.id])
        library.deletePlaylist(playlist)
    }
}

// MARK: - Konverter, Album, Kurzwahl, Mitwirkende

@MainActor
final class ConverterAndExtrasTests: XCTestCase {
    private var library: Library!
    private var created: [Track] = []

    override func setUp() async throws {
        library = Library(autoScan: false)
        created = []
    }

    override func tearDown() async throws {
        for track in created { library.delete(track) }
        library = nil
    }

    /// Prüft, ob eine Datei eine echte MP3 ist (ID3v2-Kopf + MPEG-Audio-Frame).
    private func assertRealMP3(_ url: URL, file: StaticString = #filePath, line: UInt = #line) throws {
        let data = try Data(contentsOf: url)
        XCTAssertGreaterThan(data.count, 1000, file: file, line: line)
        XCTAssertEqual(String(data: data.prefix(3), encoding: .ascii), "ID3", "ID3v2-Tag fehlt", file: file, line: line)
        // ID3-Größe (syncsafe) überspringen, danach muss ein MPEG-Frame (0xFFE…) beginnen
        let size = (Int(data[6]) << 21) | (Int(data[7]) << 14) | (Int(data[8]) << 7) | Int(data[9])
        let frameStart = 10 + size
        XCTAssertGreaterThan(data.count, frameStart + 2, file: file, line: line)
        XCTAssertEqual(data[frameStart], 0xFF, "kein MP3-Frame", file: file, line: line)
        XCTAssertEqual(data[frameStart + 1] & 0xE0, 0xE0, "kein MP3-Frame", file: file, line: line)
    }

    func testVideoIsConvertedToMP3() async throws {
        let src = try Fixture.tempCopy("clip.mp4", as: "video-\(UUID().uuidString.prefix(6)).mp4")
        let track = try await library.importAudio(from: src, move: true, addToDownloads: true)
        created.append(track)
        XCTAssertTrue(track.fileName.hasSuffix(".mp3"), "Ergebnis ist MP3: \(track.fileName)")
        try assertRealMP3(track.fileURL)
        XCTAssertEqual(track.duration, 2.0, accuracy: 0.4)
        XCTAssertEqual(track.title, "Videolied", "Titel wird als ID3-Tag übernommen")
        XCTAssertEqual(track.artist, "Videoband")
        XCTAssertFalse(FileManager.default.fileExists(atPath: src.path), "Video wurde nach dem Umwandeln entfernt")
        let downloads = try XCTUnwrap(library.playlist(id: Library.downloadsPlaylistID))
        XCTAssertTrue(downloads.trackIDs.contains(track.id))

        // Umgewandelter Titel lässt sich abspielen
        let player = AudioPlayer()
        player.play([track], startAt: 0)
        XCTAssertTrue(player.isPlaying)
        XCTAssertNil(player.errorMessage)
        player.stop()
    }

    func testM4AImportBecomesMP3WithUmlautTags() async throws {
        let src = try Fixture.tempCopy("short.m4a", as: "umlaut-\(UUID().uuidString.prefix(6)).m4a")
        let track = try await library.importAudio(from: src, move: false)
        created.append(track)
        XCTAssertTrue(track.fileName.hasSuffix(".mp3"))
        try assertRealMP3(track.fileURL)
        XCTAssertEqual(track.title, "Kurzes Stück")
        XCTAssertEqual(track.artist, "Ärzte-Band")
        XCTAssertEqual(track.album, "Übung")
        XCTAssertEqual(track.duration, 2.0, accuracy: 0.4)
    }

    func testVideoWithoutSoundFails() async throws {
        let src = try Fixture.tempCopy("silent.mp4", as: "stumm-\(UUID().uuidString.prefix(6)).mp4")
        do {
            _ = try await library.importAudio(from: src, move: false)
            XCTFail("Video ohne Ton darf nicht importiert werden")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("keine Tonspur"), error.localizedDescription)
        }
    }

    func testVideoDownloadNamesAreAccepted() {
        XCTAssertEqual(DownloadManager.audioFileName(suggested: "videoplayback", mime: "video/mp4", url: nil), "videoplayback.mp4")
        XCTAssertEqual(DownloadManager.audioFileName(suggested: "film.mov", mime: "video/quicktime", url: nil), "film.mov")
        XCTAssertNil(DownloadManager.audioFileName(suggested: "clip.webm", mime: "video/webm", url: nil))
        XCTAssertTrue(DownloadError.notAudio("video/webm").localizedDescription.contains("nicht unterstützt"))
    }

    func testDownloadIntoExtraPlaylist() async throws {
        let manager = DownloadManager()
        manager.library = library
        let playlist = library.createPlaylist(named: "Von Webseite")
        let src = try Fixture.tempCopy("untagged.mp3", as: "seite-\(UUID().uuidString.prefix(6)).mp3")
        manager.download(from: src, into: playlist.id)
        let done = await waitUntil(timeout: 15) { manager.items.first?.state == .finished }
        XCTAssertTrue(done, "Status: \(String(describing: manager.items.first?.state))")
        let tracks = library.tracks(in: try XCTUnwrap(library.playlist(id: playlist.id)))
        XCTAssertEqual(tracks.count, 1)
        created.append(contentsOf: tracks)
        library.deletePlaylist(playlist)
    }

    func testPinAlbumArtistAndMix() async throws {
        let a = try await library.importAudio(from: try Fixture.tempCopy("tagged.mp3", as: "mix-a-\(UUID().uuidString.prefix(6)).mp3"), move: true)
        let b = try await library.importAudio(from: try Fixture.tempCopy("tagged.mp3", as: "mix-b-\(UUID().uuidString.prefix(6)).mp3"), move: true)
        let c = try await library.importAudio(from: try Fixture.tempCopy("untagged.mp3", as: "mix-c-\(UUID().uuidString.prefix(6)).mp3"), move: true)
        created += [a, b, c]

        // Kurzwahl
        library.togglePin(a)
        XCTAssertTrue(library.isPinned(a))
        XCTAssertEqual(library.pinnedTracks.first?.id, a.id)
        XCTAssertEqual(Library(autoScan: false).pinnedIDs.first, a.id, "Kurzwahl wird gespeichert")
        library.togglePin(a)
        XCTAssertFalse(library.isPinned(a))

        // Album / Interpret
        XCTAssertTrue(Set([a.id, b.id]).isSubset(of: Set(library.tracks(inAlbum: "Testalbum").map(\.id))))
        XCTAssertFalse(library.tracks(inAlbum: "Testalbum").contains { $0.id == c.id })
        XCTAssertTrue(library.tracks(byArtist: "testband").contains { $0.id == b.id }, "ohne Groß-/Kleinschreibung")

        // Mix: Starttitel zuerst, dann derselbe Interpret
        let player = AudioPlayer()
        player.startMix(with: a, from: [a, b, c])
        XCTAssertEqual(player.queue.first?.id, a.id)
        XCTAssertEqual(player.queue.count, 3)
        XCTAssertEqual(player.queue[1].id, b.id, "gleicher Interpret kommt zuerst")
        XCTAssertTrue(player.isPlaying)
        player.stop()
        XCTAssertTrue(player.queue.isEmpty, "Wiedergabeliste geschlossen")

        // Aus Playlist entfernen
        let playlist = library.createPlaylist(named: "Temp")
        library.add(a, to: playlist)
        library.remove(a, from: playlist)
        XCTAssertEqual(library.playlist(id: playlist.id)?.trackIDs, [])
        library.deletePlaylist(playlist)
    }

    func testSongInfoReadsCredits() async throws {
        let track = try await library.importAudio(from: try Fixture.tempCopy("tagged.mp3", as: "info-\(UUID().uuidString.prefix(6)).mp3"), move: true)
        created.append(track)
        let info = await SongInfo.load(for: track)
        let credits = Dictionary(uniqueKeysWithValues: info.credits.map { ($0.label, $0.value) })
        XCTAssertEqual(credits["Interpret"], "Testband")
        XCTAssertEqual(credits["Album"], "Testalbum")
        XCTAssertEqual(credits["Komponist"], "Testkomponist")
        XCTAssertEqual(credits["Genre"], "Pop")
        XCTAssertEqual(credits["Jahr"], "2024")
        let file = Dictionary(uniqueKeysWithValues: info.file.map { ($0.label, $0.value) })
        XCTAssertEqual(file["Format"], "MP3")
        XCTAssertNotNil(file["Bitrate (ca.)"])
    }

    /// Legt eine M4A direkt im Musikordner ab (wie per Finder kopiert) und liefert den erkannten Titel.
    private func m4aInLibrary(_ prefix: String) async throws -> Track {
        let name = "\(prefix)-\(UUID().uuidString.prefix(6)).m4a"
        try FileManager.default.copyItem(at: Fixture.url("short.m4a"), to: Storage.documents.appendingPathComponent(name))
        await library.rescan()
        let track = try XCTUnwrap(library.tracks.first { $0.fileName == name })
        created.append(track)
        return track
    }

    func testConvertExistingFileKeepsPlaylistsAndPin() async throws {
        let track = try await m4aInLibrary("conv")
        XCTAssertTrue(library.convertibleTracks.contains { $0.id == track.id })
        let playlist = library.createPlaylist(named: "Konvertiert")
        library.add(track, to: playlist)
        library.togglePin(track)
        let oldURL = track.fileURL

        let converted = try await library.convertToMP3(track)
        XCTAssertEqual(converted.id, track.id, "gleiche ID")
        XCTAssertTrue(converted.fileName.hasSuffix(".mp3"))
        try assertRealMP3(converted.fileURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: oldURL.path), "Original wurde ersetzt")
        XCTAssertEqual(converted.duration, 2.0, accuracy: 0.4)
        XCTAssertEqual(library.playlist(id: playlist.id)?.trackIDs, [track.id], "bleibt in der Playlist")
        XCTAssertTrue(library.isPinned(converted), "bleibt in der Kurzwahl")
        XCTAssertFalse(library.convertibleTracks.contains { $0.id == track.id })

        await library.rescan()
        XCTAssertEqual(library.tracks.filter { $0.id == track.id }.count, 1, "kein Duplikat nach Ordner-Abgleich")

        let player = AudioPlayer()
        player.play([converted], startAt: 0)
        XCTAssertTrue(player.isPlaying)
        player.stop()
        library.deletePlaylist(playlist)
    }

    func testConvertAll() async throws {
        let a = try await m4aInLibrary("all-a")
        let b = try await m4aInLibrary("all-b")
        let result = await library.convertAllToMP3(skipping: b.id)
        XCTAssertGreaterThanOrEqual(result.converted, 1)
        XCTAssertEqual(result.failed, 0)
        XCTAssertTrue(library.track(id: a.id)?.fileName.hasSuffix(".mp3") == true)
        XCTAssertTrue(library.track(id: b.id)?.fileName.hasSuffix(".m4a") == true, "übersprungener Titel bleibt")
        XCTAssertNil(library.conversionStatus)
    }

    func testDownloadedTrackGoesIntoQueuePaused() async throws {
        let track = try await library.importAudio(from: try Fixture.tempCopy("untagged.mp3", as: "queue-\(UUID().uuidString.prefix(6)).mp3"), move: true)
        created.append(track)
        let player = AudioPlayer()
        player.libraryDidAdd(track, playlistID: Library.downloadsPlaylistID)
        XCTAssertEqual(player.queue.map(\.id), [track.id])
        XCTAssertEqual(player.current?.id, track.id)
        XCTAssertFalse(player.isPlaying, "startet nicht von selbst")
        player.stop()
    }

    func testOldLibraryFormatStillLoads() throws {
        // Bibliothek aus Version 1.0.8 (ohne Album, ohne Kurzwahl)
        let json = """
        {"tracks":[{"id":"11111111-1111-1111-1111-111111111111","fileName":"a.mp3","title":"A","artist":"B","duration":3,"addedAt":0}],
         "playlists":[]}
        """
        let state = try JSONDecoder().decode(LibraryState.self, from: Data(json.utf8))
        XCTAssertEqual(state.version, 1)
        XCTAssertEqual(state.tracks.first?.album, "")
        XCTAssertEqual(state.pinned, [])
    }
}

// MARK: - Downloads

private func isTerminal(_ state: DownloadManager.State?) -> Bool {
    switch state {
    case .finished?, .failed?: return true
    default: return false
    }
}

@MainActor
final class DownloadTests: XCTestCase {
    func testDirectDownloadEndsUpInDownloadsPlaylist() async throws {
        let library = Library(autoScan: false)
        let manager = DownloadManager()
        manager.library = library
        let src = try Fixture.tempCopy("tagged.mp3", as: "direkt-\(UUID().uuidString.prefix(6)).mp3")

        manager.download(from: src)
        let done = await waitUntil(timeout: 15) { isTerminal(manager.items.first?.state) }
        XCTAssertTrue(done, "Download wurde nicht fertig")
        XCTAssertEqual(manager.items.first?.state, .finished, "Status: \(String(describing: manager.items.first?.state))")
        XCTAssertEqual(manager.items.first?.name, "Testlied")
        XCTAssertEqual(manager.unseenCount, 1)
        XCTAssertEqual(manager.activeCount, 0)

        let playlist = try XCTUnwrap(library.playlist(id: Library.downloadsPlaylistID))
        let track = try XCTUnwrap(library.tracks(in: playlist).last)
        XCTAssertEqual(track.title, "Testlied")
        XCTAssertTrue(FileManager.default.fileExists(atPath: track.fileURL.path))
        library.delete(track)

        manager.clearFinished()
        XCTAssertTrue(manager.items.isEmpty)
    }

    func testDownloadOfNonAudioFails() async throws {
        let library = Library(autoScan: false)
        let manager = DownloadManager()
        manager.library = library
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let html = dir.appendingPathComponent("seite.html")
        try Data("<html></html>".utf8).write(to: html)
        let before = library.tracks.count

        manager.download(from: html)
        let done = await waitUntil(timeout: 15) { isTerminal(manager.items.first?.state) }
        XCTAssertTrue(done)
        guard case .failed(let message)? = manager.items.first?.state else {
            return XCTFail("Download einer HTML-Datei hätte fehlschlagen müssen")
        }
        XCTAssertFalse(message.isEmpty)
        XCTAssertEqual(library.tracks.count, before)
    }
}

// MARK: - Player

@MainActor
final class AudioPlayerTests: XCTestCase {
    private var library: Library!
    private var player: AudioPlayer!
    private var tracks: [Track] = []

    override func setUp() async throws {
        library = Library(autoScan: false)
        player = AudioPlayer()
        tracks = []
        for i in 0..<3 {
            let src = try Fixture.tempCopy("untagged.mp3", as: "player-\(i)-\(UUID().uuidString.prefix(6)).mp3")
            tracks.append(try await library.importAudio(from: src, move: true))
        }
    }

    override func tearDown() async throws {
        player.stop()
        for t in tracks { library.delete(t) }
        player = nil
        library = nil
    }

    func testPlayNextPrevious() {
        player.play(tracks, startAt: 0)
        XCTAssertEqual(player.current?.id, tracks[0].id)
        XCTAssertTrue(player.isPlaying)
        XCTAssertGreaterThan(player.duration, 1.5)

        player.next()
        XCTAssertEqual(player.current?.id, tracks[1].id)
        player.next()
        XCTAssertEqual(player.current?.id, tracks[2].id)
        player.next() // manuell am Ende → wieder von vorne
        XCTAssertEqual(player.current?.id, tracks[0].id)

        player.previous()
        XCTAssertEqual(player.current?.id, tracks[2].id, "Zurück folgt dem Verlauf")
    }

    func testPauseResumeSeek() {
        player.play(tracks, startAt: 1)
        player.pause()
        XCTAssertFalse(player.isPlaying)
        player.seek(to: 1.0)
        XCTAssertEqual(player.currentTime, 1.0, accuracy: 0.05)
        player.seek(to: 999)
        XCTAssertLessThanOrEqual(player.currentTime, player.duration)
        player.togglePlayPause()
        XCTAssertTrue(player.isPlaying)
    }

    /// App in der App-Übersicht weggewischt: Die Musik hört auf, Warteschlange und Stelle bleiben fürs Weiterhören
    func testMusicStopsWhenAppIsClosed() {
        player.play(tracks, startAt: 1)
        XCTAssertTrue(player.isPlaying)
        NotificationCenter.default.post(name: UIApplication.willTerminateNotification, object: nil)
        XCTAssertFalse(player.isPlaying, "Nach dem Beenden der App darf keine Musik mehr laufen")
        XCTAssertEqual(player.current?.id, tracks[1].id, "Der Titel bleibt zum Weiterhören gemerkt")
        XCTAssertEqual(UserDefaults.standard.integer(forKey: "playback.index"), 1)
        player.togglePlayPause()
        XCTAssertTrue(player.isPlaying, "Danach lässt sich normal weiterspielen")
    }

    func testAutoAdvanceWhenTrackEnds() async {
        player.play(tracks, startAt: 0)
        player.seek(to: max(0, player.duration - 0.3))
        let advanced = await waitUntil(timeout: 15) { self.player.current?.id == self.tracks[1].id }
        XCTAssertTrue(advanced, "Nach Titelende muss automatisch der nächste Titel kommen")
        XCTAssertTrue(player.isPlaying)
    }

    /// Probehören im Zuschneider: Wiedergabeliste wird gemerkt und danach wiederhergestellt
    func testRestoresQueueAfterPreview() {
        player.play(Array(tracks.prefix(2)), startAt: 1)
        player.pause()
        let saved = player.snapshotQueue()
        player.play([tracks[2]], startAt: 0)
        XCTAssertEqual(player.current?.id, tracks[2].id)
        player.restoreQueue(saved)
        XCTAssertEqual(player.queue.map(\.id), [tracks[0].id, tracks[1].id])
        XCTAssertEqual(player.current?.id, tracks[1].id)
        XCTAssertFalse(player.isPlaying, "war pausiert und bleibt pausiert")

        // Vorher lief nichts → danach ist die Wiedergabeliste wieder leer
        player.stop()
        let empty = player.snapshotQueue()
        player.play([tracks[2]], startAt: 0)
        player.restoreQueue(empty)
        XCTAssertTrue(player.queue.isEmpty)
        XCTAssertNil(player.current)
    }

    func testEndOfQueueStopsWithoutRepeat() async {
        player.repeatMode = .off
        player.play(tracks, startAt: 2)
        player.seek(to: max(0, player.duration - 0.3))
        let stopped = await waitUntil(timeout: 5) { !self.player.isPlaying }
        XCTAssertTrue(stopped)
        XCTAssertEqual(player.current?.id, tracks[0].id, "Springt pausiert an den Anfang")
    }

    func testRepeatAllWraps() async {
        player.repeatMode = .all
        player.play(tracks, startAt: 2)
        player.seek(to: max(0, player.duration - 0.3))
        let wrapped = await waitUntil(timeout: 5) { self.player.current?.id == self.tracks[0].id && self.player.isPlaying }
        XCTAssertTrue(wrapped)
    }

    func testRepeatOneRestartsTrack() async {
        player.repeatMode = .one
        player.play(tracks, startAt: 1)
        player.seek(to: max(0, player.duration - 0.3))
        try? await Task.sleep(nanoseconds: 1_200_000_000)
        XCTAssertEqual(player.current?.id, tracks[1].id)
        XCTAssertTrue(player.isPlaying)
    }

    func testShuffleNeverRepeatsCurrent() {
        player.shuffle = true
        player.play(tracks, startAt: 0)
        for _ in 0..<15 {
            let before = player.current?.id
            player.next()
            XCTAssertNotEqual(player.current?.id, before)
        }
    }

    func testQueueEditing() {
        player.play([tracks[0], tracks[1]], startAt: 1)
        player.playNext(tracks[2])
        XCTAssertEqual(player.queue.map(\.id), [tracks[0].id, tracks[1].id, tracks[2].id])
        player.addToQueue(tracks[0])
        XCTAssertEqual(player.queue.count, 4)

        player.removeFromQueue(at: IndexSet(integer: 0))
        XCTAssertEqual(player.index, 0)
        XCTAssertEqual(player.current?.id, tracks[1].id)

        player.removeFromQueue(at: IndexSet(integer: 0)) // laufender Titel bleibt
        XCTAssertEqual(player.current?.id, tracks[1].id)

        player.moveInQueue(from: IndexSet(integer: 0), to: 3)
        XCTAssertEqual(player.current?.id, tracks[1].id)
        XCTAssertEqual(player.queue.last?.id, tracks[1].id)
    }

    func testDeletingCurrentTrackStopsPlayback() {
        player.play(tracks, startAt: 1)
        library.delete(tracks[1])
        player.libraryDidChange(library.tracks)
        XCTAssertNil(player.current)
        XCTAssertFalse(player.isPlaying)
        tracks.remove(at: 1)
    }

    func testDeletingOtherTrackKeepsPlaying() {
        player.play(tracks, startAt: 2)
        library.delete(tracks[0])
        player.libraryDidChange(library.tracks)
        XCTAssertEqual(player.current?.id, tracks[2].id)
        XCTAssertEqual(player.queue.count, 2)
        XCTAssertTrue(player.isPlaying)
        tracks.remove(at: 0)
    }

    func testNewDownloadIsAppendedToMatchingQueue() {
        player.play(tracks, startAt: 0, playlistID: Library.downloadsPlaylistID)
        let extra = Track(id: UUID(), fileName: "x.mp3", title: "x", artist: "", duration: 1, addedAt: Date())
        player.libraryDidAdd(extra, playlistID: Library.downloadsPlaylistID)
        XCTAssertEqual(player.queue.last?.id, extra.id)
        player.libraryDidAdd(extra, playlistID: Library.downloadsPlaylistID)
        XCTAssertEqual(player.queue.filter { $0.id == extra.id }.count, 1, "nicht doppelt")
        let other = Track(id: UUID(), fileName: "y.mp3", title: "y", artist: "", duration: 1, addedAt: Date())
        player.libraryDidAdd(other, playlistID: nil)
        XCTAssertFalse(player.queue.contains(other))
    }

    func testUnplayableFileShowsError() throws {
        let broken = Track(id: UUID(), fileName: "gibt-es-nicht.mp3", title: "Kaputt", artist: "", duration: 0, addedAt: Date())
        player.play([broken], startAt: 0)
        XCTAssertFalse(player.isPlaying)
        XCTAssertNotNil(player.errorMessage)
    }
}

// MARK: - Updates

final class UpdateCheckerTests: XCTestCase {
    private let sample = """
    {
      "tag_name": "v1.0.42",
      "html_url": "https://github.com/Backewatsch/MP3-Player/releases/tag/v1.0.42",
      "body": "Neue Funktionen",
      "published_at": "2026-11-08T13:00:00Z",
      "assets": [
        { "name": "altstore-source.json", "browser_download_url": "https://github.com/x/altstore-source.json" },
        { "name": "MP3Player.ipa", "browser_download_url": "https://github.com/Backewatsch/MP3-Player/releases/download/v1.0.42/MP3Player.ipa" }
      ]
    }
    """

    func testParseRelease() throws {
        let release = try XCTUnwrap(UpdateChecker.parseRelease(Data(sample.utf8)))
        XCTAssertEqual(release.version, "1.0.42")
        XCTAssertEqual(release.build, 42)
        XCTAssertEqual(release.notes, "Neue Funktionen")
        XCTAssertEqual(release.ipaURL?.lastPathComponent, "MP3Player.ipa")
        XCTAssertNotNil(release.date)
    }

    /// Die AltStore-Quelle nennt die iPhone-Version – unabhängig von der Nummer des Release, an dem sie hängt
    func testParseAltStoreSource() throws {
        let source = """
        {
          "name": "ILLEGAL FREEDOM MUSIC",
          "apps": [
            { "name": "ILLEGAL FREEDOM MUSIC", "bundleIdentifier": "com.backewatsch.mp3player",
              "versions": [
                { "version": "1.0.107", "buildVersion": "107", "date": "2026-11-11T08:00:00Z",
                  "localizedDescription": "Suche in Playlists",
                  "downloadURL": "https://github.com/Backewatsch/MP3-Player/releases/download/v1.0.105/MP3Player.ipa",
                  "size": 123 }
              ] }
          ]
        }
        """
        let release = try XCTUnwrap(UpdateChecker.parseSource(Data(source.utf8)))
        XCTAssertEqual(release.version, "1.0.107")
        XCTAssertEqual(release.build, 107)
        XCTAssertEqual(release.notes, "Suche in Playlists")
        XCTAssertEqual(release.ipaURL?.lastPathComponent, "MP3Player.ipa")
        XCTAssertNotNil(release.date)
        XCTAssertTrue(UpdateChecker.isNewer(release, thanBuild: 105), "neuer als das Release, an dem die Quelle hängt")
        XCTAssertFalse(UpdateChecker.isNewer(release, thanBuild: 107))
        XCTAssertNil(UpdateChecker.parseSource(Data("{}".utf8)))
        XCTAssertNil(UpdateChecker.parseSource(Data(#"{"apps":[{"versions":[]}]}"#.utf8)))
        XCTAssertNil(UpdateChecker.parseSource(Data("kein json".utf8)))
    }

    func testParseRejectsGarbage() {
        XCTAssertNil(UpdateChecker.parseRelease(Data("{}".utf8)))
        XCTAssertNil(UpdateChecker.parseRelease(Data("kein json".utf8)))
        XCTAssertNil(UpdateChecker.parseRelease(Data(#"{"tag_name":"beta","html_url":"https://x"}"#.utf8)))
    }

    func testBuildNumberAndComparison() throws {
        XCTAssertEqual(UpdateChecker.buildNumber(fromTag: "v1.0.7"), 7)
        XCTAssertEqual(UpdateChecker.buildNumber(fromTag: "v1.0.123"), 123)
        XCTAssertNil(UpdateChecker.buildNumber(fromTag: "latest"))
        let release = try XCTUnwrap(UpdateChecker.parseRelease(Data(sample.utf8)))
        XCTAssertTrue(UpdateChecker.isNewer(release, thanBuild: 41))
        XCTAssertFalse(UpdateChecker.isNewer(release, thanBuild: 42))
        XCTAssertFalse(UpdateChecker.isNewer(release, thanBuild: 50))
    }

    func testAltStoreDeepLinks() throws {
        let ipa = URL(string: "https://github.com/Backewatsch/MP3-Player/releases/download/v1.0.42/MP3Player.ipa")!
        let install = try XCTUnwrap(UpdateChecker.altStoreInstallURL(for: ipa))
        XCTAssertEqual(install.scheme, "altstore")
        XCTAssertEqual(install.host, "install")
        let items = URLComponents(url: install, resolvingAgainstBaseURL: false)?.queryItems
        XCTAssertEqual(items?.first { $0.name == "url" }?.value, ipa.absoluteString)

        let source = try XCTUnwrap(UpdateChecker.altStoreAddSourceURL())
        XCTAssertEqual(source.host, "source")
        XCTAssertEqual(URLComponents(url: source, resolvingAgainstBaseURL: false)?.queryItems?.first?.value,
                       UpdateChecker.sourceURL.absoluteString)
    }

    func testAppHasVersionInfo() {
        XCTAssertFalse((Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "").isEmpty)
        XCTAssertNotNil(Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""))
    }
}

// MARK: - Neue Funktionen (Sortieren, Statistik, Klang, Herzen, Sicherung, Schlafmodus)

final class FeatureLogicTests: XCTestCase {
    private func t(_ title: String, _ artist: String, _ duration: Double, _ added: TimeInterval) -> Track {
        Track(id: UUID(), fileName: "\(title).mp3", title: title, artist: artist, duration: duration, addedAt: Date(timeIntervalSince1970: added))
    }

    func testSorting() {
        let a = t("beta", "Zed", 200, 1), b = t("Alpha", "", 300, 3), c = t("gamma", "adam", 100, 2)
        let all = [a, b, c]
        XCTAssertEqual(TrackSort.title.apply(all).map(\.id), [b.id, a.id, c.id])
        XCTAssertEqual(TrackSort.artist.apply(all).map(\.id), [c.id, a.id, b.id], "ohne Interpret am Ende")
        XCTAssertEqual(TrackSort.newest.apply(all).map(\.id), [b.id, c.id, a.id])
        XCTAssertEqual(TrackSort.mostPlayed.apply(all, playCounts: [c.id: 5, a.id: 2]).map(\.id), [c.id, a.id, b.id])
        XCTAssertEqual(TrackSort.longest.apply(all).map(\.id), [b.id, a.id, c.id])
    }

    func testStats() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Berlin")!
        let now = cal.date(from: DateComponents(year: 2026, month: 11, day: 20, hour: 12))!
        let lastMonth = cal.date(from: DateComponents(year: 2026, month: 10, day: 5, hour: 12))!
        let a = t("A", "Zed", 200, 1), c = t("C", "adam", 100, 2)
        var state = LibraryState(tracks: [a, c], playlists: [], pinned: [])
        state.favorites = [a.id, UUID()]
        state.plays = [PlayEvent(trackID: a.id, at: now), PlayEvent(trackID: c.id, at: now), PlayEvent(trackID: a.id, at: now),
                       PlayEvent(trackID: c.id, at: lastMonth), PlayEvent(trackID: c.id, at: lastMonth)]
        state.listenedByMonth = ["2026-11": 3900, "2026-10": 60]
        let s = StatsSummary.make(state, now: now, calendar: cal)
        XCTAssertEqual(s.playsThisMonth, 3)
        XCTAssertEqual(s.playsTotal, 5)
        XCTAssertEqual(s.topThisMonth.map { $0.0.id }, [a.id, c.id])
        XCTAssertEqual(s.topAllTime.first?.0.id, c.id)
        XCTAssertEqual(s.topArtists.first?.0, "adam")
        XCTAssertEqual(s.listenedThisMonth, 3900)
        XCTAssertEqual(s.listenedTotal, 3960)
        XCTAssertEqual(s.favoriteCount, 1)
        XCTAssertEqual(StatsSummary.formatListening(3900), "1 Std. 5 Min.")
        XCTAssertEqual(StatsSummary.monthKey(now, calendar: cal), "2026-11")
    }

    func testEqualizerCurvesAndFades() {
        let bass = SoundPresets.preset(named: "Bass-Boost")!
        XCTAssertEqual(SoundPresets.gain(bass, at: 30), 6, accuracy: 0.001)
        XCTAssertEqual(SoundPresets.gain(bass, at: 5000), 0, accuracy: 0.001)
        XCTAssertEqual(SoundPresets.gains(bass).count, SoundPresets.bands.count)
        XCTAssertEqual(SoundPresets.volume(position: 0, duration: 200, fade: 0, sleepRemaining: nil), 1)
        XCTAssertEqual(SoundPresets.volume(position: 2, duration: 200, fade: 4, sleepRemaining: nil), 0.5, accuracy: 0.001)
        XCTAssertEqual(SoundPresets.volume(position: 199, duration: 200, fade: 4, sleepRemaining: nil), 0.25, accuracy: 0.001)
        XCTAssertEqual(SoundPresets.volume(position: 0, duration: 10, fade: 4, sleepRemaining: nil), 1, "zu kurzes Lied")
        XCTAssertEqual(SoundPresets.volume(position: 100, duration: 200, fade: 4, sleepRemaining: 5), 0.5, accuracy: 0.001)
    }

    func testSleepTimerInput() {
        XCTAssertEqual(SleepTimerView.parseMinutes("30"), 30)
        XCTAssertEqual(SleepTimerView.parseMinutes(" 5 "), 5)
        XCTAssertNil(SleepTimerView.parseMinutes("0"))
        XCTAssertNil(SleepTimerView.parseMinutes("1441"))
        XCTAssertNil(SleepTimerView.parseMinutes("abc"))
        XCTAssertEqual(SleepTimerView.format(1800), "30:00")
        XCTAssertEqual(SleepTimerView.format(3750), "1:02:30")
    }
}

@MainActor
final class FeatureLibraryTests: XCTestCase {
    private var library: Library!
    private var tracks: [Track] = []

    override func setUp() async throws {
        library = Library(autoScan: false)
        tracks = []
        for i in 0..<3 {
            let src = try Fixture.tempCopy("untagged.mp3", as: "feature-\(i)-\(UUID().uuidString.prefix(6)).mp3")
            tracks.append(try await library.importAudio(from: src, move: true))
        }
    }

    override func tearDown() async throws {
        library.deleteAll(library.tracks.filter { t in tracks.contains { $0.id == t.id } })
        for p in library.playlists where p.name.hasPrefix("Test-") { library.deletePlaylist(p) }
        library = nil
    }

    func testFavoritesMultiSelectAndStats() {
        let (a, b, c) = (tracks[0], tracks[1], tracks[2])
        library.toggleFavorite(a)
        library.toggleFavorite(c)
        XCTAssertEqual(library.favoriteTracks.prefix(2).map(\.id), [c.id, a.id])
        library.toggleFavorite(a)
        XCTAssertFalse(library.isFavorite(a))
        library.addFavorites([a, b])
        XCTAssertTrue(library.isFavorite(b))

        let playlist = library.createPlaylist(named: "Test-Mix")
        library.addAll([a, b], to: playlist)
        library.addAll([b, c], to: playlist)
        XCTAssertEqual(library.playlist(id: playlist.id)?.trackIDs, [a.id, b.id, c.id])

        library.recordPlay(a.id); library.recordPlay(a.id); library.recordPlay(b.id)
        library.recordStart(a.id)
        library.addListened(90)
        let stats = library.stats
        XCTAssertGreaterThanOrEqual(stats.playsThisMonth, 3)
        // Nur die eigenen Testlieder betrachten: Im Simulator können Lieder anderer Tests liegen
        let own = Set(tracks.map(\.id))
        XCTAssertEqual(library.mostPlayed().first { own.contains($0.0.id) }?.0.id, a.id)
        XCTAssertEqual(library.history().first { own.contains($0.0.id) }?.0.id, a.id)

        library.deleteAll([b])
        XCTAssertFalse(library.isFavorite(b))
        XCTAssertEqual(library.playlist(id: playlist.id)?.trackIDs, [a.id, c.id])
        tracks.remove(at: 1)
    }

    func testBackupRestoresPlaylistsAndHearts() throws {
        let (a, b) = (tracks[0], tracks[1])
        let playlist = library.createPlaylist(named: "Test-Party")
        library.addAll([b, a], to: playlist)
        library.toggleFavorite(b)
        library.recordPlay(b.id)
        let doc = try BackupDocument(state: library.stateSnapshot)
        let backup = try Library.decodeBackup(doc.data)

        library.deletePlaylist(library.playlist(id: playlist.id)!)
        library.toggleFavorite(b)
        let result = library.restore(from: backup)
        XCTAssertGreaterThanOrEqual(result.matched, 2)
        let restored = try XCTUnwrap(library.playlists.first { $0.name == "Test-Party" })
        XCTAssertEqual(restored.trackIDs, [b.id, a.id])
        XCTAssertTrue(library.isFavorite(b))
        // Nochmal: nichts doppelt
        library.restore(from: backup)
        XCTAssertEqual(library.playlists.filter { $0.name == "Test-Party" }.count, 1)
        XCTAssertThrowsError(try Library.decodeBackup(Data("kein json".utf8)))
    }
}

@MainActor
final class FeaturePlayerTests: XCTestCase {
    private var library: Library!
    private var player: AudioPlayer!
    private var tracks: [Track] = []

    override func setUp() async throws {
        library = Library(autoScan: false)
        player = AudioPlayer()
        tracks = []
        for i in 0..<4 {
            let src = try Fixture.tempCopy("untagged.mp3", as: "fp-\(i)-\(UUID().uuidString.prefix(6)).mp3")
            tracks.append(try await library.importAudio(from: src, move: true))
        }
    }

    override func tearDown() async throws {
        player.stop()
        player.sound.enabled = false
        player.sound.preset = "Normal"
        for t in tracks { library.delete(t) }
        player = nil
        library = nil
    }

    func testPlayNextKeepsOrderAlsoWithShuffle() {
        player.play([tracks[0], tracks[1]], startAt: 0)
        player.playNext(tracks[2])
        player.playNext(tracks[3])
        XCTAssertEqual(player.queue.map(\.id), [tracks[0].id, tracks[2].id, tracks[3].id, tracks[1].id])
        player.next()
        XCTAssertEqual(player.current?.id, tracks[2].id)

        player.shuffle = true
        XCTAssertEqual(player.current?.id, tracks[2].id, "Zufall ändert den laufenden Titel nicht")
        player.playNext(tracks[0])
        player.next()
        XCTAssertEqual(player.current?.id, tracks[0].id, "Als Nächstes gilt auch bei Zufall")
        player.shuffle = false
        XCTAssertEqual(player.current?.id, tracks[0].id)
    }

    func testSleepTimerStopsMusic() async {
        player.repeatMode = .one
        player.play([tracks[0]], startAt: 0)
        XCTAssertTrue(player.isPlaying)
        player.startSleepTimer(seconds: 1.5)
        XCTAssertNotNil(player.sleepRemaining)
        let stopped = await waitUntil(timeout: 6) { !self.player.isPlaying }
        XCTAssertTrue(stopped, "Schlafmodus schaltet die Musik nicht aus")
        XCTAssertNil(player.sleepRemaining)

        player.play([tracks[0]], startAt: 0)
        player.startSleepTimer(seconds: 1)
        player.cancelSleepTimer()
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        XCTAssertTrue(player.isPlaying, "Abgebrochener Timer schaltet nichts aus")
    }

    func testEqualizerAppliesPreset() {
        player.sound.choose("Bass-Boost")
        XCTAssertTrue(player.equalizerActive)
        XCTAssertEqual(player.appliedGains.first ?? 0, 6, accuracy: 0.01)
        player.sound.setBand(9, 3)
        XCTAssertEqual(player.sound.preset, SoundPresets.custom)
        XCTAssertEqual(player.appliedGains[9], 3, accuracy: 0.01)
        player.sound.enabled = false
        XCTAssertFalse(player.equalizerActive)
    }

    func testPlayCountAndHistoryCallbacks() async {
        var started: [UUID] = []
        player.onTrackStarted = { started.append($0) }
        player.play(tracks, startAt: 1)
        XCTAssertEqual(started, [tracks[1].id])
        var played: [UUID] = []
        player.onTrackPlayed = { played.append($0) }
        // Testlied ist ~2 s lang → nach der Hälfte gilt es als gehört
        let counted = await waitUntil(timeout: 5) { played.contains(self.tracks[1].id) }
        XCTAssertTrue(counted)
    }
}

// MARK: - Zuschneiden

/// Verlustfreies Zuschneiden: nur ganze Frames, Tags bleiben, Längenangabe stimmt
final class Mp3TrimmerTests: XCTestCase {
    /// MPEG-1 Layer III, 128 kbit/s, 44,1 kHz, Stereo → 417 Bytes, 1152 Samples (≈ 26,1 ms)
    private func frame(_ fill: UInt8) -> [UInt8] {
        var f = [UInt8](repeating: fill, count: 417)
        f[0] = 0xFF; f[1] = 0xFB; f[2] = 0x90; f[3] = 0x00
        return f
    }

    private func infoFrame(frames: Int, bytes: Int) -> [UInt8] {
        var f = [UInt8](repeating: 0, count: 417)
        f[0] = 0xFF; f[1] = 0xFB; f[2] = 0x90; f[3] = 0x00
        f.replaceSubrange(36..<40, with: Array("Info".utf8))
        for (i, v) in [3, frames, bytes].enumerated() {
            let p = 40 + i * 4
            f[p] = UInt8((v >> 24) & 0xFF); f[p + 1] = UInt8((v >> 16) & 0xFF); f[p + 2] = UInt8((v >> 8) & 0xFF); f[p + 3] = UInt8(v & 0xFF)
        }
        return f
    }

    private let id3: [UInt8] = Array("ID3".utf8) + [3, 0, 0, 0, 0, 0, 20] + [UInt8](repeating: 7, count: 20)
    private let id3v1: [UInt8] = Array("TAG".utf8) + [UInt8](repeating: 1, count: 125)

    private func song(_ count: Int, info: Bool) -> [UInt8] {
        var out = id3
        if info { out += infoFrame(frames: count, bytes: 417 * (count + 1)) }
        for i in 0..<count { out += frame(UInt8(i % 100)) }
        return out + id3v1
    }

    private func int(_ b: [UInt8], _ p: Int) -> Int {
        (Int(b[p]) << 24) | (Int(b[p + 1]) << 16) | (Int(b[p + 2]) << 8) | Int(b[p + 3])
    }

    func testReadsDuration() throws {
        let p = try Mp3Trimmer.parse(song(383, info: true))
        XCTAssertEqual(p.frames.count, 383)
        XCTAssertEqual(p.durationMs, 10_004)
        XCTAssertNotNil(p.infoFrame)
    }

    func testCutsTheLastSecondsAndKeepsTags() throws {
        let trimmed = try Mp3Trimmer.trim(song(383, info: true), startMs: 0, endMs: 10_004 - 3_000)
        let p = try Mp3Trimmer.parse(trimmed)
        XCTAssertTrue((6_980...7_020).contains(p.durationMs), "war \(p.durationMs)")
        XCTAssertEqual(Array(trimmed[0..<id3.count]), id3)
        XCTAssertEqual(Array(trimmed[(trimmed.count - 128)...]), id3v1)
        XCTAssertEqual(int(trimmed, id3.count + 44), p.frames.count)
        XCTAssertEqual(int(trimmed, id3.count + 48), 417 * (p.frames.count + 1))
        XCTAssertEqual(trimmed[id3.count + 417 + 4], 0)
    }

    func testCutsStartAndEnd() throws {
        let trimmed = try Mp3Trimmer.trim(song(383, info: false), startMs: 2_000, endMs: 5_000)
        let p = try Mp3Trimmer.parse(trimmed)
        XCTAssertTrue((2_970...3_030).contains(p.durationMs), "war \(p.durationMs)")
        XCTAssertEqual(trimmed[id3.count + 4], 77, "erster behaltener Frame ist Nr. 77")
    }

    func testRefusesToCutEverything() {
        XCTAssertThrowsError(try Mp3Trimmer.trim(song(100, info: false), startMs: 1_000, endMs: 1_500))
        XCTAssertThrowsError(try Mp3Trimmer.parse([UInt8](repeating: 0x41, count: 5000)))
    }

    @MainActor
    func testTrimsRealSongInLibrary() async throws {
        let library = Library()
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("trim-\(UUID().uuidString).mp3")
        try FileManager.default.copyItem(at: Fixture.url("tagged.mp3"), to: tmp)
        let track = try await library.importAudio(from: tmp, move: true)
        defer { library.delete(track) }
        let before = await library.exactDuration(of: track)
        XCTAssertGreaterThan(before, 2)
        let copy = try await library.trim(track, start: 0, end: before - 1, asCopy: true)
        defer { library.delete(copy) }
        XCTAssertEqual(copy.title, track.title + " (gekürzt)")
        XCTAssertEqual(copy.duration, before - 1, accuracy: 0.1)
        // Original unverändert
        let original = await library.exactDuration(of: track)
        XCTAssertEqual(original, before, accuracy: 0.01)
    }
}

// MARK: - Jahresrückblick

final class RecapTests: XCTestCase {
    private var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Berlin")!
        return c
    }()

    private func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 12) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h))!
    }

    private func track(_ title: String, _ artist: String, album: String = "", added: Date? = nil) -> Track {
        var t = Track(id: UUID(), fileName: "\(title).mp3", title: title, artist: artist, duration: 180, addedAt: added ?? at(2025, 6, 1))
        t.album = album
        return t
    }

    func testBuildsTheYear() {
        let a = track("Nachtfahrt", "Luna", album: "Neon")
        let b = track("Sonnenweg", "Luna", album: "Neon", added: at(2026, 2, 1))
        let c = track("Bassline", "Kopfnicker")
        let plays = [
            PlayEvent(trackID: a.id, at: at(2026, 3, 1, 23)), PlayEvent(trackID: a.id, at: at(2026, 3, 2, 23)),
            PlayEvent(trackID: a.id, at: at(2026, 3, 3, 1)), PlayEvent(trackID: a.id, at: at(2026, 3, 3, 2)),
            PlayEvent(trackID: a.id, at: at(2026, 3, 3, 3)),
            PlayEvent(trackID: b.id, at: at(2026, 7, 10, 8)), PlayEvent(trackID: c.id, at: at(2026, 7, 11, 18)),
            PlayEvent(trackID: c.id, at: at(2025, 12, 31, 18)), // Vorjahr zählt nicht
            PlayEvent(trackID: UUID(), at: at(2026, 5, 5)),       // Lied gibt es nicht mehr
        ]
        let state = LibraryState(tracks: [a, b, c], playlists: [], pinned: [], plays: plays.reversed(),
                                 listenedByMonth: ["2026-03": 1800, "2026-07": 600, "2025-12": 5940])
        let r = Recap.build(state, year: 2026, calendar: calendar)
        XCTAssertEqual(r.plays, 7)
        XCTAssertEqual(r.listenedSeconds, 2400)
        XCTAssertEqual(r.topTracks.map(\.0.title), ["Nachtfahrt", "Bassline", "Sonnenweg"])
        XCTAssertEqual(r.topTracks.first?.1, 5)
        XCTAssertEqual(r.topArtists.first?.0, "Luna")
        XCTAssertEqual(r.topArtists.first?.1, 6)
        XCTAssertEqual(r.topAlbum?.0, "Neon")
        XCTAssertEqual(r.topMonth, 3)
        XCTAssertEqual(r.listenerType, .night)
        XCTAssertEqual(r.longestStreak, 3)
        XCTAssertEqual(r.daysListened, 5)
        XCTAssertEqual(r.firstTrack?.title, "Nachtfahrt")
        XCTAssertEqual(r.obsession?.track.id, a.id)
        XCTAssertEqual(r.obsession?.count, 3)
        XCTAssertEqual(r.obsession?.day.day, 3)
        XCTAssertEqual(r.newTracks, 1)
        XCTAssertEqual(r.distinctTracks, 3)
        XCTAssertEqual(r.distinctArtists, 2)
        XCTAssertEqual(r.topWeekday, 3) // 3. März 2026 ist ein Dienstag
        XCTAssertTrue(r.hasData)
    }

    func testPicksTheRightYear() {
        let a = track("A", "X"), b = track("B", "Y")
        var plays = (1...5).map { PlayEvent(trackID: a.id, at: at(2025, 11, $0)) }
        plays.append(PlayEvent(trackID: b.id, at: at(2026, 1, 2)))
        let state = LibraryState(tracks: [a, b], playlists: [], pinned: [], plays: plays)
        XCTAssertEqual(Recap.availableYears(state, calendar: calendar), [2025])
        XCTAssertEqual(Recap.defaultYear(state, now: at(2026, 1, 10), calendar: calendar), 2025)
        XCTAssertNil(Recap.defaultYear(LibraryState(tracks: [], playlists: [], pinned: []), now: at(2026, 11, 9), calendar: calendar))
        let r = Recap.build(LibraryState(tracks: [a, b], playlists: [], pinned: [], plays: [PlayEvent(trackID: a.id, at: at(2026, 1, 5))]),
                            year: 2026, calendar: calendar)
        XCTAssertEqual(r.listenedSeconds, 180, "ohne Hörzeit: aus Liedlänge geschätzt")
        XCTAssertFalse(r.hasData)
        XCTAssertEqual(Recap.number(12_345), "12.345")
    }
}

// MARK: - Genres, Suche, Infos in die MP3 schreiben

final class SearchAndTagsTests: XCTestCase {
    func testNormalizesGenres() {
        XCTAssertEqual(Genres.normalize("(17)"), "Rock")
        XCTAssertEqual(Genres.normalize("7"), "Hip-Hop")
        XCTAssertEqual(Genres.normalize("(17)Rock"), "Rock")
        XCTAssertEqual(Genres.normalize("hip hop"), "Hip-Hop")
        XCTAssertEqual(Genres.normalize("Pop; Dance"), "Pop")
        XCTAssertEqual(Genres.normalize("deutsch rap"), "Deutschrap")
        XCTAssertEqual(Genres.normalize("  "), "")
        XCTAssertEqual(Genres.normalize("(999)"), "")
    }

    func testSearchFindsCategories() {
        var a = Track(id: UUID(), fileName: "a.mp3", title: "Bella Ciao", artist: "Band", duration: 3, addedAt: Date())
        a.genre = "Folk"
        var b = Track(id: UUID(), fileName: "b.mp3", title: "Sommernacht", artist: "Bella", duration: 3, addedAt: Date())
        b.album = "Bella Album"; b.genre = "Hip-Hop"
        let playlist = Playlist(id: UUID(), name: "Bella Mix", trackIDs: [])
        let r = LibrarySearch.run(tracks: [a, b], playlists: [playlist], query: "bella")
        XCTAssertEqual(r.artists, ["Bella"])
        XCTAssertEqual(r.albums, ["Bella Album"])
        XCTAssertEqual(r.playlists.map(\.name), ["Bella Mix"])
        XCTAssertEqual(r.tracks.first?.title, "Bella Ciao", "Titel-Treffer zuerst")
        XCTAssertEqual(LibrarySearch.run(tracks: [a, b], playlists: [], query: "hip").genres, ["Hip-Hop"])
        XCTAssertTrue(LibrarySearch.run(tracks: [a, b], playlists: [], query: "xyz").isEmpty)
        XCTAssertEqual(LibrarySearch.remember(["alt", "Bella"], "bella"), ["bella", "alt"])
        XCTAssertEqual(LibrarySearch.remember(["alt"], "a"), ["alt"], "zu kurz")
    }

    /// Suche in einer Liste: Titel, Interpret und Album zählen, die Reihenfolge der Liste bleibt
    func testFilterKeepsListOrder() {
        var a = Track(id: UUID(), fileName: "a.mp3", title: "Regen", artist: "Bella", duration: 3, addedAt: Date())
        a.album = "Nachts"
        var b = Track(id: UUID(), fileName: "b.mp3", title: "Bella Ciao", artist: "Marco", duration: 3, addedAt: Date())
        b.album = "Sommer"
        var c = Track(id: UUID(), fileName: "c.mp3", title: "Sonne", artist: "Andere", duration: 3, addedAt: Date())
        c.album = "Tag"
        let list = [a, b, c]
        XCTAssertEqual(LibrarySearch.filter(list, query: "bella").map(\.title), ["Regen", "Bella Ciao"], "Reihenfolge der Liste bleibt")
        XCTAssertEqual(LibrarySearch.filter(list, query: "SOMMER").map(\.title), ["Bella Ciao"], "Album zählt, Groß/Klein egal")
        XCTAssertEqual(LibrarySearch.filter(list, query: "  "), list, "leere Suche zeigt alles")
        XCTAssertTrue(LibrarySearch.filter(list, query: "xyz").isEmpty)
    }

    func testWritesTagsIntoMP3() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("tags-\(UUID().uuidString).mp3")
        try FileManager.default.copyItem(at: Fixture.url("tagged.mp3"), to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let before = try Mp3Trimmer.parse([UInt8](Data(contentsOf: url))).frames.count
        try Id3Writer.write(url, Id3Writer.Update(title: "Neuer Titel", artist: "Neue Band", album: "Neues Album", genre: "Hip-Hop", lyrics: "Erste Zeile"))
        let track = await Library.makeTrack(for: url, artworkTo: FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).jpg"))
        XCTAssertEqual(track.title, "Neuer Titel")
        XCTAssertEqual(track.artist, "Neue Band")
        XCTAssertEqual(track.album, "Neues Album")
        XCTAssertEqual(track.genre, "Hip-Hop")
        let lyrics = try await AVURLAsset(url: url).load(.lyrics)
        XCTAssertEqual(lyrics, "Erste Zeile")
        // Musik unverändert
        XCTAssertEqual(try Mp3Trimmer.parse([UInt8](Data(contentsOf: url))).frames.count, before)
    }
}

// MARK: - Eigenes Cover entfernen holt das Original zurück

final class CoverRestoreTests: XCTestCase {
    @MainActor
    func testRemovingCustomCoverRestoresOriginal() async throws {
        let library = Library()
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("cover-\(UUID().uuidString).mp3")
        try FileManager.default.copyItem(at: Fixture.url("tagged.mp3"), to: tmp)
        let track = try await library.importAudio(from: tmp, move: true)
        defer { library.delete(track) }
        let original = try XCTUnwrap(Id3Writer.picture(track.fileURL), "Testlied hat ein Cover")

        let red = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 40)).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 40, height: 40))
        }
        library.setCustomCover(track, image: red)
        await Library.awaitFileWrites()
        let custom = try XCTUnwrap(Id3Writer.picture(track.fileURL))
        XCTAssertNotEqual(custom, original, "eigenes Cover steht in der MP3")
        XCTAssertTrue(library.hasCustomCover(track))

        await library.removeCustomCover(track)
        XCTAssertEqual(Id3Writer.picture(track.fileURL), original, "Original-Cover wiederhergestellt")
        XCTAssertFalse(library.hasCustomCover(track))
        XCTAssertFalse(FileManager.default.fileExists(atPath: Library.originalCoverURL(track).path))
    }
}

// MARK: - Ein-/Ausblenden beim Zuschneiden

final class FadeTests: XCTestCase {
    func testFadeGain() {
        let f = AudioConverter.Fade(fadeIn: 2, fadeOut: 4, total: 10)
        XCTAssertEqual(f.gain(at: 0), 0, accuracy: 0.001)
        XCTAssertEqual(f.gain(at: 1), 0.5, accuracy: 0.001)
        XCTAssertEqual(f.gain(at: 5), 1, accuracy: 0.001)
        XCTAssertEqual(f.gain(at: 8), 0.5, accuracy: 0.001)
        XCTAssertEqual(f.gain(at: 10), 0, accuracy: 0.001)
        XCTAssertEqual(AudioConverter.Fade(fadeIn: 0, fadeOut: 0, total: 1).gain(at: 0), 1, accuracy: 0.001)
    }

    /// Mittlere Lautstärke in einem Zeitabschnitt (Sekunden)
    private func loudness(_ url: URL, from: Double, to: Double) throws -> Float {
        let file = try AVAudioFile(forReading: url)
        let rate = file.processingFormat.sampleRate
        let start = AVAudioFramePosition(from * rate)
        let count = AVAudioFrameCount(max(0, min(to * rate, Double(file.length)) - Double(start)))
        guard count > 0, let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: count) else { return 0 }
        file.framePosition = start
        try file.read(into: buffer, frameCount: count)
        guard let data = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return 0 }
        var sum: Float = 0
        for i in 0..<Int(buffer.frameLength) { sum += abs(data[i]) }
        return sum / Float(buffer.frameLength)
    }

    @MainActor
    func testFadeOutKeepsTagsAndGetsQuieter() async throws {
        let library = Library()
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("fade-\(UUID().uuidString).mp3")
        try FileManager.default.copyItem(at: Fixture.url("tagged.mp3"), to: tmp)
        let track = try await library.importAudio(from: tmp, move: true)
        defer { library.delete(track) }
        let before = await library.exactDuration(of: track)
        let cover = Id3Writer.picture(track.fileURL)
        XCTAssertNotNil(cover)

        let result = try await library.trim(track, start: 0, end: before, asCopy: false, fadeOut: 1)
        XCTAssertEqual(result.duration, before, accuracy: 0.25)
        XCTAssertEqual(Id3Writer.picture(track.fileURL), cover, "Cover bleibt")
        let reread = await Library.makeTrack(for: track.fileURL, artworkTo: FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).jpg"))
        XCTAssertEqual(reread.title, "Testlied")
        let startLevel = try loudness(track.fileURL, from: 0.2, to: 1.0)
        let endLevel = try loudness(track.fileURL, from: before - 0.4, to: before)
        XCTAssertGreaterThan(startLevel, 0.001)
        XCTAssertLessThan(endLevel, startLevel * 0.6, "Ende leiser als Anfang")
    }
}

// MARK: - Widget-Verknüpfungen

final class WidgetLinkTests: XCTestCase {
    func testParsesLinks() {
        XCTAssertEqual(WidgetLink(url: URL(string: "ifmusic://resume")!), .resume)
        XCTAssertEqual(WidgetLink(url: URL(string: "ifmusic://shuffle")!), .shuffle)
        XCTAssertEqual(WidgetLink(url: URL(string: "IFMUSIC://Favorites")!), .favorites)
        XCTAssertNil(WidgetLink(url: URL(string: "ifmusic://unbekannt")!))
        XCTAssertNil(WidgetLink(url: URL(string: "file:///tmp/lied.mp3")!), "Dateien werden weiter importiert")
        for link in WidgetLink.allCases { XCTAssertEqual(WidgetLink(url: link.url), link) }
    }
}
