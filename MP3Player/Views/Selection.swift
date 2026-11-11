import SwiftUI

/// Mehrfachauswahl in Titellisten
@MainActor
final class SelectionModel: ObservableObject {
    @Published var active = false
    @Published var ids = Set<UUID>()

    func toggle(_ id: UUID) {
        if ids.contains(id) { ids.remove(id) } else { ids.insert(id) }
    }

    func start(with id: UUID? = nil) {
        active = true
        if let id { ids.insert(id) }
    }

    func clear() {
        active = false
        ids = []
    }
}

/// Leiste unten während der Auswahl (wie in den Apple-Apps): Als Nächstes · Zu Playlist · Herz · (Entfernen) · Löschen.
/// Sie sitzt unten, damit die große Überschrift der Liste stehen bleibt.
struct SelectionBar: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @ObservedObject var selection: SelectionModel
    let all: [Track]
    var playlist: Playlist? = nil
    @State private var showAdd = false
    @State private var confirmDelete = false

    private var chosen: [Track] { all.filter { selection.ids.contains($0.id) } }

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text("\(chosen.count) ausgewählt")
                    .font(.headline)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityIdentifier("selectionCount")
                Spacer()
                Button(chosen.count == all.count ? "Keine" : "Alle") {
                    if chosen.count == all.count { selection.ids = [] } else { selection.ids = Set(all.map(\.id)) }
                }
                .accessibilityIdentifier("sel-all")
                Button("Fertig") { selection.clear() }
                    .fontWeight(.semibold)
                    .accessibilityIdentifier("sel-close")
            }
            HStack {
                action("Als Nächstes", "text.line.first.and.arrowtriangle.forward", "sel-next") {
                    let count = chosen.count
                    chosen.forEach { player.playNext($0) }
                    selection.clear()
                    Notice.show("\(count) Titel als Nächstes", symbol: "text.line.first.and.arrowtriangle.forward")
                }
                action("Zu Playlist", "text.badge.plus", "sel-playlist") { showAdd = true }
                action("Herz", "heart.fill", "sel-fav") {
                    let count = chosen.count
                    library.addFavorites(chosen)
                    selection.clear()
                    Notice.show("\(count) Titel zu Lieblingssongs", symbol: "heart.fill")
                }
                if let playlist {
                    action("Entfernen", "minus.circle", "sel-remove") {
                        let count = chosen.count
                        chosen.forEach { library.remove($0, from: playlist) }
                        selection.clear()
                        Notice.show("\(count) Titel aus „\(playlist.name)“ entfernt", symbol: "minus.circle")
                    }
                }
                action("Löschen", "trash", "sel-delete", destructive: true) { confirmDelete = true }
            }
            .disabled(chosen.isEmpty)
        }
        .padding(12)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
        .sheet(isPresented: $showAdd) {
            AddManyToPlaylistSheet(tracks: chosen) { selection.clear() }
        }
        .confirmationDialog("\(chosen.count) Titel löschen?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) {
                let count = chosen.count
                library.deleteAll(chosen)
                selection.clear()
                Notice.show("\(count) Titel gelöscht", symbol: "trash")
            }
        } message: {
            Text("Die Dateien werden vom iPhone gelöscht und aus allen Playlists entfernt.")
        }
    }

    private func action(_ title: String, _ symbol: String, _ id: String, destructive: Bool = false,
                        perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.title3)
                Text(title).font(.caption2)
            }
            .frame(maxWidth: .infinity)
            .foregroundStyle(destructive ? Color.red : Color.accentColor)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }
}

/// Mehrere Titel zu einer Playlist hinzufügen
struct AddManyToPlaylistSheet: View {
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    let tracks: [Track]
    var onDone: () -> Void = {}
    @State private var showNew = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                Button { newName = ""; showNew = true } label: { Label("Neue Playlist …", systemImage: "plus") }
                ForEach(library.playlists) { playlist in
                    Button {
                        library.addAll(tracks, to: playlist)
                        dismiss(); onDone()
                        Notice.show("\(tracks.count) Titel zu „\(playlist.name)“ hinzugefügt", symbol: "text.badge.plus")
                    } label: { PlaylistRow(playlist: playlist) }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("addTo-\(playlist.name)")
                }
            }
            .navigationTitle("\(tracks.count) Titel hinzufügen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .alert("Neue Playlist", isPresented: $showNew) {
                TextField("Name", text: $newName)
                Button("Abbrechen", role: .cancel) {}
                Button("Erstellen") {
                    let playlist = library.createPlaylist(named: newName)
                    library.addAll(tracks, to: playlist)
                    dismiss(); onDone()
                    Notice.show("\(tracks.count) Titel zu „\(playlist.name)“ hinzugefügt", symbol: "text.badge.plus")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
