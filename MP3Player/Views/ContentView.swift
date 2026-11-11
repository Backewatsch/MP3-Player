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
        tabs
        .sheet(isPresented: $showNowPlaying) {
            NowPlayingView()
                .trackActionsHost()
                .presentationDragIndicator(.visible)
        }
        // Erscheinungsbild direkt am Fenster setzen: so klappt auch der Wechsel zurück auf „Automatisch“ sofort
        .onAppear { (Appearance(rawValue: appearance) ?? .dark).apply() }
        .onChange(of: appearance) { (Appearance(rawValue: $0) ?? .dark).apply() }
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

extension ContentView {
    /// Ab iOS 26.1 sitzt der Mini-Player im festen Platz über der Tab-Leiste (wie in Apple Music):
    /// Beim Herunterscrollen schrumpft die Tab-Leiste und der Mini-Player rückt daneben.
    /// Ältere iOS-Versionen behalten den eigenen Mini-Player über der Tab-Leiste.
    @ViewBuilder fileprivate var tabs: some View {
        if #available(iOS 26.1, *) {
            TabView(selection: $tab) { pages(ownMiniPlayer: false) }
                .tabBarMinimizeBehavior(.onScrollDown)
                .tabViewBottomAccessory(isEnabled: player.current != nil) {
                    MiniPlayerAccessory(showNowPlaying: $showNowPlaying)
                }
        } else {
            TabView(selection: $tab) { pages(ownMiniPlayer: true) }
        }
    }

    @ViewBuilder private func pages(ownMiniPlayer: Bool) -> some View {
        StartView(showNowPlaying: $showNowPlaying)
            .miniPlayer($showNowPlaying, enabled: ownMiniPlayer)
            .trackActionsHost()
            .tabItem { Label("Start", systemImage: "house.fill") }
            .tag(Tab.start)

        MediathekView()
            .miniPlayer($showNowPlaying, enabled: ownMiniPlayer)
            .trackActionsHost()
            .tabItem { Label("Mediathek", systemImage: "square.stack.fill") }
            .badge(downloads.activeCount)
            .tag(Tab.library)

        EditorView(path: $editorPath)
            .miniPlayer($showNowPlaying, enabled: ownMiniPlayer)
            .trackActionsHost()
            .tabItem { Label("Bearbeiten", systemImage: "scissors") }
            .tag(Tab.editor)
    }
}

private struct MiniPlayerModifier: ViewModifier {
    @Binding var showNowPlaying: Bool
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content.safeAreaInset(edge: .bottom, spacing: 0) {
                MiniPlayerBar(showNowPlaying: $showNowPlaying)
            }
        } else {
            content
        }
    }
}

extension View {
    func miniPlayer(_ show: Binding<Bool>, enabled: Bool = true) -> some View {
        modifier(MiniPlayerModifier(showNowPlaying: show, enabled: enabled))
    }
}

extension Notification.Name {
    /// Öffnet ein Lied im Tab „Bearbeiten“ (object: UUID des Liedes)
    static let openTrimEditor = Notification.Name("openTrimEditor")
}
