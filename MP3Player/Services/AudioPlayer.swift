import AVFoundation
import MediaPlayer
import UIKit

/// Spielt Titel ab – auch im Hintergrund und bei gesperrtem Bildschirm.
@MainActor
final class AudioPlayer: NSObject, ObservableObject {
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
    /// Zufallswiedergabe verwaltet die App selbst: „Als Nächstes“ kommt so immer direkt nach dem laufenden Titel.
    @Published var shuffle = false {
        didSet { if oldValue != shuffle { applyShuffle() } }
    }
    @Published var repeatMode: RepeatMode = .off
    @Published var errorMessage: String?
    /// Schlafmodus: Zeitpunkt, zu dem die Musik ausgeht (`nil` = aus)
    @Published private(set) var sleepEnd: Date?

    /// Equalizer & Überblenden
    let sound = SoundSettings()

    /// Meldet die vom Decoder ermittelte Dauer (genauer als die Metadaten bei VBR-MP3s).
    var onDurationLoaded: ((UUID, TimeInterval) -> Void)?
    /// Titel gestartet (Verlauf)
    var onTrackStarted: ((UUID) -> Void)?
    /// Titel richtig gehört: mind. 30 s oder die Hälfte („Oft gespielt“, Statistik)
    var onTrackPlayed: ((UUID) -> Void)?
    /// Hörzeit in Sekunden (Statistik)
    var onListened: ((Double) -> Void)?

    /// ID der Playlist, aus der die Warteschlange stammt (`nil` = Alle Titel).
    private(set) var sourcePlaylistID: UUID?
    private(set) var queueIsAllTracks = false

    var current: Track? {
        guard let index, queue.indices.contains(index) else { return nil }
        return queue[index]
    }

    // Audio-Engine: Player → Equalizer → Ausgang
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let eq = AVAudioUnitEQ(numberOfBands: SoundPresets.bands.count)
    private var file: AVAudioFile?
    private var connectedFormat: AVAudioFormat?
    private var sampleRate: Double = 44_100
    private var fileLength: AVAudioFramePosition = 0
    /// Frame, an dem das aktuell geplante Stück beginnt
    private var segmentStart: AVAudioFramePosition = 0
    /// Position beim Pausieren (Frames)
    private var pausedFrame: AVAudioFramePosition = 0
    /// Zähler, damit Rückmeldungen alter (abgebrochener) Abschnitte ignoriert werden
    private var generation = 0

    private var timer: Timer?
    private var history: [Int] = []
    private var observers: [NSObjectProtocol] = []
    /// Reihenfolge ohne Zufall (für das Ausschalten der Zufallswiedergabe)
    private var originalOrder: [Track] = []
    /// Wie viele „Als Nächstes“-Titel schon hinter dem laufenden stehen
    private var nextUpCount = 0
    /// Für Verlauf/Zählung des laufenden Titels
    private var startedID: UUID?
    private var countedID: UUID?
    private var heardSeconds: Double = 0
    private var listenedPending: Double = 0
    private var lastTick: Date?

