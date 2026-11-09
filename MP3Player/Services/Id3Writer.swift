import Foundation

/// Schreibt Titel, Interpret, Album, Genre, Cover und Songtext direkt in die MP3 (ID3v2-Tag).
/// Andere Angaben im Tag bleiben erhalten (getestet).
enum Id3Writer {
    /// Was geändert werden soll – `nil` lässt den Wert unverändert, "" entfernt ihn
    struct Update {
        var title: String?
        var artist: String?
        var album: String?
        var genre: String?
        /// JPEG-Daten; leere Daten entfernen das Cover
        var cover: Data?
        var lyrics: String?
    }

    private struct Frame { let id: String; let flags: [UInt8]; let data: [UInt8] }

    static func write(_ url: URL, _ update: Update) throws {
        let bytes = [UInt8](try Data(contentsOf: url))
        let (version, frames, audioStart) = readTag(bytes)
        var replaced = Set<String>()
        if update.title != nil { replaced.insert("TIT2") }
        if update.artist != nil { replaced.insert("TPE1") }
        if update.album != nil { replaced.insert("TALB") }
        if update.genre != nil { replaced.insert("TCON") }
        if update.cover != nil { replaced.insert("APIC") }
        if update.lyrics != nil { replaced.insert("USLT") }
        var out = frames.filter { !replaced.contains($0.id) }
        func text(_ id: String, _ value: String?) {
            if let value, !value.isEmpty { out.append(Frame(id: id, flags: [0, 0], data: [1] + utf16(value))) }
        }
        text("TIT2", update.title)
        text("TPE1", update.artist)
        text("TALB", update.album)
        text("TCON", update.genre)
        if let jpeg = update.cover, !jpeg.isEmpty {
            // Kodierung 0, MIME, Bildtyp 3 (Vorderseite), leere Beschreibung
            out.append(Frame(id: "APIC", flags: [0, 0], data: [0] + Array("image/jpeg".utf8) + [0, 3, 0] + [UInt8](jpeg)))
        }
        if let lyrics = update.lyrics, !lyrics.isEmpty {
            out.append(Frame(id: "USLT", flags: [0, 0], data: [1] + Array("deu".utf8) + utf16("") + utf16(lyrics)))
        }

        var body: [UInt8] = []
        for f in out {
            body += Array(f.id.utf8)
            body += version >= 4 ? synchsafe(f.data.count) : int32(f.data.count)
            body += f.flags
            body += f.data
        }
        body += [UInt8](repeating: 0, count: 1024) // Platz für spätere Änderungen
        var result = Array("ID3".utf8) + [UInt8(version), 0, 0] + synchsafe(body.count) + body
        result += bytes[min(audioStart, bytes.count)...]
        // Komplett schreiben, dann austauschen (die alte Datei bleibt bei Fehlern unangetastet)
        try Data(result).write(to: url, options: .atomic)
    }

    /// Bilddaten des Covers (APIC) aus der MP3 – `nil`, wenn keins drin ist
    static func picture(_ url: URL) -> Data? {
        guard let bytes = try? [UInt8](Data(contentsOf: url)) else { return nil }
        guard let frame = readTag(bytes).1.first(where: { $0.id == "APIC" }) else { return nil }
        let d = frame.data
        guard d.count > 4 else { return nil }
        let encoding = d[0]
        var i = 1
        while i < d.count && d[i] != 0 { i += 1 } // MIME-Typ
        i += 2 // Ende des MIME-Typs + Bildtyp
        // Beschreibung überspringen (UTF-16 endet mit zwei Nullbytes)
        if encoding == 1 || encoding == 2 {
            while i + 1 < d.count && !(d[i] == 0 && d[i + 1] == 0) { i += 2 }
            i += 2
        } else {
            while i < d.count && d[i] != 0 { i += 1 }
            i += 1
        }
        guard i < d.count else { return nil }
        return Data(d[i...])
    }

    /// Vorhandenes Tag lesen: Version, Frames (bei 2.2 keine) und wo die Musik beginnt
    private static func readTag(_ b: [UInt8]) -> (Int, [Frame], Int) {
        guard b.count >= 10, b[0] == 0x49, b[1] == 0x44, b[2] == 0x33 else { return (3, [], 0) }
        let version = Int(b[3])
        let flags = Int(b[5])
        let size = synchsafe(b, 6)
        let footer = version >= 4 && flags & 0x10 != 0 ? 10 : 0
        let audioStart = 10 + size + footer
        guard (3...4).contains(version), 10 + size <= b.count else { return (3, [], audioStart) } // ID3v2.2: ersetzen
        var body = Array(b[10..<10 + size])
        if flags & 0x80 != 0 && version == 3 { body = deunsync(body) }
        var pos = 0
        if flags & 0x40 != 0 && body.count >= 4 { pos = version >= 4 ? synchsafe(body, 0) : int32(body, 0) + 4 }
        var frames: [Frame] = []
        while pos + 10 <= body.count {
            let idBytes = body[pos..<pos + 4]
            guard idBytes.allSatisfy({ (0x30...0x39).contains($0) || (0x41...0x5A).contains($0) }) else { break }
            let id = String(decoding: idBytes, as: UTF8.self)
            let len = version >= 4 ? synchsafe(body, pos + 4) : int32(body, pos + 4)
            guard len > 0, pos + 10 + len <= body.count else { break }
            let frameFlags = Array(body[pos + 8..<pos + 10])
            // Nur einfache Frames übernehmen (ohne Kompression/Verschlüsselung)
            if frameFlags[1] == 0 { frames.append(Frame(id: id, flags: frameFlags, data: Array(body[pos + 10..<pos + 10 + len]))) }
            pos += 10 + len
        }
        return (version, frames, audioStart)
    }

    private static func utf16(_ s: String) -> [UInt8] {
        var out: [UInt8] = [0xFF, 0xFE]
        for unit in s.utf16 { out.append(UInt8(unit & 0xFF)); out.append(UInt8(unit >> 8)) }
        return out + [0, 0]
    }
    private static func synchsafe(_ n: Int) -> [UInt8] {
        [UInt8((n >> 21) & 0x7F), UInt8((n >> 14) & 0x7F), UInt8((n >> 7) & 0x7F), UInt8(n & 0x7F)]
    }
    private static func int32(_ n: Int) -> [UInt8] {
        [UInt8((n >> 24) & 0xFF), UInt8((n >> 16) & 0xFF), UInt8((n >> 8) & 0xFF), UInt8(n & 0xFF)]
    }
    private static func synchsafe(_ b: [UInt8], _ o: Int) -> Int {
        (Int(b[o] & 0x7F) << 21) | (Int(b[o + 1] & 0x7F) << 14) | (Int(b[o + 2] & 0x7F) << 7) | Int(b[o + 3] & 0x7F)
    }
    private static func int32(_ b: [UInt8], _ o: Int) -> Int {
        (Int(b[o]) << 24) | (Int(b[o + 1]) << 16) | (Int(b[o + 2]) << 8) | Int(b[o + 3])
    }
    private static func deunsync(_ b: [UInt8]) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(b.count)
        var i = 0
        while i < b.count {
            out.append(b[i])
            if b[i] == 0xFF, i + 1 < b.count, b[i + 1] == 0 { i += 1 }
            i += 1
        }
        return out
    }
}
