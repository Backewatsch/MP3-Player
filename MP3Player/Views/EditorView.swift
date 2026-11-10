import SwiftUI

/// Tab „Bearbeiten“: Lied auswählen und zuschneiden
struct EditorView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @Binding var path: NavigationPath
    @State private var search = ""

    private var shown: [Track] {
        let q = search.trimmingCharacters(in: .whitespaces)
        return library.tracks
            .filter { q.isEmpty || $0.title.localizedCaseInsensitiveContains(q) || $0.artist.localizedCaseInsensitiveContains(q) }
            .sorted { $0.addedAt > $1.addedAt }
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if library.tracks.isEmpty {
                    EmptyStateView(title: "Noch keine Musik", systemImage: "scissors",
                                   message: "Sobald Lieder in der Mediathek sind, kannst du sie hier zuschneiden.")
                } else {
                    List {
                        Section {
                            ForEach(shown) { track in
                                NavigationLink(value: track.id) { TrackRow(track: track) }
                                    .accessibilityIdentifier("edit-\(track.title)")
                            }
                        } footer: {
                            Text("Wähle ein Lied, um es zuzuschneiden – z. B. die letzten Sekunden mit Gerede oder Werbung wegschneiden.")
                        }
                    }
                    .listStyle(.insetGrouped)
                    .searchable(text: $search, prompt: "Lied suchen")
                }
            }
            .accessibilityIdentifier("editorScreen")
            .navigationTitle("Bearbeiten")
            .navigationDestination(for: UUID.self) { id in
                if let track = library.tracks.first(where: { $0.id == id }) {
                    TrimView(track: track)
                }
            }
        }
    }
}

