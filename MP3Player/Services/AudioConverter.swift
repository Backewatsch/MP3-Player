import AVFoundation

/// Wandelt Videos (MP4, MOV, M4V …) und Audioformate in eine M4A-Audiodatei (AAC) um.
///
/// iOS bringt keinen MP3-Encoder mit; M4A/AAC klingt bei gleicher Größe mindestens so gut
/// und lässt sich überall abspielen.
enum AudioConverter {
    enum ConversionError: LocalizedError {
        case noAudioTrack
        case exportFailed(String)

        var errorDescription: String? {
            switch self {
            case .noAudioTrack: return "Die Datei enthält keine Tonspur."
            case .exportFailed(let reason): return "Umwandlung fehlgeschlagen: \(reason)"
            }
        }
    }

    /// Wandelt `source` in eine neue M4A-Datei mit dem Namen `baseName` um und liefert deren Adresse.
    static func convertToM4A(_ source: URL, baseName: String) async throws -> URL {
        let asset = AVURLAsset(url: source)
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard !audioTracks.isEmpty else { throw ConversionError.noAudioTrack }

        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw ConversionError.exportFailed("Export wird nicht unterstützt.")
        }
        let output = Storage.makeIncomingDirectory().appendingPathComponent("\(baseName).m4a")
        // Titel, Interpret, Cover usw. übernehmen
        session.metadata = (try? await asset.load(.metadata)) ?? []

        if #available(iOS 18.0, *) {
            do {
                try await session.export(to: output, as: .m4a)
            } catch {
                throw ConversionError.exportFailed(error.localizedDescription)
            }
        } else {
            session.outputURL = output
            session.outputFileType = .m4a
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously { continuation.resume() }
            }
            guard session.status == .completed else {
                throw ConversionError.exportFailed(session.error?.localizedDescription ?? "Unbekannter Fehler")
            }
        }
        return output
    }
}
