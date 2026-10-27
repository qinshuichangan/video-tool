import AVFoundation
import CoreTransferable
import SwiftUI
import UniformTypeIdentifiers

/// 从相册选视频时，把文件复制到 App 自己的临时文件夹
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let target = try EditorModel.makeImportURL(name: received.file.lastPathComponent)
            try FileManager.default.copyItem(at: received.file, to: target)
            return PickedMovie(url: target)
        }
    }
}

@MainActor
final class EditorModel: ObservableObject {
    static let formatKey = "outputFormat"
    static let bitrateKey = "audioBitrate"
    static let preciseKey = "preciseTrim"

    // 已载入的文件
    @Published private(set) var sourceURL: URL?
    @Published private(set) var sourceName = ""
    @Published private(set) var duration: Double = 0
    @Published private(set) var hasVideo = false
    @Published private(set) var hasAudio = false
    @Published private(set) var isLoading = false

    // 截取范围（文字输入）
    @Published var startText = ""
    @Published var endText = ""
    @Published private(set) var currentTime: Double = 0

    // 导出
    @Published private(set) var isExporting = false
    @Published private(set) var progress: Double = 0
    @Published private(set) var resultURL: URL?
    @Published var errorMessage: String?

    let player = AVPlayer()
    private var timeObserver: Any?
    private var boundaryObserver: Any?
    private var exportTask: Task<URL, Error>?

