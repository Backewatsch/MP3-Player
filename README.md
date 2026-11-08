# ILLEGAL FREEDOM MUSIC – MP3-Player für Android und iPhone

Eine native iOS-App (SwiftUI, iOS 16+) zum Abspielen von MP3- und anderen Audiodateien – mit eingebautem Browser, über den du Audiodateien aus dem Internet herunterladen kannst. Heruntergeladene Titel landen automatisch in der Bibliothek und in der Playlist **„Heruntergeladen“**.

## Funktionen

**Player**
- Spielt MP3, M4A/AAC, WAV, AIFF, CAF und FLAC
- Wiedergabe im Hintergrund und bei gesperrtem Bildschirm, Steuerung im Sperrbildschirm / Kontrollzentrum / mit Kopfhörer-Tasten
- Liest Titel, Interpret und Cover aus den ID3-Tags
- Zufallswiedergabe, Wiederholen (alle / einen), Warteschlange („Als Nächstes“, „Zur Warteschlange“)
- Pausiert bei Anrufen und wenn Kopfhörer abgezogen werden

**Aufbau: zwei Tabs**
- **Mediathek**: Kurzwahl, Titel, Alben, Interpreten und alle Playlists – neue Playlists direkt hier erstellen (oder ＋ → „Neue Playlist“)
- **Lieder downloaden**: interner Browser mit Download-Liste (Knopf unten mit Zähler) und Link-Feld

**Titel-Optionen (Tippen auf „…“ oder lange drücken)**
- Als Nächstes abspielen · Zu Playlist hinzufügen (auch neue Playlist) · Teilen
- Mix starten (Titel zuerst, dann gemischt – gleicher Interpret bevorzugt)
- In die Wiedergabeliste · Album anzeigen · Künstlerseite anzeigen
- Mitwirkende an Song anzeigen (Komponist, Genre, Jahr, Label, Dateiinfos aus den Tags)
- An Kurzwahl anpinnen (Leiste oben in der Bibliothek)
- Infos bearbeiten · Aus Playlist entfernen · Wiedergabeliste schließen · Aus Mediathek entfernen

**Konverter – alles wird MP3**
- Alles, was in die App kommt, wird zu einer **echten MP3-Datei** (192 kbit/s, 44,1 kHz Stereo): Videos (MP4, MOV, M4V) und andere Audioformate (M4A, AAC, WAV, AIFF, FLAC) – aus Downloads, dem internen Browser und der Dateien-App
- Titel, Interpret, Album und Cover werden als ID3-Tags übernommen (Umlaute korrekt)
- Vorhandene Nicht-MP3-Dateien (z. B. per Finder kopiert): einzeln über „…“ → „In MP3 umwandeln“ oder alle unter *Info & Updates → Konverter*. Playlists, Kurzwahl und Cover bleiben erhalten
- Im Browser: Startet auf einer Seite ein Audio/Video, erscheint „Als Audio laden“. Seiten werden automatisch nach Dateien durchsucht (Zähler am Wellen-Symbol). Alles Geladene kommt zusätzlich in die **Wiedergabeliste**
- MP3-Kodierung mit dem Open-Source-Encoder **LAME 3.100** (LGPL, siehe `ThirdParty/`)
- Grenzen: Streams (blob:/MediaSource, z. B. YouTube, Spotify) und Formate, die iOS nicht lesen kann (WebM, OGG), lassen sich nicht umwandeln

**Design**: durchgehend dunkles Design (Dark Mode)

**Bibliothek & Playlists**
- Suche nach Titel/Interpret, Titel-Infos bearbeiten, löschen
- Eigene Playlists anlegen, umbenennen, sortieren (Bearbeiten → ziehen)
- Import über die Dateien-App (＋ → „Aus Dateien importieren“) oder „Öffnen in … MP3 Player“
- Dateien, die du per Finder (Mac) bzw. iTunes-Dateifreigabe (Windows) in den App-Ordner kopierst, werden automatisch erkannt

