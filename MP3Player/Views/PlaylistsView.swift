import SwiftUI

struct PlaylistDetailView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    let playlistID: UUID

    @State private var showAddTracks = false
    @State private var showRename = false
    @State private var showCoverPicker = false
    @State private var renameText = ""
    @State private var query = ""
    @StateObject private var selection = SelectionModel()

    var body: some View {
        if let playlist = library.playlist(id: playlistID) {
            content(playlist)
        } else {
            EmptyStateView(title: "Playlist gelöscht", systemImage: "music.note.list", message: "")
        }
    }

    private func content(_ playlist: Playlist) -> some View {
        let tracks = library.tracks(in: playlist)
        let shown = LibrarySearch.filter(tracks, query: query)
        let searching = !query.trimmingCharacters(in: .whitespaces).isEmpty
        // Beim Suchen stimmen die Zeilen nicht mit der Reihenfolge der Playlist überein: dann nicht umsortieren
        let move: ((IndexSet, Int) -> Void)? = searching ? nil : { library.moveTracks(from: $0, to: $1, in: playlist) }
        return List {
            if !tracks.isEmpty && !searching {
                Section {
                    header(playlist, tracks)
                }
            }
            Section {
                ForEach(shown) { track in
                    TrackListRow(track: track, playlist: playlist, selection: selection) {
                        // Abgespielt wird aus der ganzen Playlist, damit es nach dem Treffer weitergeht
                        player.play(tracks, startAt: tracks.firstIndex(where: { $0.id == track.id }) ?? 0, playlistID: playlist.id)
                    }
                }
                .onDelete { offsets in
                    let doomed = offsets.compactMap { shown.indices.contains($0) ? shown[$0] : nil }
                    doomed.forEach { library.remove($0, from: playlist) }
                }
                .onMove(perform: move)
            } footer: {
                if tracks.isEmpty {
                    Text(playlist.id == Library.downloadsPlaylistID
                         ? "Titel, die du per Link herunterlädst, erscheinen automatisch hier."
                         : "Füge über ＋ Titel hinzu.")
                } else if searching {
                    ListSearchFooter(query: query, shown: shown.count, total: tracks.count)
                } else {
                    Text("\(tracks.count) Titel · \(tracks.reduce(0) { $0 + $1.duration }.formattedTime)")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(playlist.name)
        .listSearch($query, enabled: !tracks.isEmpty)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if selection.active { SelectionBar(selection: selection, all: shown, playlist: playlist) }
        }
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                EditButton()
                Menu {
                    Button { showAddTracks = true } label: { Label("Titel hinzufügen", systemImage: "plus") }
                    if !tracks.isEmpty {
                        Button { selection.start() } label: { Label("Mehrere auswählen", systemImage: "checkmark.circle") }
                    }
                    if playlist.id != Library.downloadsPlaylistID {
                        Button { renameText = playlist.name; showRename = true } label: { Label("Umbenennen", systemImage: "pencil") }
                    }
                    Button { showCoverPicker = true } label: { Label("Cover ändern", systemImage: "photo") }
                    if library.hasPlaylistCover(playlist) {
                        Button(role: .destructive) {
                            library.removePlaylistCover(playlist)
                            Notice.show("Eigenes Cover entfernt", symbol: "photo")
                        } label: { Label("Eigenes Cover entfernen", systemImage: "trash") }
                    }
                } label: { Image(systemName: "ellipsis.circle") }
                .accessibilityLabel("Mehr")
                .accessibilityIdentifier("playlistMenu")
            }
        }
        .sheet(isPresented: $showAddTracks) {
            AddTracksSheet(playlist: playlist)
        }
        .sheet(isPresented: $showCoverPicker) {
            CoverPicker { image in
                library.setPlaylistCover(playlist, image: image)
                Notice.show("Cover geändert", symbol: "photo")
            }
        }
        .alert("Playlist umbenennen", isPresented: $showRename) {
            TextField("Name", text: $renameText)
            Button("Abbrechen", role: .cancel) {}
            Button("Sichern") { library.renamePlaylist(playlist, to: renameText) }
        }
    }

    /// Kopf wie bei Alben und Mixen: großes Cover (antippen zum Ändern), darunter „Abspielen“ und „Zufällig“
    private func header(_ playlist: Playlist, _ tracks: [Track]) -> some View {
        VStack(spacing: 14) {
            Button { showCoverPicker = true } label: {
                PlaylistArtwork(playlist: playlist, first: tracks.first, size: 180, cornerRadius: 12)
                    .shadow(color: .black.opacity(0.25), radius: 16, y: 8)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Cover der Playlist")
            .accessibilityHint("Tippen, um ein eigenes Cover zu wählen")
            .accessibilityIdentifier("playlistCover")
            HStack(spacing: 12) {
                Button {
                    player.shuffle = false
                    player.play(tracks, startAt: 0, playlistID: playlist.id)
                } label: { Label("Abspielen", systemImage: "play.fill").frame(maxWidth: .infinity) }
                    .accessibilityIdentifier("playAllButton")
                Button {
                    player.shuffle = true
                    player.play(tracks, startAt: Int.random(in: 0..<tracks.count), playlistID: playlist.id)
                } label: { Label("Zufällig", systemImage: "shuffle").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
}

struct AddTracksSheet: View {
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    let playlist: Playlist
    @State private var selected = Set<UUID>()

    var body: some View {
        NavigationStack {
            List(library.sortedTracks) { track in
                let already = playlist.trackIDs.contains(track.id)
                Button {
                    if selected.contains(track.id) { selected.remove(track.id) } else { selected.insert(track.id) }
                } label: {
                    HStack {
                        TrackRow(track: track)
                        Image(systemName: already || selected.contains(track.id) ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(already ? Color.secondary : Color.accentColor)
                    }
                }
                .buttonStyle(.plain)
                .disabled(already)
            }
            .navigationTitle("Titel hinzufügen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Hinzufügen (\(selected.count))") {
                        for track in library.sortedTracks where selected.contains(track.id) {
                            library.add(track, to: playlist)
                        }
                        dismiss()
                    }
                    .disabled(selected.isEmpty)
                }
            }
        }
    }
}
