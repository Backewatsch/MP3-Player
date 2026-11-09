import Foundation

/// Genre-Angaben vereinheitlichen (getestet)
enum Genres {
    /// ID3v1-Genres (alte MP3s speichern oft nur „(17)“ oder „17“)
    static let id3v1 = [
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    ]

    /// „(17)“, „17“, „(17)Rock“, „hip hop“ → „Rock“ bzw. „Hip-Hop“; leer wenn unbekannt
    static func normalize(_ raw: String?) -> String {
        var s = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return "" }
        if s.hasPrefix("("), let close = s.firstIndex(of: ")"), let n = Int(s[s.index(after: s.startIndex)..<close]) {
            let rest = s[s.index(after: close)...].trimmingCharacters(in: .whitespaces)
            s = rest.isEmpty ? (id3v1.indices.contains(n) ? id3v1[n] : "") : rest
        }
        if let n = Int(s) { s = id3v1.indices.contains(n) ? id3v1[n] : "" }
        // Mehrere Genres („Pop; Dance“) → das erste
        s = s.split(whereSeparator: { ";/,".contains($0) }).first.map { String($0).trimmingCharacters(in: .whitespaces) } ?? ""
        if s.isEmpty { return "" }
        let key = s.lowercased().replacingOccurrences(of: "[\\s_-]+", with: "", options: .regularExpression)
        switch key {
        case "hiphop", "hiphoprap": return "Hip-Hop"
        case "rnb", "r&b", "rhythmandblues": return "R&B"
        case "electronic", "electronica", "electro", "edm": return "Electronic"
        case "deutschrap", "germanrap": return "Deutschrap"
        default:
            return s.split(separator: " ").map { w -> String in
                let word = String(w)
                if word.count <= 3, word.allSatisfy({ $0.isUppercase || !$0.isLetter }) { return word }
                return word.prefix(1).uppercased() + word.dropFirst()
            }.joined(separator: " ")
        }
    }
}

/// Suche über Lieder, Interpreten, Alben, Genres und Playlists (getestet)
enum LibrarySearch {
    struct Results {
        var tracks: [Track] = []
        var artists: [String] = []
        var albums: [String] = []
        var genres: [String] = []
        var playlists: [Playlist] = []
        var isEmpty: Bool { tracks.isEmpty && artists.isEmpty && albums.isEmpty && genres.isEmpty && playlists.isEmpty }
    }

    /// Treffer-Güte: 3 = beginnt damit, 2 = Wort beginnt damit, 1 = enthält
    static func score(_ text: String, _ q: String) -> Int {
        let t = Duplicates.normalize(text)
        if q.isEmpty || t.isEmpty { return 0 }
        if t.hasPrefix(q) { return 3 }
        if t.split(separator: " ").contains(where: { $0.hasPrefix(q) }) { return 2 }
        return t.contains(q) ? 1 : 0
    }

    static func run(tracks: [Track], playlists: [Playlist], query: String) -> Results {
        let q = Duplicates.normalize(query)
        guard !q.isEmpty else { return Results() }
        func distinctNames(_ names: [String]) -> [String] {
            var seen = Set<String>()
            return names.filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
                .map { ($0, score($0, q)) }.filter { $0.1 > 0 }
                .sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0.localizedCaseInsensitiveCompare($1.0) == .orderedAscending }
                .map(\.0)
        }
        let scored = tracks.map { t in (t, max(score(t.title, q) * 3, score(t.artist, q) * 2, score(t.album, q), score(t.genre, q))) }
            .filter { $0.1 > 0 }
            .sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0.title.localizedCaseInsensitiveCompare($1.0.title) == .orderedAscending }
        return Results(
            tracks: scored.map(\.0),
            artists: Array(distinctNames(tracks.map(\.artist)).prefix(10)),
            albums: Array(distinctNames(tracks.map(\.album)).prefix(10)),
            genres: Array(distinctNames(tracks.map(\.genre)).prefix(10)),
            playlists: playlists.filter { score($0.name, q) > 0 }.sorted { score($0.name, q) > score($1.name, q) }
        )
    }

    /// Letzte Suchen: neueste zuerst, ohne Doppelte, höchstens `max`
    static func remember(_ recent: [String], _ query: String, max: Int = 8) -> [String] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return recent }
        return Array(([q] + recent.filter { $0.caseInsensitiveCompare(q) != .orderedSame }).prefix(max))
    }
}
