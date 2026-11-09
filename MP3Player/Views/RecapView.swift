import SwiftUI

/// „Dein Jahr in Musik“: Rückblick als Folge von Bildschirmen zum Durchtippen
struct RecapView: View {
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    let year: Int

    @State private var recap: Recap.Result?
    @State private var index = 0
    @State private var progress: Double = 0
    @State private var paused = false
    @State private var shareImage: Image?

    private static let slideSeconds = 7.0
    private let ticker = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    var body: some View {
        let slides = recap.map(Self.slides) ?? []
        ZStack {
            if slides.indices.contains(index), let r = recap {
                let slide = slides[index]
                LinearGradient(colors: slide.colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                    .ignoresSafeArea()
                    .animation(.easeInOut(duration: 0.5), value: index)
                slideContent(slide.id, r)
                    .id(index)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                    .padding(.horizontal, 28)
                    .padding(.top, 70)
                    .padding(.bottom, slide.id == "summary" ? 110 : 30)
                    .accessibilityIdentifier("recap-\(slide.id)")
            }
            VStack(spacing: 10) {
                HStack(spacing: 4) {
                    ForEach(slides.indices, id: \.self) { i in
                        GeometryReader { geo in
                            Capsule().fill(.white.opacity(0.3))
                                .overlay(alignment: .leading) {
                                    Capsule().fill(.white)
                                        .frame(width: geo.size.width * (i < index ? 1 : i == index ? (index == slides.count - 1 ? 1 : progress) : 0))
                                }
                        }
                        .frame(height: 3)
                    }
                }
                HStack {
                    Text("DEIN JAHR \(String(year))").font(.caption.weight(.heavy)).tracking(2)
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark").font(.body.weight(.semibold)).frame(width: 36, height: 36)
                            .background(.white.opacity(0.2), in: Circle())
                    }
                    .accessibilityLabel("Schließen")
                    .accessibilityIdentifier("recapClose")
                }
                Spacer()
                if index == slides.count - 1, let r = recap { summaryButtons(r) }
            }
            .padding(.horizontal, 14)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .contentShape(Rectangle())
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in paused = true }
                .onEnded { value in
                    paused = false
                    // Kurzes Tippen: links zurück, sonst weiter
                    guard abs(value.translation.width) < 10, abs(value.translation.height) < 10 else { return }
                    // Oben (Schließen) und unten (Knöpfe) nicht blättern
                    let height = UIScreen.main.bounds.height
                    guard value.location.y > 110, value.location.y < height - 140 else { return }
                    if value.location.x < UIScreen.main.bounds.width / 3 { go(-1, count: slides.count) } else { go(1, count: slides.count) }
                }
        )
        .accessibilityIdentifier("recapStory")
        .onReceive(ticker) { _ in
            guard !paused, !slides.isEmpty, index < slides.count - 1 else { return }
            progress += 0.05 / Self.slideSeconds
            if progress >= 1 { go(1, count: slides.count) }
        }
        .onAppear { recap = library.recap(year: year) }
        .statusBarHidden()
    }

    private func go(_ step: Int, count: Int) {
        let next = min(max(index + step, 0), count - 1)
        guard next != index else { return }
        withAnimation(.easeInOut(duration: 0.35)) { index = next }
        progress = 0
    }

    // MARK: Folien

    private struct Slide { let id: String; let colors: [Color] }

    private static let palette: [[Color]] = [
        [Color(red: 0.06, green: 0.13, blue: 0.15), Color(red: 0.17, green: 0.33, blue: 0.39)],
        [Color(red: 0.23, green: 0.11, blue: 0.44), Color(red: 0.84, green: 0.43, blue: 0.47)],
        [Color(red: 0.07, green: 0.31, blue: 0.37), Color(red: 0.44, green: 0.70, blue: 0.50)],
        [Color(red: 0.70, green: 0.27, blue: 0.57), Color(red: 0.95, green: 0.37, blue: 0.47)],
        [Color(red: 0.04, green: 0.28, blue: 0.42), Color(red: 0.96, green: 0.38, blue: 0.09)],
        [Color(red: 0.25, green: 0.16, blue: 0.35), Color(red: 0.18, green: 0.03, blue: 0.26)],
        [Color(red: 0.11, green: 0.59, blue: 0.42), Color(red: 0.04, green: 0.24, blue: 0.18)],
        [Color(red: 0.16, green: 0.24, blue: 0.53), Color(red: 0.27, green: 0.64, blue: 0.28)],
        [Color(red: 0.56, green: 0.18, blue: 0.89), Color(red: 0.29, green: 0.0, blue: 0.88)],
    ]

    private static func slides(_ r: Recap.Result) -> [Slide] {
        var ids = ["intro", "minutes"]
        if r.firstTrack != nil { ids.append("first") }
        if !r.topTracks.isEmpty { ids.append("topSong") }
        if r.topTracks.count > 1 { ids.append("topSongs") }
        if !r.topArtists.isEmpty { ids.append("topArtist") }
        if r.obsession != nil { ids.append("loop") }
        if r.listenerType != nil { ids.append("type") }
        if r.topMonth != nil { ids.append("months") }
        ids.append("facts")
        var result = ids.enumerated().map { Slide(id: $0.element, colors: palette[$0.offset % palette.count]) }
        result.append(Slide(id: "summary", colors: [Color(red: 0.02, green: 0.03, blue: 0.05), Color(red: 0.11, green: 0.23, blue: 0.29)]))
        return result
    }

    @ViewBuilder private func slideContent(_ id: String, _ r: Recap.Result) -> some View {
        switch id {
        case "intro":
            VStack(spacing: 10) {
                big(String(r.year), size: 88)
                headline("Dein Jahr in Musik")
                body("Tippe rechts für weiter, links für zurück. Gedrückt halten pausiert.").padding(.top, 8)
            }
        case "minutes":
            let minutes = Int(r.listenedSeconds / 60)
            VStack(spacing: 6) {
                body("Du hast dieses Jahr")
                big(Recap.number(minutes))
                headline("Minuten Musik gehört")
                body(minutesText(minutes) + "\n\(Recap.number(r.plays)) Lieder an \(r.daysListened) Tagen.").padding(.top, 12)
            }
        case "first":
            if let t = r.firstTrack {
                VStack(spacing: 14) {
                    body("Damit ging's los")
                    ArtworkView(track: t, size: 190, cornerRadius: 14).shadow(radius: 20, y: 10)
                    headline(t.title)
                    body(t.artist.isEmpty ? "Unbekannter Interpret" : t.artist)
                    if let at = r.firstAt { body("Dein erstes Lied am \(Self.dateText(at))") }
                }
            }
        case "topSong":
            if let top = r.topTracks.first {
                let t = top.0, n = top.1
                VStack(spacing: 14) {
                    body("Dein Song des Jahres")
                    ArtworkView(track: t, size: 240, cornerRadius: 16).shadow(radius: 24, y: 12)
                    headline(t.title)
                    body(t.artist.isEmpty ? "Unbekannter Interpret" : t.artist)
                    Text("\(n) Mal gehört").font(.headline)
                }
            }
        case "topSongs":
            VStack(alignment: .leading, spacing: 14) {
                headline("Deine Top-Songs").frame(maxWidth: .infinity, alignment: .leading)
                ForEach(Array(r.topTracks.enumerated()), id: \.offset) { i, item in
                    HStack(spacing: 12) {
                        Text("\(i + 1)").font(.system(size: 28, weight: .black, design: .rounded)).frame(width: 30)
                        ArtworkView(track: item.0, size: 54, cornerRadius: 8)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.0.title).font(.headline).lineLimit(1)
                            Text((item.0.artist.isEmpty ? "Unbekannt" : item.0.artist) + " · \(item.1)×")
                                .font(.subheadline).opacity(0.75).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                }
            }
        case "topArtist":
            if let top = r.topArtists.first {
                let artist = top.0, n = top.1
                VStack(spacing: 10) {
                    body("Dein Interpret des Jahres")
                    big(artist, size: 46)
                    Text("\(n) Lieder gehört").font(.headline)
                    if r.topArtists.count > 1 {
                        VStack(spacing: 4) {
                            ForEach(Array(r.topArtists.dropFirst().enumerated()), id: \.offset) { i, item in
                                body("\(i + 2). \(item.0) · \(item.1)×")
                            }
                        }
                        .padding(.top, 18)
                    }
                }
            }
        case "loop":
            if let o = r.obsession {
                VStack(spacing: 12) {
                    headline("Dauerschleife!")
                    ArtworkView(track: o.track, size: 150, cornerRadius: 14).shadow(radius: 16, y: 8)
                    body("Am \(o.day.day ?? 1). \(Recap.months[(o.day.month ?? 1) - 1]) lief „\(o.track.title)“")
                    big("\(o.count)×", size: 60)
                    body("an einem einzigen Tag.")
                }
            }
        case "type":
            if let type = r.listenerType {
                VStack(spacing: 10) {
                    body("Du bist " + (type == .night ? "eine" : "ein"))
                    big(type.type, size: 42)
                    body(type.text)
                    let maxCount = max(1, r.dayparts.values.max() ?? 1)
                    VStack(spacing: 8) {
                        ForEach(Recap.Daypart.allCases, id: \.self) { part in
                            HStack {
                                Text(part.label).frame(width: 86, alignment: .leading)
                                GeometryReader { geo in
                                    Capsule().fill(.white.opacity(part == type ? 1 : 0.45))
                                        .frame(width: max(6, geo.size.width * Double(r.dayparts[part] ?? 0) / Double(maxCount)))
                                }
                                .frame(height: 14)
                            }
                        }
                    }
                    .padding(.top, 20)
                }
            }
        case "months":
            if let month = r.topMonth {
                VStack(spacing: 10) {
                    body("Dein stärkster Monat")
                    big(Recap.months[month - 1], size: 44)
                    let useMinutes = r.minutesByMonth.contains { $0 > 0 }
                    let values = useMinutes ? r.minutesByMonth : r.playsByMonth.map(Double.init)
                    let maxValue = max(1, values.max() ?? 1)
                    HStack(alignment: .bottom, spacing: 5) {
                        ForEach(0..<12, id: \.self) { i in
                            VStack(spacing: 4) {
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(.white.opacity(i == month - 1 ? 1 : 0.45))
                                    .frame(height: max(3, 140 * values[i] / maxValue))
                                Text(String(Recap.months[i].prefix(1))).font(.caption2)
                            }
                        }
                    }
                    .frame(height: 170, alignment: .bottom)
                    .padding(.top, 20)
                }
            }
        case "facts":
            VStack(spacing: 20) {
                headline("Noch mehr von dir")
                fact(Recap.number(r.distinctTracks), "verschiedene Lieder von \(r.distinctArtists) Interpreten")
                if r.longestStreak > 1 { fact("\(r.longestStreak) Tage", "am Stück Musik gehört – deine längste Serie") }
                if r.newTracks > 0 { fact("\(r.newTracks)", "neue Lieder in deiner Mediathek") }
                if let w = r.topWeekday { fact(Recap.weekdays[(w - 1) % 7], "ist dein Musik-Tag") }
                if let album = r.topAlbum { fact(album.0, "ist dein Album des Jahres") }
            }
        default:
            RecapSummaryCard(recap: r)
                .onAppear { renderShareImage(r) }
        }
    }

    private func summaryButtons(_ r: Recap.Result) -> some View {
        HStack(spacing: 12) {
            Button {
                withAnimation { index = 0 }
                progress = 0
            } label: {
                Label("Nochmal", systemImage: "arrow.counterclockwise").frame(maxWidth: .infinity, minHeight: 50)
                    .background(.white.opacity(0.18), in: Capsule())
            }
            .accessibilityIdentifier("recapReplay")
            if let shareImage {
                ShareLink(item: shareImage, preview: SharePreview("Mein Jahr \(String(r.year)) in Musik", image: shareImage)) {
                    Label("Teilen", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity, minHeight: 50)
                        .foregroundStyle(.black)
                        .background(.white, in: Capsule())
                }
                .accessibilityIdentifier("recapShare")
            }
        }
        .font(.headline)
        .buttonStyle(.plain)
    }

    /// Zusammenfassung als Bild zum Teilen
    @MainActor private func renderShareImage(_ r: Recap.Result) {
        let renderer = ImageRenderer(content: RecapSummaryCard(recap: r).frame(width: 360))
        renderer.scale = 3
        if let ui = renderer.uiImage { shareImage = Image(uiImage: ui) }
    }

    private func minutesText(_ minutes: Int) -> String {
        if minutes >= 1440 { return String(format: "Das sind %.1f Tage am Stück.", Double(minutes) / 1440).replacingOccurrences(of: ".", with: ",") }
        if minutes >= 120 { return "Das sind \(minutes / 60) Stunden." }
        return "Das ist erst der Anfang!"
    }

    static func dateText(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.day, .month], from: date)
        return "\(c.day ?? 1). \(Recap.months[(c.month ?? 1) - 1])"
    }

    private func big(_ text: String, size: CGFloat = 76) -> some View {
        Text(text).font(.system(size: size, weight: .black, design: .rounded))
            .multilineTextAlignment(.center).lineLimit(3).minimumScaleFactor(0.5)
    }
    private func headline(_ text: String) -> some View {
        Text(text).font(.system(.title, design: .rounded).weight(.heavy)).multilineTextAlignment(.center).lineLimit(3)
    }
    private func body(_ text: String) -> some View {
        Text(text).font(.title3).multilineTextAlignment(.center).opacity(0.9)
    }
    private func fact(_ big: String, _ text: String) -> some View {
        VStack(spacing: 2) {
            Text(big).font(.system(size: 34, weight: .black, design: .rounded)).lineLimit(2).minimumScaleFactor(0.6)
            body(text)
        }
    }
}

