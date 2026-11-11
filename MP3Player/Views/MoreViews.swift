import PhotosUI
import SwiftUI

/// Songtext groß zum Mitlesen – aus der Datei oder selbst eingetragen
struct LyricsView: View {
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    @AppStorage("showOwnCovers") private var ownCovers = true
    let track: Track
    @State private var text: String?
    @State private var loaded = false
    @State private var editing = false
    @State private var draft = ""

    var body: some View {
        let color = ArtworkColor.color(for: track, ownCovers: ownCovers)
        NavigationStack {
            Group {
                if editing {
                    TextEditor(text: $draft)
                        .scrollContentBackground(.hidden)
                        .font(.title3)
                        .padding()
                        .accessibilityIdentifier("lyricsField")
                } else {
                    ScrollView {
                        VStack(alignment: .leading) {
                            if !loaded {
                                ProgressView()
                            } else if let text {
                                Text(text).font(.title2.weight(.bold)).lineSpacing(8)
                                    .accessibilityIdentifier("lyricsText")
                            } else {
                                Text("Für dieses Lied ist kein Songtext gespeichert.\n\nTippe oben auf „Bearbeiten“ und füge ihn ein – er bleibt dann gespeichert.")
                                    .font(.title3).foregroundStyle(.white.opacity(0.8))
                                    .accessibilityIdentifier("lyricsEmpty")
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(24)
                    }
                }
            }
            .foregroundStyle(.white)
            .background(LinearGradient(colors: [color, color.opacity(0.7), .black], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
            .navigationTitle(track.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(editing ? "Abbrechen" : "Fertig") { if editing { editing = false } else { dismiss() } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if editing {
                        Button("Sichern") {
                            library.setLyrics(track, draft)
                            text = draft.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                            editing = false
                        }
                        .accessibilityIdentifier("lyricsSave")
                    } else {
                        Button("Bearbeiten") { draft = text ?? ""; editing = true }
                            .accessibilityIdentifier("lyricsEdit")
                    }
                }
            }
            .task {
                text = await library.lyrics(for: track)
                loaded = true
            }
        }
    }
}

extension SmartPlaylist {
    var colors: [Color] {
        switch self {
        case .recentlyAdded: return [Color(red: 0.18, green: 0.44, blue: 0.62), Color.accentColor]
        case .onRepeat: return [Color(red: 0.48, green: 0.18, blue: 0.35), Color(red: 0.88, green: 0.48, blue: 0.63)]
        case .rediscover: return [Color(red: 0.18, green: 0.42, blue: 0.31), Color(red: 0.5, green: 0.82, blue: 0.66)]
        case .neverPlayed: return [Color(red: 0.42, green: 0.35, blue: 0.18), Color(red: 0.88, green: 0.76, blue: 0.48)]
        }
    }
}

/// Kachel einer automatischen Playlist
struct SmartTile: View {
    let kind: SmartPlaylist
    let count: Int
    var size: CGFloat = 140

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottomLeading) {
                LinearGradient(colors: kind.colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                Text(kind.title).font(.headline.weight(.black)).foregroundStyle(.white).padding(10)
            }
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            Text(kind.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Text("\(count) Titel").font(.caption2).foregroundStyle(.secondary)
        }
        .frame(width: size)
    }
}

/// Waagerechte Reihe „Deine Mixe“
struct SmartRow: View {
    @EnvironmentObject private var library: Library

    var body: some View {
        let state = library.stateSnapshot
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 14) {
                ForEach(SmartPlaylist.allCases) { kind in
                    NavigationLink(value: LibraryRoute.smart(kind.rawValue)) {
                        SmartTile(kind: kind, count: kind.tracks(state).count)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("mix-\(kind.rawValue)")
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

/// Automatische Playlist anzeigen
struct SmartPlaylistView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @StateObject private var selection = SelectionModel()
    @State private var query = ""
    let kind: SmartPlaylist

    var body: some View {
        let tracks = library.smartTracks(kind)
        let shown = LibrarySearch.filter(tracks, query: query)
        let searching = !query.trimmingCharacters(in: .whitespaces).isEmpty
        List {
            if !searching {
                Section {
                    CollectionHeader(title: kind.title, subtitle: "\(kind.subtitle) · \(tracks.count) Titel", color: kind.colors[0], tracks: tracks) {
                        ZStack {
                            LinearGradient(colors: kind.colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                            Image(systemName: "sparkles").font(.system(size: 60)).foregroundStyle(.white)
                        }
                        .frame(width: 180, height: 180)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .frame(maxWidth: .infinity)
                    }
                }
            }
            Section {
                if tracks.isEmpty { Text("Hier erscheinen Lieder, sobald es passende gibt.").foregroundStyle(.secondary) }
                ForEach(shown) { track in
                    TrackListRow(track: track, selection: selection) {
                        player.play(tracks, startAt: tracks.firstIndex(where: { $0.id == track.id }) ?? 0)
                    }
                }
            } footer: {
                if searching { ListSearchFooter(query: query, shown: shown.count, total: tracks.count) }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .listSearch($query, enabled: !tracks.isEmpty)
        .safeAreaInset(edge: .top) {
            if selection.active { SelectionBar(selection: selection, all: shown) }
        }
    }
}

/// Doppelte Lieder finden und aufräumen
struct DuplicatesView: View {
    @EnvironmentObject private var library: Library

    var body: some View {
        let groups = library.duplicateGroups
        List {
            if groups.isEmpty {
                Text("Keine Doppelten 🎉 Jedes Lied ist nur einmal in deiner Mediathek.")
            } else {
                Section {
                    Text("\(groups.count) Lieder sind mehrfach vorhanden. Beim Aufräumen bleibt jeweils eins (MP3, meistgespielt) – Playlists, Herzen und Zähler werden übernommen.")
                        .font(.footnote)
                        .accessibilityIdentifier("duplicatesSummary")
                    Button { groups.forEach { library.mergeDuplicates($0) } } label: {
                        Label("Alle aufräumen", systemImage: "sparkles")
                    }
                    .accessibilityIdentifier("mergeAll")
                }
                ForEach(groups, id: \.first!.id) { group in
                    Section {
                        ForEach(group) { t in
                            HStack(spacing: 10) {
                                ArtworkView(track: t, size: 40)
                                VStack(alignment: .leading) {
                                    Text(t.title).lineLimit(1)
                                    Text("\(t.fileName) · \(t.duration.formattedTime) · \(library.playCount(t))× gehört")
                                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                        }
                        Button("Aufräumen") { library.mergeDuplicates(group) }
                    }
                }
            }
        }
        .navigationTitle("Doppelte Lieder")
    }
}

/// Bild aus der Galerie wählen (eigenes Cover)
struct CoverPicker: View {
    let onPicked: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var item: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Image(systemName: "photo.on.rectangle.angled").font(.system(size: 54)).foregroundStyle(Color.accentColor)
                Text("Wähle ein Bild aus deiner Galerie als Cover.").multilineTextAlignment(.center)
                PhotosPicker(selection: $item, matching: .images) {
                    Label("Bild auswählen", systemImage: "photo").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.accentColor)
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Cover ändern")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .onChange(of: item) { newItem in
                guard let newItem else { return }
                Task {
                    if let data = try? await newItem.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        onPicked(image)
                    }
                    dismiss()
                }
            }
        }
        .presentationDetents([.medium])
    }
}
