import Foundation

/// Jahresrückblick „Dein Jahr in Musik“ – reine Auswertung der Hördaten (getestet)
enum Recap {
    /// Ab so vielen gehörten Liedern lohnt sich ein Rückblick
    static let minPlays = 3

    enum Daypart: String, CaseIterable {
        case morning, day, evening, night

        var label: String {
            switch self { case .morning: return "Morgens"; case .day: return "Tagsüber"; case .evening: return "Abends"; case .night: return "Nachts" }
        }
        var type: String {
            switch self { case .morning: return "Frühaufsteher"; case .day: return "Tagträumer"; case .evening: return "Feierabend-Hörer"; case .night: return "Nachteule" }
        }
        var text: String {
            switch self {
            case .morning: return "Du startest am liebsten mit Musik in den Tag."
            case .day: return "Deine Musik begleitet dich durch den Tag."
            case .evening: return "Wenn der Tag vorbei ist, geht bei dir die Musik an."
            case .night: return "Wenn andere schlafen, drehst du auf."
            }
        }
        static func of(hour: Int) -> Daypart {
            switch hour {
            case 5...10: return .morning
            case 11...16: return .day
            case 17...21: return .evening
            default: return .night
            }
        }
    }

    struct Obsession: Equatable {
        let track: Track
        let count: Int
        let day: DateComponents
    }

    struct Result {
        var year: Int
        var listenedSeconds: Double
        var plays: Int
        var distinctTracks: Int
        var distinctArtists: Int
        var topTracks: [(Track, Int)]
        var topArtists: [(String, Int)]
        var topAlbum: (String, Int)?
        /// Hörminuten je Monat (Index 0 = Januar)
        var minutesByMonth: [Double]
        var playsByMonth: [Int]
        /// Stärkster Monat (1–12)
        var topMonth: Int?
        var dayparts: [Daypart: Int]
        var listenerType: Daypart?
        /// Wochentag (1 = Sonntag … 7 = Samstag, wie Calendar)
        var topWeekday: Int?
        var daysListened: Int
        var longestStreak: Int
        var firstTrack: Track?
        var firstAt: Date?
        var obsession: Obsession?
        var newTracks: Int

        var hasData: Bool { plays >= Recap.minPlays }
    }

    static let months = ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"]
    static let weekdays = ["Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"]

