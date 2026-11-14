import AVFoundation
import SwiftUI

/// Reinhören in ein importiertes Lied – bewusst getrennt vom normalen Player: Importierte Lieder sollen nicht in
/// Wiedergabeliste, Verlauf oder Statistik geraten, solange sie nicht übernommen sind.
@MainActor
final class SongPreviewPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    /// Kennung des Liedes, das gerade zum Reinhören läuft
    @Published private(set) var playingID: UUID?
    private var player: AVAudioPlayer?

    override init() {
        super.init()
        // Anruf, Wecker, Siri: iOS hält den Ton an – dann gilt das Lied auch hier nicht mehr als laufend
        NotificationCenter.default.addObserver(self, selector: #selector(sessionInterrupted(_:)),
                                               name: AVAudioSession.interruptionNotification, object: nil)
    }

    @objc nonisolated private func sessionInterrupted(_ note: Notification) {
        let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
        guard type == AVAudioSession.InterruptionType.began.rawValue else { return }
        Task { @MainActor in self.stop() }
    }

    /// Startet das Lied; läuft es schon, hält es an.
    func toggle(_ id: UUID, url: URL) {
        if playingID == id, player?.isPlaying == true {
            stop()
            return
        }
        stop()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
            let newPlayer = try AVAudioPlayer(contentsOf: url)
            newPlayer.delegate = self
            guard newPlayer.play() else { return }
            player = newPlayer
            playingID = id
        } catch {
            // Datei nicht lesbar: einfach nichts abspielen
            stop()
        }
    }

    func stop() {
        player?.stop()
        player = nil
        playingID = nil
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        // Nur anhalten, wenn inzwischen kein anderes Lied zum Reinhören läuft
        let finished = ObjectIdentifier(player)
        Task { @MainActor in
            if let current = self.player, ObjectIdentifier(current) == finished { self.stop() }
        }
    }
}

/// „Importierte Lieder“: Lieder von einem anderen Handy, die noch nicht in der Titel-Liste sind.
/// Antippen = reinhören, ＋ = in die Titel-Liste übernehmen, „Auswählen“ = mehrere auf einmal.
struct ImportedTracksView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @StateObject private var selection = SelectionModel()
    @StateObject private var preview = SongPreviewPlayer()
    @State private var query = ""
    @State private var toDelete: [Track] = []
    @State private var confirmDelete = false

    var body: some View {
        let tracks = library.importedTracks
        let shown = LibrarySearch.filter(tracks, query: query)
        let searching = !query.trimmingCharacters(in: .whitespaces).isEmpty
        Group {
            if tracks.isEmpty {
                EmptyStateView(title: "Keine importierten Lieder mehr.", systemImage: "tray",
                               message: "Neue Lieder holst du über das Zahnrad › „Neues Handy“ › „Lieder importieren“ hierher.")
                    .background(Color(.systemGroupedBackground).ignoresSafeArea())
            } else {
                List {
                    if !searching {
                        Section {
                            Button { adopt(tracks) } label: {
                                Label("Alle übernehmen", systemImage: "plus.circle.fill").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .accessibilityIdentifier("importedAdoptAll")
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                        } footer: {
                            Text("Diese Lieder sind noch nicht in deiner Titel-Liste. Tippe auf ＋, um ein Lied zu übernehmen. Antippen spielt ein Lied zum Reinhören.")
                        }
                    }
                    Section {
                        ForEach(shown) { track in
                            ImportedRow(track: track, previewing: preview.playingID == track.id, selection: selection,
                                        onPreview: { togglePreview(track) }, onAdopt: { adopt([track]) })
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) { delete([track]) } label: { Label("Löschen", systemImage: "trash") }
                                }
                                .swipeActions(edge: .leading) {
                                    Button { adopt([track]) } label: { Label("Übernehmen", systemImage: "plus") }
                                        .tint(Color.accentColor)
                                }
                        }
                    } footer: {
                        if searching {
                            ListSearchFooter(query: query, shown: shown.count, total: tracks.count)
                        } else {
                            Text("\(Self.songs(tracks.count)) · \(tracks.reduce(0) { $0 + $1.duration }.formattedTime)")
                        }
                    }
                }
                .listSearch($query)
            }
        }
        .navigationTitle("Importierte Lieder")
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                if !tracks.isEmpty {
                    Menu {
                        Button(role: .destructive) {
                            toDelete = tracks
                            confirmDelete = true
                        } label: { Label("Alle löschen", systemImage: "trash") }
                    } label: { Image(systemName: "ellipsis.circle") }
                    .accessibilityLabel("Mehr")
                    .accessibilityIdentifier("importedMenu")
                    Button(selection.active ? "Fertig" : "Auswählen") {
                        if selection.active { selection.clear() } else { selection.start() }
                    }
                    .accessibilityIdentifier("selectButton")
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if selection.active {
                ImportedSelectionBar(selection: selection, all: shown, onAdopt: { adopt($0) }, onDelete: { list in
                    toDelete = list
                    confirmDelete = true
                })
            }
        }
        .confirmationDialog("\(Self.songs(toDelete.count)) löschen?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) { delete(toDelete) }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Die Dateien werden aus dem Ordner „Importierte Lieder“ gelöscht. Deine Titel-Liste bleibt unverändert.")
        }
        // Beim Öffnen mit dem Ordner abgleichen (z. B. über die Dateien-App hineinkopierte Lieder)
        .task { await library.syncImportedFolder() }
        // Reinhören endet beim Verlassen der Seite und sobald der normale Player spielt
        .onDisappear { preview.stop() }
        .onChange(of: player.isPlaying) { playing in
            if playing { preview.stop() }
        }
    }

    /// „1 Lied“ / „5 Lieder“
    private static func songs(_ count: Int) -> String {
        count == 1 ? "1 Lied" : "\(count) Lieder"
    }

    private func togglePreview(_ track: Track) {
        // Der normale Player macht Pause, solange reingehört wird
        if preview.playingID != track.id, player.isPlaying { player.pause() }
        preview.toggle(track.id, url: library.importedFileURL(track))
    }

    private func stopPreview(ifIn list: [Track]) {
        if let playing = preview.playingID, list.contains(where: { $0.id == playing }) { preview.stop() }
    }

    private func adopt(_ list: [Track]) {
        guard !list.isEmpty else { return }
        stopPreview(ifIn: list)
        let ids = list.map(\.id)
        Task {
            let count = await library.adoptImported(ids)
            selection.clear()
            if count == 0 {
                Notice.show("Die Lieder ließen sich nicht übernehmen", symbol: "exclamationmark.triangle.fill")
            } else {
                Notice.show("\(Self.songs(count)) in die Titel-Liste übernommen", symbol: "plus.circle.fill")
            }
        }
    }

    private func delete(_ list: [Track]) {
        guard !list.isEmpty else { return }
        stopPreview(ifIn: list)
        let count = library.deleteImported(list.map(\.id))
        selection.clear()
        toDelete = []
        Notice.show("\(Self.songs(count)) gelöscht", symbol: "trash")
    }
}

