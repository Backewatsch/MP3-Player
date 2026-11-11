import XCTest

final class MP3PlayerUITests: XCTestCase {
    /// Test-MP3s vom lokalen Testserver des Workflows (python3 -m http.server auf dem Mac; der Simulator erreicht ihn über localhost)
    private let mp3URL1 = "http://localhost:8765/tagged.mp3"
    private let mp3URL2 = "http://localhost:8765/untagged.mp3"
    /// 30 Sekunden lang: damit „läuft“ und „pausiert“ sicher zu sehen sind (das kurze Testlied ist nach 3 s zu Ende)
    private let longMp3URL = "http://localhost:8765/long.mp3"

    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uiTestReset"]
        app.launch()
    }

    private func screenshot(_ name: String) {
        // Bildschirmfotos sind nur Beiwerk: Im Simulator läuft die Aufnahme manchmal in eine Zeitüberschreitung.
        // Das darf den Test nicht scheitern lassen.
        let options = XCTExpectedFailure.Options()
        options.isStrict = false
        let stopAtFailure = continueAfterFailure
        continueAfterFailure = true
        var taken: XCUIScreenshot?
        XCTExpectFailure("Bildschirmfoto im Simulator nicht möglich", options: options) {
            taken = XCUIScreen.main.screenshot()
        }
        continueAfterFailure = stopAtFailure
        guard let shot = taken, !shot.pngRepresentation.isEmpty else { return }
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
        if !button.waitForExistence(timeout: 5) || !button.isHittable {
            // iOS 26: Nach dem Herunterscrollen ist die Tab-Leiste geschrumpft – Zurückscrollen klappt sie wieder auf
            for _ in 0..<3 where !(button.exists && button.isHittable) { app.swipeDown() }
            // Letzter Ausweg: die geschrumpfte Leiste antippen
            if !(button.exists && button.isHittable), app.tabBars.firstMatch.exists { app.tabBars.firstMatch.tap() }
        }
        XCTAssertTrue(button.waitForExistence(timeout: 10), "Tab \(name) fehlt")
        // Direkt nach dem Start geht ein Tippen im Simulator manchmal verloren – dann noch einmal
        for _ in 0..<3 {
            button.tap()
            if button.isSelected { break }
            Thread.sleep(forTimeInterval: 1)
        }
    }

    /// Wartet, bis ein Element die erwartete Beschriftung hat
    private func waitForLabel(_ element: XCUIElement, _ label: String, timeout: TimeInterval = 8) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", label), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    /// Kurzer Hinweis oben auf dem Bildschirm („An Kurzwahl angepinnt“ …)
    private func notice(_ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// Suchfeld mit diesem Platzhalter; falls der Simulator es anders benennt, das erste Suchfeld
    private func searchField(_ placeholder: String) -> XCUIElement {
        let named = app.searchFields[placeholder].firstMatch
        return named.waitForExistence(timeout: 5) ? named : app.searchFields.firstMatch
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
        // iOS 26: Das erste Wischen schrumpft oft nur die Tab-Leiste – so lange wischen, bis die Playlists zu sehen sind
        XCTAssertTrue(reveal(app.staticTexts["Heruntergeladen"].firstMatch), "Playlists stehen in der Mediathek")
        XCTAssertTrue(app.buttons["newPlaylistButton"].exists, "Neue Playlist in der Mediathek")
        screenshot("01b-Mediathek-Playlists")

        tab("Bearbeiten")
        XCTAssertTrue(app.staticTexts["Noch keine Musik"].waitForExistence(timeout: 5), "Tab „Bearbeiten“ fehlt")
        screenshot("03-Bearbeiten-leer")

        // ＋-Menü: Ordner nach Liedern durchsuchen, Download per Link
        tab("Mediathek")
        let add = app.buttons["addMenu"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 5), "＋-Menü fehlt")
        add.tap()
        XCTAssertTrue(app.buttons["Ordner nach Liedern durchsuchen"].waitForExistence(timeout: 5),
                      "Menüpunkt „Ordner nach Liedern durchsuchen“ fehlt")
        screenshot("01c-Hinzufuegen-Menue")
        let link = app.buttons["Von Link herunterladen"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 5), "Menüpunkt „Von Link herunterladen“ fehlt")
        link.tap()
        XCTAssertTrue(app.buttons["closeDownloads"].waitForExistence(timeout: 5), "Download-Liste öffnet sich nicht")
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
        field.typeText(longMp3URL)
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
        XCTAssertTrue(app.buttons["playlistCover"].firstMatch.exists, "Großes Cover fehlt auf der Playlist-Seite")
        play.tap()
        XCTAssertTrue(waitForLabel(miniButton, "Pause"), "Wiedergabe läuft nicht")
        screenshot("07-Playlist-spielt")
        // Menü der Playlist: Cover ändern
        app.buttons["playlistMenu"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Cover ändern"].firstMatch.waitForExistence(timeout: 5), "Menüpunkt „Cover ändern“ fehlt")
        screenshot("07b-Playlist-Menue")
        app.buttons["Cover ändern"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Bild auswählen"].firstMatch.waitForExistence(timeout: 5), "Cover-Auswahl öffnet sich nicht")
        app.buttons["Abbrechen"].firstMatch.tap()

        // Großer Player
        app.otherElements["miniPlayer"].firstMatch.tap()
        let big = app.buttons["bigPlayPause"].firstMatch
        XCTAssertTrue(big.waitForExistence(timeout: 5))
        screenshot("08-Player")
        // Umschalten muss funktionieren: aus „läuft“ wird „pausiert“ und umgekehrt (bis zu 3 Versuche, falls ein Tippen verloren geht)
        var toggled = false
        for _ in 0..<3 where !toggled {
            let before = big.label
            big.tap()
            toggled = waitForLabel(big, before == "Pause" ? "Abspielen" : "Pause", timeout: 5)
        }
        XCTAssertTrue(toggled, "Play/Pause funktioniert nicht")
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
        // Kurze Rückmeldung oben auf dem Bildschirm
        XCTAssertTrue(notice("An Kurzwahl angepinnt").waitForExistence(timeout: 6), "Hinweis nach dem Anpinnen fehlt")
        screenshot("15b-Hinweis")
        goBackToRoot()
        // Kurzwahl steht ganz oben – die Liste ist evtl. noch nach unten gescrollt
        // (der Hinweis „An Kurzwahl angepinnt“ zählt nicht)
        let pinned = app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] %@ AND NOT (label CONTAINS %@)",
                                                            "Kurzwahl", "angepinnt")).firstMatch
        for _ in 0..<6 where !(pinned.exists && pinned.isHittable) { app.swipeDown() }
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
        // Die Abschnitte stehen in dieser Reihenfolge untereinander: Klang, Ordner mit Musik, Sicherung, Umwandeln
        XCTAssertTrue(reveal(app.buttons["openSound"].firstMatch), "Klang fehlt")
        XCTAssertTrue(reveal(app.buttons["addMusicFolder"].firstMatch), "Ordner mit Musik fehlt")
        screenshot("13a-Einstellungen-Ordner")
        let create = app.buttons["backupCreate"].firstMatch
        XCTAssertTrue(reveal(create), "Sicherung fehlt")
        XCTAssertTrue(app.buttons["backupRestore"].firstMatch.waitForExistence(timeout: 5), "Wiederherstellen fehlt")
        screenshot("13b-Einstellungen-Sicherung")
        XCTAssertTrue(reveal(app.buttons["convertAllButton"].firstMatch), "Konverter fehlt")
        // „Sicherung erstellen“ fragt: mit Musik oder nur die Listen
        if !(create.exists && create.isHittable) { app.swipeDown() }
        if create.exists && create.isHittable {
            create.tap()
            XCTAssertTrue(app.buttons["Alles mit Musik (große Datei)"].firstMatch.waitForExistence(timeout: 5),
                          "Auswahl „Alles mit Musik“ fehlt")
            XCTAssertTrue(app.buttons["Nur Playlists, Herzen und Statistik"].firstMatch.exists, "Kleine Sicherung fehlt")
            screenshot("13c-Sicherung-Auswahl")
        }
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

    /// Suche innerhalb einer Playlist: filtert die Lieder und meldet, wenn nichts passt
    func test8_SearchInPlaylist() {
        downloadTestSong(mp3URL1)
        tab("Mediathek")
        let downloadsRow = app.staticTexts["Heruntergeladen"].firstMatch
        XCTAssertTrue(reveal(downloadsRow), "Playlist „Heruntergeladen“ fehlt")
        downloadsRow.tap()
        let field = searchField("In dieser Liste suchen")
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Suchfeld fehlt in der Playlist")
        field.tap()
        field.typeText("xyz")
        let none = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Keine Treffer")).firstMatch
        XCTAssertTrue(none.waitForExistence(timeout: 10), "Suche ohne Treffer zeigt keinen Hinweis")
        XCTAssertFalse(app.buttons["trackMore"].exists, "Lied wird trotz unpassender Suche angezeigt")
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 3))
        field.typeText("test")
        XCTAssertTrue(app.buttons["trackMore"].firstMatch.waitForExistence(timeout: 10), "Suche findet das Lied nicht")
        let count = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "1 von 1")).firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 10), "Trefferzahl fehlt")
        screenshot("28-Suche-in-Playlist")
    }

    /// Suche auf der Startseite über die ganze Mediathek
    func test9_SearchOnStart() {
        downloadTestSong(mp3URL1)
        tab("Start")
        // Das Suchfeld sitzt unter dem Titel und kann weggescrollt sein
        let anyField = app.searchFields.firstMatch
        if !(anyField.exists && anyField.isHittable) { app.swipeDown() }
        let field = searchField("Lieder, Interpreten, Alben, Genres")
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Suchfeld fehlt auf der Startseite")
        field.tap()
        field.typeText("test")
        XCTAssertTrue(app.buttons["trackMore"].firstMatch.waitForExistence(timeout: 10), "Start-Suche findet das Lied nicht")
        screenshot("29-Start-Suche")
    }
}
