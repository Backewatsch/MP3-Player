import Compression
import Foundation

// ZIP lesen und schreiben – so viel, wie die Komplett-Sicherung braucht.
// iOS bringt dafür nichts mit; deshalb hier das Nötige: Einträge ohne Kompression schreiben,
// Einträge ohne Kompression oder mit Deflate lesen, jeweils auch für sehr große Archive (ZIP64).

/// Prüfsumme CRC-32, wie sie ZIP verwendet
enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { index -> UInt32 in
        var c = UInt32(index)
        for _ in 0..<8 { c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1) }
        return c
    }

    /// Rechnet weitere Daten in eine laufende Prüfsumme ein (Start: 0)
    static func update(_ crc: UInt32, _ data: Data) -> UInt32 {
        var c = crc ^ 0xFFFF_FFFF
        data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            for byte in buffer { c = table[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
        }
        return c ^ 0xFFFF_FFFF
    }
}

private extension Data {
    /// Zahlen im ZIP-Format (niedrigstes Byte zuerst), `offset` ab Beginn dieser Daten
    func u16(_ offset: Int) -> Int {
        Int(self[startIndex + offset]) | Int(self[startIndex + offset + 1]) << 8
    }

    func u32(_ offset: Int) -> UInt64 {
        var value: UInt64 = 0
        for i in 0..<4 { value |= UInt64(self[startIndex + offset + i]) << UInt64(8 * i) }
        return value
    }

    func u64(_ offset: Int) -> UInt64 {
        var value: UInt64 = 0
        for i in 0..<8 { value |= UInt64(self[startIndex + offset + i]) << UInt64(8 * i) }
        return value
    }
}

private struct ByteWriter {
    var data = Data()

    mutating func u16(_ value: Int) {
        data.append(UInt8(value & 0xFF))
        data.append(UInt8((value >> 8) & 0xFF))
    }

    mutating func u32(_ value: UInt64) {
        for i in 0..<4 { data.append(UInt8((value >> UInt64(8 * i)) & 0xFF)) }
    }

    mutating func u64(_ value: UInt64) {
        for i in 0..<8 { data.append(UInt8((value >> UInt64(8 * i)) & 0xFF)) }
    }
}

enum ZipError: LocalizedError {
    case notAZip
    case damaged(String)
    case unsupported(String)

    var errorDescription: String? {
        switch self {
        case .notAZip: return "Die Datei ist kein ZIP-Archiv."
        case .damaged(let what): return "Das Archiv ist beschädigt (\(what))."
        case .unsupported(let what): return "Dieser Eintrag lässt sich nicht lesen: \(what)."
        }
    }
}

/// Liest ein ZIP-Archiv über sein Inhaltsverzeichnis am Ende der Datei.
final class ZipReader: @unchecked Sendable {
    struct Entry {
        let name: String
        /// 0 = gespeichert, 8 = Deflate
        let method: Int
        let flags: Int
        let crc: UInt32
        let compressedSize: UInt64
        let size: UInt64
        let headerOffset: UInt64
    }

    let entries: [Entry]
    private let handle: FileHandle
    private let byName: [String: Int]

