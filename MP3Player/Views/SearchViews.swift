import SwiftUI

// MARK: - Suche in einer Liederliste (Playlist, Titel, Lieblingssongs, Album …)

extension View {
    /// Suchfeld „In dieser Liste suchen“ unter dem Titel – nur wenn die Liste Lieder hat
    @ViewBuilder
    func listSearch(_ query: Binding<String>, enabled: Bool = true) -> some View {
        if enabled {
            searchable(text: query, placement: .navigationBarDrawer(displayMode: .always), prompt: "In dieser Liste suchen")
        } else {
            self
        }
    }
}

/// Fußzeile einer durchsuchten Liste: „3 von 20 Titeln“ oder „Keine Treffer“
struct ListSearchFooter: View {
    let query: String
    let shown: Int
    let total: Int

    var body: some View {
        Text(shown == 0 ? "Keine Treffer für „\(query.trimmingCharacters(in: .whitespaces))“" : "\(shown) von \(total) Titeln")
            .accessibilityIdentifier("listSearchCount")
    }
}

// MARK: - Suche über die ganze Mediathek (Start und Mediathek)

extension View {
    /// Suchfeld für die ganze Mediathek; schlägt die letzten Suchen vor
    func librarySearch(_ search: Binding<String>) -> some View {
        modifier(LibrarySearchField(search: search))
    }
}

private struct LibrarySearchField: ViewModifier {
    @Binding var search: String
    @AppStorage("recentSearches") private var recentRaw = ""

    private var recent: [String] { recentRaw.split(separator: "\n").map(String.init) }

    func body(content: Content) -> some View {
        content
            .searchable(text: $search, prompt: "Lieder, Interpreten, Alben, Genres")
            .searchSuggestions {
                if search.isEmpty {
                    ForEach(recent, id: \.self) { q in
                        Label(q, systemImage: "clock.arrow.circlepath").searchCompletion(q)
                    }
                }
            }
            .onSubmit(of: .search) {
                recentRaw = LibrarySearch.remember(recent, search).joined(separator: "\n")
            }
    }
}

/// Suchergebnisse nach Art sortiert (wie in den Apple-Apps): Abschnitte für die Liste in Start und Mediathek
struct LibrarySearchResults: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @AppStorage("recentSearches") private var recentRaw = ""
    let search: String

    private func rememberSearch() {
        let recent = recentRaw.split(separator: "\n").map(String.init)
        recentRaw = LibrarySearch.remember(recent, search).joined(separator: "\n")
    }

    var body: some View {
        let r = LibrarySearch.run(tracks: library.tracks, playlists: library.playlists, query: search)
        if r.isEmpty {
            Section {
                Text("Keine Treffer für „\(search.trimmingCharacters(in: .whitespaces))“").foregroundStyle(.secondary)
            }
        }
        if !r.artists.isEmpty {
            Section {
                ForEach(r.artists, id: \.self) { name in
                    NavigationLink(value: LibraryRoute.artist(name)) {
                        resultRow(name, "Interpret", art: ArtworkView(track: library.tracks(byArtist: name).first, size: 44, cornerRadius: 22))
                    }
                    .simultaneousGesture(TapGesture().onEnded { rememberSearch() })
                }
            } header: { Text("Interpreten") }
        }
        if !r.albums.isEmpty || !r.genres.isEmpty || !r.playlists.isEmpty {
            Section {
                ForEach(r.albums, id: \.self) { name in
                    NavigationLink(value: LibraryRoute.album(name)) {
                        resultRow(name, "Album", art: ArtworkView(track: library.tracks(inAlbum: name).first, size: 44, cornerRadius: 6))
                    }
                }
                ForEach(r.genres, id: \.self) { name in
                    NavigationLink(value: LibraryRoute.genre(name)) {
                        resultRow(name, "Genre", art: symbolArt("guitars.fill"))
                    }
                }
                ForEach(r.playlists) { p in
                    NavigationLink(value: LibraryRoute.playlist(p.id)) {
                        resultRow(p.name, "Playlist · \(p.trackIDs.count) Titel", art: PlaylistArtwork(playlist: p, first: library.tracks(in: p).first, size: 44, cornerRadius: 6))
                    }
                }
            } header: { Text("Alben, Genres und Playlists") }
        }
        if !r.tracks.isEmpty {
            Section {
                ForEach(Array(r.tracks.enumerated()), id: \.element.id) { index, track in
                    TrackListRow(track: track) { rememberSearch(); player.play(r.tracks, startAt: index) }
                }
            } header: { Text("Lieder (\(r.tracks.count))") }
        }
    }

    private func resultRow<A: View>(_ title: String, _ subtitle: String, art: A) -> some View {
        HStack(spacing: 12) {
            art
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(1)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }

    private func symbolArt(_ symbol: String) -> some View {
        Image(systemName: symbol)
            .font(.title3)
            .foregroundStyle(.white)
            .frame(width: 44, height: 44)
            .background(LinearGradient(colors: [Color.accentColor.opacity(0.7), Color.accentColor], startPoint: .top, endPoint: .bottom),
                        in: RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

/// Ziel eines Verweises in Start und Mediathek (Titel, Album, Playlist, Mix …)
struct LibraryDestination: View {
    @EnvironmentObject private var library: Library
    let route: LibraryRoute

    var body: some View {
        switch route {
        case .allTracks:
            AllTracksView()
        case .albums:
            GroupListView(title: "Alben", names: MediathekView.groupNames(library.tracks.map(\.album)), symbol: "square.stack",
                          route: { .album($0) }, tracksFor: { library.tracks(inAlbum: $0) })
        case .artists:
            GroupListView(title: "Interpreten", names: MediathekView.groupNames(library.tracks.map(\.artist)), symbol: "music.mic",
                          route: { .artist($0) }, tracksFor: { library.tracks(byArtist: $0) })
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
        case .genres:
            GroupListView(title: "Genres", names: library.genreNames, symbol: "guitars",
                          route: { .genre($0) }, tracksFor: { library.tracks(inGenre: $0) })
        case .genre(let name):
            TrackCollectionList(title: name, subtitle: "Genre", tracks: library.tracks(inGenre: name))
                .navigationTitle(name)
        }
    }
}
