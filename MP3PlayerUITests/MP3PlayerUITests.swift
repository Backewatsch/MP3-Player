import XCTest

final class MP3PlayerUITests: XCTestCase {
    /// Frei verfügbare Beispiel-MP3s (SoundHelix, für Tests gedacht).
    private let mp3URL1 = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
    private let mp3URL2 = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"

    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uiTestReset"]
        app.launch()
    }

    private func screenshot(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"], !dir.isEmpty {
            let url = URL(fileURLWithPath: dir).appendingPathComponent("\(name).png")
            try? shot.pngRepresentation.write(to: url)
        }
    }

    private func tab(_ name: String) {
        let button = app.tabBars.buttons[name].firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 10), "Tab \(name) fehlt")
        button.tap()
    }

    private func waitForFinishedDownload(timeout: TimeInterval) {
        let finished = app.staticTexts["In der Playlist „Heruntergeladen“"].firstMatch
        if !finished.waitForExistence(timeout: timeout) {
            screenshot("Fehler-Download")
            XCTFail("Download wurde nicht fertig")
        }
    }

    func test1_AllTabsShowUp() {
        XCTAssertTrue(app.staticTexts["Noch keine Musik"].waitForExistence(timeout: 10))
        screenshot("01-Titel-leer")

        tab("Playlists")
        XCTAssertTrue(app.staticTexts["Heruntergeladen"].waitForExistence(timeout: 5))
        screenshot("02-Playlists")

        tab("Browser")
        XCTAssertTrue(app.textFields["addressField"].waitForExistence(timeout: 5))
        let startPage = app.webViews.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "Internet Archive")).firstMatch
        XCTAssertTrue(startPage.waitForExistence(timeout: 15), "Startseite des Browsers wird nicht angezeigt")
        screenshot("03-Browser-Startseite")

        tab("Downloads")
        XCTAssertTrue(app.textFields["downloadLinkField"].waitForExistence(timeout: 5))
        screenshot("04-Downloads-leer")
    }

    func test2_DownloadByLinkThenPlayFromPlaylist() {
        tab("Downloads")
        let field = app.textFields["downloadLinkField"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(mp3URL1)
        app.buttons["downloadStartButton"].tap()
        screenshot("05-Download-laeuft")
        waitForFinishedDownload(timeout: 120)
        screenshot("06-Download-fertig")

        // Titel ist in der Playlist „Heruntergeladen“ und lässt sich abspielen
        tab("Playlists")
        XCTAssertTrue(app.staticTexts["1 Titel"].waitForExistence(timeout: 5), "Playlist zählt den neuen Titel nicht")
        app.staticTexts["Heruntergeladen"].firstMatch.tap()
        let play = app.buttons["Abspielen"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        play.tap()

        let miniButton = app.buttons["miniPlayPause"].firstMatch
        XCTAssertTrue(miniButton.waitForExistence(timeout: 5), "Mini-Player erscheint nicht")
        XCTAssertEqual(miniButton.label, "Pause", "Wiedergabe läuft nicht")
        sleep(3)
        XCTAssertEqual(miniButton.label, "Pause", "Wiedergabe hat sich selbst gestoppt")
        screenshot("07-Playlist-spielt")

        // Großer Player
        app.otherElements["miniPlayer"].firstMatch.tap()
        let big = app.buttons["bigPlayPause"].firstMatch
        XCTAssertTrue(big.waitForExistence(timeout: 5))
        screenshot("08-Player")
        big.tap()
        XCTAssertEqual(big.label, "Abspielen", "Pause funktioniert nicht")
        big.tap()
        XCTAssertEqual(big.label, "Pause")

        // Titel taucht auch in der Bibliothek auf
        app.buttons["Schließen"].tap()
        tab("Titel")
        XCTAssertFalse(app.staticTexts["Noch keine Musik"].exists)
        screenshot("09-Bibliothek")
    }

    func test3_DownloadThroughInternalBrowser() {
        tab("Browser")
        let address = app.textFields["addressField"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        address.tap()
        address.typeText(mp3URL2 + "\n")
        sleep(2)
        screenshot("10-Browser-Download")

        tab("Downloads")
        waitForFinishedDownload(timeout: 120)
        screenshot("11-Browser-Download-fertig")

        tab("Playlists")
        XCTAssertTrue(app.staticTexts["1 Titel"].waitForExistence(timeout: 5))
    }

    func test5_SettingsShowsVersionAndUpdates() {
        let gear = app.buttons["settingsButton"]
        XCTAssertTrue(gear.waitForExistence(timeout: 10))
        gear.tap()
        XCTAssertTrue(app.staticTexts["currentVersion"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["checkUpdatesButton"].exists)
        // Ergebnis der Update-Prüfung abwarten (egal welches)
        let done = NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@",
                               "neueste Version", "Update verfügbar", "Keine öffentliche Version", "Fehler")
        _ = app.staticTexts.containing(done).firstMatch.waitForExistence(timeout: 20)
        screenshot("13-Info-Updates")
        app.buttons["Fertig"].tap()
    }

    func test4_YouTubeShowsHint() {
        tab("Browser")
        let address = app.textFields["addressField"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        address.tap()
        address.typeText("https://m.youtube.com\n")
        let hint = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "YouTube werden nicht unterstützt")).firstMatch
        XCTAssertTrue(hint.waitForExistence(timeout: 30))
        screenshot("12-YouTube-Hinweis")
    }
}
