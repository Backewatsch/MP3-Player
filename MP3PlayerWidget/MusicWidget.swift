import SwiftUI
import WidgetKit

// Startbildschirm-Widget: Schnellstart für Weiterhören, Zufällig und Lieblingssongs.
// Das Widget braucht keine gemeinsamen Daten mit der App – so funktioniert es auch bei selbst installierten Apps.

struct MusicEntry: TimelineEntry {
    let date: Date
}

struct MusicProvider: TimelineProvider {
    func placeholder(in context: Context) -> MusicEntry { MusicEntry(date: Date()) }
    func getSnapshot(in context: Context, completion: @escaping (MusicEntry) -> Void) { completion(MusicEntry(date: Date())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<MusicEntry>) -> Void) {
        completion(Timeline(entries: [MusicEntry(date: Date())], policy: .never))
    }
}

struct MusicWidgetView: View {
    @Environment(\.widgetFamily) private var family

    private var gradient: LinearGradient {
        LinearGradient(colors: [Color(red: 0.23, green: 0.11, blue: 0.44), Color(red: 1.0, green: 0.22, blue: 0.37)],
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "play.fill").font(.title2)
            }
            .widgetURL(WidgetLink.resume.url)
            .widgetBackground(Color.clear)
        case .systemMedium:
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Image(systemName: "music.note").font(.title2.weight(.bold))
                    Spacer(minLength: 0)
                    Text("ILLEGAL\nFREEDOM\nMUSIC").font(.caption.weight(.heavy)).lineSpacing(1)
                }
                .frame(maxHeight: .infinity, alignment: .leading)
                Spacer(minLength: 0)
                ForEach(WidgetLink.allCases, id: \.self) { link in
                    Link(destination: link.url) {
                        VStack(spacing: 8) {
                            Image(systemName: link.symbol).font(.title3.weight(.semibold))
                                .frame(width: 48, height: 48)
                                .background(.white.opacity(0.22), in: Circle())
                            Text(link.title).font(.caption2.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
                        }
                        .frame(width: 72)
                    }
                }
            }
            .foregroundStyle(.white)
            .widgetBackground(gradient)
        default:
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: "music.note").font(.title3.weight(.bold))
                Spacer(minLength: 0)
                Image(systemName: "play.fill").font(.title.weight(.bold))
                    .frame(width: 54, height: 54)
                    .background(.white.opacity(0.22), in: Circle())
                Text("Weiterhören").font(.footnote.weight(.bold))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .foregroundStyle(.white)
            .widgetURL(WidgetLink.resume.url)
            .widgetBackground(gradient)
        }
    }
}

extension View {
    /// Hintergrund passend zur iOS-Version (ab iOS 17 als Container-Hintergrund)
    @ViewBuilder func widgetBackground<S: ShapeStyle>(_ style: S) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            containerBackground(style, for: .widget)
        } else {
            padding().background(Rectangle().fill(style))
        }
    }
}

@main
struct MusicWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "MusicWidget", provider: MusicProvider()) { _ in
            MusicWidgetView()
        }
        .configurationDisplayName("ILLEGAL FREEDOM MUSIC")
        .description("Musik mit einem Tipp starten: Weiterhören, Zufällig oder Lieblingssongs.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular])
    }
}
