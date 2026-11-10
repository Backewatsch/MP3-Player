import Foundation

/// Verknüpfungen vom Startbildschirm-Widget in die App (ifmusic://resume, …/shuffle, …/favorites)
enum WidgetLink: String, CaseIterable {
    case resume, shuffle, favorites

    static let scheme = "ifmusic"

    var url: URL { URL(string: "\(Self.scheme)://\(rawValue)")! }

    init?(url: URL) {
        guard url.scheme?.lowercased() == Self.scheme, let host = url.host?.lowercased(), let link = WidgetLink(rawValue: host) else { return nil }
        self = link
    }

    var title: String {
        switch self {
        case .resume: return "Weiterhören"
        case .shuffle: return "Zufällig"
        case .favorites: return "Lieblingssongs"
        }
    }

    var symbol: String {
        switch self {
        case .resume: return "play.fill"
        case .shuffle: return "shuffle"
        case .favorites: return "heart.fill"
        }
    }
}
