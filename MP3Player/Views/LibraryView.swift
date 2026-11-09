import SwiftUI
import UniformTypeIdentifiers

/// Navigationsziele innerhalb der Mediathek
enum LibraryRoute: Hashable {
    case allTracks
    case albums
    case artists
    case album(String)
    case artist(String)
    case playlist(UUID)
    case favorites
    case stats
    case smart(String)
    case duplicates
}

/// Tab „Mediathek“: Kurzwahl, Titel/Alben/Interpreten und Playlists (inkl. neue Playlist erstellen).
struct MediathekView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var downloads: DownloadManager
    @EnvironmentObject private var actions: TrackActions

    @State private var path = NavigationPath()
    @State private var search = ""
    @State private var showImporter = false
    @State private var showDownloads = false
    @State private var importError: String?
    @State private var showSettings = false
    @State private var showNewPlaylist = false
    @State private var newPlaylistName = ""

    private var searchResults: [Track] {
        let q = search.trimmingCharacters(in: .whitespaces)
        return library.sortedTracks.filter {
            $0.title.localizedCaseInsensitiveContains(q) || $0.artist.localizedCaseInsensitiveContains(q)
                || $0.album.localizedCaseInsensitiveContains(q)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if search.trimmingCharacters(in: .whitespaces).isEmpty {
                    overview
                } else {
                    let results = searchResults
                    Section {
                        ForEach(Array(results.enumerated()), id: \.element.id) { index, track in
                            TrackListRow(track: track) { player.play(results, startAt: index) }
                        }
                    } header: { SectionTitle(text: "\(results.count) Treffer") }
                }
            }
            .listStyle(.insetGrouped)
            .safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 0) {
                    UpdateBanner { showSettings = true }
                    if let status = library.conversionStatus {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(status).font(.footnote).lineLimit(2)
                            Spacer()
                        }
                        .padding(12)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 6)
                    }
                }
            }
            .navigationTitle("Mediathek")
            .searchable(text: $search, prompt: "Titel, Interpret oder Album")
            .navigationDestination(for: LibraryRoute.self, destination: destination)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Info & Updates")
                        .accessibilityIdentifier("settingsButton")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { newPlaylistName = ""; showNewPlaylist = true } label: {
                            Label("Neue Playlist", systemImage: "text.badge.plus")
                        }
                        Button { showImporter = true } label: {
                            Label("Aus Dateien importieren / Video umwandeln", systemImage: "folder")
                        }
                        Button { showDownloads = true } label: {
                            Label("Von Link herunterladen", systemImage: "link")
                        }
                        .accessibilityIdentifier("menuLink")
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Hinzufügen")
                    .accessibilityIdentifier("addMenu")
                }
            }
            .fileImporter(isPresented: $showImporter,
                          allowedContentTypes: [.audio, .mp3Audio, .movie, .mpeg4Movie, .quickTimeMovie],
                          allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls):
                    Task {
                        var failures: [String] = []
                        for url in urls {
                            do { try await library.importAudio(from: url, move: false) }
                            catch { failures.append(error.localizedDescription) }
                        }
                        if !failures.isEmpty { importError = failures.joined(separator: "\n") }
                    }
                case .failure(let error):
                    importError = error.localizedDescription
                }
            }
            .alert("Neue Playlist", isPresented: $showNewPlaylist) {
                TextField("Name", text: $newPlaylistName)
                    .accessibilityIdentifier("newPlaylistName")
                Button("Abbrechen", role: .cancel) {}
                Button("Erstellen") {
                    let playlist = library.createPlaylist(named: newPlaylistName)
                    path.append(LibraryRoute.playlist(playlist.id))
                }
            }
            .alert("Import fehlgeschlagen", isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(importError ?? "")
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
            }
            .sheet(isPresented: $showDownloads) {
                DownloadsView(isSheet: true)
            }
        }
    }

    // MARK: Übersicht

    @ViewBuilder private var overview: some View {
        if library.tracks.isEmpty {
            Section {
                VStack(spacing: 10) {
                    Image(systemName: "music.note")
                        .font(.system(size: 40))
                        .foregroundStyle(.secondary)
                    Text("Noch keine Musik").font(.headline)
                    Text("Importiere Dateien über ＋ (Dateien-App oder Link), teile eine MP3 aus Safari mit der App oder kopiere Musik per Finder-Dateifreigabe aufs iPhone.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
            }
        } else {
            Section {
                HStack(spacing: 12) {
                    Button {
                        player.shuffle = false
                        player.play(library.sortedTracks, startAt: 0, allTracks: true)
                    } label: {
                        Label("Abspielen", systemImage: "play.fill").frame(maxWidth: .infinity)
                    }
                    Button {
                        let all = library.sortedTracks
                        player.shuffle = true
                        player.play(all, startAt: Int.random(in: 0..<all.count), allTracks: true)
                    } label: {
                        Label("Zufällig", systemImage: "shuffle").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.bordered)
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }
        }

        if !library.pinnedTracks.isEmpty {
            Section {
                PinnedStrip(tracks: library.pinnedTracks)
                    .listRowInsets(EdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0))
                    .listRowBackground(Color.clear)
            } header: { SectionTitle(text: "Kurzwahl") }
        }

        if !library.tracks.isEmpty {
            Section {
                SmartRow()
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
                    .listRowBackground(Color.clear)
            } header: { SectionTitle(text: "Deine Mixe") }
        }

        Section {
            NavigationLink(value: LibraryRoute.favorites) {
                categoryRow("Lieblingssongs", "heart.fill", count: library.favoriteTracks.count)
            }
            .accessibilityIdentifier("category-Lieblingssongs")
            NavigationLink(value: LibraryRoute.allTracks) {
                categoryRow("Titel", "music.note", count: library.tracks.count)
            }
            .accessibilityIdentifier("category-Titel")
            NavigationLink(value: LibraryRoute.albums) {
                categoryRow("Alben", "square.stack", count: albumNames.count)
            }
            .accessibilityIdentifier("category-Alben")
            NavigationLink(value: LibraryRoute.artists) {
                categoryRow("Interpreten", "music.mic", count: artistNames.count)
            }
            .accessibilityIdentifier("category-Interpreten")
            NavigationLink(value: LibraryRoute.stats) {
                categoryRow("Statistik", "chart.bar.fill", count: nil)
            }
            .accessibilityIdentifier("category-Statistik")
        } header: {
            SectionTitle(text: "Bibliothek")
        }

        Section {
            Button {
                newPlaylistName = ""
                showNewPlaylist = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "plus")
                        .font(.title3.weight(.semibold))
                        .frame(width: 52, height: 52)
                        .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    Text("Neue Playlist").foregroundStyle(Color.accentColor)
                }
            }
            .accessibilityIdentifier("newPlaylistButton")

            ForEach(library.playlists) { playlist in
                NavigationLink(value: LibraryRoute.playlist(playlist.id)) {
                    PlaylistRow(playlist: playlist)
                }
                .deleteDisabled(playlist.id == Library.downloadsPlaylistID)
            }
            .onDelete { offsets in
                let doomed = offsets.compactMap { library.playlists.indices.contains($0) ? library.playlists[$0] : nil }
                doomed.forEach(library.deletePlaylist)
            }
        } header: {
            SectionTitle(text: "Playlists")
        }
    }

    private func categoryRow(_ title: String, _ symbol: String, count: Int?) -> some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(Color.accentColor)
                .frame(width: 30)
            Text(title)
            Spacer()
            if let count { Text("\(count)").foregroundStyle(.secondary) }
        }
        .padding(.vertical, 4)
    }

    private var albumNames: [String] { Self.groupNames(library.tracks.map(\.album)) }
    private var artistNames: [String] { Self.groupNames(library.tracks.map(\.artist)) }

    static func groupNames(_ names: [String]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for name in names where !name.isEmpty {
            let key = name.lowercased()
            if seen.insert(key).inserted { result.append(name) }
        }
        return result.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    // MARK: Ziele

    @ViewBuilder private func destination(_ route: LibraryRoute) -> some View {
        switch route {
        case .allTracks:
            AllTracksView()
        case .albums:
            GroupListView(title: "Alben", names: albumNames, symbol: "square.stack") { .album($0) }
                tracksFor: { library.tracks(inAlbum: $0) }
        case .artists:
            GroupListView(title: "Interpreten", names: artistNames, symbol: "music.mic") { .artist($0) }
                tracksFor: { library.tracks(byArtist: $0) }
        case .album(let name):
            TrackCollectionList(title: name, subtitle: "Album", tracks: library.tracks(inAlbum: name))
                .navigationTitle(name)
        case .artist(let name):
            TrackCollectionList(title: name, subtitle: "Interpret", tracks: library.tracks(byArtist: name))
                .navigationTitle(name)
        case .playlist(let id):
            PlaylistDetailView(playlistID: id)
        case .favorites:
            FavoritesView()
        case .stats:
            StatsView()
        case .smart(let name):
            if let kind = SmartPlaylist(rawValue: name) { SmartPlaylistView(kind: kind) }
        case .duplicates:
            DuplicatesView()
        }
    }
}

