import SwiftUI

struct ArtworkView: View {
    let track: Track?
    var size: CGFloat
    var cornerRadius: CGFloat = 6

    var body: some View {
        Group {
            if let track, let image = ArtworkCache.shared.image(for: track) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                ZStack {
                    LinearGradient(colors: [Color.accentColor.opacity(0.85), Color.purple.opacity(0.7)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing)
                    Image(systemName: "music.note")
                        .font(.system(size: size * 0.42, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.9))
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}

struct TrackRow: View {
    @EnvironmentObject private var player: AudioPlayer
    let track: Track

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(track: track, size: 46)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.body)
                    .foregroundStyle(player.current?.id == track.id ? Color.accentColor : .primary)
                    .lineLimit(1)
                Text(track.artist.isEmpty ? "Unbekannter Interpret" : track.artist)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if player.current?.id == track.id {
                Image(systemName: player.isPlaying ? "speaker.wave.2.fill" : "speaker.fill")
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

/// Kontextmenü für einen Titel (als Nächstes, Warteschlange, Playlist, Bearbeiten, Löschen).
struct TrackMenu: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var player: AudioPlayer
    let track: Track
    var onEdit: () -> Void
    var onDelete: () -> Void
    var removeFromPlaylist: (() -> Void)?

    var body: some View {
        Button { player.playNext(track) } label: { Label("Als Nächstes spielen", systemImage: "text.insert") }
        Button { player.addToQueue(track) } label: { Label("Zur Warteschlange", systemImage: "text.append") }
        Menu {
            ForEach(library.playlists) { playlist in
                Button(playlist.name) { library.add(track, to: playlist) }
            }
        } label: {
            Label("Zu Playlist hinzufügen", systemImage: "music.note.list")
        }
        Button { onEdit() } label: { Label("Infos bearbeiten", systemImage: "pencil") }
        if let removeFromPlaylist {
            Button { removeFromPlaylist() } label: { Label("Aus Playlist entfernen", systemImage: "minus.circle") }
        }
        Button(role: .destructive) { onDelete() } label: { Label("Löschen", systemImage: "trash") }
    }
}

/// Dialog zum Bearbeiten von Titel und Interpret.
struct EditTrackSheet: View {
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    let track: Track
    @State private var title = ""
    @State private var artist = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Titel", text: $title)
                TextField("Interpret", text: $artist)
            }
            .navigationTitle("Infos bearbeiten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Sichern") {
                        library.rename(track, title: title, artist: artist)
                        dismiss()
                    }
                }
            }
            .onAppear {
                title = track.title
                artist = track.artist
            }
        }
        .presentationDetents([.medium])
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
