import SwiftUI

/// Cyberpunk-Farben (gedämpft, augenschonend) – wie in der Android-App
extension Color {
    /// Haupt-Akzent: Babyblau
    static let cyberAccent = Color(red: 0.537, green: 0.812, blue: 0.941)
    /// Zweiter Akzent: gedämpftes Cyan
    static let neonCyan = Color(red: 0.310, green: 0.686, blue: 0.733)
    /// Gedämpftes Signal-Gelb
    static let neonYellow = Color(red: 0.784, green: 0.722, blue: 0.353)
    static let cyberBlack = Color(red: 0.024, green: 0.031, blue: 0.043)
    static let cyberPanel = Color(red: 0.043, green: 0.059, blue: 0.082)
    static let cyberPanelHigh = Color(red: 0.067, green: 0.086, blue: 0.122)
    static let cyberLine = Color(red: 0.122, green: 0.165, blue: 0.227)
}

/// Dunkler Hintergrund mit feinem Raster und leichtem Leuchten
struct CyberBackground: View {
    var body: some View {
        ZStack {
            Color.cyberBlack
            GeometryReader { geo in
                Path { p in
                    let step: CGFloat = 28
                    var x: CGFloat = 0
                    while x < geo.size.width { p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: geo.size.height)); x += step }
                    var y: CGFloat = 0
                    while y < geo.size.height { p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: geo.size.width, y: y)); y += step }
                }
                .stroke(Color.neonCyan.opacity(0.035), lineWidth: 1)
            }
            RadialGradient(colors: [Color.cyberAccent.opacity(0.10), .clear], center: .topTrailing, startRadius: 10, endRadius: 420)
        }
        .ignoresSafeArea()
    }
}

extension View {
    /// Listen und Formulare im dunklen Cyberpunk-Stil
    func cyberList() -> some View {
        self.scrollContentBackground(.hidden)
            .background(CyberBackground())
    }

    /// Zeilenhintergrund in Listen
    func cyberRow() -> some View { listRowBackground(Color.cyberPanel.opacity(0.92)) }
}

/// Abschnittsüberschrift im Terminal-Stil: „▮ // OFT GESPIELT“
struct CyberHeader: View {
    let text: String
    var body: some View {
        HStack(spacing: 6) {
            Rectangle().fill(Color.cyberAccent).frame(width: 3, height: 13)
            Text("// ").foregroundColor(Color.cyberAccent) + Text(text.uppercased()).foregroundColor(Color.neonCyan)
        }
        .font(.system(.footnote, design: .monospaced).weight(.bold))
    }
}

/// Titel mit leichtem Glitch-Effekt (versetzte Cyan- und Babyblau-Kopie)
struct GlitchText: View {
    let text: String
    var font: Font = .system(.title2, design: .monospaced).weight(.black)

    var body: some View {
        ZStack {
            Text(text).foregroundStyle(Color.neonCyan.opacity(0.55)).offset(x: -1.2)
            Text(text).foregroundStyle(Color.cyberAccent.opacity(0.55)).offset(x: 1.2)
            Text(text).foregroundStyle(Color(red: 0.86, green: 0.90, blue: 0.93))
        }
        .font(font)
    }
}
