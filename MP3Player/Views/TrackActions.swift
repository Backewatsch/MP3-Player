import SwiftUI

/// Steuert das Optionsmenü eines Titels und die Folge-Dialoge (Playlist wählen, Album, Interpret …).
@MainActor
final class TrackActions: ObservableObject {
    enum Sheet: Identifiable {
        case menu(Track, Playlist?)
        case addToPlaylist(Track)
        case album(String)
        case artist(String)
        case info(Track)
        case edit(Track)

        var id: String {
            switch self {
            case .menu(let t, let p): return "menu-\(t.id)-\(p?.id.uuidString ?? "")"
            case .addToPlaylist(let t): return "add-\(t.id)"
            case .album(let a): return "album-\(a)"
            case .artist(let a): return "artist-\(a)"
            case .info(let t): return "info-\(t.id)"
            case .edit(let t): return "edit-\(t.id)"
            }
        }
    }

    @Published var sheet: Sheet?
    @Published var confirmDelete: Track?
    private var afterDismiss: (() -> Void)?

    func open(_ track: Track, in playlist: Playlist? = nil) {
        show(.menu(track, playlist))
    }

    /// Öffnet einen Dialog – ist schon einer offen, erst nach dessen Schließen.
    func show(_ next: Sheet) {
        then { self.sheet = next }
    }

    func requestDelete(_ track: Track) {
        then { self.confirmDelete = track }
    }

    func close() {
        sheet = nil
    }

    func sheetDismissed() {
        let action = afterDismiss
        afterDismiss = nil
        action?()
    }

    private func then(_ action: @escaping () -> Void) {
        if sheet == nil {
            action()
        } else {
            afterDismiss = action
            sheet = nil
        }
    }
}

private struct TrackActionsHost: ViewModifier {
    @StateObject private var actions = TrackActions()
    @EnvironmentObject private var library: Library

    func body(content: Content) -> some View {
        content
            .environmentObject(actions)
            .sheet(item: $actions.sheet, onDismiss: actions.sheetDismissed) { sheet in
                Group {
                    switch sheet {
                    case .menu(let track, let playlist):
                        TrackActionsSheet(track: track, playlist: playlist)
                    case .addToPlaylist(let track):
                        AddToPlaylistSheet(track: track)
                    case .album(let album):
                        TrackCollectionView(title: album, subtitle: "Album", tracks: library.tracks(inAlbum: album))
                    case .artist(let artist):
                        TrackCollectionView(title: artist, subtitle: "Interpret", tracks: library.tracks(byArtist: artist))
                    case .info(let track):
                        SongInfoSheet(track: track)
                    case .edit(let track):
                        EditTrackSheet(track: track)
                    }
                }
                .environmentObject(actions)
            }
            .confirmationDialog(
                "Aus Mediathek entfernen?",
                isPresented: Binding(get: { actions.confirmDelete != nil }, set: { if !$0 { actions.confirmDelete = nil } }),
                titleVisibility: .visible,
                presenting: actions.confirmDelete
            ) { track in
                Button("„\(track.title)“ entfernen", role: .destructive) { library.delete(track) }
            } message: { _ in
                Text("Die Datei wird vom iPhone gelöscht und aus allen Playlists entfernt.")
            }
    }
}

extension View {
    /// Macht das Titel-Optionsmenü für alle Zeilen in dieser Ansicht verfügbar.
    func trackActionsHost() -> some View { modifier(TrackActionsHost()) }
}

// MARK: - Optionsmenü