**Browser & Downloads**
- Interner Browser mit Adress-/Suchleiste (DuckDuckGo), Vor/Zurück, Startseite mit freien Musikquellen
- Ein Tipp auf einen Link zu einer Audiodatei lädt sie herunter
- Lange auf einen Link drücken → **„Als Audio herunterladen“**
- Wellen-Symbol: durchsucht die Seite nach Audio- und Videodateien, einzeln laden, „Alle laden“ oder **„Alle als neue Playlist laden“**
- Download direkt per Link im Tab „Downloads“ (oder ＋ → „Von Link herunterladen“)
- Fortschrittsanzeige, Fehlermeldungen, erneut versuchen

### Warum kein YouTube-Download?

YouTube liefert keine MP3-Dateien aus, sondern verschlüsselte/segmentierte Videostreams. Sie herunterzuladen und umzuwandeln verstößt gegen die YouTube-Nutzungsbedingungen und bei urheberrechtlich geschützter Musik in der Regel auch gegen das Urheberrecht (in Deutschland u. a. § 95a UrhG – Umgehung technischer Schutzmaßnahmen). Deshalb zeigt der Browser auf YouTube nur einen Hinweis. Legale Alternativen sind auf der Startseite verlinkt (Internet Archive, Free Music Archive, Jamendo, ccMixter, Incompetech, LibriVox), außerdem bieten viele Künstler auf Bandcamp oder ihrer eigenen Seite MP3-Downloads an.

## Android (Samsung & alle anderen) – direkt aus dem Internet installieren

Die Android-App (Ordner `android/`, Kotlin + Jetpack Compose, ab Android 8) hat dieselben Funktionen wie die iPhone-App – und lässt sich **ohne Computer** installieren:

1. Auf dem Handy diese Seite öffnen: **https://github.com/Backewatsch/MP3-Player/releases/latest**
2. Unter *Assets* auf **`MP3Player.apk`** tippen und herunterladen.
3. Die Datei öffnen. Android fragt beim ersten Mal, ob der Browser (z. B. Samsung Internet oder Chrome) **„Apps aus unbekannten Quellen installieren“** darf → in den Einstellungen erlauben → zurück → **Installieren**.
4. Fertig. Updates: Die App zeigt „Update verfügbar“ → *Info & Updates* → **Jetzt installieren** (beim ersten Mal erlauben, dass die App Updates installieren darf). Musik und Playlists bleiben erhalten.