struct PlaylistRow: View {
    @EnvironmentObject private var library: Library
    let playlist: Playlist

    var body: some View {
        let tracks = library.tracks(in: playlist)
        HStack(spacing: 12) {
            PlaylistArtwork(playlist: playlist, first: tracks.first, size: 52)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if playlist.id == Library.downloadsPlaylistID {
                        Image(systemName: "arrow.down.circle.fill").foregroundStyle(Color.accentColor)
                    }
                    Text(playlist.name)
                }
                Text("\(tracks.count) Titel")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// Alle Titel – sortierbar, mit Mehrfachauswahl
struct AllTracksView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var actions: TrackActions
    @AppStorage("trackSort") private var sortRaw = TrackSort.title.rawValue
    @StateObject private var selection = SelectionModel()

    private var sort: TrackSort { TrackSort(rawValue: sortRaw) ?? .title }

    var body: some View {
        let tracks = library.tracksSorted(by: sort)
        List {
            if !tracks.isEmpty {
                Section {
                    PlayShuffleRow(tracks: tracks, allTracks: true)
                } footer: {
                    Text("Sortiert: \(sort.label) · Gedrückt halten → „Auswählen“")
                }
            }
            Section {
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    TrackListRow(track: track, selection: selection) {
                        player.play(tracks, startAt: index, allTracks: true)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { actions.requestDelete(track) } label: { Label("Entfernen", systemImage: "trash") }
                        Button { player.addToQueue(track) } label: { Label("Wiedergabeliste", systemImage: "text.append") }
                            .tint(.indigo)
                    }
                    .swipeActions(edge: .leading) {
                        Button { player.playNext(track) } label: { Label("Als Nächstes", systemImage: "text.line.first.and.arrowtriangle.forward") }
                            .tint(Color.accentColor)
                        Button { library.toggleFavorite(track) } label: { Label("Herz", systemImage: library.isFavorite(track) ? "heart.slash" : "heart") }
                            .tint(.pink)
                    }
                }
            } footer: {
                Text("\(tracks.count) Titel")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Titel")
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Menu {
                    Picker("Sortieren", selection: $sortRaw) {
                        ForEach(TrackSort.allCases) { option in
                            Text(option.label).tag(option.rawValue)
                        }
                    }
                } label: { Image(systemName: "arrow.up.arrow.down") }
                .accessibilityLabel("Sortieren")
                .accessibilityIdentifier("sortButton")
                if !tracks.isEmpty {
                    Button(selection.active ? "Fertig" : "Auswählen") { if selection.active { selection.clear() } else { selection.start() } }
                        .accessibilityIdentifier("selectButton")
                }
            }
        }
        .safeAreaInset(edge: .top) {
            if selection.active { SelectionBar(selection: selection, all: tracks) }
        }
    }
}

