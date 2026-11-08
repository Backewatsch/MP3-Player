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

/// Leiste unten während der Auswahl: Als Nächstes · Zu Playlist · Herz · (Entfernen) · Löschen
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
                    .font(.system(.headline, design: .monospaced))
                    .foregroundStyle(Color.neonCyan)
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
                    chosen.forEach { player.playNext($0) }
                    selection.clear()
                }
                action("Zu Playlist", "text.badge.plus", "sel-playlist") { showAdd = true }
                action("Herz", "heart.fill", "sel-fav") {
                    library.addFavorites(chosen)
                    selection.clear()
                }
                if let playlist {
                    action("Entfernen", "minus.circle", "sel-remove") {
                        chosen.forEach { library.remove($0, from: playlist) }
                        selection.clear()
                    }
                }
                action("Löschen", "trash", "sel-delete", destructive: true) { confirmDelete = true }
            }
            .disabled(chosen.isEmpty)
        }
        .padding(12)
        .background(Color.cyberPanelHigh.opacity(0.97))
        .overlay(alignment: .top) { Rectangle().fill(Color.cyberAccent.opacity(0.4)).frame(height: 1) }
        .sheet(isPresented: $showAdd) {
            AddManyToPlaylistSheet(tracks: chosen) { selection.clear() }
        }
        .confirmationDialog("\(chosen.count) Titel löschen?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) {
                library.deleteAll(chosen)
                selection.clear()
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
            .foregroundStyle(destructive ? Color.red : Color.cyberAccent)
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
                    } label: { PlaylistRow(playlist: playlist) }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("addTo-\(playlist.name)")
                }
            }
            .cyberList()
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
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
