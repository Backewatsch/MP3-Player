import AVKit
import MediaPlayer
import SwiftUI

struct MiniPlayerBar: View {
    @EnvironmentObject private var player: AudioPlayer
    @Binding var showNowPlaying: Bool

    var body: some View {
        if let track = player.current {
            HStack(spacing: 12) {
                ArtworkView(track: track, size: 44, cornerRadius: 8)
                    .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                VStack(alignment: .leading, spacing: 1) {
                    Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(track.artist.isEmpty ? "Unbekannter Interpret" : track.artist)
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
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
            .buttonStyle(.plain)
            .padding(.leading, 8)
            .padding(.trailing, 6)
            .padding(.vertical, 6)
            .background(alignment: .bottom) {
                // Feiner Fortschritt am unteren Rand
                GeometryReader { geo in
                    Capsule()
                        .fill(Color.primary.opacity(0.35))
                        .frame(width: player.duration > 0 ? geo.size.width * min(1, player.currentTime / player.duration) : 0, height: 2)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
                .padding(.horizontal, 12)
            }
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color.primary.opacity(0.06), lineWidth: 0.5))
            .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
            .padding(.horizontal, 10)
            .padding(.bottom, 6)
            .contentShape(Rectangle())
            .onTapGesture { showNowPlaying = true }
            // Wischen: links = nächster Titel, rechts = vorheriger
            .gesture(DragGesture(minimumDistance: 30).onEnded { value in
                if value.translation.width < -60 { UIImpactFeedbackGenerator(style: .light).impactOccurred(); player.next() }
                else if value.translation.width > 60 { UIImpactFeedbackGenerator(style: .light).impactOccurred(); player.previous() }
            })
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
    @State private var showSound = false
    @State private var showSleep = false
    @State private var showLyrics = false
    @State private var dragX: CGFloat = 0
    @AppStorage("showOwnCovers") private var ownCovers = true
    @EnvironmentObject private var library: Library

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
                    Button { dismiss() } label: { Image(systemName: "chevron.down").font(.body.weight(.semibold)) }
                        .accessibilityLabel("Schließen")
                }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button { showSound = true } label: { Image(systemName: "slider.vertical.3") }
                        .accessibilityLabel("Klang / Equalizer")
                        .accessibilityIdentifier("soundButton")
                    Button { showSleep = true } label: {
                        HStack(spacing: 3) {
                            if let remaining = player.sleepRemaining {
                                TimelineView(.periodic(from: .now, by: 1)) { _ in
                                    Text(SleepTimerView.format(player.sleepRemaining ?? remaining))
                                        .font(.caption.monospacedDigit())
                                }
                            }
                            Image(systemName: player.sleepRemaining == nil ? "moon" : "moon.fill")
                        }
                        .foregroundStyle(.white)
                    }
                    .accessibilityLabel(player.sleepRemaining == nil ? "Schlafmodus" : "Schlafmodus läuft")
                    .accessibilityIdentifier("sleepButton")
                    if let track = player.current {
                        Button { actions.open(track) } label: { Image(systemName: "ellipsis.circle.fill").symbolRenderingMode(.hierarchical) }
                            .accessibilityLabel("Optionen")
                            .accessibilityIdentifier("nowPlayingMore")
                    }
                }
            }
            .tint(.white)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .background(nowPlayingBackground.ignoresSafeArea())
            .sheet(isPresented: $showQueue) { QueueView() }
            .sheet(isPresented: $showLyrics) { if let t = player.current { LyricsView(track: t) } }
            .sheet(isPresented: $showSound) { SoundView(sound: player.sound) }
            .sheet(isPresented: $showSleep) { SleepTimerView() }
        }
    }

    /// Unscharf vergrößertes Cover hinter dem Player (wie in den Apple-Apps)
    private var nowPlayingBackground: some View {
        let color = ArtworkColor.color(for: player.current, ownCovers: ownCovers)
        return GeometryReader { geo in
            ZStack {
                color
                if let track = player.current {
                    ArtworkView(track: track, size: max(geo.size.width, geo.size.height) * 1.2, cornerRadius: 0)
                        .blur(radius: 70)
                        .opacity(0.9)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                }
                LinearGradient(colors: [.black.opacity(0.15), .black.opacity(0.45)], startPoint: .top, endPoint: .bottom)
            }
        }
        .animation(.easeInOut(duration: 0.6), value: player.current?.id)
    }

    private func content(_ track: Track) -> some View {
        VStack(spacing: 22) {
            Spacer(minLength: 0)
            // Großes Cover: wischen wechselt den Titel, bei Pause etwas kleiner
            ArtworkView(track: track, size: 320, cornerRadius: 14)
                .shadow(color: .black.opacity(player.isPlaying ? 0.45 : 0.25), radius: player.isPlaying ? 28 : 12, y: player.isPlaying ? 16 : 6)
                .scaleEffect(player.isPlaying ? 1 : 0.82)
                .offset(x: dragX * 0.4)
                .animation(.spring(response: 0.45, dampingFraction: 0.72), value: player.isPlaying)
                .gesture(DragGesture(minimumDistance: 20)
                    .onChanged { dragX = $0.translation.width }
                    .onEnded { value in
                        if value.translation.width < -80 { UIImpactFeedbackGenerator(style: .light).impactOccurred(); player.next() }
                        else if value.translation.width > 80 { UIImpactFeedbackGenerator(style: .light).impactOccurred(); player.previous() }
                        withAnimation(.spring()) { dragX = 0 }
                    })
                .frame(maxHeight: 340)

            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title).font(.title3.weight(.semibold)).lineLimit(1)
                    Text(track.artist.isEmpty ? "Unbekannter Interpret" : track.artist)
                        .font(.title3).foregroundStyle(.white.opacity(0.65)).lineLimit(1)
                }
                Spacer()
                // Herz: Lieblingssong
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    library.toggleFavorite(track)
                } label: {
                    Image(systemName: library.isFavorite(track) ? "heart.fill" : "heart")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)
                        .scaleEffect(library.isFavorite(track) ? 1.12 : 1)
                        .animation(.spring(response: 0.3, dampingFraction: 0.4), value: library.isFavorite(track))
                        .frame(width: 34, height: 34)
                        .background(.white.opacity(0.18), in: Circle())
                }
                .accessibilityLabel(library.isFavorite(track) ? "Aus Lieblingssongs entfernen" : "Zu Lieblingssongs")
                .accessibilityIdentifier("favButton")
            }
            .padding(.horizontal, 28)

            VStack(spacing: 6) {
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
                .tint(.white.opacity(0.85))
                HStack {
                    Text((scrubbing ? scrubValue : player.currentTime).formattedTime)
                    Spacer()
                    Text("-" + max(0, player.duration - (scrubbing ? scrubValue : player.currentTime)).formattedTime)
                }
                .font(.caption2.weight(.medium).monospacedDigit())
                .foregroundStyle(.white.opacity(0.6))
            }
            .padding(.horizontal, 28)

            HStack {
                Button { player.shuffle.toggle() } label: {
                    Image(systemName: "shuffle").font(.body.weight(.semibold))
                        .foregroundStyle(player.shuffle ? .white : .white.opacity(0.55))
                        .frame(width: 36, height: 36)
                        .background(player.shuffle ? .white.opacity(0.2) : .clear, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .accessibilityLabel(player.shuffle ? "Zufallswiedergabe an" : "Zufallswiedergabe aus")
                Spacer()
                Button { UIImpactFeedbackGenerator(style: .light).impactOccurred(); player.previous() } label: {
                    Image(systemName: "backward.fill").font(.system(size: 34))
                }
                .accessibilityLabel("Vorheriger Titel")
                Spacer()
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    player.togglePlayPause()
                } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 50))
                        .frame(width: 70, height: 70)
                }
                .accessibilityLabel(player.isPlaying ? "Pause" : "Abspielen")
                .accessibilityIdentifier("bigPlayPause")
                Spacer()
                Button { UIImpactFeedbackGenerator(style: .light).impactOccurred(); player.next() } label: {
                    Image(systemName: "forward.fill").font(.system(size: 34))
                }
                .accessibilityLabel("Nächster Titel")
                Spacer()
                Button(action: player.cycleRepeatMode) {
                    Image(systemName: player.repeatMode.symbol).font(.body.weight(.semibold))
                        .foregroundStyle(player.repeatMode == .off ? .white.opacity(0.55) : .white)
                        .frame(width: 36, height: 36)
                        .background(player.repeatMode == .off ? .clear : .white.opacity(0.2), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .accessibilityLabel(player.repeatMode == .off ? "Wiederholen aus" : player.repeatMode == .all ? "Alle wiederholen" : "Titel wiederholen")
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)

            // Lautstärke (System) wie in den Apple-Apps
            HStack(spacing: 10) {
                Image(systemName: "speaker.fill").font(.caption)
                SystemVolumeSlider().frame(height: 30)
                Image(systemName: "speaker.wave.3.fill").font(.caption)
            }
            .foregroundStyle(.white.opacity(0.6))
            .padding(.horizontal, 28)

            // Untere Leiste: Songtext, AirPlay, Wiedergabeliste
            HStack {
                Button { showLyrics = true } label: {
                    Image(systemName: "quote.bubble").font(.title3).frame(width: 44, height: 44)
                }
                .accessibilityLabel("Songtext")
                .accessibilityIdentifier("lyricsButton")
                Spacer()
                AirPlayButton().frame(width: 44, height: 44)
                    .accessibilityLabel("AirPlay")
                Spacer()
                Button { showQueue = true } label: {
                    Image(systemName: "list.bullet").font(.title3).frame(width: 44, height: 44)
                }
                .accessibilityLabel("Als Nächstes")
                .disabled(player.queue.isEmpty)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white.opacity(0.7))
            .padding(.horizontal, 44)
            Spacer(minLength: 0)
        }
        .foregroundStyle(.white)
        .padding(.bottom, 8)
        .environment(\.colorScheme, .dark)
    }
}

/// Systemlautstärke (MPVolumeView) – nur auf echten Geräten mit Regler
struct SystemVolumeSlider: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: .zero)
        view.showsVolumeSlider = true
        view.tintColor = .white
        return view
    }
    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

/// AirPlay-Knopf (Ausgabe auf Lautsprecher, Kopfhörer, Apple TV …)
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = UIColor.white.withAlphaComponent(0.7)
        view.activeTintColor = .white
        view.prioritizesVideoDevices = false
        return view
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
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
