import SwiftUI

/// Start-Tab: Weiterhören, Lieblingssongs, Statistik, Oft gespielt, Zuletzt gehört, Verlauf
struct StartView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @Binding var showNowPlaying: Bool
    @State private var confirmClear = false
    @State private var showAllHistory = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("> SYSTEM ONLINE_").font(.system(.footnote, design: .monospaced)).foregroundStyle(Color.neonYellow)
                        GlitchText(text: "ILLEGAL", font: .system(.largeTitle, design: .monospaced).weight(.black))
                        Text("FREEDOM MUSIC").font(.system(.title, design: .monospaced).weight(.black)).foregroundStyle(Color.cyberAccent)
                        Text("// \(greeting), Runner").font(.system(.footnote, design: .monospaced)).foregroundStyle(Color.neonCyan)
                    }
                    .listRowBackground(Color.clear)
                    .accessibilityIdentifier("startScreen")
                }

                if library.tracks.isEmpty {
                    Section {
                        EmptyStateView(title: "Willkommen!", systemImage: "music.note",
                                       message: "Lade im Tab „Lieder downloaden“ Musik oder importiere Dateien in der Mediathek. Hier erscheinen dann deine meistgehörten Lieder und dein Verlauf.")
                    }
                    .cyberRow()
                } else {
                    content
                }
            }
            .cyberList()
            .navigationBarHidden(true)
            .navigationDestination(for: LibraryRoute.self) { route in
                switch route {
                case .stats: StatsView()
                case .favorites: FavoritesView()
                default: EmptyView()
                }
            }
            .confirmationDialog("Verlauf löschen?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Löschen", role: .destructive) { library.clearHistory() }
            } message: {
                Text("Verlauf und „Oft gespielt“ werden zurückgesetzt. Deine Lieder bleiben erhalten.")
            }
        }
    }

    @ViewBuilder private var content: some View {
        if let current = player.current {
            Section { 
                Button { showNowPlaying = true } label: {
                    HStack(spacing: 12) {
                        ArtworkView(track: current, size: 60, cornerRadius: 10)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(current.title).font(.headline).lineLimit(1)
                            Text(current.artist.isEmpty ? "Unbekannter Interpret" : current.artist).font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: player.isPlaying ? "waveform" : "play.fill").foregroundStyle(Color.cyberAccent)
                    }
                }
                .buttonStyle(.plain)
                .cyberRow()
            } header: { CyberHeader(text: "Weiterhören") }
        }

        let favorites = library.favoriteTracks
        if !favorites.isEmpty {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        ForEach(Array(favorites.prefix(20).enumerated()), id: \.element.id) { index, track in
                            Button { player.play(favorites, startAt: index) } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    ArtworkView(track: track, size: 116, cornerRadius: 12)
                                    Text(track.title).font(.footnote.weight(.semibold)).lineLimit(1)
                                    Text(track.artist.isEmpty ? " " : track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                                .frame(width: 116)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .listRowInsets(EdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0))
                .listRowBackground(Color.clear)
            } header: {
                HStack {
                    CyberHeader(text: "Lieblingssongs")
                    Spacer()
                    Button("Alle abspielen") { player.shuffle = false; player.play(favorites, startAt: 0) }
                        .font(.footnote).foregroundStyle(Color.cyberAccent)
                }
            }
        }

        let stats = library.stats
        Section {
            NavigationLink(value: LibraryRoute.stats) {
                HStack(spacing: 12) {
                    Image(systemName: "chart.bar.fill").foregroundStyle(Color.cyberAccent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Diesen Monat: \(StatsSummary.formatListening(stats.listenedThisMonth)) · \(stats.playsThisMonth) Lieder")
                            .font(.subheadline.weight(.semibold))
                        Text(stats.topThisMonth.first.map { "Dein Nr.-1-Song: \($0.0.title)" } ?? "Top 10, Hörzeit und Top-Interpreten")
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
            .accessibilityIdentifier("startStats")
            .cyberRow()
        } header: { CyberHeader(text: "Deine Statistik") }

        let most = library.mostPlayed(limit: 10)
        Section {
            if most.isEmpty {
                Text("Noch nichts – Lieder, die du öfter hörst (ab 30 Sekunden), erscheinen hier.")
                    .font(.footnote).foregroundStyle(.secondary).cyberRow()
            }
            ForEach(Array(most.enumerated()), id: \.offset) { index, item in
                Button { player.play(most.map { $0.0 }, startAt: index) } label: {
                    HStack(spacing: 10) {
                        Text(String(format: "%02d", index + 1)).font(.system(.body, design: .monospaced).weight(.bold))
                            .foregroundStyle(Color.neonYellow).frame(width: 30)
                        TrackRow(track: item.0)
                        Image(systemName: "flame.fill").foregroundStyle(Color.cyberAccent)
                        Text("\(item.1)×").font(.footnote.weight(.semibold)).foregroundStyle(Color.neonYellow)
                    }
                }
                .buttonStyle(.plain)
                .cyberRow()
            }
        } header: {
            HStack {
                CyberHeader(text: "Oft gespielt")
                Spacer()
                if !most.isEmpty {
                    Button("Alle abspielen") { player.shuffle = false; player.play(most.map { $0.0 }, startAt: 0) }
                        .font(.footnote).foregroundStyle(Color.cyberAccent)
                }
            }
        }

        let recent = library.recentlyPlayed(limit: 15)
        if !recent.isEmpty {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        ForEach(Array(recent.enumerated()), id: \.element.id) { index, track in
                            Button { player.play(recent, startAt: index) } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    ArtworkView(track: track, size: 110, cornerRadius: 12)
                                    Text(track.title).font(.footnote.weight(.semibold)).lineLimit(1)
                                }
                                .frame(width: 110)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .listRowInsets(EdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0))
                .listRowBackground(Color.clear)
            } header: { CyberHeader(text: "Zuletzt gehört") }
        }

        let history = library.history()
        Section {
            if history.isEmpty { Text("Noch leer.").font(.footnote).foregroundStyle(.secondary).cyberRow() }
            ForEach(Array((showAllHistory ? history : Array(history.prefix(15))).enumerated()), id: \.offset) { _, item in
                Button { player.play([item.0], startAt: 0) } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "clock.arrow.circlepath").foregroundStyle(.secondary)
                        ArtworkView(track: item.0, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.0.title).lineLimit(1)
                            Text(item.1, style: .relative).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("history-\(item.0.title)")
                .cyberRow()
            }
            if history.count > 15 && !showAllHistory {
                Button("Ganzen Verlauf anzeigen (\(history.count))") { showAllHistory = true }
                    .foregroundStyle(Color.cyberAccent).cyberRow()
            }
        } header: {
            HStack {
                CyberHeader(text: "Verlauf")
                Spacer()
                if !history.isEmpty { Button("Löschen") { confirmClear = true }.font(.footnote).foregroundStyle(.secondary) }
            }
        }
    }

    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 5...10: return "Guten Morgen"
        case 11...17: return "Hallo"
        case 18...22: return "Guten Abend"
        default: return "Gute Nacht"
        }
    }
}

