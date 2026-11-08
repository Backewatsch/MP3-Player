import AVFoundation
import LAME
import UIKit

/// Wandelt Videos (MP4, MOV, M4V …) und Audioformate (M4A, AAC, WAV, AIFF, FLAC …) in echte MP3-Dateien um.
///
/// Dekodiert wird mit AVFoundation, kodiert mit dem LAME-Encoder (192 kbit/s CBR, 44,1 kHz Stereo).
/// Titel, Interpret, Album und Cover werden als ID3v2-Tags übernommen.
enum AudioConverter {
    enum ConversionError: LocalizedError {
        case noAudioTrack
        case encoderFailed(String)
        case readFailed(String)

        var errorDescription: String? {
            switch self {
            case .noAudioTrack: return "Die Datei enthält keine Tonspur."
            case .encoderFailed(let reason): return "MP3-Umwandlung fehlgeschlagen: \(reason)"
            case .readFailed(let reason): return "Die Datei konnte nicht gelesen werden: \(reason)"
            }
        }
    }

    struct Tags {
        var title: String?
        var artist: String?
        var album: String?
        var artwork: Data?
    }

    static let bitrate: Int32 = 192
    private static let sampleRate = 44_100

    /// Wandelt `source` in eine neue MP3-Datei mit dem Namen `baseName` um und liefert deren Adresse.
    static func convertToMP3(_ source: URL, baseName: String) async throws -> URL {
        let asset = AVURLAsset(url: source)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        guard !tracks.isEmpty else { throw ConversionError.noAudioTrack }
        let tags = await readTags(from: asset)
        let output = Storage.makeIncomingDirectory().appendingPathComponent("\(baseName).mp3")

        try await Task.detached(priority: .userInitiated) {
            try encode(asset: asset, tracks: tracks, to: output, tags: tags)
        }.value
        return output
    }

    /// Liest Titel, Interpret, Album und Cover aus der Quelldatei.
    static func readTags(from asset: AVAsset) async -> Tags {
        var tags = Tags()
        for item in (try? await asset.load(.commonMetadata)) ?? [] {
            switch item.commonKey {
            case .commonKeyTitle?: tags.title = try? await item.load(.stringValue)
            case .commonKeyArtist?: tags.artist = try? await item.load(.stringValue)
            case .commonKeyAlbumName?: tags.album = try? await item.load(.stringValue)
            case .commonKeyArtwork?:
                if tags.artwork == nil, let data = try? await item.load(.dataValue), let image = UIImage(data: data) {
                    // Cover verkleinert als JPEG einbetten
                    let thumb = image.preparingThumbnail(of: CGSize(width: 500, height: 500)) ?? image
                    tags.artwork = thumb.jpegData(compressionQuality: 0.85)
                }
            default: break
            }
        }
        return tags
    }

    // MARK: Kodieren (läuft im Hintergrund)

    private static func encode(asset: AVAsset, tracks: [AVAssetTrack], to output: URL, tags: Tags) throws {
        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            throw ConversionError.readFailed(error.localizedDescription)
        }
        // AVFoundation liefert 16-Bit-PCM, Stereo, 44,1 kHz (mischt, wandelt und resampelt bei Bedarf)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 2
        ]
        let readerOutput = AVAssetReaderAudioMixOutput(audioTracks: tracks, audioSettings: settings)
        guard reader.canAdd(readerOutput) else { throw ConversionError.readFailed("Tonspur nicht lesbar.") }
        reader.add(readerOutput)
        guard reader.startReading() else {
            throw ConversionError.readFailed(reader.error?.localizedDescription ?? "Unbekannter Fehler")
        }
        defer { if reader.status == .reading { reader.cancelReading() } }

        guard let lame = lame_init() else { throw ConversionError.encoderFailed("LAME konnte nicht starten.") }
        defer { lame_close(lame) }
        lame_set_in_samplerate(lame, Int32(sampleRate))
        lame_set_num_channels(lame, 2)
        lame_set_VBR(lame, vbr_off)
        lame_set_brate(lame, bitrate)
        lame_set_quality(lame, 2) // 0 = beste, 9 = schnellste; 2 = sehr gut

        // ID3v2-Tags (UTF-16, damit Umlaute korrekt sind)
        id3tag_init(lame)
        id3tag_add_v2(lame)
        id3tag_v2_only(lame)
        setTextFrame(lame, "TIT2", tags.title)
        setTextFrame(lame, "TPE1", tags.artist)
        setTextFrame(lame, "TALB", tags.album)
        if let artwork = tags.artwork {
            _ = artwork.withUnsafeBytes { raw in
                id3tag_set_albumart(lame, raw.baseAddress?.assumingMemoryBound(to: CChar.self), raw.count)
            }
        }

        guard lame_init_params(lame) >= 0 else { throw ConversionError.encoderFailed("Ungültige Einstellungen.") }

        guard FileManager.default.createFile(atPath: output.path, contents: nil) else {
            throw ConversionError.encoderFailed("Zieldatei konnte nicht angelegt werden.")
        }
        let handle = try FileHandle(forWritingTo: output)
        defer { try? handle.close() }

        var mp3Buffer = [UInt8](repeating: 0, count: 64 * 1024)
        var pcm = Data()

        while let sampleBuffer = readerOutput.copyNextSampleBuffer() {
            guard let block = CMSampleBufferGetDataBuffer(sampleBuffer) else { continue }
            let length = CMBlockBufferGetDataLength(block)
            guard length > 0 else { continue }
            if pcm.count < length { pcm = Data(count: length) }
            let status = pcm.withUnsafeMutableBytes { raw in
                CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: raw.baseAddress!)
            }
            guard status == kCMBlockBufferNoErr else { throw ConversionError.readFailed("Lesefehler \(status)") }

            let frames = Int32(length / 4) // 2 Kanäle × 2 Byte
            // Empfohlene Puffergröße laut lame.h: 1,25 × Samples + 7200
            let needed = Int(Double(frames) * 1.25) + 7200
            if mp3Buffer.count < needed { mp3Buffer = [UInt8](repeating: 0, count: needed) }
            let capacity = Int32(mp3Buffer.count)

            let written = pcm.withUnsafeMutableBytes { raw -> Int32 in
                let samples = raw.baseAddress!.assumingMemoryBound(to: Int16.self)
                return mp3Buffer.withUnsafeMutableBufferPointer { out in
                    lame_encode_buffer_interleaved(lame, samples, frames, out.baseAddress, capacity)
                }
            }
            guard written >= 0 else { throw ConversionError.encoderFailed("Fehler \(written)") }
            if written > 0 { handle.write(Data(mp3Buffer[0..<Int(written)])) }
        }

        if reader.status == .failed {
            throw ConversionError.readFailed(reader.error?.localizedDescription ?? "Unbekannter Fehler")
        }

        let capacity = Int32(mp3Buffer.count)
        let flushed = mp3Buffer.withUnsafeMutableBufferPointer { out in
            lame_encode_flush(lame, out.baseAddress, capacity)
        }
        if flushed > 0 { handle.write(Data(mp3Buffer[0..<Int(flushed)])) }
    }

    /// Setzt einen ID3v2-Textrahmen als UTF-16 (mit BOM), damit Umlaute erhalten bleiben.
    private static func setTextFrame(_ lame: OpaquePointer, _ frame: String, _ value: String?) {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return }
        var text: [UInt16] = [0xFEFF] + Array(value.utf16) + [0]
        _ = text.withUnsafeMutableBufferPointer { buffer in
            id3tag_set_textinfo_utf16(lame, frame, buffer.baseAddress)
        }
    }
}
