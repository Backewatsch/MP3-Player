import SwiftUI

struct ArtworkView: View {
    let track: Track?
    var size: CGFloat
    var cornerRadius: CGFloat = 6
    /// Eigene Cover der Lieder statt des App-Logos (Einstellung, Standard: an)
    @AppStorage("showOwnCovers") private var showOwnCovers = true

    var body: some View {
        Group {
            if let track, showOwnCovers || Library.customCoverIDs.contains(track.id), let image = ArtworkCache.shared.image(for: track) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else if showOwnCovers {
                // Kein Cover: grauer Platzhalter mit Note (wie in den Apple-Apps)
                ZStack {
                    LinearGradient(colors: [Color(.systemGray4), Color(.systemGray5)], startPoint: .top, endPoint: .bottom)
                    Image(systemName: "music.note")
                        .font(.system(size: size * 0.38, weight: .medium))
                        .foregroundStyle(Color(.systemGray))
                }
            } else {
                // App-Logo neben jedem Lied
                Image("Logo")
                    .resizable()
                    .scaledToFill()
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).stroke(Color.primary.opacity(0.08), lineWidth: 0.5))
    }
}

struct TrackRow: View {
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var library: Library
    let track: Track

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(track: track, size: 46)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.body.weight(player.current?.id == track.id ? .bold : .regular))
                    .foregroundStyle(player.current?.id == track.id ? Color.accentColor : .primary)
                    .lineLimit(1)
                Text(track.artist.isEmpty ? "Unbekannter Interpret" : track.artist)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if library.isFavorite(track) {
                Image(systemName: "heart.fill")
                    .font(.caption2)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityLabel("Lieblingssong")
            }
            if player.current?.id == track.id {
                Image(systemName: player.isPlaying ? "waveform" : "speaker.fill")
                    .foregroundStyle(Color.accentColor)
                    .font(.footnote)
            }
            Text(track.duration.formattedTime)
                .font(.footnote.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
    }
}

/// Dialog zum Bearbeiten von Titel, Interpret, Album und Genre (wird auch in die MP3 geschrieben)
struct EditTrackSheet: View {
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    let track: Track
    @State private var title = ""
    @State private var artist = ""
    @State private var album = ""
    @State private var genre = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Titel", text: $title)
                    TextField("Interpret", text: $artist)
                    TextField("Album", text: $album)
                    TextField("Genre", text: $genre)
                } footer: {
                    Text("Die Angaben werden auch in die MP3 geschrieben – so stimmen sie auch in anderen Apps.")
                }
                if !library.genreNames.isEmpty {
                    Section("Vorhandene Genres") {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(library.genreNames, id: \.self) { g in
                                    Button(g) { genre = g }
                                        .buttonStyle(.bordered)
                                        .buttonBorderShape(.capsule)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Infos bearbeiten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Sichern") {
                        library.editInfo(track, title: title, artist: artist, album: album, genre: genre)
                        dismiss()
                    }
                    .accessibilityIdentifier("editSave")
                }
            }
            .onAppear {
                let current = library.track(id: track.id) ?? track
                title = current.title
                artist = current.artist
                album = current.album
                genre = current.genre
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Leerer Zustand mit Symbol und Text (iOS 16 kompatibel).
struct EmptyStateView: View {
    let title: String
    let systemImage: String
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text(title).font(.title3.bold())
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