/// Lieblingssongs (mit Mehrfachauswahl)
struct FavoritesView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @StateObject private var selection = SelectionModel()

    var body: some View {
        let tracks = library.favoriteTracks
        Group {
            if tracks.isEmpty {
                EmptyStateView(title: "Noch keine Lieblingssongs", systemImage: "heart",
                               message: "Tippe im Player oder im Menü eines Liedes auf das Herz – dann erscheint es hier.")
                    .background(CyberBackground())
            } else {
                List {
                    Section {
                        PlayShuffleRow(tracks: tracks)
                    }
                    Section {
                        ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                            TrackListRow(track: track, selection: selection) { player.play(tracks, startAt: index) }
                                .cyberRow()
                        }
                    } footer: {
                        Text("\(tracks.count) Titel · \(tracks.reduce(0) { $0 + $1.duration }.formattedTime)")
                    }
                }
                .cyberList()
            }
        }
        .navigationTitle("Lieblingssongs")
        .toolbar {
            if !tracks.isEmpty {
                Button(selection.active ? "Fertig" : "Auswählen") { if selection.active { selection.clear() } else { selection.start() } }
                    .accessibilityIdentifier("selectButton")
            }
        }
        .safeAreaInset(edge: .top) {
            if selection.active { SelectionBar(selection: selection, all: tracks) }
        }
    }
}

/// „Abspielen“ und „Zufällig“
struct PlayShuffleRow: View {
    @EnvironmentObject private var player: AudioPlayer
    let tracks: [Track]
    var playlistID: UUID? = nil
    var allTracks = false

    var body: some View {
        HStack(spacing: 12) {
            Button {
                player.shuffle = false
                player.play(tracks, startAt: 0, playlistID: playlistID, allTracks: allTracks)
            } label: { Label("Abspielen", systemImage: "play.fill").frame(maxWidth: .infinity) }
                .accessibilityIdentifier("playAllButton")
            Button {
                player.shuffle = true
                player.play(tracks, startAt: Int.random(in: 0..<max(tracks.count, 1)), playlistID: playlistID, allTracks: allTracks)
            } label: { Label("Zufällig", systemImage: "shuffle").frame(maxWidth: .infinity) }
        }
        .buttonStyle(.bordered)
        .tint(Color.cyberAccent)
        .disabled(tracks.isEmpty)
        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
        .listRowBackground(Color.clear)
    }
}

