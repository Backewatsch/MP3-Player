import AVFoundation
import Foundation

/// Klang-Voreinstellungen und Lautstärke-Rechnung (rein, getestet).
enum SoundPresets {
    struct Preset {
        let name: String
        /// Stützpunkte Frequenz (Hz) → Verstärkung (dB)
        let curve: [(Float, Float)]
    }

    static let custom = "Eigene"

    /// Mittenfrequenzen der 10 Bänder
    static let bands: [Float] = [32, 64, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000]

    static let all: [Preset] = [
        Preset(name: "Normal", curve: [(60, 0), (14_000, 0)]),
        Preset(name: "Bass-Boost", curve: [(60, 6), (150, 4.5), (400, 1.5), (1_000, 0), (14_000, 0)]),
        Preset(name: "Pop", curve: [(60, -1), (230, 2), (910, 4), (3_600, 2), (14_000, -1)]),
        Preset(name: "Rock", curve: [(60, 5), (230, 3), (910, -1), (3_600, 3), (14_000, 5)]),
        Preset(name: "Hip-Hop", curve: [(60, 5), (230, 4), (910, 0), (3_600, 1), (14_000, 3)]),
        Preset(name: "Elektro", curve: [(60, 5), (230, 2), (910, 0), (3_600, 2), (14_000, 5)]),
        Preset(name: "Gesang", curve: [(60, -2), (230, 0), (910, 3), (3_600, 4), (14_000, 1)]),
        Preset(name: "Klassik", curve: [(60, 4), (230, 2), (910, -1), (3_600, 2), (14_000, 4)]),
        Preset(name: "Höhen", curve: [(60, 0), (910, 0), (3_600, 3), (14_000, 6)]),
    ]

    static func preset(named name: String) -> Preset? { all.first { $0.name == name } }

    /// Verstärkung in dB bei `hz` (linear auf logarithmischer Frequenzachse)
    static func gain(_ preset: Preset, at hz: Float) -> Float {
        let c = preset.curve
        guard let first = c.first, let last = c.last else { return 0 }
        if hz <= first.0 { return first.1 }
        if hz >= last.0 { return last.1 }
        for i in 0..<(c.count - 1) {
            let (f1, g1) = c[i], (f2, g2) = c[i + 1]
            if hz >= f1 && hz <= f2 {
                let t = (log(hz) - log(f1)) / (log(f2) - log(f1))
                return g1 + (g2 - g1) * t
            }
        }
        return 0
    }

    static func gains(_ preset: Preset) -> [Float] { bands.map { gain(preset, at: $0) } }

    /// Lautstärke 0…1 aus Ein-/Ausblenden und Schlafmodus (letzte 10 s leiser werden)
    static func volume(position: Double, duration: Double, fade: Double, sleepRemaining: Double?) -> Float {
        var v: Double = 1
        if fade > 0, duration >= fade * 3 {
            if position < fade { v = min(v, max(0, position) / fade) }
            let remaining = duration - position
            if remaining < fade { v = min(v, max(0, remaining) / fade) }
        }
        if let s = sleepRemaining, s < 10 { v *= max(0.05, s / 10) }
        return Float(min(1, max(0, v)))
    }
}

/// Gespeicherte Klang-Einstellungen; wendet sie auf den Equalizer an.
@MainActor
final class SoundSettings: ObservableObject {
    @Published var enabled: Bool { didSet { save() } }
    @Published var preset: String { didSet { save() } }
    @Published var custom: [Float] { didSet { save() } }
    /// Ein-/Ausblenden zwischen Liedern in Sekunden (0 = aus)
    @Published var crossfade: Double { didSet { save() } }

    private let defaults: UserDefaults
    var onChange: (() -> Void)?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.bool(forKey: "sound.enabled")
        preset = defaults.string(forKey: "sound.preset") ?? "Normal"
        custom = (defaults.array(forKey: "sound.custom") as? [Double])?.map { Float($0) } ?? Array(repeating: 0, count: SoundPresets.bands.count)
        crossfade = defaults.double(forKey: "sound.crossfade")
    }

    /// Pegel der 10 Bänder in dB
    var currentGains: [Float] {
        if preset == SoundPresets.custom {
            return (0..<SoundPresets.bands.count).map { custom.indices.contains($0) ? custom[$0] : 0 }
        }
        return SoundPresets.gains(SoundPresets.preset(named: preset) ?? SoundPresets.all[0])
    }

    func choose(_ name: String) {
        if name == SoundPresets.custom && preset != SoundPresets.custom { custom = currentGains }
        preset = name
        enabled = true
    }

    func setBand(_ index: Int, _ gain: Float) {
        var g = currentGains
        guard g.indices.contains(index) else { return }
        g[index] = gain
        custom = g
        preset = SoundPresets.custom
        enabled = true
    }

    private func save() {
        defaults.set(enabled, forKey: "sound.enabled")
        defaults.set(preset, forKey: "sound.preset")
        defaults.set(custom.map { Double($0) }, forKey: "sound.custom")
        defaults.set(crossfade, forKey: "sound.crossfade")
        onChange?()
    }
}
