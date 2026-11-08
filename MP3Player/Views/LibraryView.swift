import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var downloads: DownloadManager

    @State private var search = ""
    @State private var showImporter = false
    @State private var showLinkPrompt = false
    @State private var linkText = ""
    @State private var editing: Track?
    @State private var importError: String?
    @State private var pendingDelete: Track?

    private var visibleTracks: [Track] {
        let all = library.sortedTracks
        let q = search.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return all }
        return all.filter { $0.title.localizedCaseInsensitiveContains(q) || $0.artist.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if library.tracks.isEmpty {
                    EmptyStateView(
                        title: "Noch keine Musik",
                        systemImage: "music.note",
                        message: "Importiere MP3-Dateien über ＋ aus der Dateien-App, lade sie im Browser-Tab herunter oder kopiere sie per Finder/iTunes-Dateifreigabe auf dein iPhone."
                    )
                } else {
                    list
                }
            }
            .navigationTitle("Titel")
            .searchable(text: $search, prompt: "Titel oder Interpret")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { showImporter = true } label: { Label("Aus Dateien importieren", systemImage: "folder") }
                        Button { linkText = UIPasteboard.general.string ?? ""; showLinkPrompt = true } label: {
                            Label("Von Link herunterladen", systemImage: "link")
                        }
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Hinzufügen")
                }
            }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.audio, .mp3Audio], allowsMultipleSelection: true) { result in
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
            .alert("Von Link herunterladen", isPresented: $showLinkPrompt) {
                TextField("https://…/lied.mp3", text: $linkText)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Abbrechen", role: .cancel) {}
                Button("Laden") { startLinkDownload() }
            } message: {
                Text("Direkter Link zu einer MP3-/Audiodatei.")
            }
            .alert("Import fehlgeschlagen", isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(importError ?? "")
            }
            .confirmationDialog("Titel löschen?", isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
                                titleVisibility: .visible, presenting: pendingDelete) { track in
                Button("„\(track.title)“ löschen", role: .destructive) { library.delete(track) }
            } message: { _ in
                Text("Die Datei wird vom iPhone entfernt.")
            }
            .sheet(item: $editing) { track in
                EditTrackSheet(track: track)
            }
        }
    }

    private var list: some View {
        let tracks = visibleTracks
        return List {
            if search.isEmpty {
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
            Section {
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    Button {
                        player.play(tracks, startAt: index, allTracks: search.isEmpty)
                    } label: {
                        TrackRow(track: track)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        TrackMenu(track: track, onEdit: { editing = track }, onDelete: { pendingDelete = track })
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { pendingDelete = track } label: { Label("Löschen", systemImage: "trash") }
                        Button { player.addToQueue(track) } label: { Label("Warteschlange", systemImage: "text.append") }
                            .tint(.indigo)
                    }
                }
            } footer: {
                Text("\(tracks.count) Titel")
            }
        }
        .listStyle(.insetGrouped)
    }

    private func startLinkDownload() {
        let text = linkText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: text), url.scheme?.hasPrefix("http") == true else {
            importError = "Ungültiger Link."
            return
        }
        downloads.download(from: url)
    }
}

extension UTType {
    static let mp3Audio = UTType("public.mp3") ?? .audio
}
