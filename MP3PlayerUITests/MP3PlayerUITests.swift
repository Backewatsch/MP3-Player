import XCTest

final class MP3PlayerUITests: XCTestCase {
    /// Test-MP3s vom lokalen Testserver des Workflows (python3 -m http.server auf dem Mac; der Simulator erreicht ihn über localhost)
    private let mp3URL1 = "http://localhost:8765/tagged.mp3"
    private let mp3URL2 = "http://localhost:8765/untagged.mp3"

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

    /// Mediathek → ＋ → „Von Link herunterladen“
    private func openDownloads() {
        tab("Mediathek")
        let add = app.buttons["addMenu"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 5), "＋-Menü fehlt")
        add.tap()
        let item = app.buttons["Von Link herunterladen"].firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 5), "Menüpunkt „Von Link herunterladen“ fehlt")
        item.tap()
        XCTAssertTrue(app.buttons["closeDownloads"].waitForExistence(timeout: 5), "Download-Liste öffnet sich nicht")
    }

    private func downloadTestSong(_ url: String) {
        openDownloads()
        let field = app.textFields["downloadLinkField"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(url)
        app.buttons["downloadStartButton"].tap()
        waitForFinishedDownload(timeout: 120)
        app.buttons["closeDownloads"].tap()
    }

    /// Nach oben wischen, bis das Element sichtbar ist (Listen laden Zeilen erst beim Scrollen)
    @discardableResult
    private func reveal(_ element: XCUIElement, swipes: Int = 8) -> Bool {
        for _ in 0..<swipes {
            if element.exists && element.isHittable { return true }
            app.swipeUp()
        }
        return element.exists
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
        // Start-Tab ist die erste Seite
        XCTAssertTrue(app.otherElements["startScreen"].waitForExistence(timeout: 10) || app.staticTexts["Willkommen!"].waitForExistence(timeout: 5))
        screenshot("00-Start-leer")
        tab("Mediathek")
        XCTAssertTrue(app.staticTexts["Noch keine Musik"].waitForExistence(timeout: 10))
        screenshot("01-Mediathek-leer")
        app.swipeUp()
        XCTAssertTrue(app.staticTexts["Heruntergeladen"].waitForExistence(timeout: 5), "Playlists stehen in der Mediathek")
        XCTAssertTrue(app.buttons["newPlaylistButton"].exists, "Neue Playlist in der Mediathek")
        screenshot("01b-Mediathek-Playlists")

        tab("Bearbeiten")
        XCTAssertTrue(app.staticTexts["Noch keine Musik"].waitForExistence(timeout: 5), "Tab „Bearbeiten“ fehlt")
        screenshot("03-Bearbeiten-leer")

        openDownloads()
        XCTAssertTrue(app.textFields["downloadLinkField"].waitForExistence(timeout: 5))
        screenshot("04-Downloads-leer")
        app.buttons["closeDownloads"].tap()
    }

    func test2_CreatePlaylistInLibrary() {
        tab("Mediathek")
        let new = app.buttons["newPlaylistButton"]
        if !new.waitForExistence(timeout: 5) { app.swipeUp() }
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
        if !app.staticTexts["Sommerhits"].waitForExistence(timeout: 3) { app.swipeUp() }
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
        XCTAssertEqual(miniButton.label, "Abspielen", "Download darf nicht von selbst losspielen")

        // Playlist „Heruntergeladen“ in der Mediathek
        tab("Mediathek")
        let downloadsRow = app.staticTexts["Heruntergeladen"].firstMatch
        XCTAssertTrue(reveal(downloadsRow), "Playlist „Heruntergeladen“ fehlt")
        XCTAssertTrue(app.staticTexts["1 Titel"].exists, "Playlist zählt den neuen Titel nicht")
        downloadsRow.tap()
        let play = app.buttons["playAllButton"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        play.tap()
        XCTAssertEqual(miniButton.label, "Pause", "Wiedergabe läuft nicht")
        // Das Testlied ist nur ~2 s lang – danach springt der Player pausiert an den Anfang (kein Fehler)
        screenshot("07-Playlist-spielt")

        // Großer Player
        app.otherElements["miniPlayer"].firstMatch.tap()
        let big = app.buttons["bigPlayPause"].firstMatch
        XCTAssertTrue(big.waitForExistence(timeout: 5))
        screenshot("08-Player")
        // Umschalten muss funktionieren (das kurze Testlied kann schon zu Ende sein)
        let before = big.label
        big.tap()
        XCTAssertNotEqual(big.label, before, "Play/Pause funktioniert nicht")
        big.tap()
        XCTAssertEqual(big.label, before)
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
        let pinned = app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] %@", "Kurzwahl")).firstMatch
        XCTAssertTrue(pinned.waitForExistence(timeout: 5), "Kurzwahl erscheint nicht")
        screenshot("16-Mediathek-Kurzwahl")
    }

    /// Tab „Bearbeiten“: Ende wegschneiden und als neues Lied speichern
    func test4_TrimSong() {
        downloadTestSong(mp3URL1)
        tab("Bearbeiten")
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "edit-")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "Lied fehlt im Tab „Bearbeiten“")
        screenshot("10-Bearbeiten-Auswahl")
        row.tap()
        XCTAssertTrue(app.staticTexts["trimSummary"].waitForExistence(timeout: 10), "Zuschneiden öffnet sich nicht")
        let minus = app.buttons["trimEnd-minus"].firstMatch
        XCTAssertTrue(minus.waitForExistence(timeout: 5))
        minus.tap()
        screenshot("11-Zuschneiden")
        let save = app.buttons["trimSaveCopy"].firstMatch
        if !save.isHittable { app.swipeUp() }
        save.tap()
        let ok = app.alerts.buttons["OK"].firstMatch
        XCTAssertTrue(ok.waitForExistence(timeout: 15), "Speichern meldet sich nicht")
        let text = app.alerts.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "gekürzt")).firstMatch
        XCTAssertTrue(text.exists, "Zuschneiden fehlgeschlagen")
        screenshot("12-Zuschneiden-fertig")
        ok.tap()
        let copy = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "(gekürzt)")).firstMatch
        XCTAssertTrue(copy.waitForExistence(timeout: 10), "Gekürztes Lied fehlt in der Liste")
    }

    func test5_SettingsShowsVersionUpdatesAndConverter() {
        tab("Mediathek")
        let gear = app.buttons["settingsButton"]
        XCTAssertTrue(gear.waitForExistence(timeout: 10))
        gear.tap()
        XCTAssertTrue(app.staticTexts["currentVersion"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["checkUpdatesButton"].exists)
        let done = NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@",
                               "neueste Version", "Update verfügbar", "Keine öffentliche Version", "Fehler")
        _ = app.staticTexts.containing(done).firstMatch.waitForExistence(timeout: 20)
        screenshot("13-Info-Updates")
        app.swipeUp()
        XCTAssertTrue(app.buttons["backupCreate"].waitForExistence(timeout: 5), "Sicherung fehlt")
        XCTAssertTrue(app.buttons["openSound"].exists, "Klang fehlt")
        if !app.buttons["convertAllButton"].exists { app.swipeUp() }
        XCTAssertTrue(app.buttons["convertAllButton"].waitForExistence(timeout: 5), "Konverter fehlt")
        screenshot("13b-Einstellungen-Sicherung")
        app.buttons["Fertig"].firstMatch.tap()
    }

    /// Lieblingssongs, Sortieren, Mehrfachauswahl, Statistik, Klang und Schlafmodus
    func test7_NewFeatures() {
        downloadTestSong(mp3URL1)

        // Player: Herz, Klang, Schlafmodus
        let mini = app.otherElements["miniPlayer"].firstMatch
        XCTAssertTrue(mini.waitForExistence(timeout: 5))
        mini.tap()
        let fav = app.buttons["favButton"].firstMatch
        XCTAssertTrue(fav.waitForExistence(timeout: 5))
        fav.tap()
        XCTAssertEqual(fav.label, "Aus Lieblingssongs entfernen")
        app.buttons["soundButton"].firstMatch.tap()
        XCTAssertTrue(app.switches["eqSwitch"].waitForExistence(timeout: 5), "Klang-Fenster fehlt")
        app.buttons["preset-Bass-Boost"].firstMatch.tap()
        screenshot("20-Klang")
        app.buttons["Fertig"].firstMatch.tap()
        app.buttons["sleepButton"].firstMatch.tap()
        let minutes = app.textFields["sleepMinutes"]
        XCTAssertTrue(minutes.waitForExistence(timeout: 5), "Schlafmodus-Feld fehlt")
        minutes.tap()
        minutes.typeText("30")
        screenshot("21-Schlafmodus")
        app.buttons["sleepStart"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Schlafmodus läuft"].waitForExistence(timeout: 5), "Schlafmodus läuft nicht")
        screenshot("22-Schlafmodus-laeuft")
        app.buttons["Schließen"].tap()

        // Mediathek: Lieblingssongs, Sortieren, Auswahl, Statistik
        tab("Mediathek")
        app.buttons["category-Lieblingssongs"].firstMatch.tap()
        XCTAssertTrue(app.buttons["trackMore"].firstMatch.waitForExistence(timeout: 5), "Lieblingssong fehlt")
        screenshot("23-Lieblingssongs")
        goBackToRoot()
        app.buttons["category-Titel"].firstMatch.tap()
        let sortButton = app.buttons["sortButton"].firstMatch
        XCTAssertTrue(sortButton.waitForExistence(timeout: 5))
        sortButton.tap()
        XCTAssertTrue(app.buttons["Zuletzt hinzugefügt"].waitForExistence(timeout: 5), "Sortier-Menü fehlt")
        screenshot("24-Sortieren")
        app.buttons["Zuletzt hinzugefügt"].tap()
        app.buttons["selectButton"].firstMatch.tap()
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "select-")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        XCTAssertTrue(app.staticTexts["1 ausgewählt"].waitForExistence(timeout: 5), "Auswahl funktioniert nicht")
        screenshot("25-Auswahl")
        app.buttons["sel-close"].tap()
        goBackToRoot()
        app.swipeUp()
        app.buttons["category-Statistik"].firstMatch.tap()
        XCTAssertTrue(app.otherElements["stat-month"].waitForExistence(timeout: 5) || app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Hörzeit")).firstMatch.waitForExistence(timeout: 5))
        screenshot("26-Statistik")
        goBackToRoot()

        // Start-Tab zeigt Lieblingssongs und Statistik
        tab("Start")
        let stats = app.buttons["startStats"].firstMatch
        XCTAssertTrue(reveal(stats) || app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Diesen Monat")).firstMatch.exists,
                      "Statistik fehlt auf der Startseite")
        screenshot("27-Start")
    }
}
