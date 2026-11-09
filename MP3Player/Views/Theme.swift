import SwiftUI

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
