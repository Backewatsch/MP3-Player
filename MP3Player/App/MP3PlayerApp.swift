import SwiftUI

@main
@MainActor
struct MP3PlayerApp: App {
    @StateObject private var library = Library()
    @StateObject private var player = AudioPlayer()
    @StateObject private var downloads = DownloadManager()
    @StateObject private var browser = BrowserModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(library)
                .environmentObject(player)
                .environmentObject(downloads)
                .environmentObject(browser)
                .onAppear(perform: connect)
                .onOpenURL { url in
                    // „Öffnen in … MP3 Player“ aus der Dateien-App oder anderen Apps
                    Task { try? await library.importAudio(from: url, move: false) }
                }
                .onReceive(library.$tracks) { tracks in
                    player.libraryDidChange(tracks)
                }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { Task { await library.rescan() } }
        }
    }

    private func connect() {
        downloads.library = library
        browser.downloads = downloads
        library.onTrackAdded = { [weak player = player] track, playlistID in
            player?.libraryDidAdd(track, playlistID: playlistID)
        }
    }
}
