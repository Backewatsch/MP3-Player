import Foundation

/// Schneidet MP3s verlustfrei zu: Es werden nur ganze MP3-Frames (je ca. 26 ms) weggelassen, nichts wird neu kodiert.
/// Tags (Titel, Cover, Songtext) bleiben erhalten, die Längenangabe (Xing/Info) wird angepasst.
enum Mp3Trimmer {
    enum TrimError: LocalizedError {
        case noAudio, tooShort, notMP3
        var errorDescription: String? {
            switch self {
            case .noAudio: return "Keine MP3-Daten gefunden."
            case .tooShort: return "Es muss mindestens 1 Sekunde übrig bleiben."
            case .notMP3: return "Nur MP3-Dateien können zugeschnitten werden."
            }
        }
    }

    struct Frame: Equatable {
        let offset: Int
        let length: Int
        let samples: Int
        let sampleRate: Int
        var durationUs: Int64 { Int64(samples) * 1_000_000 / Int64(sampleRate) }
    }

    struct Parsed {
        let bytes: [UInt8]
        /// Alles vor dem ersten Frame (ID3v2-Tag)
        let headerEnd: Int
        /// Xing/Info/VBRI-Frame mit Längenangaben (kein Ton)
        let infoFrame: Frame?
        let infoTagPos: Int
        let frames: [Frame]
        /// Alles nach dem letzten Frame (ID3v1/APE-Tag)
        let tailStart: Int
        var durationMs: Int64 { frames.reduce(0) { $0 + $1.durationUs } / 1000 }
    }

