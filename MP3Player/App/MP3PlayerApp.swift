import SwiftUI

@main
@MainActor
struct MP3PlayerApp: App {
    @StateObject private var library = Library()
    @StateObject private var player = AudioPlayer()
    @StateObject private var downloads = DownloadManager()
    @StateObject private var browser = BrowserModel()
    @StateObject private var updates = UpdateChecker()
    @Environment(\.scenePhase) private var scenePhase
    @State private var openError: String?

    init() {
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
                .environmentObject(browser)
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
        browser.downloads = downloads
        library.onTrackAdded = { [weak player = player] track, playlistID in
            player?.libraryDidAdd(track, playlistID: playlistID)
        }
        player.onDurationLoaded = { [weak library = library] id, duration in
            library?.updateDuration(of: id, to: duration)
        }
        // Verlauf, „Oft gespielt“ und Statistik
        player.onTrackStarted = { [weak library = library] id in library?.recordStart(id) }
        player.onTrackPlayed = { [weak library = library] id in library?.recordPlay(id) }
        player.onListened = { [weak library = library] seconds in library?.addListened(seconds) }
    }
}
