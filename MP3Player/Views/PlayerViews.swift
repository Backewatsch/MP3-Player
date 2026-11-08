import SwiftUI

struct MiniPlayerBar: View {
    @EnvironmentObject private var player: AudioPlayer
    @Binding var showNowPlaying: Bool

    var body: some View {
        if let track = player.current {
            VStack(spacing: 0) {
                GeometryReader { geo in
                    Rectangle()
                        .fill(Color.accentColor)
                        .frame(width: player.duration > 0 ? geo.size.width * min(1, player.currentTime / player.duration) : 0)
                }
                .frame(height: 2)

                HStack(spacing: 12) {
                    ArtworkView(track: track, size: 40)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        if !track.artist.isEmpty {
                            Text(track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    Spacer()
                    Button(action: player.togglePlayPause) {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.title2)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(player.isPlaying ? "Pause" : "Abspielen")
                    .accessibilityIdentifier("miniPlayPause")
                    Button(action: player.next) {
                        Image(systemName: "forward.fill")
                            .font(.title3)
                            .frame(width: 40, height: 44)
                    }
                    .accessibilityLabel("Nächster Titel")
                }
                .foregroundStyle(.primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
            }
            .background(.regularMaterial)
            .contentShape(Rectangle())
            .onTapGesture { showNowPlaying = true }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("miniPlayer")
        }
    }
}

struct NowPlayingView: View {
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var actions: TrackActions
    @Environment(\.dismiss) private var dismiss
    @State private var scrubbing = false
    @State private var scrubValue: Double = 0
    @State private var showQueue = false

    var body: some View {
        NavigationStack {
            Group {
                if let track = player.current {
                    content(track)
                } else {
                    EmptyStateView(title: "Nichts wird abgespielt", systemImage: "music.note", message: "Wähle einen Titel in der Bibliothek.")
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "chevron.down") }
                        .accessibilityLabel("Schließen")
                }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button { showQueue = true } label: { Image(systemName: "list.bullet") }
                        .accessibilityLabel("Wiedergabeliste")
                        .disabled(player.queue.isEmpty)
                    if let track = player.current {
                        Button { actions.open(track) } label: { Image(systemName: "ellipsis") }
                            .accessibilityLabel("Optionen")
                            .accessibilityIdentifier("nowPlayingMore")
                    }
                }
            }
            .background(nowPlayingBackground.ignoresSafeArea())
            .sheet(isPresented: $showQueue) { QueueView() }
        }
    }

    /// Dunkler Verlauf mit der Akzentfarbe hinter dem Player
    private var nowPlayingBackground: some View {
        LinearGradient(colors: [Color.accentColor.opacity(0.45), Color.purple.opacity(0.25), Color.black],
                       startPoint: .top, endPoint: .bottom)
    }

    private func content(_ track: Track) -> some View {
        VStack(spacing: 24) {
            Spacer(minLength: 0)
            ArtworkView(track: track, size: 300, cornerRadius: 16)
                .shadow(color: .black.opacity(0.25), radius: 18, y: 8)
                .scaleEffect(player.isPlaying ? 1 : 0.88)
                .animation(.spring(response: 0.4, dampingFraction: 0.7), value: player.isPlaying)

            VStack(spacing: 4) {
                Text(track.title).font(.title2.bold()).lineLimit(2).multilineTextAlignment(.center)
                Text(track.artist.isEmpty ? "Unbekannter Interpret" : track.artist)
                    .font(.title3).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.horizontal)

            VStack(spacing: 4) {
                Slider(
                    value: Binding(
                        get: { scrubbing ? scrubValue : player.currentTime },
                        set: { scrubValue = $0 }
                    ),
                    in: 0...max(player.duration, 1),
                    onEditingChanged: { editing in
                        if editing {
                            scrubValue = player.currentTime
                            scrubbing = true
                        } else {
                            player.seek(to: scrubValue)
                            scrubbing = false
                        }
                    }
                )
                HStack {
                    Text((scrubbing ? scrubValue : player.currentTime).formattedTime)
                    Spacer()
                    Text("-" + max(0, player.duration - (scrubbing ? scrubValue : player.currentTime)).formattedTime)
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 28)

            HStack(spacing: 36) {
                Button { player.shuffle.toggle() } label: {
                    Image(systemName: "shuffle").font(.title3)
                        .foregroundStyle(player.shuffle ? Color.accentColor : .secondary)
                }
                .accessibilityLabel(player.shuffle ? "Zufallswiedergabe an" : "Zufallswiedergabe aus")
                Button(action: player.previous) { Image(systemName: "backward.fill").font(.title) }
                    .accessibilityLabel("Vorheriger Titel")
                Button(action: player.togglePlayPause) {
                    Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 72))
                }
                .accessibilityLabel(player.isPlaying ? "Pause" : "Abspielen")
                .accessibilityIdentifier("bigPlayPause")
                Button(action: player.next) { Image(systemName: "forward.fill").font(.title) }
                    .accessibilityLabel("Nächster Titel")
                Button(action: player.cycleRepeatMode) {
                    Image(systemName: player.repeatMode.symbol).font(.title3)
                        .foregroundStyle(player.repeatMode == .off ? .secondary : Color.accentColor)
                }
                .accessibilityLabel(player.repeatMode == .off ? "Wiederholen aus" : player.repeatMode == .all ? "Alle wiederholen" : "Titel wiederholen")
            }
            .foregroundStyle(.primary)
            Spacer(minLength: 0)
        }
        .padding(.bottom, 24)
    }
}

struct QueueView: View {
    @EnvironmentObject private var player: AudioPlayer
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(player.queue.enumerated()), id: \.offset) { index, track in
                    Button { player.play(queueIndex: index) } label: { TrackRow(track: track) }
                        .buttonStyle(.plain)
                        .deleteDisabled(index == player.index)
                }
                .onDelete { player.removeFromQueue(at: $0) }
                .onMove { player.moveInQueue(from: $0, to: $1) }
            }
            .navigationTitle("Wiedergabeliste")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { EditButton() }
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
            }
        }
    }
}
