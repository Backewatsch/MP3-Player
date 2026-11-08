import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var downloads: DownloadManager
    @EnvironmentObject private var actions: TrackActions

    @State private var search = ""
    @State private var showImporter = false
    @State private var showLinkPrompt = false
    @State private var linkText = ""
    @State private var importError: String?
    @State private var showSettings = false

    private var visibleTracks: [Track] {
        let all = library.sortedTracks
        let q = search.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return all }
        return all.filter { $0.title.localizedCaseInsensitiveContains(q) || $0.artist.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                UpdateBanner { showSettings = true }
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
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Info & Updates")
                        .accessibilityIdentifier("settingsButton")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { showImporter = true } label: { Label("Aus Dateien importieren / Video umwandeln", systemImage: "folder") }
                        Button { linkText = UIPasteboard.general.string ?? ""; showLinkPrompt = true } label: {
                            Label("Von Link herunterladen", systemImage: "link")
                        }
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Hinzufügen")
                }
            }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.audio, .mp3Audio, .movie, .mpeg4Movie, .quickTimeMovie], allowsMultipleSelection: true) { result in
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
            .sheet(isPresented: $showSettings) {
                SettingsView()
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
            if search.isEmpty && !library.pinnedTracks.isEmpty {
                Section("Kurzwahl") {
                    PinnedStrip(tracks: library.pinnedTracks)
                        .listRowInsets(EdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0))
                }
            }
            Section {
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    TrackListRow(track: track) {
                        player.play(tracks, startAt: index, allTracks: search.isEmpty)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { actions.requestDelete(track) } label: { Label("Entfernen", systemImage: "trash") }
                        Button { player.addToQueue(track) } label: { Label("Wiedergabeliste", systemImage: "text.append") }
                            .tint(.indigo)
                    }
                    .swipeActions(edge: .leading) {
                        Button { player.playNext(track) } label: { Label("Als Nächstes", systemImage: "text.line.first.and.arrowtriangle.forward") }
                            .tint(.accentColor)
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
