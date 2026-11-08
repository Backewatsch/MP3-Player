import XCTest
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

    @MainActor
    func testBrowserInputParsing() {
        XCTAssertEqual(BrowserModel.url(fromInput: "https://archive.org")?.absoluteString, "https://archive.org")
        XCTAssertEqual(BrowserModel.url(fromInput: "archive.org/details/audio")?.absoluteString, "https://archive.org/details/audio")
        let search = BrowserModel.url(fromInput: "freie musik mp3")
        XCTAssertEqual(search?.host, "duckduckgo.com")
        XCTAssertTrue(search?.absoluteString.contains("q=freie%20musik%20mp3") == true)
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

    func testAutoAdvanceWhenTrackEnds() async {
        player.play(tracks, startAt: 0)
        player.seek(to: max(0, player.duration - 0.3))
        let advanced = await waitUntil(timeout: 5) { self.player.current?.id == self.tracks[1].id }
        XCTAssertTrue(advanced, "Nach Titelende muss automatisch der nächste Titel kommen")
        XCTAssertTrue(player.isPlaying)
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