/// Zusammenfassung (auch als Bild zum Teilen)
struct RecapSummaryCard: View {
    let recap: Recap.Result

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("MEIN JAHR \(String(recap.year)) IN MUSIK").font(.subheadline.weight(.black)).tracking(1.5)
            if let t = recap.topTracks.first?.0 {
                HStack(spacing: 14) {
                    ArtworkView(track: t, size: 84, cornerRadius: 10)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Song des Jahres").font(.caption).opacity(0.75)
                        Text(t.title).font(.title3.weight(.bold)).lineLimit(2)
                        Text(t.artist).font(.subheadline).opacity(0.85).lineLimit(1)
                    }
                }
            }
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Top-Songs").font(.caption).opacity(0.75)
                    ForEach(Array(recap.topTracks.enumerated()), id: \.offset) { i, item in
                        Text("\(i + 1). \(item.0.title)").font(.footnote).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Top-Interpreten").font(.caption).opacity(0.75)
                    ForEach(Array(recap.topArtists.enumerated()), id: \.offset) { i, item in
                        Text("\(i + 1). \(item.0)").font(.footnote).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Hörzeit").font(.caption).opacity(0.75)
                    Text("\(Recap.number(Int(recap.listenedSeconds / 60))) Min.").font(.title2.weight(.black))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Hörer-Typ").font(.caption).opacity(0.75)
                    Text(recap.listenerType?.type ?? "–").font(.title2.weight(.black)).lineLimit(1).minimumScaleFactor(0.6)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Text("ILLEGAL FREEDOM MUSIC").font(.caption2).tracking(2).opacity(0.6)
        }
        .foregroundStyle(.white)
        .padding(22)
        .background(LinearGradient(colors: [Color(red: 0.17, green: 0.33, blue: 0.39), Color(red: 0.23, green: 0.11, blue: 0.44), Color(red: 0.84, green: 0.43, blue: 0.47)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing),
                    in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityIdentifier("recapSummaryCard")
    }
}

/// Auffällige Karte „Dein Jahr in Musik“ (Start und Statistik)
struct RecapBanner: View {
    let year: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("DEIN JAHR \(String(year))").font(.caption.weight(.heavy)).tracking(2)
                    Text("Dein Rückblick in Musik ist da").font(.headline)
                    Text("Top-Songs, Hörzeit, dein Hörer-Typ und mehr").font(.footnote).opacity(0.85)
                }
                Spacer()
                Image(systemName: "play.fill").font(.title3).foregroundStyle(.black)
                    .frame(width: 44, height: 44).background(.white, in: Circle())
            }
            .foregroundStyle(.white)
            .padding(16)
            .background(LinearGradient(colors: [Color(red: 0.23, green: 0.11, blue: 0.44), Color(red: 0.84, green: 0.43, blue: 0.47), Color(red: 1, green: 0.69, blue: 0.48)],
                                       startPoint: .leading, endPoint: .trailing),
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("recapBanner-\(year)")
    }
}

/// Jahr für `.fullScreenCover(item:)`
struct RecapYear: Identifiable { let id: Int }