    init() {
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 20), queue: .main
        ) { [weak self] time in
            guard let self else { return }
            let seconds = time.seconds
            guard seconds.isFinite else { return }
            Task { @MainActor in self.currentTime = seconds }
        }
    }

    // MARK: - 范围

    var startSeconds: Double? { TimeText.parse(startText) }
    var endSeconds: Double? { TimeText.parse(endText) }

    /// 范围有问题时返回提示文字，没问题返回 nil
    var rangeProblem: String? {
        guard sourceURL != nil else { return nil }
        guard let start = startSeconds else { return "开始时间格式不对（例：1:23 或 83.5）" }
        guard let end = endSeconds else { return "结束时间格式不对（例：2:05 或 125）" }
        if start >= duration { return "开始时间超过了总长度 \(TimeText.format(duration))" }
        if end > duration + 0.05 { return "结束时间超过了总长度 \(TimeText.format(duration))" }
        if end <= start { return "结束时间要比开始时间晚" }
        return nil
    }

    var selectedLength: Double? {
        guard rangeProblem == nil, let s = startSeconds, let e = endSeconds else { return nil }
        return min(e, duration) - s
    }

    private var isFullRange: Bool {
        guard let s = startSeconds, let e = endSeconds else { return false }
        return s < 0.005 && e >= duration - 0.005
    }

    func setStartToCurrent() { startText = TimeText.format(currentTime) }
    func setEndToCurrent() { endText = TimeText.format(min(currentTime, duration)) }

    func resetRange() {
        startText = TimeText.format(0)
        endText = TimeText.format(duration)
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero)
    }

    /// 只播放选中的部分，到结束时间自动停
    func previewSelection() {
        guard rangeProblem == nil, let start = startSeconds, let end = endSeconds else { return }
        removeBoundaryObserver()
        let endTime = CMTime(seconds: min(end, duration), preferredTimescale: 600)
        boundaryObserver = player.addBoundaryTimeObserver(
            forTimes: [NSValue(time: endTime)], queue: .main
        ) { [weak self] in
            guard let self else { return }
            Task { @MainActor in
                self.player.pause()
                self.removeBoundaryObserver()
            }
        }
        let player = self.player
        player.seek(to: CMTime(seconds: start, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero) { finished in
            guard finished else { return }
            Task { @MainActor in player.play() }
        }
    }

    private func removeBoundaryObserver() {
        if let boundaryObserver { player.removeTimeObserver(boundaryObserver) }
        boundaryObserver = nil
    }

    // MARK: - 载入

    static func importFolder() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("Imported", isDirectory: true)
    }

    /// 在临时文件夹里准备一个不重名的位置（nonisolated：相册导入时在后台调用）
    nonisolated static func makeImportURL(name: String) throws -> URL {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("Imported", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent(name.isEmpty ? "video.mov" : name)
    }

    /// 从「文件」App 或别的 App 传来的文件：先复制一份再用
    func loadExternal(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let target = try Self.makeImportURL(name: url.lastPathComponent)
            try FileManager.default.copyItem(at: url, to: target)
            load(target)
        } catch {
            errorMessage = "读取文件失败：\(error.localizedDescription)"
        }
    }

    func loadPhotoItem(_ movie: PickedMovie) {
        load(movie.url)
    }

    /// 载入一个已经在 App 里的文件
    func load(_ url: URL) {
        cancelExport()
        removeBoundaryObserver()
        player.pause()
        cleanImports(keeping: url)

        isLoading = true
        errorMessage = nil
        resultURL = nil
        Task {
            defer { isLoading = false }
            let asset = AVURLAsset(url: url)
            do {
                let length = try await asset.load(.duration).seconds
                let videoTracks = try await asset.loadTracks(withMediaType: .video)
                let audioTracks = try await asset.loadTracks(withMediaType: .audio)
                guard length.isFinite, length > 0 else {
                    errorMessage = "读不出这个文件的长度，可能不是视频或音频"
                    return
                }
                guard !videoTracks.isEmpty || !audioTracks.isEmpty else {
                    errorMessage = "这个文件里没有画面也没有声音"
                    return
                }
                sourceURL = url
                sourceName = url.deletingPathExtension().lastPathComponent
                duration = length
                hasVideo = !videoTracks.isEmpty
                hasAudio = !audioTracks.isEmpty
                currentTime = 0
                resetRange()
                player.replaceCurrentItem(with: AVPlayerItem(asset: asset))
            } catch {
                errorMessage = "打开失败：\(error.localizedDescription)"
            }
        }
    }

    /// 删掉以前导入的临时文件（正在用的那个除外）
    private func cleanImports(keeping url: URL) {
        let folder = Self.importFolder()
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else { return }
        let keepPath = url.standardizedFileURL.path
        for item in items where !keepPath.hasPrefix(item.standardizedFileURL.path + "/") {
            try? fm.removeItem(at: item)
        }
    }

    // MARK: - 导出

    func export(format: OutputFormat, bitrate: Int, precise: Bool) {
        guard let source = sourceURL, !isExporting else { return }
        if let problem = rangeProblem {
            errorMessage = problem
            return
        }
        guard let start = startSeconds, let end = endSeconds else { return }
        if format == .video && !hasVideo {
            errorMessage = "这个文件没有画面，只能转成音频"
            return
        }
        if format.isAudio && !hasAudio {
            errorMessage = "这个文件里没有声音"
            return
        }

        player.pause()
        let range = CMTimeRange(
            start: CMTime(seconds: start, preferredTimescale: 600),
            end: CMTime(seconds: min(end, duration), preferredTimescale: 600))
        let output = Self.outputURL(name: sourceName, start: start, end: end,
                                    full: isFullRange, ext: format.fileExtension)

        isExporting = true
        progress = 0
        errorMessage = nil
        resultURL = nil

        let report: @Sendable (Double) -> Void = { [weak self] value in
            guard let self else { return }
            Task { @MainActor in self.progress = value }
        }
        let task = Task.detached(priority: .userInitiated) { () async throws -> URL in
            if format == .video {
                return try await Exporter.trimVideo(source: source, range: range, precise: precise,
                                                    output: output, progress: report)
            } else {
                return try await Exporter.convertAudio(source: source, range: range, format: format,
                                                       bitrate: bitrate, output: output, progress: report)
            }
        }
        exportTask = task

        Task {
            do {
                let url = try await task.value
                resultURL = url
            } catch is CancellationError {
                errorMessage = "已取消"
            } catch {
                errorMessage = error.localizedDescription
            }
            isExporting = false
            exportTask = nil
        }
    }

    func cancelExport() {
        exportTask?.cancel()
    }

    // MARK: - 文件名

    static func outputFolder() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// 例：「我的视频_1m05s-2m30s.mp3」，整段的话就是「我的视频.mp3」；重名时加 (2)、(3)…
    static func outputURL(name: String, start: Double, end: Double, full: Bool, ext: String) -> URL {
        var base = name.isEmpty ? "输出" : name
        if !full {
            base += "_\(TimeText.fileStamp(start))-\(TimeText.fileStamp(end))"
        }
        let folder = outputFolder()
        var url = folder.appendingPathComponent(base).appendingPathExtension(ext)
        var n = 2
        let fm = FileManager.default
        // 视频可能被改存成 .mov，两种都要避开
        while fm.fileExists(atPath: url.path)
                || (ext == "mp4" && fm.fileExists(atPath: url.deletingPathExtension().appendingPathExtension("mov").path)) {
            url = folder.appendingPathComponent("\(base) (\(n))").appendingPathExtension(ext)
            n += 1
        }
        return url
    }
}
