import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var downloads: DownloadManager
    @State private var tab = Tab.start
    @State private var showNowPlaying = false
    @State private var editorPath = NavigationPath()
    @AppStorage(Appearance.key) private var appearance = Appearance.dark.rawValue

    enum Tab: Hashable { case start, library, editor }

    var body: some View {
        TabView(selection: $tab) {
            StartView(showNowPlaying: $showNowPlaying)
                .miniPlayer($showNowPlaying)
                .trackActionsHost()
                .tabItem { Label("Start", systemImage: "house.fill") }
                .tag(Tab.start)

            MediathekView()
                .miniPlayer($showNowPlaying)
                .trackActionsHost()
                .tabItem { Label("Mediathek", systemImage: "square.stack.fill") }
                .badge(downloads.activeCount)
                .tag(Tab.library)

            EditorView(path: $editorPath)
                .miniPlayer($showNowPlaying)
                .trackActionsHost()
                .tabItem { Label("Bearbeiten", systemImage: "scissors") }
                .tag(Tab.editor)
        }
        .sheet(isPresented: $showNowPlaying) {
            NowPlayingView()
                .trackActionsHost()
                .presentationDragIndicator(.visible)
        }
        .preferredColorScheme((Appearance(rawValue: appearance) ?? .dark).colorScheme)
        .onReceive(NotificationCenter.default.publisher(for: .openTrimEditor)) { note in
            // „Zuschneiden“ aus dem Menü eines Liedes
            guard let id = note.object as? UUID else { return }
            showNowPlaying = false
            tab = .editor
            editorPath = NavigationPath()
            editorPath.append(id)
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

extension Notification.Name {
    /// Öffnet ein Lied im Tab „Bearbeiten“ (object: UUID des Liedes)
    static let openTrimEditor = Notification.Name("openTrimEditor")
}
