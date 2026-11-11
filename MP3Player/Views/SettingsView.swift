import SwiftUI
import UniformTypeIdentifiers

/// Info & Updates
struct SettingsView: View {
    @EnvironmentObject private var updates: UpdateChecker
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @Environment(\.dismiss) private var dismiss
    @State private var confirmConvertAll = false
    @State private var conversionResult: String?
    @Environment(\.openURL) private var openURL
    @State private var copied = false
    @AppStorage("showOwnCovers") private var showOwnCovers = true
    @AppStorage(Appearance.key) private var appearance = Appearance.dark.rawValue
    @AppStorage("autoplayBluetooth") private var autoplayBluetooth = false
    @State private var showDuplicates = false
    @State private var showSound = false
    @State private var exportDocument: BackupDocument?
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var backupStatus: String?
    @EnvironmentObject private var folders: FolderScanner
    /// Der Datei-Dialog öffnet eine Sicherung oder wählt einen Ordner mit Musik
    @State private var pickFolder = false
    @State private var confirmBackup = false
    @State private var backupBusy = false
    @State private var fullBackupURL: URL?
    @State private var showMover = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        Image("Logo")
                            .resizable()
                            .frame(width: 56, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text("ILLEGAL FREEDOM MUSIC").font(.headline)
                            Text("Version \(updates.currentVersion) (Build \(updates.currentBuild))")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .accessibilityIdentifier("currentVersion")
                            Text("\(library.tracks.count) Titel · \(library.playlists.count) Playlists")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section("Updates") {
                    statusRow
                    if let release = updates.availableRelease {
                        if !release.notes.isEmpty {
                            Text(release.notes)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(8)
                        }
                        if let ipa = release.ipaURL, let deepLink = UpdateChecker.altStoreInstallURL(for: ipa) {
                            Button {
                                openURL(deepLink)
                            } label: {
                                Label("Mit AltStore installieren", systemImage: "arrow.down.app")
                            }
                        }
                        if let ipa = release.ipaURL {
                            Button { openURL(ipa) } label: {
                                Label("IPA herunterladen (Sideloadly/SideStore)", systemImage: "square.and.arrow.down")
                            }
                        }
                        Button { openURL(release.pageURL) } label: {
                            Label("Release auf GitHub ansehen", systemImage: "safari")
                        }
                    }
                    Button {
                        Task { await updates.check() }
                    } label: {
                        Label("Jetzt nach Updates suchen", systemImage: "arrow.clockwise")
                    }
                    .disabled(updates.state == .checking)
                    .accessibilityIdentifier("checkUpdatesButton")
                }

                Section {
                    Picker("Erscheinungsbild", selection: $appearance) {
                        ForEach(Appearance.allCases) { Text($0.label).tag($0.rawValue) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("appearancePicker")
                    Toggle("Eigene Cover der Lieder anzeigen", isOn: $showOwnCovers)
                        .tint(Color.accentColor)
                        .accessibilityIdentifier("ownCoversSwitch")
                } header: {
                    Text("Darstellung")
                } footer: {
                    Text(showOwnCovers ? "Lieder zeigen ihr eigenes Cover (falls vorhanden)." : "Neben jedem Lied steht das App-Logo.")
                }

                Section {
                    Toggle(isOn: $autoplayBluetooth) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Bei Bluetooth automatisch abspielen")
                            Text("Kopfhörer oder Auto verbinden → Musik läuft weiter").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .tint(Color.accentColor)
                    .accessibilityIdentifier("btAutoplaySwitch")
                    Button { showDuplicates = true } label: {
                        Label("Doppelte Lieder aufräumen (\(library.duplicateGroups.count))", systemImage: "sparkles")
                    }
                    .accessibilityIdentifier("openDuplicates")
                } header: {
                    Text("Wiedergabe")
                } footer: {
                    Text("CarPlay ist mit einer kostenlosen Apple-ID leider nicht möglich – im Auto funktioniert die Steuerung über Bluetooth und den Sperrbildschirm.")
                }
                .sheet(isPresented: $showDuplicates) { NavigationStack { DuplicatesView() } }

                Section {
                    Button { showSound = true } label: {
                        Label("Equalizer & Überblenden" + (player.sound.enabled ? " · \(player.sound.preset)" : "")
                              + (player.sound.crossfade > 0 ? " · \(Int(player.sound.crossfade)) s" : ""),
                              systemImage: "slider.vertical.3")
                    }
                    .accessibilityIdentifier("openSound")
                } header: { Text("Klang") }

                folderSection

                backupSection

                Section {
                    if let status = library.conversionStatus {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(status).font(.footnote)
                        }
                    } else {
                        Button {
                            confirmConvertAll = true
                        } label: {
                            Label("Alle in MP3 umwandeln (\(library.convertibleTracks.count))",
                                  systemImage: "arrow.triangle.2.circlepath")
                        }
                        .disabled(library.convertibleTracks.isEmpty)
                        .accessibilityIdentifier("convertAllButton")
                    }
                    if let conversionResult {
                        Text(conversionResult).font(.footnote).foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Konverter")
                } footer: {
                    Text("Neue Titel werden beim Laden automatisch zu MP3 (192 kbit/s). Hier wandelst du vorhandene Dateien in anderen Formaten (z. B. per Finder kopierte M4A, WAV, FLAC) um. Playlists, Kurzwahl und Cover bleiben erhalten, das Original wird ersetzt. Einzelne Titel: „…“ → „In MP3 umwandeln“.")
                }
                .confirmationDialog("Alle \(library.convertibleTracks.count) Titel in MP3 umwandeln?",
                                    isPresented: $confirmConvertAll, titleVisibility: .visible) {
                    Button("Umwandeln") {
                        Task {
                            let result = await library.convertAllToMP3(skipping: player.current?.id)
                            conversionResult = "Fertig: \(result.converted) umgewandelt" + (result.failed > 0 ? ", \(result.failed) fehlgeschlagen" : "") + "."
                        }
                    }
                    Button("Abbrechen", role: .cancel) {}
                } message: {
                    Text("Die Originaldateien werden ersetzt. Der gerade laufende Titel wird übersprungen.")
                }

                Section {
                    if let addSource = UpdateChecker.altStoreAddSourceURL() {
                        Button { openURL(addSource) } label: {
                            Label("Als Quelle zu AltStore hinzufügen", systemImage: "plus.app")
                        }
                    }
                    Button {
                        UIPasteboard.general.string = UpdateChecker.sourceURL.absoluteString
                        copied = true
                    } label: {
                        Label(copied ? "Quellen-Link kopiert ✓" : "Quellen-Link kopieren (für SideStore)",
                              systemImage: "doc.on.doc")
                    }
                } header: {
                    Text("Automatische Updates")
                } footer: {
                    Text("Wenn du die Quelle in AltStore oder SideStore hinzufügst, zeigen diese Apps neue Versionen automatisch an und installieren sie mit einem Tipp. Deine Musik bleibt bei einem Update erhalten.")
                }
            }
            .sheet(isPresented: $showSound) { SoundView(sound: player.sound) }
            .navigationTitle("Info & Updates")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
            }
            .task {
                if updates.state == .idle { await updates.check() }
            }
        }
    }

    /// Ordner aus der Dateien-App, die nach Liedern durchsucht werden
    @ViewBuilder private var folderSection: some View {
        Section {
            ForEach(folders.folders) { folder in
                HStack {
                    Label(folder.name, systemImage: "folder")
                    Spacer()
                    Text("\(folder.seen.count) Lieder").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .onDelete { offsets in
                let doomed = offsets.compactMap { folders.folders.indices.contains($0) ? folders.folders[$0] : nil }
                doomed.forEach { folders.remove($0) }
            }
            Button { pickFolder = true; showImporter = true } label: {
                Label("Ordner hinzufügen", systemImage: "folder.badge.plus")
            }
            .accessibilityIdentifier("addMusicFolder")
            // Ein gemeinsamer Datei-Dialog: Ordner mit Musik wählen oder Sicherung öffnen.
            // Jeder Datei-Dialog hängt an einer eigenen Zeile – mehrere an derselben Ansicht stören sich gegenseitig.
            .fileImporter(isPresented: $showImporter, allowedContentTypes: pickFolder ? [.folder] : [.json, .zip]) { result in
                switch result {
                case .success(let url):
                    if pickFolder {
                        Task { await folders.add(url) }
                    } else {
                        restore(from: url)
                    }
                case .failure(let error):
                    if !pickFolder { backupStatus = "Wiederherstellen fehlgeschlagen: \(error.localizedDescription)" }
                }
            }
            if !folders.folders.isEmpty {
                Button { Task { await folders.scan(force: true) } } label: {
                    Label("Jetzt durchsuchen", systemImage: "arrow.clockwise")
                }
                .disabled(folders.isScanning)
            }
            if let status = folders.status {
                HStack(spacing: 10) {
                    if folders.isScanning { ProgressView() }
                    Text(status).font(.footnote).foregroundStyle(.secondary)
                }
                .accessibilityIdentifier("folderStatus")
            }
        } header: {
            Text("Ordner mit Musik")
        } footer: {
            Text("Wähle einen Ordner aus der Dateien-App, zum Beispiel „Auf meinem iPhone“ oder einen Ordner in iCloud Drive. Die App durchsucht ihn samt Unterordnern nach Liedern und übernimmt alle – auch später bei jedem Start, wenn neue dazukommen. Die Lieder werden in die App kopiert; die Originale bleiben, wo sie sind. Das ganze iPhone auf einmal zu durchsuchen erlaubt Apple keiner App.")
        }
    }

    /// Sicherung erstellen (mit oder ohne Musik) und wiederherstellen
    @ViewBuilder private var backupSection: some View {
        Section {
            Button { confirmBackup = true } label: { Label("Sicherung erstellen", systemImage: "square.and.arrow.up") }
                .disabled(backupBusy)
                .accessibilityIdentifier("backupCreate")
                .confirmationDialog("Was soll gesichert werden?", isPresented: $confirmBackup, titleVisibility: .visible) {
                    Button("Alles mit Musik (große Datei)") { createFullBackup() }
                    Button("Nur Playlists, Herzen und Statistik") {
                        exportDocument = try? BackupDocument(state: library.stateSnapshot)
                        showExporter = exportDocument != nil
                    }
                    Button("Abbrechen", role: .cancel) {}
                } message: {
                    Text("„Alles mit Musik“ ist für ein neues Handy gedacht. Die Datei lässt sich auch in der Android-App wiederherstellen.")
                }
                // Die fertige Komplett-Sicherung an einen Ort in der Dateien-App legen
                .modifier(BackupMover(isPresented: $showMover, file: fullBackupURL, onDone: { result in
                    switch result {
                    case .success: backupStatus = "✓ Sicherung mit Musik gespeichert."
                    case .failure(let error): backupStatus = "Sicherung fehlgeschlagen: \(error.localizedDescription)"
                    }
                    discardFullBackup()
                }, onCancel: {
                    backupStatus = "Sicherung nicht gespeichert."
                    discardFullBackup()
                }))
            Button { pickFolder = false; showImporter = true } label: {
                Label("Sicherung wiederherstellen", systemImage: "arrow.counterclockwise")
            }
            .disabled(backupBusy)
            .accessibilityIdentifier("backupRestore")
            .fileExporter(isPresented: $showExporter, document: exportDocument, contentType: .json,
                          defaultFilename: "ILLEGAL-FREEDOM-MUSIC-Sicherung") { result in
                switch result {
                case .success: backupStatus = "✓ Sicherung gespeichert."
                case .failure(let error): backupStatus = "Sicherung fehlgeschlagen: \(error.localizedDescription)"
                }
            }
            if let backupStatus {
                HStack(spacing: 10) {
                    if backupBusy { ProgressView() }
                    Text(backupStatus).font(.footnote).foregroundStyle(.secondary)
                }
                .accessibilityIdentifier("backupStatus")
            }
        } header: {
            Text("Sicherung")
        } footer: {
            Text("„Alles mit Musik“ sichert Lieder, Cover, Playlists, Herzen, Kurzwahl, Verlauf und Statistik in eine Datei. Die kleine Sicherung enthält alles außer den Liedern. Wiederherstellen lässt sich beides, auch eine Sicherung aus der Android-App – dabei geht nichts Vorhandenes verloren.")
        }
    }

    /// Komplett-Sicherung erstellen und danach fragen, wohin sie soll
    private func createFullBackup() {
        guard !backupBusy else { return }
        backupBusy = true
        backupStatus = "Sichere die Lieder …"
        Task {
            do {
                let url = try await library.makeFullBackup { done, total in
                    // Nachzügler nicht über die Fertig-Meldung schreiben
                    if fullBackupURL == nil { backupStatus = "Sichere Lied \(min(done + 1, total)) von \(total) …" }
                }
                backupStatus = "Die Sicherung ist fertig – wähle, wo sie gespeichert werden soll."
                fullBackupURL = url
                showMover = true
            } catch {
                backupStatus = "Sicherung fehlgeschlagen: \(error.localizedDescription)"
            }
            backupBusy = false
        }
    }

    /// Räumt die Komplett-Sicherung aus dem Zwischenspeicher: nach dem Verschieben liegt dort nur noch der leere Ordner,
    /// nach dem Abbrechen die nicht gespeicherte Datei.
    private func discardFullBackup() {
        guard let url = fullBackupURL else { return }
        fullBackupURL = nil
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
    }

    /// Sicherung wiederherstellen: Komplett-Sicherung (ZIP, auch von Android) oder kleine Sicherung (JSON)
    private func restore(from url: URL) {
        let accessing = url.startAccessingSecurityScopedResource()
        guard ZipReader.isZip(url) else {
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                let r = library.restore(from: try Library.decodeBackup(data))
                backupStatus = "✓ Wiederhergestellt: \(r.matched) Lieder erkannt, \(r.playlists) Playlists"
                    + (r.missing > 0 ? " · \(r.missing) Lieder fehlen auf diesem iPhone." : ".")
            } catch {
                backupStatus = "Wiederherstellen fehlgeschlagen: \(error.localizedDescription)"
            }
            return
        }
        backupBusy = true
        backupStatus = "Lese die Sicherung …"
        Task {
            do {
                let r = try await library.restoreFull(from: url) { done, total in
                    backupStatus = "Stelle Lied \(done) von \(total) wieder her …"
                }
                backupStatus = "✓ Wiederhergestellt: \(r.restored) Lieder zurückgeholt, \(r.matched) waren schon da, \(r.playlists) Playlists"
                    + (r.missing > 0 ? " · \(r.missing) Lieder fehlen in der Sicherung." : ".")
            } catch {
                backupStatus = "Wiederherstellen fehlgeschlagen: \(error.localizedDescription)"
            }
            if accessing { url.stopAccessingSecurityScopedResource() }
            backupBusy = false
        }
    }

    @ViewBuilder private var statusRow: some View {
        switch updates.state {
        case .idle:
            Text("Noch nicht geprüft").foregroundStyle(.secondary)
        case .checking:
            HStack(spacing: 10) {
                ProgressView()
                Text("Suche nach Updates …")
            }
        case .upToDate:
            Label("Du hast die neueste Version.", systemImage: "checkmark.seal.fill")
                .foregroundStyle(.green)
        case .available(let release):
            Label("Update verfügbar: Version \(release.version)", systemImage: "sparkles")
                .foregroundStyle(Color.accentColor)
                .font(.body.weight(.semibold))
        case .noReleases:
            VStack(alignment: .leading, spacing: 4) {
                Label("Keine öffentliche Version gefunden.", systemImage: "lock")
                    .foregroundStyle(.secondary)
                Text("Ist das GitHub-Repository privat, können iPhone und AltStore die Updates nicht laden. Stelle es unter GitHub → Settings → „Change visibility“ auf öffentlich.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle")
                .foregroundStyle(.orange)
        }
    }
}

/// Hinweis-Leiste in der Bibliothek, wenn ein Update bereitsteht.
struct UpdateBanner: View {
    @EnvironmentObject private var updates: UpdateChecker
    var onTap: () -> Void

    var body: some View {
        if let release = updates.availableRelease {
            Button(action: onTap) {
                HStack(spacing: 10) {
                    Image(systemName: "sparkles")
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Update verfügbar").font(.subheadline.weight(.semibold))
                        Text("Version \(release.version) – tippen zum Installieren").font(.caption)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                }
                .foregroundStyle(.white)
                .padding(12)
                .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("updateBanner")
        }
    }
}

/// Dialog „Wohin mit der Komplett-Sicherung?“. Ab iOS 17 meldet er auch das Abbrechen – dann wird die fertige Datei
/// gleich wieder gelöscht. Unter iOS 16 räumt der nächste Start der App den Zwischenspeicher auf.
private struct BackupMover: ViewModifier {
    @Binding var isPresented: Bool
    let file: URL?
    let onDone: (Result<URL, Error>) -> Void
    let onCancel: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content.fileMover(isPresented: $isPresented, file: file, onCompletion: onDone, onCancellation: onCancel)
        } else {
            content.fileMover(isPresented: $isPresented, file: file, onCompletion: onDone)
        }
    }
}