/// Liste von Alben oder Interpreten
struct GroupListView: View {
    let title: String
    let names: [String]
    let symbol: String
    let route: (String) -> LibraryRoute
    let tracksFor: (String) -> [Track]

    init(title: String, names: [String], symbol: String,
         route: @escaping (String) -> LibraryRoute, tracksFor: @escaping (String) -> [Track]) {
        self.title = title
        self.names = names
        self.symbol = symbol
        self.route = route
        self.tracksFor = tracksFor
    }

    var body: some View {
        List {
            if names.isEmpty {
                Text("Keine Angaben in den Musikdateien gefunden.").foregroundStyle(.secondary)
            }
            ForEach(names, id: \.self) { name in
                let tracks = tracksFor(name)
                NavigationLink(value: route(name)) {
                    HStack(spacing: 12) {
                        ArtworkView(track: tracks.first, size: 52, cornerRadius: 8)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(name).lineLimit(1)
                            Text("\(tracks.count) Titel").font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(title)
    }
}

/// Angepinnte Titel als waagerechte Leiste („Kurzwahl“).
struct PinnedStrip: View {
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var actions: TrackActions
    let tracks: [Track]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 14) {
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    Button {
                        player.play(tracks, startAt: index)
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            ArtworkView(track: track, size: 110, cornerRadius: 12)
                            Text(track.title).font(.footnote.weight(.semibold)).lineLimit(1)
                            Text(track.artist.isEmpty ? " " : track.artist)
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        .frame(width: 110)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button { actions.open(track) } label: { Label("Optionen …", systemImage: "ellipsis.circle") }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

extension UTType {
    static let mp3Audio = UTType("public.mp3") ?? .audio
}

/// Cover einer Playlist: eigenes Bild, sonst das Cover des ersten Titels
struct PlaylistArtwork: View {
    @EnvironmentObject private var library: Library
    let playlist: Playlist
    let first: Track?
    var size: CGFloat
    var cornerRadius: CGFloat = 8

    var body: some View {
        let _ = library.coverVersion
        if let image = UIImage(contentsOfFile: library.playlistCoverURL(playlist).path) {
            Image(uiImage: image).resizable().scaledToFill()
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        } else {
            ArtworkView(track: first, size: size, cornerRadius: cornerRadius)
        }
    }
}