    private static let bitratesV1 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]
    private static let bitratesV2 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]
    private static let sampleRates = [44100, 48000, 32000]

    static func frame(at pos: Int, in b: [UInt8], end: Int) -> Frame? {
        guard pos + 4 <= end else { return nil }
        let b1 = Int(b[pos + 1]), b2 = Int(b[pos + 2])
        guard b[pos] == 0xFF, b1 & 0xE0 == 0xE0 else { return nil }
        let version = (b1 >> 3) & 3 // 0 = MPEG 2.5, 2 = MPEG 2, 3 = MPEG 1
        guard version != 1, (b1 >> 1) & 3 == 1 else { return nil } // nur Layer III
        let bitrateIndex = (b2 >> 4) & 0xF, rateIndex = (b2 >> 2) & 3
        guard bitrateIndex != 0, bitrateIndex != 15, rateIndex != 3 else { return nil }
        let padding = (b2 >> 1) & 1
        let mpeg1 = version == 3
        let sampleRate = sampleRates[rateIndex] / (version == 3 ? 1 : version == 2 ? 2 : 4)
        let bitrate = (mpeg1 ? bitratesV1 : bitratesV2)[bitrateIndex] * 1000
        let length = (mpeg1 ? 144 : 72) * bitrate / sampleRate + padding
        guard length >= 4, pos + length <= end else { return nil }
        return Frame(offset: pos, length: length, samples: mpeg1 ? 1152 : 576, sampleRate: sampleRate)
    }

    private static func ascii(_ b: [UInt8], _ pos: Int, _ text: String) -> Bool {
        let t = Array(text.utf8)
        guard pos >= 0, pos + t.count <= b.count else { return false }
        return b[pos..<pos + t.count].elementsEqual(t)
    }

    private static func id3v2End(_ b: [UInt8]) -> Int {
        var pos = 0
        while pos + 10 <= b.count, ascii(b, pos, "ID3") {
            let size = (Int(b[pos + 6] & 0x7F) << 21) | (Int(b[pos + 7] & 0x7F) << 14) | (Int(b[pos + 8] & 0x7F) << 7) | Int(b[pos + 9] & 0x7F)
            pos += 10 + size + (b[pos + 5] & 0x10 != 0 ? 10 : 0)
        }
        return min(pos, b.count)
    }

    static func parse(_ bytes: [UInt8]) throws -> Parsed {
        let start = id3v2End(bytes)
        var end = bytes.count
        if end >= 128, ascii(bytes, end - 128, "TAG") { end -= 128 }
        var frames: [Frame] = []
        var pos = start
        var headerEnd = -1
        while pos < end {
            if let f = frame(at: pos, in: bytes, end: end),
               f.offset + f.length >= end || frame(at: f.offset + f.length, in: bytes, end: end) != nil || !frames.isEmpty {
                if headerEnd < 0 { headerEnd = pos }
                frames.append(f)
                pos += f.length
            } else {
                if !frames.isEmpty, ascii(bytes, pos, "TAG") || ascii(bytes, pos, "APETAGEX") || ascii(bytes, pos, "LYRICS") { break }
                pos += 1
            }
        }
        guard let first = frames.first, let last = frames.last else { throw TrimError.noAudio }
        let tailStart = last.offset + last.length
        let mono = (bytes[first.offset + 3] >> 6) == 3
        let side = first.samples == 1152 ? (mono ? 17 : 32) : (mono ? 9 : 17)
        let xing = first.offset + 4 + side
        var info: Frame?
        var infoPos = -1
        if ascii(bytes, xing, "Xing") || ascii(bytes, xing, "Info") { info = first; infoPos = xing }
        else if ascii(bytes, first.offset + 36, "VBRI") { info = first }
        return Parsed(bytes: bytes, headerEnd: headerEnd, infoFrame: info, infoTagPos: infoPos,
                      frames: info != nil ? Array(frames.dropFirst()) : frames, tailStart: tailStart)
    }

    static func durationMs(of url: URL) throws -> Int64 { try parse([UInt8](Data(contentsOf: url))).durationMs }

    /// Behält den Teil von `startMs` bis `endMs`
    static func trim(_ bytes: [UInt8], startMs: Int64, endMs: Int64) throws -> [UInt8] {
        let p = try parse(bytes)
        let total = p.durationMs
        let from = min(max(startMs, 0), total)
        let to = min(max(endMs, from), total)
        guard to - from >= 1000 else { throw TrimError.tooShort }

        // Frames, deren Mitte im gewünschten Bereich liegt
        var kept: [Frame] = []
        var t: Int64 = 0
        for f in p.frames {
            let mid = (t + f.durationUs / 2) / 1000
            if mid >= from && mid < to { kept.append(f) }
            t += f.durationUs
        }
        guard let firstKept = kept.first, let lastKept = kept.last else { throw TrimError.tooShort }
        let cutStart = firstKept != p.frames.first
        let cutEnd = lastKept != p.frames.last

        var out: [UInt8] = []
        out.reserveCapacity(bytes.count)
        out.append(contentsOf: bytes[0..<p.headerEnd])
        if p.infoFrame != nil, p.infoTagPos >= 0 {
            out.append(contentsOf: updatedXing(p, kept: kept, cutStart: cutStart, cutEnd: cutEnd))
        }
        for f in kept { out.append(contentsOf: bytes[f.offset..<f.offset + f.length]) }
        out.append(contentsOf: bytes[p.tailStart...])
        return out
    }

    /// Xing/Info-Frame mit neuer Frame-Anzahl, Größe und Sprungtabelle
    private static func updatedXing(_ p: Parsed, kept: [Frame], cutStart: Bool, cutEnd: Bool) -> [UInt8] {
        let info = p.infoFrame!
        var frame = Array(p.bytes[info.offset..<info.offset + info.length])
        var pos = p.infoTagPos - info.offset
        let flags = readInt(frame, pos + 4)
        pos += 8
        let dataSize = info.length + kept.reduce(0) { $0 + $1.length }
        if flags & 1 != 0 { writeInt(&frame, pos, kept.count); pos += 4 }
        if flags & 2 != 0 { writeInt(&frame, pos, dataSize); pos += 4 }
        if flags & 4 != 0 {
            let totalUs = kept.reduce(Int64(0)) { $0 + $1.durationUs }
            var idx = 0, timeUs: Int64 = 0, bytePos = Int64(info.length)
            for i in 0..<100 {
                let target = totalUs * Int64(i) / 100
                while idx < kept.count && timeUs + kept[idx].durationUs <= target {
                    timeUs += kept[idx].durationUs; bytePos += Int64(kept[idx].length); idx += 1
                }
                frame[pos + i] = UInt8(min(255, max(0, bytePos * 256 / Int64(dataSize))))
            }
            pos += 100
        }
        if flags & 8 != 0 { pos += 4 }
        // LAME-Kopf: Encoder-Verzögerung/Auffüllung gelten nach dem Schnitt nicht mehr
        if pos + 24 <= frame.count {
            let q = pos + 21
            let v = (Int(frame[q]) << 16) | (Int(frame[q + 1]) << 8) | Int(frame[q + 2])
            let delay = cutStart ? 0 : v >> 12
            let padding = cutEnd ? 0 : v & 0xFFF
            let nv = (delay << 12) | padding
            frame[q] = UInt8((nv >> 16) & 0xFF); frame[q + 1] = UInt8((nv >> 8) & 0xFF); frame[q + 2] = UInt8(nv & 0xFF)
        }
        return frame
    }

    private static func readInt(_ b: [UInt8], _ pos: Int) -> Int {
        (Int(b[pos]) << 24) | (Int(b[pos + 1]) << 16) | (Int(b[pos + 2]) << 8) | Int(b[pos + 3])
    }

    private static func writeInt(_ b: inout [UInt8], _ pos: Int, _ v: Int) {
        b[pos] = UInt8((v >> 24) & 0xFF); b[pos + 1] = UInt8((v >> 16) & 0xFF); b[pos + 2] = UInt8((v >> 8) & 0xFF); b[pos + 3] = UInt8(v & 0xFF)
    }
}

