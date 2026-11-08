import SwiftUI

/// Klang: Equalizer (10 Bänder) mit Voreinstellungen und Ein-/Ausblenden zwischen Liedern
struct SoundView: View {
    @EnvironmentObject private var player: AudioPlayer
    @ObservedObject var sound: SoundSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Equalizer", isOn: $sound.enabled)
                        .tint(Color.cyberAccent)
                        .accessibilityIdentifier("eqSwitch")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(SoundPresets.all.map(\.name) + [SoundPresets.custom], id: \.self) { name in
                                Button(name) { sound.choose(name) }
                                    .font(.system(.footnote, design: .monospaced))
                                    .padding(.horizontal, 12).padding(.vertical, 7)
                                    .background(sound.preset == name ? Color.cyberAccent.opacity(0.22) : Color.cyberPanelHigh,
                                                in: RoundedRectangle(cornerRadius: 8))
                                    .foregroundStyle(sound.preset == name ? Color.cyberAccent : .primary)
                                    .buttonStyle(.plain)
                                    .accessibilityIdentifier("preset-\(name)")
                            }
                        }
                    }
                    let gains = sound.currentGains
                    ForEach(Array(SoundPresets.bands.enumerated()), id: \.offset) { i, hz in
                        HStack {
                            Text(hz >= 1000 ? "\(Int(hz / 1000)) kHz" : "\(Int(hz)) Hz")
                                .font(.caption.monospacedDigit()).frame(width: 54, alignment: .leading)
                            Slider(value: Binding(get: { Double(gains[i]) }, set: { sound.setBand(i, Float($0)) }), in: -12...12)
                                .tint(Color.cyberAccent)
                                .disabled(!sound.enabled)
                            Text(String(format: "%+.0f dB", gains[i]))
                                .font(.caption.monospacedDigit()).foregroundStyle(Color.neonCyan).frame(width: 50)
                        }
                    }
                } header: { CyberHeader(text: "Equalizer") }
                .cyberRow()

                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(sound.crossfade == 0 ? "Aus – Lieder beginnen und enden normal"
                             : "Lieder werden am Ende \(Int(sound.crossfade)) s sanft aus- und am Anfang eingeblendet")
                            .font(.footnote).foregroundStyle(.secondary)
                            .accessibilityIdentifier("crossfadeText")
                        Slider(value: $sound.crossfade, in: 0...12, step: 1).tint(Color.cyberAccent)
                            .accessibilityIdentifier("crossfadeSlider")
                    }
                } header: { CyberHeader(text: "Überblenden") }
                .cyberRow()
            }
            .cyberList()
            .navigationTitle("Klang")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Schlafmodus: Minuten eingeben, danach geht die Musik aus
struct SleepTimerView: View {
    @EnvironmentObject private var player: AudioPlayer
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @FocusState private var focused: Bool

    static func parseMinutes(_ text: String) -> Int? {
        guard let m = Int(text.trimmingCharacters(in: .whitespaces)), (1...1440).contains(m) else { return nil }
        return m
    }

    var body: some View {
        let minutes = Self.parseMinutes(text)
        NavigationStack {
            Form {
                if let remaining = player.sleepRemaining {
                    Section {
                        TimelineView(.periodic(from: .now, by: 1)) { _ in
                            Text("Läuft – Musik geht aus in \(Self.format(player.sleepRemaining ?? remaining))")
                                .foregroundStyle(Color.cyberAccent)
                                .accessibilityIdentifier("sleepRemaining")
                        }
                        Button("Timer beenden", role: .destructive) { player.cancelSleepTimer(); dismiss() }
                            .accessibilityIdentifier("sleepCancel")
                    }
                    .cyberRow()
                }
                Section {
                    TextField("Minuten", text: $text)
                        .keyboardType(.numberPad)
                        .focused($focused)
                        .accessibilityIdentifier("sleepMinutes")
                    HStack {
                        ForEach([15, 30, 45, 60], id: \.self) { m in
                            Button("\(m)") { text = "\(m)" }
                                .buttonStyle(.bordered)
                                .tint(minutes == m ? Color.cyberAccent : .gray)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    if !text.isEmpty && minutes == nil {
                        Text("Bitte 1 bis 1440 Minuten eingeben").font(.footnote).foregroundStyle(.red)
                    }
                } header: {
                    Text("Nach wie vielen Minuten soll die Musik ausgehen?")
                } footer: {
                    Text("In den letzten Sekunden wird die Musik sanft leiser.")
                }
                .cyberRow()
            }
            .cyberList()
            .navigationTitle("Schlafmodus")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(player.sleepRemaining == nil ? "Starten" : "Neu starten") {
                        if let minutes { player.startSleepTimer(minutes: minutes); dismiss() }
                    }
                    .disabled(minutes == nil)
                    .accessibilityIdentifier("sleepStart")
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium])
    }

    static func format(_ seconds: TimeInterval) -> String {
        let total = Int(seconds.rounded(.up))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
