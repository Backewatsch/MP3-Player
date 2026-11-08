import SwiftUI

struct PlaylistDetailView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    let playlistID: UUID

    @State private var showAddTracks = false
    @State private var showRename = false
    @State private var renameText = ""
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
        return List {
            if !tracks.isEmpty {
                Section {
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
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                }
            }
            Section {
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    TrackListRow(track: track, playlist: playlist, selection: selection) {
                        player.play(tracks, startAt: index, playlistID: playlist.id)
                    }
                    .cyberRow()
                }
                .onDelete { library.removeTracks(at: $0, from: playlist) }
                .onMove { library.moveTracks(from: $0, to: $1, in: playlist) }
            } footer: {
                if tracks.isEmpty {
                    Text(playlist.id == Library.downloadsPlaylistID
                         ? "Titel, die du im Browser herunterlädst, erscheinen automatisch hier."
                         : "Füge über ＋ Titel hinzu.")
                } else {
                    Text("\(tracks.count) Titel · \(tracks.reduce(0) { $0 + $1.duration }.formattedTime)")
                }
            }
        }
        .listStyle(.insetGrouped)
        .cyberList()
        .navigationTitle(playlist.name)
        .safeAreaInset(edge: .bottom) {
            if selection.active { SelectionBar(selection: selection, all: tracks, playlist: playlist) }
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
                } label: { Image(systemName: "ellipsis.circle") }
                .accessibilityLabel("Mehr")
            }
        }
        .sheet(isPresented: $showAddTracks) {
            AddTracksSheet(playlist: playlist)
        }
        .alert("Playlist umbenennen", isPresented: $showRename) {
            TextField("Name", text: $renameText)
            Button("Abbrechen", role: .cancel) {}
            Button("Sichern") { library.renamePlaylist(playlist, to: renameText) }
        }
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