    override init() {
        super.init()
        configureSession()
        configureRemoteCommands()
        engine.attach(node)
        engine.attach(eq)
        for (i, band) in eq.bands.enumerated() {
            band.filterType = .parametric
            band.frequency = SoundPresets.bands[i]
            band.bandwidth = 1.0
            band.gain = 0
            band.bypass = false
        }
        applySound()
        sound.onChange = { [weak self] in self?.applySound() }
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
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.engineConfigurationChanged() }
        })
    }

    // MARK: Steuerung

    /// Startet die Wiedergabe einer Liste ab einem bestimmten Titel.
    func play(_ tracks: [Track], startAt startIndex: Int, playlistID: UUID? = nil, allTracks: Bool = false) {
        guard tracks.indices.contains(startIndex) else { return }
        originalOrder = tracks
        if shuffle, tracks.count > 1 {
            let first = tracks[startIndex]
            var rest = tracks
            rest.remove(at: startIndex)
            queue = [first] + rest.shuffled()
            history = []
            sourcePlaylistID = playlistID
            queueIsAllTracks = allTracks
            load(index: 0, autoplay: true)
        } else {
            queue = tracks
            history = []
            sourcePlaylistID = playlistID
            queueIsAllTracks = allTracks
            load(index: startIndex, autoplay: true)
        }
    }

    func togglePlayPause() {
        isPlaying ? pause() : resume()
    }

    func resume() {
        guard file != nil else {
            if !queue.isEmpty { load(index: index ?? 0, autoplay: true) }
            return
        }
        if let end = sleepEnd, end <= Date() { sleepEnd = nil }
        startEngineIfNeeded()
        node.play()
        isPlaying = true
        noteStart()
        startTimer()
        updateNowPlaying()
    }

    func pause() {
        guard file != nil else { isPlaying = false; return }
        pausedFrame = currentFrame
        node.pause()
        engine.pause() // spart Akku, solange pausiert
        isPlaying = false
        stopTimer()
        flushListening()
        currentTime = Double(pausedFrame) / sampleRate
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
        guard file != nil else { return }
        let clamped = max(0, min(time, duration))
        let frame = min(AVAudioFramePosition(clamped * sampleRate), fileLength)
        schedule(from: frame)
        pausedFrame = frame
        if isPlaying {
            startEngineIfNeeded()
            node.play()
        }
        currentTime = Double(frame) / sampleRate
        applyVolume()
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

    /// „Als Nächstes abspielen“: direkt nach dem laufenden Titel – mehrere in der gewählten Reihenfolge.
    func playNext(_ track: Track) {
        if queue.isEmpty { play([track], startAt: 0); return }
        let insertAt = min((index ?? -1) + 1 + nextUpCount, queue.count)
        queue.insert(track, at: insertAt)
        nextUpCount += 1
        if let cur = current, let o = originalOrder.firstIndex(where: { $0.id == cur.id }) {
            originalOrder.insert(track, at: min(o + nextUpCount, originalOrder.count))
        } else {
            originalOrder.append(track)
        }
        history = []
    }

    func addToQueue(_ track: Track) {
        if queue.isEmpty { play([track], startAt: 0); return }
        queue.append(track)
        originalOrder.append(track)
    }

    func removeFromQueue(at offsets: IndexSet) {
        if let i = index, offsets.contains(i) { return } // laufenden Titel nicht entfernen
        let removed = offsets.compactMap { queue.indices.contains($0) ? queue[$0].id : nil }
        let removedBefore = index.map { i in offsets.filter { $0 < i }.count } ?? 0
        queue.remove(atOffsets: offsets)
        for id in removed {
            if let o = originalOrder.firstIndex(where: { $0.id == id }) { originalOrder.remove(at: o) }
        }
        if let i = index { index = i - removedBefore }
        history = []
    }

    func moveInQueue(from source: IndexSet, to destination: Int) {
        guard let i = index else { queue.move(fromOffsets: source, toOffset: destination); return }
        var positions = Array(queue.indices)
        positions.move(fromOffsets: source, toOffset: destination)
        queue = positions.map { queue[$0] }
        index = positions.firstIndex(of: i)
        if !shuffle { originalOrder = queue }
        history = []
    }

    func cycleRepeatMode() {
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }

    /// Neuer Titel in der Bibliothek. Heruntergeladene Titel (mit Ziel-Playlist) kommen immer in die
    /// Wiedergabeliste; Importe aus der Dateien-App nur, wenn gerade „Alle Titel“ läuft.
    func libraryDidAdd(_ track: Track, playlistID: UUID?) {
        guard !queue.contains(where: { $0.id == track.id }) else { return }
        if playlistID != nil {
            enqueue(track)
        } else if !queue.isEmpty, queueIsAllTracks {
            queue.append(track)
            originalOrder.append(track)
        }
    }

    /// Hängt einen Titel an die Wiedergabeliste an, ohne die Wiedergabe zu starten.
    /// Ist die Liste leer, steht der Titel pausiert bereit.
    func enqueue(_ track: Track) {
        if queue.isEmpty {
            queue = [track]
            originalOrder = [track]
            history = []
            sourcePlaylistID = nil
            queueIsAllTracks = false
            load(index: 0, autoplay: false)
        } else {
            queue.append(track)
            originalOrder.append(track)
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
        originalOrder = originalOrder.compactMap { byID[$0.id] }
        history = []
        if let newIndex {
            index = newIndex
            updateNowPlaying()
        } else if hadCurrent {
            stop()
        }
    }

    func stop() {
        generation += 1
        node.stop()
        engine.pause()
        flushListening()
        file = nil
        isPlaying = false
        stopTimer()
        index = nil
        queue = []
        originalOrder = []
        nextUpCount = 0
        currentTime = 0
        duration = 0
        sleepEnd = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: Schlafmodus

    func startSleepTimer(minutes: Int) { startSleepTimer(seconds: Double(minutes) * 60) }

    func startSleepTimer(seconds: Double) {
        sleepEnd = Date().addingTimeInterval(seconds)
        applyVolume()
    }

    func cancelSleepTimer() {
        sleepEnd = nil
        applyVolume()
    }

    var sleepRemaining: TimeInterval? { sleepEnd.map { max(0, $0.timeIntervalSinceNow) } }

    // MARK: Equalizer

    /// Pegel, die der Equalizer gerade hat (für Tests)
    var appliedGains: [Float] { eq.bands.map(\.gain) }
    var equalizerActive: Bool { !eq.bypass }

    private func applySound() {
        let gains = sound.currentGains
        for (i, band) in eq.bands.enumerated() where gains.indices.contains(i) { band.gain = gains[i] }
        eq.bypass = !sound.enabled
        applyVolume()
    }

    // MARK: Intern

    private var currentFrame: AVAudioFramePosition {
        guard isPlaying, let nodeTime = node.lastRenderTime, nodeTime.isSampleTimeValid,
              let playerTime = node.playerTime(forNodeTime: nodeTime) else { return pausedFrame }
        return min(fileLength, segmentStart + max(0, playerTime.sampleTime))
    }

    private func startEngineIfNeeded() {
        guard !engine.isRunning else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        engine.prepare()
        do { try engine.start() } catch { errorMessage = "Audio kann nicht gestartet werden: \(error.localizedDescription)" }
    }

    /// Verbindet Player → Equalizer → Ausgang im Format der Datei
    private func connect(for format: AVAudioFormat) {
        if let c = connectedFormat, c.sampleRate == format.sampleRate, c.channelCount == format.channelCount { return }
        let wasRunning = engine.isRunning
        if wasRunning { engine.stop() }
        engine.connect(node, to: eq, format: format)
        engine.connect(eq, to: engine.mainMixerNode, format: format)
        connectedFormat = format
        if wasRunning { startEngineIfNeeded() }
    }

    /// Plant die Datei ab `frame` ein (ohne zu starten)
    private func schedule(from frame: AVAudioFramePosition) {
        guard let file else { return }
        generation += 1
        let gen = generation
        node.stop()
        segmentStart = max(0, min(frame, fileLength))
        let count = AVAudioFrameCount(max(0, fileLength - segmentStart))
        guard count > 0 else {
            Task { @MainActor [weak self] in
                guard let self, self.generation == gen else { return }
                self.trackFinished()
            }
            return
        }
        node.scheduleSegment(file, startingFrame: segmentStart, frameCount: count, at: nil,
                             completionCallbackType: .dataPlayedBack) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.generation == gen else { return }
                self.trackFinished()
            }
        }
    }

    private func load(index i: Int, autoplay: Bool) {
        guard queue.indices.contains(i) else { stop(); return }
        let track = queue[i]
        flushListening()
        do {
            let f = try AVAudioFile(forReading: track.fileURL)
            connect(for: f.processingFormat)
            file = f
            sampleRate = f.processingFormat.sampleRate
            fileLength = f.length
            index = i
            nextUpCount = 0
            startedID = nil
            countedID = nil
            heardSeconds = 0
            duration = Double(fileLength) / sampleRate
            currentTime = 0
            pausedFrame = 0
            onDurationLoaded?(track.id, duration)
            schedule(from: 0)
            applyVolume()
            if autoplay {
                startEngineIfNeeded()
                node.play()
                isPlaying = true
                noteStart()
                startTimer()
            } else {
                isPlaying = false
                stopTimer()
            }
            updateNowPlaying()
        } catch {
            errorMessage = "„\(track.title)“ kann nicht abgespielt werden: \(error.localizedDescription)"
            generation += 1
            node.stop()
            file = nil
            index = i
            isPlaying = false
            stopTimer()
        }
    }

    private func nextIndex(after i: Int, userInitiated: Bool) -> Int? {
        guard !queue.isEmpty else { return nil }
        if i + 1 < queue.count { return i + 1 }
        return (repeatMode == .all || userInitiated) ? 0 : nil
    }

    /// Zufall an: laufender Titel zuerst, Rest gemischt. Zufall aus: ursprüngliche Reihenfolge.
    private func applyShuffle() {
        guard let cur = current else { return }
        if shuffle {
            originalOrder = queue
            var rest = queue
            if let i = index { rest.remove(at: i) }
            queue = [cur] + rest.shuffled()
            index = 0
        } else if !originalOrder.isEmpty {
            queue = originalOrder
            index = queue.firstIndex { $0.id == cur.id } ?? 0
        }
        nextUpCount = 0
        history = []
    }

    private func trackFinished() {
        guard let i = index else { return }
        flushListening()
        if repeatMode == .one {
            schedule(from: 0)
            pausedFrame = 0
            startedID = nil
            countedID = nil
            heardSeconds = 0
            if isPlaying { startEngineIfNeeded(); node.play(); noteStart() }
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

    private func noteStart() {
        guard let id = current?.id, startedID != id else { return }
        startedID = id
        onTrackStarted?(id)
    }

    private func flushListening() {
        if listenedPending > 0 { onListened?(listenedPending) }
        listenedPending = 0
        lastTick = nil
    }

    private func applyVolume() {
        node.volume = SoundPresets.volume(position: currentTime, duration: duration, fade: sound.crossfade,
                                          sleepRemaining: sleepRemaining)
    }

    private func startTimer() {
        stopTimer()
        lastTick = Date()
        let t = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
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
        guard file != nil, isPlaying else { return }
        let now = Date()
        let delta = min(1, max(0, now.timeIntervalSince(lastTick ?? now)))
        lastTick = now
        let position = Double(currentFrame) / sampleRate
        // Lautstärke (Ein-/Ausblenden, Schlafmodus) bei jedem Tick
        currentTime = position
        applyVolume()
        // Schlafmodus abgelaufen → Musik aus
        if let end = sleepEnd, end <= now {
            sleepEnd = nil
            pause()
            applyVolume()
            return
        }
        // Hörzeit und „richtig gehört“
        listenedPending += delta
        heardSeconds += delta
        if listenedPending >= 30 { flushListening(); lastTick = now }
        if let id = current?.id, countedID != id, heardSeconds >= min(30, max(1, duration / 2)) {
            countedID = id
            onTrackPlayed?(id)
        }
    }

    private func engineConfigurationChanged() {
        // z. B. Kopfhörer angeschlossen: Engine neu starten und an gleicher Stelle weiterspielen
        guard file != nil else { return }
        let frame = isPlaying ? currentFrame : pausedFrame
        let wasPlaying = isPlaying
        connectedFormat = nil
        if let f = file { connect(for: f.processingFormat) }
        schedule(from: frame)
        pausedFrame = frame
        if wasPlaying { startEngineIfNeeded(); node.play() }
    }

    private func handleInterruption(typeValue: UInt?, optionsValue: UInt?) {
        guard let typeValue, let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
        switch type {
        case .began:
            if isPlaying {
                pause()
                resumeAfterInterruption = true
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
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        if !track.artist.isEmpty { info[MPMediaItemPropertyArtist] = track.artist }
        // Sperrbildschirm: App-Logo, außer „Eigene Cover“ ist eingeschaltet
        let ownCover = UserDefaults.standard.bool(forKey: "showOwnCovers") ? ArtworkCache.shared.image(for: track) : nil
        if let image = ownCover ?? UIImage(named: "Logo") {
            info[MPMediaItemPropertyArtwork] = Self.makeArtwork(image)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// Bewusst `nonisolated`: der Handler wird von iOS auf einem Hintergrund-Thread aufgerufen.
    nonisolated private static func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}