    /// 12345 → „12.345“
    static func number(_ n: Int) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale(identifier: "de_DE")
        return f.string(from: NSNumber(value: n)) ?? "\(n)"
    }

    /// Jahre mit Hördaten, neuestes zuerst
    static func availableYears(_ state: LibraryState, calendar: Calendar = .current) -> [Int] {
        let known = Set(state.tracks.map(\.id))
        var counts: [Int: Int] = [:]
        for p in state.plays where known.contains(p.trackID) { counts[calendar.component(.year, from: p.at), default: 0] += 1 }
        return counts.filter { $0.value >= minPlays }.keys.sorted(by: >)
    }

    /// Welches Jahr zeigen? Im Januar noch das Vorjahr, solange das neue Jahr kaum Daten hat
    static func defaultYear(_ state: LibraryState, now: Date = Date(), calendar: Calendar = .current) -> Int? {
        let years = availableYears(state, calendar: calendar)
        let current = calendar.component(.year, from: now)
        if calendar.component(.month, from: now) == 1, years.contains(current - 1) {
            let thisYear = state.plays.filter { calendar.component(.year, from: $0.at) == current }.count
            if thisYear < 50 { return current - 1 }
        }
        return years.contains(current) ? current : years.first
    }

    static func build(_ state: LibraryState, year: Int, calendar: Calendar = .current) -> Result {
        let byID = Dictionary(state.tracks.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let plays = state.plays
            .filter { byID[$0.trackID] != nil && calendar.component(.year, from: $0.at) == year }
            .sorted { $0.at < $1.at }
        let tracks = plays.compactMap { byID[$0.trackID] }

        func ranked(_ keys: [String], limit: Int, name: (String) -> String) -> [(String, Int)] {
            var counts: [String: Int] = [:]
            for k in keys { counts[k, default: 0] += 1 }
            return counts.sorted {
                $0.value != $1.value ? $0.value > $1.value
                    : name($0.key).localizedCaseInsensitiveCompare(name($1.key)) == .orderedAscending
            }.prefix(limit).map { ($0.key, $0.value) }
        }

        let topTracks = ranked(tracks.map { $0.id.uuidString }, limit: 5) { id in byID[UUID(uuidString: id)!]?.title ?? "" }
            .compactMap { pair -> (Track, Int)? in byID[UUID(uuidString: pair.0)!].map { ($0, pair.1) } }
        let artists = tracks.map { $0.artist.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let topArtists = ranked(artists, limit: 5) { $0 }
        let albums = tracks.map { $0.album.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let topAlbum = ranked(albums, limit: 1) { $0 }.first

        let minutesByMonth = (1...12).map { m in (state.listenedByMonth[String(format: "%04d-%02d", year, m)] ?? 0) / 60 }
        let playsByMonth = (1...12).map { m in plays.filter { calendar.component(.month, from: $0.at) == m }.count }
        var listened = state.listenedByMonth.filter { $0.key.hasPrefix("\(year)-") }.values.reduce(0, +)
        // Ältere Daten ohne Hörzeit: aus den Liedlängen schätzen
        if listened == 0 { listened = tracks.reduce(0) { $0 + $1.duration } }
        let topMonth: Int?
        if minutesByMonth.contains(where: { $0 > 0 }) {
            topMonth = minutesByMonth.indices.max { minutesByMonth[$0] < minutesByMonth[$1] }.map { $0 + 1 }
        } else if playsByMonth.contains(where: { $0 > 0 }) {
            topMonth = playsByMonth.indices.max { playsByMonth[$0] < playsByMonth[$1] }.map { $0 + 1 }
        } else {
            topMonth = nil
        }

        var dayparts: [Daypart: Int] = [:]
        var weekdays: [Int: Int] = [:]
        for p in plays {
            dayparts[Daypart.of(hour: calendar.component(.hour, from: p.at)), default: 0] += 1
            weekdays[calendar.component(.weekday, from: p.at), default: 0] += 1
        }
        let listenerType = dayparts.max { $0.value != $1.value ? $0.value < $1.value : $0.key.rawValue > $1.key.rawValue }?.key
        let topWeekday = weekdays.max { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key }?.key

        // Tage mit Musik und längste Serie
        let days = Set(plays.map { calendar.startOfDay(for: $0.at) }).sorted()
        var longest = 0, run = 0
        var previous: Date?
        for d in days {
            if let p = previous, calendar.date(byAdding: .day, value: 1, to: p) == d { run += 1 } else { run = 1 }
            longest = max(longest, run)
            previous = d
        }

        // Ein Lied an einem einzigen Tag in Dauerschleife
        var perDay: [String: (UUID, Date, Int)] = [:]
        for p in plays {
            let day = calendar.startOfDay(for: p.at)
            let key = "\(p.trackID.uuidString)|\(day.timeIntervalSince1970)"
            let old = perDay[key]?.2 ?? 0
            perDay[key] = (p.trackID, day, old + 1)
        }
        let best = perDay.values.max { $0.2 != $1.2 ? $0.2 < $1.2 : $0.1 < $1.1 }
        let obsession = best.flatMap { b -> Obsession? in
            guard b.2 >= 3, let t = byID[b.0] else { return nil }
            return Obsession(track: t, count: b.2, day: calendar.dateComponents([.year, .month, .day], from: b.1))
        }

        return Result(
            year: year,
            listenedSeconds: listened,
            plays: plays.count,
            distinctTracks: Set(tracks.map(\.id)).count,
            distinctArtists: Set(artists.map { $0.lowercased() }).count,
            topTracks: topTracks,
            topArtists: topArtists,
            topAlbum: topAlbum,
            minutesByMonth: minutesByMonth,
            playsByMonth: playsByMonth,
            topMonth: topMonth,
            dayparts: dayparts,
            listenerType: listenerType,
            topWeekday: topWeekday,
            daysListened: days.count,
            longestStreak: longest,
            firstTrack: plays.first.flatMap { byID[$0.trackID] },
            firstAt: plays.first?.at,
            obsession: obsession,
            newTracks: state.tracks.filter { calendar.component(.year, from: $0.addedAt) == year }.count
        )
    }
}

extension Library {
    var recapYears: [Int] { Recap.availableYears(stateSnapshot) }
    var recapDefaultYear: Int? { Recap.defaultYear(stateSnapshot) }
    func recap(year: Int) -> Recap.Result { Recap.build(stateSnapshot, year: year) }
}
