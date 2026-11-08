import SwiftUI
import WebKit

struct BrowserView: View {
    @EnvironmentObject private var browser: BrowserModel
    @EnvironmentObject private var downloads: DownloadManager
    @FocusState private var addressFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            addressBar
            if browser.isLoading {
                ProgressView(value: browser.progress)
                    .progressViewStyle(.linear)
                    .frame(height: 2)
            } else {
                Divider()
            }
            if browser.isYouTube {
                youTubeBanner
            }
            WebView(webView: browser.webView)
                .ignoresSafeArea(.keyboard)
            toolbar
        }
        .overlay(alignment: .top) {
            if let notice = browser.notice {
                Text(notice)
                    .font(.footnote)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .shadow(radius: 6)
                    .padding(.top, 60)
                    .padding(.horizontal, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .onTapGesture { browser.notice = nil }
                    .task(id: notice) {
                        try? await Task.sleep(nanoseconds: 3_500_000_000)
                        withAnimation { browser.notice = nil }
                    }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: browser.notice)
        .sheet(isPresented: $browser.showFoundAudio) {
            FoundAudioSheet()
        }
    }

    private var addressBar: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Suchen oder Adresse eingeben", text: $browser.address)
                    .keyboardType(.webSearch)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .focused($addressFocused)
                    .onSubmit { browser.submitAddress() }
                if addressFocused && !browser.address.isEmpty {
                    Button { browser.address = "" } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10, style: .continuous))

            Menu {
                Button { browser.scanForAudio() } label: { Label("Audiodateien auf Seite suchen", systemImage: "waveform") }
                Button { browser.downloadCurrentPage() } label: { Label("Diese Adresse als Audio laden", systemImage: "arrow.down.circle") }
                Button { browser.loadStartPage() } label: { Label("Startseite", systemImage: "house") }
            } label: {
                Image(systemName: "ellipsis.circle").font(.title3)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var youTubeBanner: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "info.circle.fill")
            Text("Downloads von YouTube werden nicht unterstützt – das verbieten die YouTube-Nutzungsbedingungen. Nutze freie Quellen von der Startseite.")
                .font(.footnote)
        }
        .foregroundStyle(.secondary)
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.12))
    }

    private var toolbar: some View {
        HStack {
            Button(action: browser.goBack) { Image(systemName: "chevron.backward") }
                .disabled(!browser.canGoBack)
            Spacer()
            Button(action: browser.goForward) { Image(systemName: "chevron.forward") }
                .disabled(!browser.canGoForward)
            Spacer()
            Button(action: browser.scanForAudio) { Image(systemName: "waveform") }
            Spacer()
            Button(action: browser.loadStartPage) { Image(systemName: "house") }
            Spacer()
            Button(action: browser.reload) { Image(systemName: browser.isLoading ? "xmark" : "arrow.clockwise") }
        }
        .font(.title3)
        .padding(.horizontal, 28)
        .padding(.vertical, 10)
        .background(.bar)
    }
}

struct WebView: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct FoundAudioSheet: View {
    @EnvironmentObject private var browser: BrowserModel
    @EnvironmentObject private var downloads: DownloadManager
    @Environment(\.dismiss) private var dismiss
    @State private var started = Set<URL>()

    var body: some View {
        NavigationStack {
            List(browser.foundAudio, id: \.self) { url in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(url.lastPathComponent.removingPercentEncoding ?? url.lastPathComponent).lineLimit(2)
                        Text(url.host ?? "").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if started.contains(url) {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    } else {
                        Button {
                            downloads.download(from: url)
                            started.insert(url)
                        } label: {
                            Image(systemName: "arrow.down.circle").font(.title2)
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
            .navigationTitle("\(browser.foundAudio.count) Audiodateien")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Fertig") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Alle laden") {
                        for url in browser.foundAudio where !started.contains(url) {
                            downloads.download(from: url)
                            started.insert(url)
                        }
                    }
                    .disabled(started.count == browser.foundAudio.count)
                }
            }
        }
    }
}