    /// Beginnt die Datei wie ein ZIP-Archiv („PK“)?
    static func isZip(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 4), head.count == 4 else { return false }
        return head.u16(0) == 0x4B50
    }

    init(url: URL) throws {
        let handle = try FileHandle(forReadingFrom: url)
        let fileSize = try handle.seekToEnd()
        // Das „Ende des Inhaltsverzeichnisses“ steht in den letzten 64 KB (22 Byte + höchstens 65535 Byte Kommentar)
        let tailLength = min(fileSize, 66_000)
        try handle.seek(toOffset: fileSize - tailLength)
        let tail = try handle.read(upToCount: Int(tailLength)) ?? Data()
        guard let end = Self.findEnd(in: tail) else { throw ZipError.notAZip }
        var count = UInt64(tail.u16(end + 10))
        var directorySize = tail.u32(end + 12)
        var directoryOffset = tail.u32(end + 16)

        // ZIP64: Die echten Werte stehen in einem zweiten Ende-Eintrag, auf den ein Verweis direkt davor zeigt
        if count == 0xFFFF || directorySize == 0xFFFF_FFFF || directoryOffset == 0xFFFF_FFFF {
            guard end >= 20, tail.u32(end - 20) == 0x0706_4B50 else { throw ZipError.damaged("ZIP64-Verweis") }
            let recordOffset = tail.u64(end - 12)
            guard recordOffset <= fileSize else { throw ZipError.damaged("ZIP64-Verweis") }
            try handle.seek(toOffset: recordOffset)
            guard let record = try handle.read(upToCount: 56), record.count == 56, record.u32(0) == 0x0606_4B50 else {
                throw ZipError.damaged("ZIP64-Ende")
            }
            count = record.u64(32)
            directorySize = record.u64(40)
            directoryOffset = record.u64(48)
        }
        guard directoryOffset <= fileSize, directorySize <= fileSize - directoryOffset, directorySize < 512_000_000 else {
            throw ZipError.damaged("Inhaltsverzeichnis")
        }
        try handle.seek(toOffset: directoryOffset)
        let directory = try handle.read(upToCount: Int(directorySize)) ?? Data()
        guard directory.count == Int(directorySize) else { throw ZipError.damaged("Inhaltsverzeichnis") }

        var entries: [Entry] = []
        var position = 0
        while UInt64(entries.count) < count {
            guard position + 46 <= directory.count, directory.u32(position) == 0x0201_4B50 else {
                throw ZipError.damaged("Inhaltsverzeichnis")
            }
            let flags = directory.u16(position + 8)
            let method = directory.u16(position + 10)
            let crc = UInt32(directory.u32(position + 16))
            var compressedSize = directory.u32(position + 20)
            var size = directory.u32(position + 24)
            let nameLength = directory.u16(position + 28)
            let extraLength = directory.u16(position + 30)
            let commentLength = directory.u16(position + 32)
            var headerOffset = directory.u32(position + 42)
            let nameStart = position + 46
            let extraStart = nameStart + nameLength
            let extraEnd = extraStart + extraLength
            guard extraEnd + commentLength <= directory.count else { throw ZipError.damaged("Inhaltsverzeichnis") }
            let nameData = directory.subdata(in: (directory.startIndex + nameStart)..<(directory.startIndex + extraStart))
            let name = String(decoding: nameData, as: UTF8.self)

            // Zusatzfeld 0x0001 (ZIP64): 64-Bit-Werte für alles, was oben als „passt nicht“ markiert ist
            var extra = extraStart
            while extra + 4 <= extraEnd {
                let id = directory.u16(extra)
                let length = directory.u16(extra + 2)
                if id == 0x0001 {
                    var field = extra + 4
                    let fieldEnd = min(field + length, extraEnd)
                    if size == 0xFFFF_FFFF, field + 8 <= fieldEnd { size = directory.u64(field); field += 8 }
                    if compressedSize == 0xFFFF_FFFF, field + 8 <= fieldEnd { compressedSize = directory.u64(field); field += 8 }
                    if headerOffset == 0xFFFF_FFFF, field + 8 <= fieldEnd { headerOffset = directory.u64(field); field += 8 }
                }
                extra += 4 + length
            }
            entries.append(Entry(name: name, method: method, flags: flags, crc: crc, compressedSize: compressedSize,
                                 size: size, headerOffset: headerOffset))
            position = extraEnd + commentLength
        }

        var byName: [String: Int] = [:]
        for (index, entry) in entries.enumerated() where byName[entry.name] == nil { byName[entry.name] = index }
        self.handle = handle
        self.entries = entries
        self.byName = byName
    }

    deinit { try? handle.close() }

    private static func findEnd(in tail: Data) -> Int? {
        var index = tail.count - 22
        while index >= 0 {
            if tail.u32(index) == 0x0605_4B50 { return index }
            index -= 1
        }
        return nil
    }

    func entry(named name: String) -> Entry? {
        byName[name].map { entries[$0] }
    }

    /// Inhalt eines (kleinen) Eintrags im Speicher
    func data(of entry: Entry) throws -> Data {
        var result = Data()
        try read(entry) { result.append($0) }
        return result
    }

    /// Schreibt den Inhalt eines Eintrags entpackt in eine Datei (eine vorhandene wird ersetzt)
    func extract(_ entry: Entry, to destination: URL) throws {
        let fm = FileManager.default
        try? fm.removeItem(at: destination)
        guard fm.createFile(atPath: destination.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        let output = try FileHandle(forWritingTo: destination)
        do {
            try read(entry) { try output.write(contentsOf: $0) }
            try output.close()
        } catch {
            try? output.close()
            try? fm.removeItem(at: destination)
            throw error
        }
    }

    /// Liest einen Eintrag stückweise, entpackt ihn und prüft die Prüfsumme
    private func read(_ entry: Entry, sink: (Data) throws -> Void) throws {
        guard entry.flags & 1 == 0 else { throw ZipError.unsupported("\(entry.name) ist verschlüsselt") }
        try handle.seek(toOffset: entry.headerOffset)
        guard let header = try handle.read(upToCount: 30), header.count == 30, header.u32(0) == 0x0403_4B50 else {
            throw ZipError.damaged(entry.name)
        }
        try handle.seek(toOffset: entry.headerOffset + 30 + UInt64(header.u16(26)) + UInt64(header.u16(28)))

        let crc: UInt32
        switch entry.method {
        case 0: crc = try readStored(entry, sink: sink)
        case 8: crc = try readDeflated(entry, sink: sink)
        default: throw ZipError.unsupported("\(entry.name) (Packverfahren \(entry.method))")
        }
        guard crc == entry.crc else { throw ZipError.damaged(entry.name) }
    }

    private static let chunkSize = 1 << 18

    private func readStored(_ entry: Entry, sink: (Data) throws -> Void) throws -> UInt32 {
        var crc: UInt32 = 0
        var remaining = entry.compressedSize
        while remaining > 0 {
            let chunk = try handle.read(upToCount: Int(min(remaining, UInt64(Self.chunkSize)))) ?? Data()
            guard !chunk.isEmpty else { throw ZipError.damaged(entry.name) }
            remaining -= UInt64(chunk.count)
            crc = CRC32.update(crc, chunk)
            try sink(chunk)
        }
        return crc
    }

    private func readDeflated(_ entry: Entry, sink: (Data) throws -> Void) throws -> UInt32 {
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        // COMPRESSION_ZLIB ist reines Deflate ohne Kopf – genau das, was in ZIP-Einträgen steht
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw ZipError.damaged(entry.name)
        }
        defer { compression_stream_destroy(stream) }
        let outputSize = Self.chunkSize
        let output = UnsafeMutablePointer<UInt8>.allocate(capacity: outputSize)
        defer { output.deallocate() }

        var crc: UInt32 = 0
        var remaining = entry.compressedSize
        while true {
            var chunk = Data()
            if remaining > 0 {
                chunk = try handle.read(upToCount: Int(min(remaining, UInt64(Self.chunkSize)))) ?? Data()
                guard !chunk.isEmpty else { throw ZipError.damaged(entry.name) }
                remaining -= UInt64(chunk.count)
            }
            let last = remaining == 0
            var status = COMPRESSION_STATUS_OK
            try chunk.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Void in
                stream.pointee.src_ptr = raw.bindMemory(to: UInt8.self).baseAddress ?? UnsafePointer(output)
                stream.pointee.src_size = raw.count
                repeat {
                    stream.pointee.dst_ptr = output
                    stream.pointee.dst_size = outputSize
                    status = compression_stream_process(stream, last ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0)
                    guard status != COMPRESSION_STATUS_ERROR else { throw ZipError.damaged(entry.name) }
                    let produced = outputSize - stream.pointee.dst_size
                    if produced > 0 {
                        let block = Data(bytes: output, count: produced)
                        crc = CRC32.update(crc, block)
                        try sink(block)
                    }
                    // weiter, solange noch Eingabe da ist oder der Ausgabepuffer ganz voll war (dann wartet noch mehr)
                } while status == COMPRESSION_STATUS_OK && (stream.pointee.src_size > 0 || stream.pointee.dst_size == 0)
            }
            if status == COMPRESSION_STATUS_END { return crc }
            // Alle Daten gelesen, aber der gepackte Strom ist nicht zu Ende: abgeschnitten
            if last { throw ZipError.damaged(entry.name) }
        }
    }
}