// MARK: - Lieder zuschneiden

extension Library {
    func canTrim(_ track: Track) -> Bool {
        Storage.isMP3(track.fileName) && FileManager.default.fileExists(atPath: track.fileURL.path)
    }

    /// Genaue Länge laut MP3-Frames (Sekunden)
    func exactDuration(of track: Track) async -> Double {
        let url = track.fileURL
        let ms = await Task.detached { try? Mp3Trimmer.durationMs(of: url) }.value
        return ms.map { Double($0) / 1000 } ?? track.duration
    }

    /// Behält nur den Teil von `start` bis `end` (Sekunden).
    /// - Parameter asCopy: `true` = als neues Lied „… (gekürzt)“ speichern, `false` = Original ersetzen
    @discardableResult
    func trim(_ track: Track, start: Double, end: Double, asCopy: Bool) async throws -> Track {
        guard canTrim(track) else { throw Mp3Trimmer.TrimError.notMP3 }
        let source = track.fileURL
        let startMs = Int64(start * 1000), endMs = Int64(end * 1000)
        let (trimmed, newDuration) = try await Task.detached { () throws -> ([UInt8], Double) in
            let out = try Mp3Trimmer.trim([UInt8](Data(contentsOf: source)), startMs: startMs, endMs: endMs)
            return (out, Double(try Mp3Trimmer.parse(out).durationMs) / 1000)
        }.value

        if asCopy {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: dir) }
            let base = (track.fileName as NSString).deletingPathExtension
            let file = dir.appendingPathComponent("\(base) (gekürzt).mp3")
            try Data(trimmed).write(to: file)
            let copy = try await importAudio(from: file, move: true)
            rename(copy, title: track.title + " (gekürzt)", artist: track.artist)
            setExactDuration(of: copy.id, to: newDuration)
            return tracks.first { $0.id == copy.id } ?? copy
        }

        // Original ersetzen: erst komplett schreiben, dann austauschen (bricht nie halb ab)
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("trim-\(track.id.uuidString).mp3")
        try Data(trimmed).write(to: tmp)
        _ = try FileManager.default.replaceItemAt(source, withItemAt: tmp)
        setExactDuration(of: track.id, to: newDuration)
        return tracks.first { $0.id == track.id } ?? track
    }
}
