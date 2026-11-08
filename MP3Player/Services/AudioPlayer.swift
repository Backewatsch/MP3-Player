import AVFoundation
import MediaPlayer
import UIKit

/// Spielt Titel ab – auch im Hintergrund und bei gesperrtem Bildschirm.
@MainActor
final class AudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    enum RepeatMode: CaseIterable {
        case off, all, one

        var symbol: String {
            switch self {
            case .off, .all: return "repeat"
            case .one: return "repeat.1"
            }
        }
    }

    @Published private(set) var queue: [Track] = []
    @Published private(set) var index: Int?
    @Published private(set) var isPlaying = false
    @Published private(set) var currentTime: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published var shuffle = false
    @Published var repeatMode: RepeatMode = .off
    @Published var errorMessage: String?

    /// Meldet die vom Decoder ermittelte Dauer (genauer als die Metadaten bei VBR-MP3s).
    var onDurationLoaded: ((UUID, TimeInterval) -> Void)?

    /// ID der Playlist, aus der die Warteschlange stammt (`nil` = Alle Titel).
    private(set) var sourcePlaylistID: UUID?
    private(set) var queueIsAllTracks = false

    var current: Track? {
        guard let index, queue.indices.contains(index) else { return nil }
        return queue[index]
    }

    private var player: AVAudioPlayer?
    private var timer: Timer?
    private var history: [Int] = []
    private var observers: [NSObjectProtocol] = []

    override init() {
        super.init()
        configureSession()
        configureRemoteCommands()
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let typeValue = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let optionsValue = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt
            Task { @MainActor in self?.handleInterruption(typeValue: typeValue, optionsValue: optionsValue) }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let reasonValue = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            Task { @MainActor in
                // Kopfhörer abgezogen → pausieren
                if reasonValue == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue { self?.pause() }
            }
        })
    }

    // MARK: Steuerung

    /// Startet die Wiedergabe einer Liste ab einem bestimmten Titel.
    func play(_ tracks: [Track], startAt startIndex: Int, playlistID: UUID? = nil, allTracks: Bool = false) {
        guard tracks.indices.contains(startIndex) else { return }
        queue = tracks
        history = []
        sourcePlaylistID = playlistID
        queueIsAllTracks = allTracks
        load(index: startIndex, autoplay: true)
    }

    func togglePlayPause() {
        isPlaying ? pause() : resume()
    }

    func resume() {
        guard let player else {
            if !queue.isEmpty { load(index: index ?? 0, autoplay: true) }
            return
        }
        try? AVAudioSession.sharedInstance().setActive(true)
        player.play()
        isPlaying = true
        startTimer()
        updateNowPlaying()
    }

    func pause() {
        player?.pause()
        isPlaying = false
        stopTimer()
        currentTime = player?.currentTime ?? 0
        updateNowPlaying()
    }

    func next() {
        guard let i = index, !queue.isEmpty else { return }
        let n = nextIndex(after: i, userInitiated: true) ?? 0
        history.append(i)
        load(index: n, autoplay: true)
    }

    func previous() {
        guard let i = index else { return }
        if currentTime > 3 {
            seek(to: 0)
        } else if let last = history.popLast(), queue.indices.contains(last) {
            load(index: last, autoplay: isPlaying)
        } else if i > 0 {
            load(index: i - 1, autoplay: isPlaying)
        } else {
            seek(to: 0)
        }
    }

    func seek(to time: TimeInterval) {
        guard let player else { return }
        player.currentTime = max(0, min(time, player.duration))
        currentTime = player.currentTime
        updateNowPlaying()
    }

    func play(queueIndex: Int) {
        guard queue.indices.contains(queueIndex) else { return }
        if let i = index { history.append(i) }
        load(index: queueIndex, autoplay: true)
    }

    /// „Mix starten“: der Titel zuerst, dann gemischt – Titel desselben Interpreten bevorzugt.
    func startMix(with seed: Track, from all: [Track]) {
        let others = all.filter { $0.id != seed.id }
        let sameArtist = seed.artist.isEmpty ? [] : others.filter { $0.artist == seed.artist }.shuffled()
        let rest = others.filter { seed.artist.isEmpty || $0.artist != seed.artist }.shuffled()
        shuffle = false
        play([seed] + sameArtist + rest, startAt: 0, allTracks: true)
    }

    func playNext(_ track: Track) {
        if queue.isEmpty { play([track], startAt: 0); return }
        let insertAt = (index ?? -1) + 1
        queue.insert(track, at: min(insertAt, queue.count))
        history = []
    }

    func addToQueue(_ track: Track) {
        if queue.isEmpty { play([track], startAt: 0); return }
        queue.append(track)
    }

    func removeFromQueue(at offsets: IndexSet) {
        if let i = index, offsets.contains(i) { return } // laufenden Titel nicht entfernen
        let removedBefore = index.map { i in offsets.filter { $0 < i }.count } ?? 0
        queue.remove(atOffsets: offsets)
        if let i = index { index = i - removedBefore }
        history = []
    }

    func moveInQueue(from source: IndexSet, to destination: Int) {
        guard let i = index else { queue.move(fromOffsets: source, toOffset: destination); return }
        var positions = Array(queue.indices)
        positions.move(fromOffsets: source, toOffset: destination)
        queue = positions.map { queue[$0] }
        index = positions.firstIndex(of: i)
        history = []
    }

    func cycleRepeatMode() {
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }

    /// Neuer Titel in der Bibliothek → an die laufende Warteschlange anhängen, falls sie passt.
    func libraryDidAdd(_ track: Track, playlistID: UUID?) {
        guard !queue.isEmpty, !queue.contains(where: { $0.id == track.id }) else { return }
        if queueIsAllTracks || (playlistID != nil && playlistID == sourcePlaylistID) {
            queue.append(track)
        }
    }

    /// Titel wurde gelöscht bzw. geändert.
    func libraryDidChange(_ tracks: [Track]) {
        guard !queue.isEmpty else { return }
        let byID = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var newQueue: [Track] = []
        var newIndex: Int?
        for (position, track) in queue.enumerated() {
            guard let updated = byID[track.id] else { continue }
            if position == index { newIndex = newQueue.count }
            newQueue.append(updated)
        }
        guard newQueue != queue else { return }
        let hadCurrent = index != nil
        queue = newQueue
        history = []
        if let newIndex {
            index = newIndex
            updateNowPlaying()
        } else if hadCurrent {
            stop()
        }
    }

    func stop() {
        player?.stop()
        player = nil
        isPlaying = false
        stopTimer()
        index = nil
        queue = []
        currentTime = 0
        duration = 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: Intern

    private func load(index i: Int, autoplay: Bool) {
        guard queue.indices.contains(i) else { stop(); return }
        let track = queue[i]
        do {
            let p = try AVAudioPlayer(contentsOf: track.fileURL)
            p.delegate = self
            p.prepareToPlay()
            player?.stop()
            player = p
            index = i
            duration = p.duration
            currentTime = 0
            onDurationLoaded?(track.id, p.duration)
            if autoplay {
                try? AVAudioSession.sharedInstance().setActive(true)
                p.play()
                isPlaying = true
                startTimer()
            } else {
                isPlaying = false
                stopTimer()
            }
            updateNowPlaying()
        } catch {
            errorMessage = "„\(track.title)“ kann nicht abgespielt werden: \(error.localizedDescription)"
            player?.stop()
            player = nil
            index = i
            isPlaying = false
            stopTimer()
        }
    }

    private func nextIndex(after i: Int, userInitiated: Bool) -> Int? {
        guard !queue.isEmpty else { return nil }
        if shuffle, queue.count > 1 {
            var n = i
            while n == i { n = Int.random(in: 0..<queue.count) }
            return n
        }
        if i + 1 < queue.count { return i + 1 }
        return (repeatMode == .all || userInitiated) ? 0 : nil
    }

    private func trackFinished() {
        guard let i = index else { return }
        if repeatMode == .one {
            player?.currentTime = 0
            player?.play()
            updateNowPlaying()
            return
        }
        if let n = nextIndex(after: i, userInitiated: false) {
            history.append(i)
            load(index: n, autoplay: true)
        } else {
            // Ende der Liste: zurück zum Anfang, pausiert
            load(index: 0, autoplay: false)
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.trackFinished() }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        let message = error?.localizedDescription ?? "Unbekannter Fehler"
        Task { @MainActor in
            self.errorMessage = "Fehler beim Abspielen: \(message)"
            self.trackFinished()
        }
    }

    private func startTimer() {
        stopTimer()
        let t = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func tick() {
        guard let player else { return }
        currentTime = player.currentTime
    }

    private func handleInterruption(typeValue: UInt?, optionsValue: UInt?) {
        guard let typeValue, let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
        switch type {
        case .began:
            if isPlaying {
                player?.pause()
                isPlaying = false
                stopTimer()
                resumeAfterInterruption = true
                updateNowPlaying()
            }
        case .ended:
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue ?? 0)
            if options.contains(.shouldResume), resumeAfterInterruption { resume() }
            resumeAfterInterruption = false
        @unknown default:
            break
        }
    }

    private var resumeAfterInterruption = false

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
    }

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.resume() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayPause() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.next() }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.previous() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let position = event.positionTime
            Task { @MainActor in self?.seek(to: position) }
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let track = current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player?.currentTime ?? 0,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        if !track.artist.isEmpty { info[MPMediaItemPropertyArtist] = track.artist }
        if let image = ArtworkCache.shared.image(for: track) {
            info[MPMediaItemPropertyArtwork] = Self.makeArtwork(image)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// Bewusst `nonisolated`: der Handler wird von iOS auf einem Hintergrund-Thread aufgerufen.
    nonisolated private static func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}
