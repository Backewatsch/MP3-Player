import SwiftUI

/// Info & Updates
struct SettingsView: View {
    @EnvironmentObject private var updates: UpdateChecker
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var copied = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        Image(systemName: "music.note")
                            .font(.title.weight(.semibold))
                            .foregroundStyle(.white)
                            .frame(width: 56, height: 56)
                            .background(LinearGradient(colors: [Color.accentColor, .purple],
                                                       startPoint: .topLeading, endPoint: .bottomTrailing),
                                        in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text("MP3 Player").font(.headline)
                            Text("Version \(updates.currentVersion) (Build \(updates.currentBuild))")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .accessibilityIdentifier("currentVersion")
                            Text("\(library.tracks.count) Titel · \(library.playlists.count) Playlists")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section("Updates") {
                    statusRow
                    if let release = updates.availableRelease {
                        if !release.notes.isEmpty {
                            Text(release.notes)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(8)
                        }
                        if let ipa = release.ipaURL, let deepLink = UpdateChecker.altStoreInstallURL(for: ipa) {
                            Button {
                                openURL(deepLink)
                            } label: {
                                Label("Mit AltStore installieren", systemImage: "arrow.down.app")
                            }
                        }
                        if let ipa = release.ipaURL {
                            Button { openURL(ipa) } label: {
                                Label("IPA herunterladen (Sideloadly/SideStore)", systemImage: "square.and.arrow.down")
                            }
                        }
                        Button { openURL(release.pageURL) } label: {
                            Label("Release auf GitHub ansehen", systemImage: "safari")
                        }
                    }
                    Button {
                        Task { await updates.check() }
                    } label: {
                        Label("Jetzt nach Updates suchen", systemImage: "arrow.clockwise")
                    }
                    .disabled(updates.state == .checking)
                    .accessibilityIdentifier("checkUpdatesButton")
                }

                Section {
                    if let addSource = UpdateChecker.altStoreAddSourceURL() {
                        Button { openURL(addSource) } label: {
                            Label("Als Quelle zu AltStore hinzufügen", systemImage: "plus.app")
                        }
                    }
                    Button {
                        UIPasteboard.general.string = UpdateChecker.sourceURL.absoluteString
                        copied = true
                    } label: {
                        Label(copied ? "Quellen-Link kopiert ✓" : "Quellen-Link kopieren (für SideStore)",
                              systemImage: "doc.on.doc")
                    }
                } header: {
                    Text("Automatische Updates")
                } footer: {
                    Text("Wenn du die Quelle in AltStore oder SideStore hinzufügst, zeigen diese Apps neue Versionen automatisch an und installieren sie mit einem Tipp. Deine Musik bleibt bei einem Update erhalten.")
                }
            }
            .navigationTitle("Info & Updates")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
            }
            .task {
                if updates.state == .idle { await updates.check() }
            }
        }
    }

    @ViewBuilder private var statusRow: some View {
        switch updates.state {
        case .idle:
            Text("Noch nicht geprüft").foregroundStyle(.secondary)
        case .checking:
            HStack(spacing: 10) {
                ProgressView()
                Text("Suche nach Updates …")
            }
        case .upToDate:
            Label("Du hast die neueste Version.", systemImage: "checkmark.seal.fill")
                .foregroundStyle(.green)
        case .available(let release):
            Label("Update verfügbar: Version \(release.version)", systemImage: "sparkles")
                .foregroundStyle(Color.accentColor)
                .font(.body.weight(.semibold))
        case .noReleases:
            Label("Noch keine Version veröffentlicht.", systemImage: "clock")
                .foregroundStyle(.secondary)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle")
                .foregroundStyle(.orange)
        }
    }
}

/// Hinweis-Leiste in der Bibliothek, wenn ein Update bereitsteht.
struct UpdateBanner: View {
    @EnvironmentObject private var updates: UpdateChecker
    var onTap: () -> Void

    var body: some View {
        if let release = updates.availableRelease {
            Button(action: onTap) {
                HStack(spacing: 10) {
                    Image(systemName: "sparkles")
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Update verfügbar").font(.subheadline.weight(.semibold))
                        Text("Version \(release.version) – tippen zum Installieren").font(.caption)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                }
                .foregroundStyle(.white)
                .padding(12)
                .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("updateBanner")
        }
    }
}
