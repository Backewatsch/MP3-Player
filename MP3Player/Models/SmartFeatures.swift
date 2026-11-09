import Foundation

/// Findet doppelte Lieder (gleicher Titel + Interpret, fast gleiche Länge)
enum Duplicates {
    private static let noise = try! NSRegularExpression(
        pattern: "\\((official|offizielles|lyrics?|audio|video|hq|hd|explicit|clean|remaster(ed)?|\\d{4} remaster(ed)?)[^)]*\\)|\\[(official|lyrics?|audio|video|hq|hd|explicit)[^\\]]*\\]|\\(\\d+\\)$",
        options: [.caseInsensitive])

    static func normalize(_ text: String) -> String {
        var s = text.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "de_DE"))
        s = noise.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: " ")
        let parts = s.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }
        return parts.joined(separator: " ")
    }

    static func groups(_ tracks: [Track], tolerance: Double = 3) -> [[Track]] {
        var byName: [String: [Track]] = [:]
        for t in tracks {
            let title = normalize(t.title)
            guard !title.isEmpty else { continue }
            byName[title + "|" + normalize(t.artist), default: []].append(t)
        }
        var result: [[Track]] = []
        for (_, same) in byName where same.count >= 2 {
            var cluster: [Track] = []
            for t in same.sorted(by: { $0.duration < $1.duration }) {
                if let last = cluster.last, t.duration > 0, last.duration > 0, t.duration - last.duration > tolerance {
                    if cluster.count >= 2 { result.append(cluster) }
                    cluster = [t]
                } else {
                    cluster.append(t)
                }
            }
            if cluster.count >= 2 { result.append(cluster) }
        }
        return result.sorted { $0[0].title.localizedCaseInsensitiveCompare($1[0].title) == .orderedAscending }
    }

    /// Welches Lied bleibt: MP3, meistgespielt, in mehr Playlists, Herz, zuerst hinzugefügt
    static func keeper(_ group: [Track], playCounts: [UUID: Int], playlistCount: (Track) -> Int, favorites: Set<UUID>) -> Track {
        func score(_ t: Track) -> (Int, Int, Int, Int) {
            (Storage.isMP3(t.fileName) ? 1 : 0, playCounts[t.id] ?? 0, playlistCount(t), favorites.contains(t.id) ? 1 : 0)
        }
        return group.sorted { a, b in
            let x = score(a), y = score(b)
            if x != y { return x > y }
            return a.addedAt < b.addedAt
        }[0]
    }
}

/// Playlists, die sich selbst füllen
enum SmartPlaylist: String, CaseIterable, Identifiable {
    case recentlyAdded, onRepeat, rediscover, neverPlayed

    var id: String { rawValue }

    var title: String {
        switch self {
        case .recentlyAdded: return "Neu hinzugefügt"
        case .onRepeat: return "Dauerschleife"
        case .rediscover: return "Wiederentdecken"
        case .neverPlayed: return "Noch nie gehört"
        }
    }

    var subtitle: String {
        switch self {
        case .recentlyAdded: return "Deine neuesten Lieder"
        case .onRepeat: return "Was du gerade oft hörst"
        case .rediscover: return "Lange nicht mehr gehört"
        case .neverPlayed: return "Gib ihnen eine Chance"
        }
    }

    func tracks(_ state: LibraryState, now: Date = Date()) -> [Track] {
        let month: TimeInterval = 30 * 24 * 3600
        var lastHeard: [UUID: Date] = [:]
        for e in state.history + state.plays { lastHeard[e.trackID] = max(lastHeard[e.trackID] ?? .distantPast, e.at) }
        switch self {
        case .recentlyAdded:
            let newest = state.tracks.sorted { $0.addedAt > $1.addedAt }
            let recent = newest.filter { now.timeIntervalSince($0.addedAt) <= month }
            return Array((recent.isEmpty ? Array(newest.prefix(25)) : recent).prefix(100))
        case .onRepeat:
            var counts: [UUID: Int] = [:]
            for e in state.plays where now.timeIntervalSince(e.at) <= month { counts[e.trackID, default: 0] += 1 }
            let byID = Dictionary(state.tracks.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            var items: [(Track, Int)] = []
            for (id, n) in counts where n >= 2 { if let t = byID[id] { items.append((t, n)) } }
            items.sort { a, b in a.1 > b.1 }
            return Array(items.prefix(50)).map { $0.0 }
        case .rediscover:
            var items = state.tracks.filter { t in
                guard let d = lastHeard[t.id] else { return false }
                return now.timeIntervalSince(d) > month
            }
            items.sort { a, b in (lastHeard[a.id] ?? .distantPast) < (lastHeard[b.id] ?? .distantPast) }
            return Array(items.prefix(100))
        case .neverPlayed:
            return state.tracks.filter { (state.playCounts[$0.id] ?? 0) == 0 && lastHeard[$0.id] == nil }
                .sorted { $0.addedAt > $1.addedAt }
        }
    }
}

/// Misst die Lautheit (RMS in 100-ms-Blöcken, Stille zählt nicht)
struct LoudnessMeter {
    private let blockSamples: Int
    private var blockSum = 0.0
    private var blockCount = 0
    private var gatedSum = 0.0
    private var gatedBlocks = 0

    static let target = -14.0

    init(sampleRate: Double, channels: Int) {
        blockSamples = max(1, Int(sampleRate / 10)) * max(1, channels)
    }

    mutating func add(_ samples: UnsafeBufferPointer<Float>) {
        for v in samples {
            blockSum += Double(v * v)
            blockCount += 1
            if blockCount == blockSamples { finishBlock() }
        }
    }

    private mutating func finishBlock() {
        let ms = blockSum / Double(max(blockCount, 1))
        if ms > 1e-6 { gatedSum += ms; gatedBlocks += 1 }
        blockSum = 0
        blockCount = 0
    }

    mutating func loudnessDB() -> Double? {
        if blockCount > blockSamples / 2 { finishBlock() }
        guard gatedBlocks > 0 else { return nil }
        return 20 * log10((gatedSum / Double(gatedBlocks)).squareRoot())
    }

    /// Verstärkung in dB (−12 … +6)
    static func gain(for loudness: Double) -> Float { Float(min(6, max(-12, target - loudness))) }
}
