import AVFoundation

/// Dekodiert ein Lied und misst seine Lautheit („Lautstärke angleichen“)
enum LoudnessAnalyzer {
    /// Verstärkung in dB, damit das Lied so laut klingt wie die anderen; `nil` wenn nicht lesbar
    static func gain(of url: URL) -> Float? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let format = file.processingFormat
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 65_536) else { return nil }
        var meter = LoudnessMeter(sampleRate: format.sampleRate, channels: Int(format.channelCount))
        while true {
            do { try file.read(into: buffer) } catch { break }
            let frames = Int(buffer.frameLength)
            if frames == 0 { break }
            guard let channels = buffer.floatChannelData else { return nil }
            for ch in 0..<Int(format.channelCount) {
                meter.add(UnsafeBufferPointer(start: channels[ch], count: frames))
            }
        }
        return meter.loudnessDB().map { LoudnessMeter.gain(for: $0) }
    }
}