/// Zuschneiden: Anfang und Ende einstellen, vorhören, speichern
struct TrimView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    @Environment(\.dismiss) private var dismiss
    let track: Track

    @State private var duration: Double = 0
    @State private var start: Double = 0
    @State private var end: Double = 0
    /// Ein-/Ausblenden in Sekunden (0 = aus)
    @State private var fadeIn = 0
    @State private var fadeOut = 0
    @State private var busy = false
    @State private var confirmReplace = false
    @State private var message: String?
    @State private var previewTask: Task<Void, Never>?
    /// Wiedergabeliste von vor dem Probehören (wird beim Verlassen wiederhergestellt)
    @State private var savedQueue: AudioPlayer.SavedQueue?

    private var ready: Bool { duration > 0 }
    private var changed: Bool { ready && (start > 0.05 || end < duration - 0.05 || fadeIn > 0 || fadeOut > 0) }

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    ArtworkView(track: track, size: 64, cornerRadius: 10)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title).font(.headline).lineLimit(2)
                        Text(track.artist.isEmpty ? "Unbekannter Interpret" : track.artist)
                            .font(.subheadline).foregroundStyle(.secondary)
                        if ready { Text("Länge: \(duration.formattedTime)").font(.caption).foregroundStyle(.secondary) }
                    }
                }
                .padding(.vertical, 4)
            }

            if !library.canTrim(track) {
                Section {
                    Text("Dieses Lied ist keine MP3 und kann nicht zugeschnitten werden. Wandle es zuerst in den Einstellungen in MP3 um.")
                        .foregroundStyle(.secondary)
                }
            } else if !ready {
                Section { HStack { Spacer(); ProgressView(); Spacer() } }
            } else {
                rangeSection
                quickSection
                fadeSection
                saveSection
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Zuschneiden")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("trimEditor")
        .task {
            guard library.canTrim(track) else { return }
            let d = await library.exactDuration(of: track)
            duration = d
            start = 0
            end = d
        }
        .onDisappear {
            previewTask?.cancel()
            if let saved = savedQueue { player.restoreQueue(saved); savedQueue = nil }
        }
        .confirmationDialog("Original kürzen?", isPresented: $confirmReplace, titleVisibility: .visible) {
            Button("Kürzen", role: .destructive) { save(asCopy: false) }
                .accessibilityIdentifier("trimConfirm")
        } message: {
            Text("„\(track.title)“ wird dauerhaft auf \((end - start).formattedTime) gekürzt. Das Weggeschnittene lässt sich nicht wiederherstellen.")
        }
        .alert("Fertig", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") { message = nil; dismiss() }
        } message: {
            Text(message ?? "")
        }
    }

    // MARK: Bereich

    private var rangeSection: some View {
        Section {
            // Übersicht: was bleibt (Akzentfarbe), was wegfällt (grau)
            GeometryReader { geo in
                let w = geo.size.width
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.systemFill))
                    Capsule().fill(Color.accentColor)
                        .frame(width: max(4, w * (end - start) / duration))
                        .offset(x: w * start / duration)
                }
            }
            .frame(height: 8)
            .padding(.vertical, 8)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text("Bleibt: \(Self.precise(start)) – \(Self.precise(end))  (\((end - start).formattedTime))")
                    .font(.subheadline.weight(.semibold))
                    .accessibilityIdentifier("trimSummary")
                Text(cutText).font(.footnote).foregroundStyle(.secondary)
            }

            edgeRow("Anfang", value: $start, range: 0...max(0, end - 1), id: "trimStart") { preview(from: start, until: start + 5) }
            edgeRow("Ende", value: $end, range: min(duration, start + 1)...duration, id: "trimEnd") { preview(from: end - 5, until: end) }
        } header: {
            Text("Bereich")
        } footer: {
            Text("▶ spielt die Stelle kurz an, damit du den Schnitt genau setzen kannst.")
        }
    }

    private func edgeRow(_ label: String, value: Binding<Double>, range: ClosedRange<Double>, id: String,
                         listen: @escaping () -> Void) -> some View {
        VStack(spacing: 6) {
            HStack {
                Text(label)
                Spacer()
                Text(Self.precise(value.wrappedValue)).font(.body.monospacedDigit()).foregroundStyle(.secondary)
                    .accessibilityIdentifier("\(id)-value")
            }
            Slider(value: value, in: range)
            HStack(spacing: 10) {
                Button("−1 s") { value.wrappedValue = min(max(value.wrappedValue - 1, range.lowerBound), range.upperBound) }
                    .accessibilityIdentifier("\(id)-minus")
                Button("+1 s") { value.wrappedValue = min(max(value.wrappedValue + 1, range.lowerBound), range.upperBound) }
                    .accessibilityIdentifier("\(id)-plus")
                Spacer()
                Button { listen() } label: { Label("Anhören", systemImage: "play.circle.fill") }
                    .accessibilityIdentifier("\(id)-listen")
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        }
        .padding(.vertical, 4)
    }

    private var cutText: String {
        var parts: [String] = []
        if start > 0.05 { parts.append("Anfang \(Self.precise(start))") }
        if end < duration - 0.05 { parts.append("Ende \(Self.precise(duration - end))") }
        return parts.isEmpty ? "Noch nichts weggeschnitten" : "Wird weggeschnitten: " + parts.joined(separator: " · ")
    }

    // MARK: Schnellwahl

    private var quickSection: some View {
        Section("Schnell wegschneiden") {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach([5, 10, 30], id: \.self) { s in
                        chip("Letzte \(s) s", id: "trim-last\(s)") { end = max(start + 1, duration - Double(s)) }
                    }
                    ForEach([5, 10], id: \.self) { s in
                        chip("Erste \(s) s", id: "trim-first\(s)") { start = min(Double(s), end - 1) }
                    }
                    chip("Zurücksetzen", id: "trimReset") { start = 0; end = duration }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func chip(_ title: String, id: String, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
            .accessibilityIdentifier(id)
    }

    // MARK: Ein-/Ausblenden

    private var fadeSection: some View {
        Section {
            fadePicker("Anfang einblenden", selection: $fadeIn, id: "fadeIn")
            fadePicker("Ende ausblenden", selection: $fadeOut, id: "fadeOut")
        } header: {
            Text("Sanft ein- und ausblenden")
        } footer: {
            if fadeIn > 0 || fadeOut > 0 {
                Text("Mit Ein-/Ausblenden wird der Ton neu umgewandelt (192 kbit/s) – das dauert einen Moment.")
            }
        }
    }

    private func fadePicker(_ title: String, selection: Binding<Int>, id: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
            Picker(title, selection: selection) {
                Text("Aus").tag(0)
                Text("1 s").tag(1)
                Text("3 s").tag(3)
                Text("5 s").tag(5)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier(id)
        }
        .padding(.vertical, 2)
    }

    // MARK: Speichern

    private var saveSection: some View {
        Section {
            Button {
                save(asCopy: true)
            } label: {
                Label("Als neues Lied speichern", systemImage: "scissors")
            }
            .disabled(!changed || busy)
            .accessibilityIdentifier("trimSaveCopy")
            Button(role: .destructive) {
                confirmReplace = true
            } label: {
                Label("Original kürzen", systemImage: "scissors.badge.ellipsis")
            }
            .disabled(!changed || busy)
            .accessibilityIdentifier("trimSaveReplace")
            if busy { HStack { Spacer(); ProgressView(); Spacer() } }
        } footer: {
            Text(fadeIn > 0 || fadeOut > 0 ? "Titel, Cover und Songtext bleiben erhalten."
                 : "Verlustfrei: Die Tonqualität bleibt gleich, nichts wird neu umgewandelt.")
        }
    }

    private func save(asCopy: Bool) {
        busy = true
        previewTask?.cancel()
        Task {
            defer { busy = false }
            do {
                let result = try await library.trim(track, start: start, end: end, asCopy: asCopy,
                                                    fadeIn: Double(fadeIn), fadeOut: Double(fadeOut))
                message = asCopy
                    ? "„\(result.title)“ gespeichert (\(result.duration.formattedTime))."
                    : "„\(result.title)“ gekürzt auf \(result.duration.formattedTime)."
            } catch {
                message = "Zuschneiden fehlgeschlagen: \(error.localizedDescription)"
            }
        }
    }

    // MARK: Vorhören

    private func preview(from: Double, until: Double) {
        previewTask?.cancel()
        if player.current?.id != track.id {
            if savedQueue == nil { savedQueue = player.saveQueue() }
            player.play([track], startAt: 0)
        }
        else if !player.isPlaying { player.resume() }
        player.seek(to: max(0, from))
        previewTask = Task { @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 150_000_000)
                guard player.current?.id == track.id, player.isPlaying else { return }
                if player.currentTime >= until { player.pause(); return }
            }
        }
    }

    /// 3:07,4 – Zeit mit Zehntelsekunden
    static func precise(_ seconds: Double) -> String {
        let s = max(0, seconds)
        return s.formattedTime + ",\(Int((s * 10).rounded(.down)) % 10)"
    }
}