/// Schreibt ein ZIP-Archiv ohne Kompression (MP3s und JPEGs lassen sich ohnehin kaum weiter packen).
/// Prüfsumme und Größe stehen im Kopf vor jedem Eintrag: So kann auch die Android-App das Archiv der Reihe nach lesen.
final class ZipWriter {
    private struct Written {
        let name: Data
        let crc: UInt32
        let size: UInt64
        let offset: UInt64
    }

    private let handle: FileHandle
    private let forceZip64: Bool
    private var written: [Written] = []
    private var position: UInt64 = 0

    /// - Parameter forceZip64: schreibt immer die Felder für sehr große Archive (nur für Tests nötig)
    init(url: URL, forceZip64: Bool = false) throws {
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        handle = try FileHandle(forWritingTo: url)
        self.forceZip64 = forceZip64
    }

    func add(name: String, data: Data) throws {
        try add(name: name) { write in try write(data) }
    }

    func add(name: String, fileAt url: URL) throws {
        let source = try FileHandle(forReadingFrom: url)
        defer { try? source.close() }
        try add(name: name) { write in
            while let chunk = try source.read(upToCount: 1 << 18), !chunk.isEmpty { try write(chunk) }
        }
    }

    private func add(name: String, body: ((Data) throws -> Void) throws -> Void) throws {
        let nameData = Data(name.utf8)
        let offset = position
        // Kopf zuerst mit Platzhaltern; Prüfsumme und Größe werden nach den Daten eingetragen
        try write(Self.localHeader(name: nameData))
        var crc: UInt32 = 0
        var size: UInt64 = 0
        try body { chunk in
            crc = CRC32.update(crc, chunk)
            size += UInt64(chunk.count)
            try self.write(chunk)
        }
        guard size < 0xFFFF_FFFF else { throw ZipError.unsupported("\(name) ist größer als 4 GB") }
        var fields = ByteWriter()
        fields.u32(UInt64(crc))
        fields.u32(size)
        fields.u32(size)
        try handle.seek(toOffset: offset + 14)
        try handle.write(contentsOf: fields.data)
        try handle.seek(toOffset: position)
        written.append(Written(name: nameData, crc: crc, size: size, offset: offset))
    }

