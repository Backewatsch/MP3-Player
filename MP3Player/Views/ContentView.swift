import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var downloads: DownloadManager
    @State private var tab = Tab.tracks
    @State private var showNowPlaying = false

    enum Tab: Hashable { case tracks, playlists, browser, downloads }

    var body: some View {
        TabView(selection: $tab) {
            LibraryView()
                .miniPlayer($showNowPlaying)
                .tabItem { Label("Titel", systemImage: "music.note") }
                .tag(Tab.tracks)

            PlaylistsView()
                .miniPlayer($showNowPlaying)
                .tabItem { Label("Playlists", systemImage: "music.note.list") }
                .tag(Tab.playlists)

            BrowserView()
                .miniPlayer($showNowPlaying)
                .tabItem { Label("Browser", systemImage: "safari") }
                .tag(Tab.browser)

            DownloadsView()
                .miniPlayer($showNowPlaying)
                .tabItem { Label("Downloads", systemImage: "arrow.down.circle") }
                .badge(downloads.activeCount + downloads.unseenCount)
                .tag(Tab.downloads)
        }
        .onChange(of: tab) { newTab in
            if newTab == .downloads { downloads.unseenCount = 0 }
        }
        .onChange(of: downloads.unseenCount) { count in
            if tab == .downloads && count > 0 { downloads.unseenCount = 0 }
        }
        .sheet(isPresented: $showNowPlaying) {
            NowPlayingView()
        }
        .alert("Hinweis", isPresented: Binding(
            get: { player.errorMessage != nil },
            set: { if !$0 { player.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { player.errorMessage = nil }
        } message: {
            Text(player.errorMessage ?? "")
        }
    }
}

private struct MiniPlayerModifier: ViewModifier {
    @Binding var showNowPlaying: Bool

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            MiniPlayerBar(showNowPlaying: $showNowPlaying)
        }
    }
}

extension View {
    func miniPlayer(_ show: Binding<Bool>) -> some View {
        modifier(MiniPlayerModifier(showNowPlaying: show))
    }
}
