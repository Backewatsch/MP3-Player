import SwiftUI

@main
@MainActor
struct MP3PlayerApp: App {
    @StateObject private var library = Library()
    @StateObject private var player = AudioPlayer()
    @StateObject private var downloads = DownloadManager()
    @StateObject private var updates = UpdateChecker()
    @Environment(\.scenePhase) private var scenePhase
    @State private var openError: String?

    init() {
        // Apple-Design: eigene Cover der Lieder standardmäßig anzeigen
        UserDefaults.standard.register(defaults: ["showOwnCovers": true])
        // Für automatische UI-Tests: mit leerer Bibliothek starten
        if ProcessInfo.processInfo.arguments.contains("-uiTestReset") {
            Storage.resetAll()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(library)
                .environmentObject(player)
                .environmentObject(downloads)
                .environmentObject(updates)
                .onAppear(perform: connect)
                .onOpenURL { url in
                    // „Öffnen in … MP3 Player“ aus der Dateien-App oder anderen Apps
                    Task {
                        do { try await library.importAudio(from: url, move: false) }
                        catch { openError = error.localizedDescription }
                    }
                }
                .onReceive(library.$tracks) { tracks in
                    player.libraryDidChange(tracks)
                }
                .alert("Import fehlgeschlagen", isPresented: Binding(get: { openError != nil }, set: { if !$0 { openError = nil } })) {
                    Button("OK", role: .cancel) {}
                } message: {
                    Text(openError ?? "")
                }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                Task { await library.rescan() }
                Task { await updates.checkIfDue() }
            }
        }
    }

    private func connect() {
        downloads.library = library
        library.onTrackAdded = { [weak player = player] track, playlistID in
            player?.libraryDidAdd(track, playlistID: playlistID)
            Task { @MainActor in await self.library.analyzeLoudness() }
        }
        player.onDurationLoaded = { [weak library = library] id, duration in
            library?.updateDuration(of: id, to: duration)
        }
        // Verlauf, „Oft gespielt“ und Statistik
        player.onTrackStarted = { [weak library = library] id in library?.recordStart(id) }
        player.onTrackPlayed = { [weak library = library] id in library?.recordPlay(id) }
        player.onListened = { [weak library = library] seconds in library?.addListened(seconds) }
        // Lautstärke angleichen
        player.gainProvider = { [weak library = library] id in library?.gain(of: id) }
        library.onGainMeasured = { [weak player = player] id, _ in
            if player?.current?.id == id { player?.applyTrackGain() }
        }
        // Weiterhören nach Neustart (nicht in automatischen UI-Tests)
        if !ProcessInfo.processInfo.arguments.contains("-uiTestReset") {
            player.restoreQueue(from: library.tracks)
        }
        Task { await library.analyzeLoudness() }
    }
}