/// Zeile eines importierten Liedes
private struct ImportedRow: View {
    let track: Track
    let previewing: Bool
    @ObservedObject var selection: SelectionModel
    let onPreview: () -> Void
    let onAdopt: () -> Void

    var body: some View {
        let selected = selection.ids.contains(track.id)
        HStack(spacing: 12) {
            if selection.active {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
            }
            ArtworkView(track: track, size: 44, cornerRadius: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .fontWeight(previewing ? .semibold : .regular)
                    .foregroundStyle(previewing ? Color.accentColor : Color.primary)
                    .lineLimit(1)
                Text(track.artist.isEmpty ? "Unbekannter Interpret" : track.artist)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if previewing {
                Image(systemName: "waveform")
                    .foregroundStyle(Color.accentColor)
                    .accessibilityLabel("Läuft zum Reinhören")
            }
            Text(track.duration.formattedTime)
                .font(.footnote.monospacedDigit())
                .foregroundStyle(.secondary)
            if !selection.active {
                Button(action: onAdopt) {
                    Image(systemName: "plus.circle.fill").font(.title2)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("„\(track.title)“ in die Titel-Liste übernehmen")
                .accessibilityIdentifier("importedAdd")
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if selection.active { selection.toggle(track.id) } else { onPreview() }
        }
        .accessibilityIdentifier("imported-\(track.title)")
    }
}

/// Leiste unten während der Auswahl: Übernehmen · Löschen
private struct ImportedSelectionBar: View {
    @ObservedObject var selection: SelectionModel
    let all: [Track]
    let onAdopt: ([Track]) -> Void
    let onDelete: ([Track]) -> Void

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
            HStack(spacing: 12) {
                Button { onAdopt(chosen) } label: {
                    Label("Übernehmen", systemImage: "plus.circle.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(chosen.isEmpty)
                .accessibilityIdentifier("importedAdoptSelected")
                Button(role: .destructive) { onDelete(chosen) } label: {
                    Label("Löschen", systemImage: "trash").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(chosen.isEmpty)
                .accessibilityIdentifier("importedDeleteSelected")
            }
        }
        .padding(12)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }
}
