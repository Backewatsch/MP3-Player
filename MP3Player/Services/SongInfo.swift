import AVFoundation

/// Detaillierte Angaben zu einem Titel („Mitwirkende an Song anzeigen“).
struct SongInfo {
    struct Entry: Identifiable, Hashable {
        let label: String
        let value: String
        var id: String { label }
    }

    var credits: [Entry] = []
    var file: [Entry] = []

    /// Liest Mitwirkende (Interpret, Komponist, Album, Genre, Jahr …) aus ID3-/iTunes-Tags und Dateiangaben.
    static func load(for track: Track) async -> SongInfo {
        let url = track.fileURL
        let asset = AVURLAsset(url: url)
        var found: [String: String] = [:]

        func put(_ label: String, _ value: String?) {
            guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty,
                  found[label] == nil else { return }
            found[label] = value
        }

        let common = (try? await asset.load(.commonMetadata)) ?? []
        var all = common
        for format in (try? await asset.load(.availableMetadataFormats)) ?? [] {
            all += (try? await asset.loadMetadata(for: format)) ?? []
        }
        for item in all {
            let value = try? await item.load(.stringValue)
            switch item.identifier {
            case .commonIdentifierArtist?, .id3MetadataLeadPerformer?, .iTunesMetadataArtist?:
                put("Interpret", value)
            case .id3MetadataBand?, .iTunesMetadataAlbumArtist?:
                put("Album-Interpret", value)
            case .commonIdentifierAlbumName?, .id3MetadataAlbumTitle?, .iTunesMetadataAlbum?:
                put("Album", value)
            case .id3MetadataComposer?, .iTunesMetadataComposer?, .commonIdentifierCreator?:
                put("Komponist", value)
            case .id3MetadataLyricist?, .iTunesMetadataLyricist?:
                put("Texter", value)
            case .id3MetadataConductor?, .iTunesMetadataConductor?:
                put("Dirigent", value)
            case .id3MetadataContentType?, .iTunesMetadataUserGenre?, .commonIdentifierType?:
                put("Genre", value)
            case .id3MetadataYear?, .id3MetadataRecordingTime?, .iTunesMetadataReleaseDate?, .commonIdentifierCreationDate?:
                put("Jahr", value.map { String($0.prefix(4)) })
            case .commonIdentifierPublisher?, .id3MetadataPublisher?, .iTunesMetadataPublisher?:
                put("Label", value)
            case .commonIdentifierCopyrights?, .id3MetadataCopyright?, .iTunesMetadataCopyright?:
                put("Copyright", value)
            default:
                break
            }
        }
        if found["Interpret"] == nil { put("Interpret", track.artist) }
        if found["Album"] == nil { put("Album", track.album) }

        let order = ["Interpret", "Album-Interpret", "Album", "Komponist", "Texter", "Dirigent", "Genre", "Jahr", "Label", "Copyright"]
        var info = SongInfo()
        info.credits = order.compactMap { key in found[key].map { Entry(label: key, value: $0) } }

        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        var file: [Entry] = [
            Entry(label: "Format", value: url.pathExtension.uppercased()),
            Entry(label: "Dauer", value: track.duration.formattedTime),
            Entry(label: "Größe", value: ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
        ]
        if track.duration > 0, size > 0 {
            let kbps = Int((Double(size) * 8 / track.duration / 1000).rounded())
            file.append(Entry(label: "Bitrate (ca.)", value: "\(kbps) kbit/s"))
        }
        file.append(Entry(label: "Datei", value: track.fileName))
        file.append(Entry(label: "Hinzugefügt", value: track.addedAt.formatted(date: .abbreviated, time: .shortened)))
        info.file = file
        return info
    }
}
