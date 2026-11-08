import SwiftUI

struct DownloadsView: View {
    @EnvironmentObject private var downloads: DownloadManager
    @State private var linkText = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField("Link zu einer MP3-Datei", text: $linkText)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .onSubmit(start)
                            .accessibilityIdentifier("downloadLinkField")
                        Button("Laden", action: start)
                            .disabled(validURL == nil)
                            .accessibilityIdentifier("downloadStartButton")
                    }
                } footer: {
                    Text("Fertige Downloads landen automatisch in der Bibliothek und in der Playlist „Heruntergeladen“.")
                }

                if downloads.items.isEmpty {
                    Section {
                        Text("Noch keine Downloads").foregroundStyle(.secondary)
                    }
                } else {
                    Section("Verlauf") {
                        ForEach(downloads.items) { item in
                            DownloadRow(item: item)
                        }
                    }
                }
            }
            .navigationTitle("Downloads")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Leeren") { downloads.clearFinished() }
                        .disabled(downloads.items.isEmpty)
                }
            }
            .onAppear { downloads.unseenCount = 0 }
        }
    }

    private var validURL: URL? {
        let text = linkText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: text), url.scheme?.hasPrefix("http") == true, url.host != nil else { return nil }
        return url
    }

    private func start() {
        guard let url = validURL else { return }
        downloads.download(from: url)
        linkText = ""
    }
}

private struct DownloadRow: View {
    @EnvironmentObject private var downloads: DownloadManager
    let item: DownloadManager.Item

    var body: some View {
        HStack(spacing: 12) {
            icon.frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name).lineLimit(1)
                switch item.state {
                case .running:
                    ProgressView(value: item.progress)
                    Text("\(Int(item.progress * 100)) %").font(.caption).foregroundStyle(.secondary)
                case .importing:
                    Text("Wird importiert …").font(.caption).foregroundStyle(.secondary)
                case .finished:
                    Text("In der Playlist „Heruntergeladen“").font(.caption).foregroundStyle(.secondary)
                case .failed(let message):
                    Text(message).font(.caption).foregroundStyle(.red).lineLimit(3)
                }
            }
            if case .failed = item.state, item.source != nil {
                Spacer()
                Button { downloads.retry(item) } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Erneut versuchen")
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder private var icon: some View {
        switch item.state {
        case .running, .importing:
            ProgressView()
        case .finished:
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.title3)
        case .failed:
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange).font(.title3)
        }
    }
}