Hinweise:
- Google Play Protect zeigt eventuell eine Warnung („unbekannter Entwickler“), weil die App nicht aus dem Play Store kommt → *Trotzdem installieren*.
- Musik liegt in `Android/data/com.backewatsch.mp3player/files/Music` (per USB am PC erreichbar). Kopierte Dateien werden automatisch erkannt und zu MP3 umgewandelt.
- Android kann zusätzlich **WebM, OGG und Opus** lesen und in MP3 umwandeln.
- **Start-Seite** mit „Weiterhören“, **„Oft gespielt“** (zählt ab 30 Sekunden Hören) und **Verlauf** (löschbar).
- **Von Samsung Music übertragen** (Mediathek → ＋ oder Start-Seite): **alle Lieder** auf einmal oder **einzeln auswählen** (mit Suche); überträgt Lieder vom Handy (ohne Doppelte, Nicht-MP3 wird umgewandelt). Playlists vorher in Samsung Music exportieren (*Playlists → ⋮ → Exportieren*, landen als .m3u im Ordner „Music“) und dann in der App auswählen – fehlende Lieder werden automatisch mitgenommen.
- **Offline:** Musik, Playlists, Player und Umwandeln funktionieren komplett ohne Internet. Nur Browser, Downloads und die Update-Suche brauchen eine Verbindung (der Browser zeigt dann eine Offline-Seite).
- Signierschlüssel: liegt bewusst im Repository (`android/keystore/`), damit Updates ohne Einrichtung funktionieren. Wer einen eigenen, geheimen Schlüssel möchte, kann ihn als GitHub-Secret hinterlegen (Umgebungsvariablen `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) – Achtung: Ein Schlüsselwechsel erfordert einmal Neuinstallation.

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

Bei jedem Push baut GitHub Actions (`.github/workflows/build.yml`) die App auf einem macOS-Server und stellt eine **unsignierte IPA** als Artefakt bereit (*Releases* (oder *Actions → neuester Lauf → Artifacts → MP3Player-unsigned-ipa*)). Diese kannst du mit einem Sideloading-Tool wie **Sideloadly** oder **AltStore** mit deiner Apple-ID signieren und installieren.

## Updates aufs iPhone (ohne Mac)

iOS erlaubt nicht, dass eine selbst installierte App sich selbst ersetzt. Updates laufen deshalb über **AltStore** oder **SideStore**: Diese Apps signieren die IPA mit deiner Apple-ID und installieren sie, und sie melden neue Versionen automatisch.

> **Voraussetzung:** Das GitHub-Repository muss **öffentlich** sein. Aus einem privaten Repository können iPhone und AltStore die Releases nicht herunterladen (GitHub → *Settings → General → Danger Zone → Change visibility*).

**So funktioniert es:**
1. Bei jedem Push baut und testet GitHub die App. Sobald die Android-Tests bestehen, veröffentlicht GitHub automatisch ein Release `v1.0.<Build>` mit `MP3Player.apk` und `MP3Player.ipa`. (GitHub lehnt das Anlegen gelegentlich mit HTTP 403 ab; der Workflow versucht es dann mehrmals. Alternativ kann man selbst einen Tag `v…` pushen oder auf GitHub unter *Releases → Draft a new release* einen Tag anlegen.)
2. Zu jedem Release gehört eine AltStore-/SideStore-Quelle. Ihre Adresse bleibt immer gleich:
   ```
   https://github.com/Backewatsch/MP3-Player/releases/latest/download/altstore-source.json
   ```
3. In der App: **Zahnrad (Titel-Tab) → Info & Updates**. Die App sucht beim Start nach neuen Versionen und zeigt dann oben in der Bibliothek „Update verfügbar“ an.

**Einmalige Einrichtung:**
- **AltStore** ([altstore.io](https://altstore.io)): AltServer auf einem Windows-PC oder Mac installieren, damit AltStore aufs iPhone bringen. Dann in der MP3-App *Info & Updates → „Als Quelle zu AltStore hinzufügen“* antippen. Danach installierst du die App über AltStore → *Durchsuchen*. Updates kommen per *Meine Apps → Aktualisieren* oder direkt per *„Mit AltStore installieren“* aus der MP3-App.
- **SideStore** ([sidestore.io](https://sidestore.io)): braucht nach der Einrichtung keinen Computer mehr. In der MP3-App „Quellen-Link kopieren“ und in SideStore unter *Quellen → +* einfügen.
- **Sideloadly** (Windows/Mac): „IPA herunterladen“ und mit Sideloadly installieren. Updates musst du hier selbst einspielen.

Deine Musik und Playlists bleiben bei einem Update erhalten, solange du die App nicht vorher löschst. Mit einer kostenlosen Apple-ID muss AltStore/SideStore die App alle 7 Tage auffrischen. Das passiert automatisch im Hintergrund, wenn das iPhone im WLAN ist (bei AltStore muss dafür auch AltServer laufen).

## Automatische Tests

Bei jedem Push laufen auf GitHub (macOS, aktueller iPhone-Simulator):

- **Unit-Tests** (`MP3PlayerTests`): ID3-Tags und Cover lesen, Import/Ablehnung von Dateien, doppelte Dateinamen, Ordner-Scan ohne Duplikate, Playlists (anlegen, sortieren, entfernen, speichern), Downloads → Playlist „Heruntergeladen“, Player (Weiter/Zurück, automatischer Titelwechsel, Wiederholen, Zufall, Warteschlange, gelöschte Titel)
- **UI-Tests** (`MP3PlayerUITests`): bedienen die App wie ein Mensch – MP3 per Link laden, in der Playlist finden und abspielen, MP3 über den internen Browser laden, YouTube-Hinweis. Die Screenshots liegen im Artefakt *test-screenshots*.

Lokal auf dem Mac: `xcodegen generate`, dann in Xcode **⌘U**.

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
ThirdParty/lame/             LAME-MP3-Encoder (LGPL), von iPhone- und Android-App gemeinsam genutzt
android/                     Android-App (Kotlin, Jetpack Compose, Media3, LAME per JNI)
MP3PlayerTests/              Unit-Tests (+ Test-MP3s mit ID3-Tags)
MP3PlayerUITests/            UI-Tests im Simulator
```
