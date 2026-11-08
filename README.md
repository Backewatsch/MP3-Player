# MP3 Player für iPhone

Eine native iOS-App (SwiftUI, iOS 16+) zum Abspielen von MP3- und anderen Audiodateien – mit eingebautem Browser, über den du Audiodateien aus dem Internet herunterladen kannst. Heruntergeladene Titel landen automatisch in der Bibliothek und in der Playlist **„Heruntergeladen“**.

## Funktionen

**Player**
- Spielt MP3, M4A/AAC, WAV, AIFF, CAF und FLAC
- Wiedergabe im Hintergrund und bei gesperrtem Bildschirm, Steuerung im Sperrbildschirm / Kontrollzentrum / mit Kopfhörer-Tasten
- Liest Titel, Interpret und Cover aus den ID3-Tags
- Zufallswiedergabe, Wiederholen (alle / einen), Warteschlange („Als Nächstes“, „Zur Warteschlange“)
- Pausiert bei Anrufen und wenn Kopfhörer abgezogen werden

**Bibliothek & Playlists**
- Suche nach Titel/Interpret, Titel-Infos bearbeiten, löschen
- Eigene Playlists anlegen, umbenennen, sortieren (Bearbeiten → ziehen)
- Import über die Dateien-App (＋ → „Aus Dateien importieren“) oder „Öffnen in … MP3 Player“
- Dateien, die du per Finder (Mac) bzw. iTunes-Dateifreigabe (Windows) in den App-Ordner kopierst, werden automatisch erkannt

**Browser & Downloads**
- Interner Browser mit Adress-/Suchleiste (DuckDuckGo), Vor/Zurück, Startseite mit freien Musikquellen
- Ein Tipp auf einen Link zu einer Audiodatei lädt sie herunter
- Lange auf einen Link drücken → **„Als Audio herunterladen“**
- Wellen-Symbol: durchsucht die Seite nach Audiodateien und `<audio>`-Elementen, einzeln oder „Alle laden“
- Download direkt per Link im Tab „Downloads“ (oder ＋ → „Von Link herunterladen“)
- Fortschrittsanzeige, Fehlermeldungen, erneut versuchen

### Warum kein YouTube-Download?

YouTube liefert keine MP3-Dateien aus, sondern verschlüsselte/segmentierte Videostreams. Sie herunterzuladen und umzuwandeln verstößt gegen die YouTube-Nutzungsbedingungen und bei urheberrechtlich geschützter Musik in der Regel auch gegen das Urheberrecht (in Deutschland u. a. § 95a UrhG – Umgehung technischer Schutzmaßnahmen). Deshalb zeigt der Browser auf YouTube nur einen Hinweis. Legale Alternativen sind auf der Startseite verlinkt (Internet Archive, Free Music Archive, Jamendo, ccMixter, Incompetech, LibriVox), außerdem bieten viele Künstler auf Bandcamp oder ihrer eigenen Seite MP3-Downloads an.

## App bauen und aufs iPhone bringen

### Variante A: Mit Mac und Xcode (empfohlen)

1. Xcode aus dem App Store installieren, dann im Terminal:
   ```bash
   brew install xcodegen
   git clone https://github.com/Backewatsch/MP3-Player.git
   cd MP3-Player
   xcodegen generate
   open MP3Player.xcodeproj
   ```
2. In Xcode das Ziel **MP3Player** → *Signing & Capabilities* → dein Apple-ID-Team wählen (kostenloser Account reicht; ggf. die Bundle-ID ändern, z. B. `de.deinname.mp3player`).
3. iPhone per Kabel anschließen, oben als Ziel auswählen und **▶ Run** drücken.
4. Auf dem iPhone: *Einstellungen → Allgemein → VPN & Geräteverwaltung* → deinem Entwicklerzertifikat vertrauen. Bei iOS 16+ zusätzlich *Einstellungen → Datenschutz & Sicherheit → Entwicklermodus* aktivieren.

> Mit einem kostenlosen Apple-Account läuft die App 7 Tage und muss dann neu installiert werden; mit dem Apple Developer Program (99 €/Jahr) ein Jahr.

### Variante B: Ohne Mac (unsignierte IPA)

Bei jedem Push baut GitHub Actions (`.github/workflows/build.yml`) die App auf einem macOS-Server und stellt eine **unsignierte IPA** als Artefakt bereit (*Actions → neuester Lauf → Artifacts → MP3Player-unsigned-ipa*). Diese kannst du mit einem Sideloading-Tool wie **Sideloadly** oder **AltStore** mit deiner Apple-ID signieren und installieren.

## Projektstruktur

```
project.yml                  XcodeGen-Projektbeschreibung (erzeugt MP3Player.xcodeproj)
MP3Player/
  App/MP3PlayerApp.swift     Einstiegspunkt, verbindet die Dienste
  Models/Models.swift        Track, Playlist, Speicherorte
  Services/Library.swift     Bibliothek, Playlists, Import, ID3-Metadaten
  Services/AudioPlayer.swift Wiedergabe, Warteschlange, Sperrbildschirm-Steuerung
  Services/DownloadManager.swift  Downloads (Browser + Link) → Bibliothek
  Services/BrowserModel.swift     WKWebView, Download-Erkennung, Startseite
  Views/                     SwiftUI-Oberfläche
```