struct TrackActionsSheet: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var actions: TrackActions
    let track: Track
    let playlist: Playlist?

    /// Immer die aktuelle Fassung aus der Bibliothek (z. B. nach dem Umbenennen)
    private var current: Track { library.track(id: track.id) ?? track }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                    .padding(.bottom, 18)

                HStack(alignment: .top, spacing: 12) {
                    tile("Als Nächstes abspielen", "text.line.first.and.arrowtriangle.forward") {
                        player.playNext(current)
                        actions.close()
                    }
                    tile("Zu Playlist hinzufügen", "text.badge.plus") {
                        actions.show(.addToPlaylist(current))
                    }
                    ShareLink(item: current.fileURL, preview: SharePreview(current.title)) {
                        tileLabel("Teilen", "square.and.arrow.up")
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("action-Teilen")
                }
                .padding(.bottom, 14)

                row(library.isFavorite(current) ? "Aus Lieblingssongs entfernen" : "Zu Lieblingssongs hinzufügen",
                    library.isFavorite(current) ? "heart.slash" : "heart") {
                    library.toggleFavorite(current)
                    actions.close()
                }
                row("Mix starten", "dot.radiowaves.left.and.right") {
                    player.startMix(with: current, from: library.tracks)
                    actions.close()
                }
                row("In die Wiedergabeliste", "text.append") {
                    player.addToQueue(current)
                    actions.close()
                }
                if !current.album.isEmpty {
                    row("Album anzeigen", "opticaldisc") { actions.show(.album(current.album)) }
                }
                if !current.artist.isEmpty {
                    row("Künstlerseite anzeigen", "music.mic") { actions.show(.artist(current.artist)) }
                }
                row("Mitwirkende an Song anzeigen", "person.2") { actions.show(.info(current)) }
                row(library.isPinned(current) ? "Von Kurzwahl lösen" : "An Kurzwahl anpinnen",
                    library.isPinned(current) ? "pin.slash" : "pin") {
                    library.togglePin(current)
                    actions.close()
                }
                row("Infos bearbeiten", "pencil") { actions.show(.edit(current)) }
                if !Storage.isMP3(current.fileName) {
                    row("In MP3 umwandeln", "arrow.triangle.2.circlepath") {
                        let target = current
                        if player.current?.id == target.id { player.stop() }
                        actions.close()
                        Task {
                            library.setConversionStatus("Wandle „\(target.title)“ in MP3 um …")
                            do {
                                try await library.convertToMP3(target)
                                library.setConversionStatus(nil)
                            } catch {
                                library.setConversionStatus("Umwandlung fehlgeschlagen: \(error.localizedDescription)")
                                try? await Task.sleep(nanoseconds: 4_000_000_000)
                                library.setConversionStatus(nil)
                            }
                        }
                    }
                }
                if let playlist {
                    row("Aus Playlist entfernen", "trash") {
                        library.remove(current, from: playlist)
                        actions.close()
                    }
                }
                if !player.queue.isEmpty {
                    row("Wiedergabeliste schließen", "text.badge.xmark") {
                        player.stop()
                        actions.close()
                    }
                }
                row("Aus Mediathek entfernen", "bookmark.slash", role: .destructive) {
                    actions.requestDelete(current)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 24)
            .padding(.bottom, 12)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var header: some View {
        HStack(spacing: 12) {
            ArtworkView(track: current, size: 54)
            VStack(alignment: .leading, spacing: 2) {
                Text(current.title).font(.headline).lineLimit(2)
                Text([current.artist, current.album].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }

    private func tile(_ title: String, _ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { tileLabel(title, symbol) }
            .buttonStyle(.plain)
            .accessibilityIdentifier("action-\(title)")
    }

    private func tileLabel(_ title: String, _ symbol: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.title2)
                .frame(maxWidth: .infinity, minHeight: 64)
                .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            Text(title)
                .font(.footnote)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
    }

    private func row(_ title: String, _ symbol: String, role: ButtonRole? = nil, action: @escaping () -> Void) -> some View {
        Button(role: role, action: action) {
            HStack(spacing: 18) {
                Image(systemName: symbol)
                    .font(.title3)
                    .frame(width: 28)
                Text(title).font(.body)
                Spacer()
            }
            .foregroundStyle(role == .destructive ? Color.red : Color.primary)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("action-\(title)")
    }
}

// MARK: - Zu Playlist hinzufügen

struct AddToPlaylistSheet: View {
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    let track: Track
    @State private var showNew = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                Button {
                    newName = ""
                    showNew = true
                } label: {
                    Label("Neue Playlist …", systemImage: "plus")
                }
                ForEach(library.playlists) { playlist in
                    let contains = playlist.trackIDs.contains(track.id)
                    Button {
                        if contains { library.remove(track, from: playlist) } else { library.add(track, to: playlist) }
                    } label: {
                        HStack(spacing: 12) {
                            ArtworkView(track: library.tracks(in: playlist).first, size: 40)
                            VStack(alignment: .leading) {
                                Text(playlist.name).foregroundStyle(.primary)
                                Text("\(library.tracks(in: playlist).count) Titel").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: contains ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(contains ? Color.accentColor : .secondary)
                                .font(.title3)
                        }
                    }
                }
            }
            .navigationTitle("Zu Playlist hinzufügen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
            }
            .alert("Neue Playlist", isPresented: $showNew) {
                TextField("Name", text: $newName)
                Button("Abbrechen", role: .cancel) {}
                Button("Erstellen") {
                    let playlist = library.createPlaylist(named: newName)
                    library.add(track, to: playlist)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Album / Interpret

/// Album oder Interpret als eigenes Fenster (aus dem Optionsmenü)
struct TrackCollectionView: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let subtitle: String
    let tracks: [Track]

    var body: some View {
        NavigationStack {
            TrackCollectionList(title: title, subtitle: subtitle, tracks: tracks)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
                }
        }
        .trackActionsHost()
    }
}

/// Kopf mit Cover + Titelliste (für Album- und Interpretenseiten)
struct TrackCollectionList: View {
    @EnvironmentObject private var player: AudioPlayer
    @StateObject private var selection = SelectionModel()
    let title: String
    let subtitle: String
    let tracks: [Track]

    var body: some View {
            List {
                Section {
                    VStack(spacing: 10) {
                        ArtworkView(track: tracks.first, size: 160, cornerRadius: 14)
                            .shadow(color: .black.opacity(0.4), radius: 12, y: 6)
                        Text(title).font(.title2.bold()).multilineTextAlignment(.center)
                        Text("\(subtitle) · \(tracks.count) Titel · \(tracks.reduce(0) { $0 + $1.duration }.formattedTime)")
                            .font(.subheadline).foregroundStyle(.secondary)
                        HStack(spacing: 12) {
                            Button {
                                player.shuffle = false
                                player.play(tracks, startAt: 0)
                            } label: { Label("Abspielen", systemImage: "play.fill").frame(maxWidth: .infinity) }
                            Button {
                                player.shuffle = true
                                player.play(tracks, startAt: Int.random(in: 0..<max(tracks.count, 1)))
                            } label: { Label("Zufällig", systemImage: "shuffle").frame(maxWidth: .infinity) }
                        }
                        .buttonStyle(.bordered)
                        .disabled(tracks.isEmpty)
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }
                Section {
                    ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                        TrackListRow(track: track, selection: selection) { player.play(tracks, startAt: index) }
                            .cyberRow()
                    }
                }
            }
            .listStyle(.insetGrouped)
            .cyberList()
            .toolbar {
                if !tracks.isEmpty {
                    Button(selection.active ? "Fertig" : "Auswählen") { if selection.active { selection.clear() } else { selection.start() } }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if selection.active { SelectionBar(selection: selection, all: tracks) }
            }
    }
}

// MARK: - Mitwirkende / Infos

struct SongInfoSheet: View {
    @Environment(\.dismiss) private var dismiss
    let track: Track
    @State private var info: SongInfo?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 12) {
                        ArtworkView(track: track, size: 60)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(track.title).font(.headline)
                            Text(track.artist.isEmpty ? "Unbekannter Interpret" : track.artist)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                if let info {
                    Section("Mitwirkende") {
                        if info.credits.isEmpty {
                            Text("In der Datei sind keine Mitwirkenden hinterlegt.").foregroundStyle(.secondary)
                        }
                        ForEach(info.credits) { entry in
                            LabeledContent(entry.label, value: entry.value)
                        }
                    }
                    Section("Datei") {
                        ForEach(info.file) { entry in
                            LabeledContent(entry.label, value: entry.value)
                        }
                    }
                } else {
                    HStack { Spacer(); ProgressView(); Spacer() }
                }
            }
            .navigationTitle("Mitwirkende")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
            }
            .task { info = await SongInfo.load(for: track) }
        }
    }
}

// MARK: - Zeile mit „…“-Knopf

/// Titelzeile: Tippen spielt ab, „…“ öffnet das Optionsmenü, langes Drücken zeigt ein Kurzmenü.
/// Mit `selection` gibt es eine Mehrfachauswahl: dann schaltet Tippen das Häkchen um.
struct TrackListRow: View {
    let track: Track
    var playlist: Playlist? = nil
    var selection: SelectionModel? = nil
    let onPlay: () -> Void

    var body: some View {
        if let selection {
            SelectableTrackRow(selection: selection, track: track, playlist: playlist, onPlay: onPlay)
        } else {
            PlainTrackRow(track: track, playlist: playlist, selection: nil, onPlay: onPlay)
        }
    }
}

/// Zeile, die auf die Auswahl reagiert (beobachtet das Auswahl-Modell)
private struct SelectableTrackRow: View {
    @ObservedObject var selection: SelectionModel
    let track: Track
    let playlist: Playlist?
    let onPlay: () -> Void

    var body: some View {
        if selection.active {
            Button { selection.toggle(track.id) } label: {
                HStack(spacing: 10) {
                    Image(systemName: selection.ids.contains(track.id) ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(selection.ids.contains(track.id) ? Color.cyberAccent : .secondary)
                    TrackRow(track: track)
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("select-\(track.title)")
        } else {
            PlainTrackRow(track: track, playlist: playlist, selection: selection, onPlay: onPlay)
        }
    }
}

private struct PlainTrackRow: View {
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var actions: TrackActions
    @EnvironmentObject private var library: Library
    let track: Track
    let playlist: Playlist?
    let selection: SelectionModel?
    let onPlay: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onPlay) { TrackRow(track: track) }
                .buttonStyle(.plain)
            Button {
                actions.open(track, in: playlist)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 34, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Optionen für \(track.title)")
            .accessibilityIdentifier("trackMore")
        }
        .contextMenu {
            if let selection {
                Button { selection.start(with: track.id) } label: { Label("Auswählen", systemImage: "checkmark.circle") }
            }
            Button { player.playNext(track) } label: { Label("Als Nächstes abspielen", systemImage: "text.line.first.and.arrowtriangle.forward") }
            Button { player.addToQueue(track) } label: { Label("In die Wiedergabeliste", systemImage: "text.append") }
            Button { library.toggleFavorite(track) } label: {
                Label(library.isFavorite(track) ? "Aus Lieblingssongs entfernen" : "Zu Lieblingssongs",
                      systemImage: library.isFavorite(track) ? "heart.slash" : "heart")
            }
            Button { actions.show(.addToPlaylist(track)) } label: { Label("Zu Playlist hinzufügen", systemImage: "text.badge.plus") }
            Button { actions.open(track, in: playlist) } label: { Label("Weitere Optionen …", systemImage: "ellipsis.circle") }
        }
    }
}