/// Hör-Statistik
struct StatsView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @State private var confirmClear = false

    private var month: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "de_DE")
        f.dateFormat = "LLLL"
        return f.string(from: Date())
    }

    var body: some View {
        let stats = library.stats
        List {
            Section {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    tile("Hörzeit im \(month)", StatsSummary.formatListening(stats.listenedThisMonth), id: "stat-month")
                    tile("Hörzeit gesamt", StatsSummary.formatListening(stats.listenedTotal), id: "stat-total")
                    tile("Gehört im \(month)", "\(stats.playsThisMonth)×", id: "stat-plays")
                    tile("Lieblingssongs", "\(stats.favoriteCount)", id: "stat-favs")
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
            Section {
                if stats.topThisMonth.isEmpty {
                    Text("Noch nichts gehört in diesem Monat. Ein Lied zählt, wenn du es mindestens 30 Sekunden (oder zur Hälfte) hörst.")
                        .font(.footnote).foregroundStyle(.secondary).cyberRow()
                }
                topRows(stats.topThisMonth)
            } header: { CyberHeader(text: "Top 10 im \(month)") }
            if !stats.topArtists.isEmpty {
                Section {
                    let max = stats.topArtists.first?.1 ?? 1
                    ForEach(Array(stats.topArtists.enumerated()), id: \.offset) { index, item in
                        HStack(spacing: 10) {
                            Text(String(format: "%02d", index + 1)).font(.system(.body, design: .monospaced).weight(.bold))
                                .foregroundStyle(Color.neonYellow).frame(width: 30)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.0).lineLimit(1)
                                bar(Double(item.1) / Double(max))
                            }
                            Text("\(item.1)×").font(.footnote.weight(.semibold)).foregroundStyle(Color.neonYellow)
                        }
                        .cyberRow()
                    }
                } header: { CyberHeader(text: "Top-Interpreten") }
            }
            if !stats.topAllTime.isEmpty {
                Section { topRows(stats.topAllTime) } header: { CyberHeader(text: "Top 10 aller Zeiten") }
            }
            Section {
                LabeledContent("Lieder", value: "\(stats.trackCount)").cyberRow()
                LabeledContent("Gesamtdauer", value: stats.libraryDuration.formattedTime).cyberRow()
                LabeledContent("Playlists", value: "\(library.playlists.count)").cyberRow()
                LabeledContent("Gehörte Lieder insgesamt", value: "\(stats.playsTotal)").cyberRow()
            } header: { CyberHeader(text: "Mediathek") }
            Section {
                Button("Statistik zurücksetzen") { confirmClear = true }.foregroundStyle(.secondary).cyberRow()
            }
        }
        .cyberList()
        .navigationTitle("Statistik")
        .confirmationDialog("Statistik zurücksetzen?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Zurücksetzen", role: .destructive) { library.clearStats() }
        } message: {
            Text("Hörzeit und Top-Listen werden gelöscht. Deine Lieder, Playlists und Herzen bleiben.")
        }
    }

    private func tile(_ label: String, _ value: String, id: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value).font(.system(.title2, design: .monospaced).weight(.bold)).foregroundStyle(Color.cyberAccent)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(LinearGradient(colors: [Color.cyberPanelHigh, Color.cyberPanel], startPoint: .top, endPoint: .bottom),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(id)
    }

    @ViewBuilder private func topRows(_ items: [(Track, Int)]) -> some View {
        let max = items.first?.1 ?? 1
        ForEach(Array(items.enumerated()), id: \.offset) { index, item in
            Button { player.play(items.map { $0.0 }, startAt: index) } label: {
                HStack(spacing: 10) {
                    Text(String(format: "%02d", index + 1)).font(.system(.body, design: .monospaced).weight(.bold))
                        .foregroundStyle(Color.neonYellow).frame(width: 30)
                    ArtworkView(track: item.0, size: 40)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.0.title).lineLimit(1)
                        bar(Double(item.1) / Double(max))
                    }
                    Text("\(item.1)×").font(.footnote.weight(.semibold)).foregroundStyle(Color.neonYellow)
                }
            }
            .buttonStyle(.plain)
            .cyberRow()
        }
    }

    private func bar(_ fraction: Double) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle().fill(Color.cyberLine)
                Rectangle().fill(LinearGradient(colors: [.cyberAccent, .neonCyan], startPoint: .leading, endPoint: .trailing))
                    .frame(width: geo.size.width * min(1, max(0.03, fraction)))
            }
        }
        .frame(height: 4)
    }
}