    private func write(_ data: Data) throws {
        try handle.write(contentsOf: data)
        position += UInt64(data.count)
    }

    private static func localHeader(name: Data) -> Data {
        var b = ByteWriter()
        b.u32(0x0403_4B50)
        b.u16(20)        // nötige ZIP-Version
        b.u16(0x0800)    // Namen stehen in UTF-8
        b.u16(0)         // gespeichert, ohne Kompression
        b.u16(0)         // Uhrzeit
        b.u16(0x0021)    // Datum: 1.1.1980
        b.u32(0)         // Prüfsumme (wird nachgetragen)
        b.u32(0)         // Größe gepackt (wird nachgetragen)
        b.u32(0)         // Größe (wird nachgetragen)
        b.u16(name.count)
        b.u16(0)         // kein Zusatzfeld
        b.data.append(name)
        return b.data
    }

    /// Schreibt das Inhaltsverzeichnis und schließt die Datei
    func finish() throws {
        let directoryOffset = position
        for entry in written {
            let big = forceZip64 || entry.offset >= 0xFFFF_FFFF
            var b = ByteWriter()
            b.u32(0x0201_4B50)
            b.u16(0x0300 | (big ? 45 : 20))   // erstellt mit: Unix – dann lesen alle Werkzeuge die Namen als UTF-8
            b.u16(big ? 45 : 20)     // nötige ZIP-Version
            b.u16(0x0800)
            b.u16(0)
            b.u16(0)
            b.u16(0x0021)
            b.u32(UInt64(entry.crc))
            b.u32(entry.size)
            b.u32(entry.size)
            b.u16(entry.name.count)
            b.u16(big ? 12 : 0)      // Zusatzfeld mit der Position als 64-Bit-Zahl
            b.u16(0)                 // Kommentar
            b.u16(0)                 // Datenträger
            b.u16(0)                 // interne Merkmale
            b.u32(0)                 // externe Merkmale
            b.u32(big ? 0xFFFF_FFFF : entry.offset)
            b.data.append(entry.name)
            if big {
                b.u16(0x0001)
                b.u16(8)
                b.u64(entry.offset)
            }
            try write(b.data)
        }
        let directorySize = position - directoryOffset
        let count = UInt64(written.count)
        let big = forceZip64 || count >= 0xFFFF || directoryOffset >= 0xFFFF_FFFF || directorySize >= 0xFFFF_FFFF
        if big {
            let recordOffset = position
            var r = ByteWriter()
            r.u32(0x0606_4B50)
            r.u64(44)                // Länge des restlichen Eintrags
            r.u16(0x0300 | 45)
            r.u16(45)
            r.u32(0)
            r.u32(0)
            r.u64(count)
            r.u64(count)
            r.u64(directorySize)
            r.u64(directoryOffset)
            r.u32(0x0706_4B50)       // Verweis auf den Eintrag davor
            r.u32(0)
            r.u64(recordOffset)
            r.u32(1)
            try write(r.data)
        }
        var e = ByteWriter()
        e.u32(0x0605_4B50)
        e.u16(0)
        e.u16(0)
        e.u16(big ? 0xFFFF : Int(count))
        e.u16(big ? 0xFFFF : Int(count))
        e.u32(big ? 0xFFFF_FFFF : directorySize)
        e.u32(big ? 0xFFFF_FFFF : directoryOffset)
        e.u16(0)
        try write(e.data)
        try handle.close()
    }
}
