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
            var hits: [(name: String, score: Int)] = []
            for name in names where !name.isEmpty && seen.insert(name.lowercased()).inserted {
                let sc = score(name, q)
                if sc > 0 { hits.append((name, sc)) }
            }
            hits.sort { a, b in
                if a.score != b.score { return a.score > b.score }
                return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
            }
            return hits.map { $0.name }
        }
        var scored: [(track: Track, score: Int)] = []
        for t in tracks {
            let best: Int = max(max(score(t.title, q) * 3, score(t.artist, q) * 2), max(score(t.album, q), score(t.genre, q)))
            if best > 0 { scored.append((t, best)) }
        }
        scored.sort { a, b in
            if a.score != b.score { return a.score > b.score }
            return a.track.title.localizedCaseInsensitiveCompare(b.track.title) == .orderedAscending
        }
        let artists: [String] = tracks.map { $0.artist }
        let albums: [String] = tracks.map { $0.album }
        let genres: [String] = tracks.map { $0.genre }
        let matchingPlaylists: [Playlist] = playlists.filter { score($0.name, q) > 0 }
            .sorted { score($0.name, q) > score($1.name, q) }
        var r = Results()
        r.tracks = scored.map { $0.track }
        r.artists = Array(distinctNames(artists).prefix(10))
        r.albums = Array(distinctNames(albums).prefix(10))
        r.genres = Array(distinctNames(genres).prefix(10))
        r.playlists = matchingPlaylists
        return r
    }

    /// Lieder einer Liste (Playlist, Album …) filtern – die Reihenfolge der Liste bleibt erhalten
    static func filter(_ tracks: [Track], query: String) -> [Track] {
        let q = Duplicates.normalize(query)
        guard !q.isEmpty else { return tracks }
        return tracks.filter { score($0.title, q) > 0 || score($0.artist, q) > 0 || score($0.album, q) > 0 }
    }

    /// Letzte Suchen: neueste zuerst, ohne Doppelte, höchstens `max`
    static func remember(_ recent: [String], _ query: String, max: Int = 8) -> [String] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return recent }
        return Array(([q] + recent.filter { $0.caseInsensitiveCompare(q) != .orderedSame }).prefix(max))
    }
}
