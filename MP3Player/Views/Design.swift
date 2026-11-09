import CoreImage
import SwiftUI
import UIKit

/// Leitfarbe eines Covers (für Player- und Listen-Hintergründe), zwischengespeichert
enum ArtworkColor {
    private static var cache: [String: UIColor] = [:]
    private static let context = CIContext(options: [.workingColorSpace: NSNull()])

    @MainActor
    static func color(for track: Track?, ownCovers: Bool) -> Color {
        let custom = track.map { ownCovers || Library.customCoverIDs.contains($0.id) } ?? false
        let key = custom ? (track?.id.uuidString ?? "-") : "logo"
        if let c = cache[key] { return Color(c) }
        let image = (custom ? track.flatMap { ArtworkCache.shared.image(for: $0) } : nil) ?? UIImage(named: "Logo")
        let c = image.flatMap(average) ?? UIColor(red: 0.12, green: 0.16, blue: 0.23, alpha: 1)
        cache[key] = c
        return Color(c)
    }

    static func invalidate(_ id: UUID) { cache[id.uuidString] = nil }

    /// Durchschnittsfarbe, kräftiger und dunkler gemacht (weiße Schrift bleibt lesbar)
    private static func average(_ image: UIImage) -> UIColor? {
        guard let input = CIImage(image: image) else { return nil }
        let filter = CIFilter(name: "CIAreaAverage", parameters: [kCIInputImageKey: input, kCIInputExtentKey: CIVector(cgRect: input.extent)])
        guard let output = filter?.outputImage else { return nil }
        var px = [UInt8](repeating: 0, count: 4)
        context.render(output, toBitmap: &px, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1), format: .RGBA8, colorSpace: nil)
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(red: CGFloat(px[0]) / 255, green: CGFloat(px[1]) / 255, blue: CGFloat(px[2]) / 255, alpha: 1)
            .getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        return UIColor(hue: h, saturation: min(1, s * 1.4 + 0.1), brightness: min(0.55, max(0.25, b)), alpha: 1)
    }
}

/// Drei hüpfende Balken: „läuft gerade“
struct PlayingBars: View {
    let playing: Bool
    @State private var phase = false

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<3, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(Color.accentColor)
                    .frame(width: 3, height: playing ? (phase ? [12, 6, 14][i] : [5, 13, 7][i]) : 5)
            }
        }
        .frame(width: 15, height: 15, alignment: .bottom)
        .animation(playing ? .easeInOut(duration: 0.42).repeatForever(autoreverses: true) : .default, value: phase)
        .onAppear { phase = true }
    }
}

/// Großer runder Abspiel-Knopf
struct RoundPlayButton: View {
    let playing: Bool
    var size: CGFloat = 56
    let action: () -> Void

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            action()
        } label: {
            Image(systemName: playing ? "pause.fill" : "play.fill")
                .font(.system(size: size * 0.4, weight: .bold))
                .foregroundStyle(.black)
                .frame(width: size, height: size)
                .background(Color.accentColor, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(playing ? "Pause" : "Abspielen")
    }
}

/// Kopf einer Sammlung (Playlist, Album, Mix) wie in Streaming-Apps
struct CollectionHeader<Art: View>: View {
    @EnvironmentObject private var player: AudioPlayer
    let title: String
    let subtitle: String
    let color: Color
    let tracks: [Track]
    var playlistID: UUID? = nil
    @ViewBuilder let art: () -> Art

    var body: some View {
        let playing = player.isPlaying && tracks.contains { $0.id == player.current?.id }
        VStack(spacing: 14) {
            art()
                .shadow(color: .black.opacity(0.25), radius: 16, y: 8)
            VStack(spacing: 3) {
                Text(title).font(.title2.weight(.bold)).lineLimit(2).multilineTextAlignment(.center)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
            }
            // Zwei Knöpfe wie in den Apple-Apps: Abspielen und Zufällig
            HStack(spacing: 12) {
                Button {
                    if playing { player.pause() } else {
                        player.shuffle = false
                        if !tracks.isEmpty { player.play(tracks, startAt: 0, playlistID: playlistID) }
                    }
                } label: {
                    Label(playing ? "Pause" : "Abspielen", systemImage: playing ? "pause.fill" : "play.fill")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .accessibilityIdentifier("playAllButton")
                Button {
                    player.shuffle = true
                    if !tracks.isEmpty { player.play(tracks, startAt: Int.random(in: 0..<tracks.count), playlistID: playlistID) }
                } label: {
                    Label("Zufällig", systemImage: "shuffle")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.accentColor)
            .disabled(tracks.isEmpty)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
}
