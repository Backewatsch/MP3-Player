import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var downloads: DownloadManager
    @State private var tab = Tab.library
    @State private var showNowPlaying = false

    enum Tab: Hashable { case library, downloader }

    var body: some View {
        TabView(selection: $tab) {
            MediathekView()
                .miniPlayer($showNowPlaying)
                .trackActionsHost()
                .tabItem { Label("Mediathek", systemImage: "music.note.house") }
                .tag(Tab.library)

            BrowserView()
                .miniPlayer($showNowPlaying)
                .tabItem { Label("Lieder downloaden", systemImage: "arrow.down.circle") }
                .badge(downloads.activeCount + downloads.unseenCount)
                .tag(Tab.downloader)
        }
        .sheet(isPresented: $showNowPlaying) {
            NowPlayingView()
                .trackActionsHost()
        }
        .preferredColorScheme(.dark)
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
