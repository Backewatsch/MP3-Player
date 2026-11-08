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

    private func openDownloads() {
        tab("Lieder downloaden")
        let button = app.buttons["downloadsButton"]
        XCTAssertTrue(button.waitForExistence(timeout: 5))
        button.tap()
    }

    private func waitForFinishedDownload(timeout: TimeInterval) {
        let finished = app.staticTexts["In der Playlist „Heruntergeladen“"].firstMatch
        if !finished.waitForExistence(timeout: timeout) {
            screenshot("Fehler-Download")
            XCTFail("Download wurde nicht fertig")
        }
    }

    private func goBackToRoot() {
        for _ in 0..<3 {
            let back = app.navigationBars.buttons.element(boundBy: 0)
            guard app.navigationBars["Mediathek"].exists == false, back.exists else { break }
            back.tap()
        }
    }

    func test1_AllTabsShowUp() {
        XCTAssertTrue(app.staticTexts["Noch keine Musik"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Heruntergeladen"].exists, "Playlists stehen in der Mediathek")
        XCTAssertTrue(app.buttons["newPlaylistButton"].exists, "Neue Playlist in der Mediathek")
        screenshot("01-Mediathek-leer")

        tab("Lieder downloaden")
        XCTAssertTrue(app.textFields["addressField"].waitForExistence(timeout: 5))
        let startPage = app.webViews.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "Internet Archive")).firstMatch
        XCTAssertTrue(startPage.waitForExistence(timeout: 15), "Startseite des Browsers wird nicht angezeigt")
        screenshot("03-Browser-Startseite")

        app.buttons["downloadsButton"].tap()
        XCTAssertTrue(app.textFields["downloadLinkField"].waitForExistence(timeout: 5))
        screenshot("04-Downloads-leer")
        app.buttons["closeDownloads"].tap()
    }

    func test2_CreatePlaylistInLibrary() {
        let new = app.buttons["newPlaylistButton"]
        XCTAssertTrue(new.waitForExistence(timeout: 10))
        new.tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText("Sommerhits")
        app.alerts.buttons["Erstellen"].tap()
        // Öffnet direkt die neue Playlist
        XCTAssertTrue(app.navigationBars["Sommerhits"].waitForExistence(timeout: 5), "Neue Playlist wird nicht geöffnet")
        screenshot("02-Neue-Playlist")
        goBackToRoot()
        XCTAssertTrue(app.staticTexts["Sommerhits"].waitForExistence(timeout: 5), "Playlist steht nicht in der Mediathek")
    }

    func test3_DownloadByLinkThenPlayAndMenu() {
        openDownloads()
        let field = app.textFields["downloadLinkField"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(mp3URL1)
        app.buttons["downloadStartButton"].tap()
        screenshot("05-Download-laeuft")
        waitForFinishedDownload(timeout: 120)
        screenshot("06-Download-fertig")
        app.buttons["closeDownloads"].tap()

        // Heruntergeladener Titel steht pausiert in der Wiedergabeliste (Mini-Player sichtbar)
        let miniButton = app.buttons["miniPlayPause"].firstMatch
        XCTAssertTrue(miniButton.waitForExistence(timeout: 5), "Download ist nicht in der Wiedergabeliste")

        // Playlist „Heruntergeladen“ in der Mediathek
        tab("Mediathek")
        XCTAssertTrue(app.staticTexts["1 Titel"].waitForExistence(timeout: 5), "Playlist zählt den neuen Titel nicht")
        app.staticTexts["Heruntergeladen"].firstMatch.tap()
        let play = app.buttons["playAllButton"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        play.tap()
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
        app.buttons["Schließen"].tap()

        // Mediathek → Titel → Optionsmenü
        goBackToRoot()
        app.buttons["category-Titel"].firstMatch.tap()
        let more = app.buttons["trackMore"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 5))
        screenshot("09-Titel")
        more.tap()
        for title in ["Als Nächstes abspielen", "Zu Playlist hinzufügen", "Mix starten", "In die Wiedergabeliste",
                      "Mitwirkende an Song anzeigen", "An Kurzwahl anpinnen"] {
            XCTAssertTrue(app.buttons["action-\(title)"].waitForExistence(timeout: 5), "Menüpunkt fehlt: \(title)")
        }
        screenshot("14-Optionen")

        app.buttons["action-Mitwirkende an Song anzeigen"].tap()
        XCTAssertTrue(app.staticTexts["Format"].waitForExistence(timeout: 10), "Mitwirkende-Ansicht fehlt")
        screenshot("15-Mitwirkende")
        app.buttons["Fertig"].firstMatch.tap()

        // An Kurzwahl anpinnen → erscheint oben in der Mediathek
        XCTAssertTrue(more.waitForExistence(timeout: 5))
        more.tap()
        let pin = app.buttons["action-An Kurzwahl anpinnen"]
        XCTAssertTrue(pin.waitForExistence(timeout: 5))
        pin.tap()
        goBackToRoot()
        XCTAssertTrue(app.staticTexts["Kurzwahl"].waitForExistence(timeout: 5) || app.staticTexts["KURZWAHL"].exists)
        screenshot("16-Mediathek-Kurzwahl")
    }

    func test4_DownloadThroughInternalBrowser() {
        tab("Lieder downloaden")
        let address = app.textFields["addressField"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        address.tap()
        address.typeText(mp3URL2 + "\n")
        sleep(2)
        screenshot("10-Browser-Download")

        app.buttons["downloadsButton"].tap()
        waitForFinishedDownload(timeout: 120)
        screenshot("11-Browser-Download-fertig")
        app.buttons["closeDownloads"].tap()

        tab("Mediathek")
        XCTAssertTrue(app.staticTexts["1 Titel"].waitForExistence(timeout: 5))
    }

    func test5_SettingsShowsVersionUpdatesAndConverter() {
        let gear = app.buttons["settingsButton"]
        XCTAssertTrue(gear.waitForExistence(timeout: 10))
        gear.tap()
        XCTAssertTrue(app.staticTexts["currentVersion"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["checkUpdatesButton"].exists)
        XCTAssertTrue(app.buttons["convertAllButton"].exists, "Konverter fehlt")
        let done = NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@",
                               "neueste Version", "Update verfügbar", "Keine öffentliche Version", "Fehler")
        _ = app.staticTexts.containing(done).firstMatch.waitForExistence(timeout: 20)
        screenshot("13-Info-Updates")
        app.buttons["Fertig"].tap()
    }

    func test6_YouTubeShowsHint() {
        tab("Lieder downloaden")
        let address = app.textFields["addressField"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        address.tap()
        address.typeText("https://m.youtube.com\n")
        let hint = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "YouTube werden nicht unterstützt")).firstMatch
        XCTAssertTrue(hint.waitForExistence(timeout: 30))
        screenshot("12-YouTube-Hinweis")
    }
}
