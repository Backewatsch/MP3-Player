import SwiftUI
import UIKit

// Apple-Design: Systemfarben, Hell/Dunkel nach Systemeinstellung, Akzentfarbe aus dem Asset-Katalog („AccentColor“).

/// Abschnittsüberschrift wie in den Apple-Apps (groß und fett, z. B. „Zuletzt gehört“)
struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.title3.weight(.bold))
            .foregroundStyle(Color.primary) // fest, nicht abhängig vom grauen Abschnittskopf
            .textCase(nil)
            .padding(.leading, -4)
    }
}

/// Erscheinungsbild der App: wie das iPhone, immer hell oder immer dunkel (Standard: dunkel)
enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark
    static let key = "appearance"
    var id: String { rawValue }
    var label: String {
        switch self { case .system: return "Automatisch"; case .light: return "Hell"; case .dark: return "Dunkel" }
    }
    var interfaceStyle: UIUserInterfaceStyle {
        switch self { case .system: return .unspecified; case .light: return .light; case .dark: return .dark }
    }

    /// Für alle Fenster der App setzen (wirkt auch auf geöffnete Blätter)
    @MainActor func apply() {
        for scene in UIApplication.shared.connectedScenes {
            for window in (scene as? UIWindowScene)?.windows ?? [] { window.overrideUserInterfaceStyle = interfaceStyle }
        }
    }
}
