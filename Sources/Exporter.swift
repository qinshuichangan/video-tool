import AVFoundation
import AudioToolbox

/// 输出格式
enum OutputFormat: String, CaseIterable, Identifiable {
    case video, compressed, m4a, aac, mp3, ogg

    var id: String { rawValue }

    var label: String {
        switch self {
        case .video: return "视频（MP4）"
        case .compressed: return "压缩视频（调码率/帧率/分辨率）"
        case .m4a: return "AAC 音频（.m4a）"
        case .aac: return "AAC 音频（.aac）"
        case .mp3: return "MP3 音频"
        case .ogg: return "OGG 音频（Vorbis）"
        }
    }

    var fileExtension: String {
        switch self {
        case .video, .compressed: return "mp4"
        case .m4a: return "m4a"
        case .aac: return "aac"
        case .mp3: return "mp3"
        case .ogg: return "ogg"
        }
    }

    var isVideo: Bool { self == .video || self == .compressed }
    var isAudio: Bool { !isVideo }
}

/// 压缩视频的设置
struct CompressSettings: Sendable {
    var shortSide: Int        // 分辨率（按短边算），0 = 原始
    var fps: Int              // 帧率，0 = 原始
    var bitrateMbps: Double   // 视频码率
    var hevc: Bool            // true = HEVC（更小），false = H.264（兼容性好）

    struct Option: Hashable, Sendable {
        let value: Int
        let label: String
    }

    static let resolutions: [Option] = [
        .init(value: 0, label: "原始"), .init(value: 1080, label: "1080p"), .init(value: 720, label: "720p"),
        .init(value: 540, label: "540p"), .init(value: 480, label: "480p"), .init(value: 360, label: "360p"),
    ]
    static let frameRates: [Option] = [
        .init(value: 0, label: "原始"), .init(value: 60, label: "60 fps"), .init(value: 30, label: "30 fps"),
        .init(value: 25, label: "25 fps"), .init(value: 24, label: "24 fps"), .init(value: 15, label: "15 fps"),
    ]
    static let bitrateRange: ClosedRange<Double> = 0.3...20

    /// 预计文件大小（MB），声音按 128 kbps 算
    static func estimatedMB(seconds: Double, bitrateMbps: Double) -> Double {
        (bitrateMbps + 0.128) * seconds / 8
    }
}

struct AppError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

enum Exporter {
    static let bitrates = [96, 128, 160, 192, 256, 320]

    // MARK: - 视频截取

    /// 截取视频。precise = false 时不重新编码（快、无损）；true 时重新编码（慢，但切点精确）。
    /// 返回实际写出的文件（不支持 mp4 时会改成 mov）。
    static func trimVideo(source: URL, range: CMTimeRange, precise: Bool, output: URL,
                          progress: @escaping @Sendable (Double) -> Void) async throws -> URL {
        let asset = AVURLAsset(url: source)
        let preset = precise ? AVAssetExportPresetHighestQuality : AVAssetExportPresetPassthrough
        guard let session = AVAssetExportSession(asset: asset, presetName: preset) else {
            throw AppError("这个视频不支持导出")
        }
        var out = output
        if session.supportedFileTypes.contains(.mp4) {
            session.outputFileType = .mp4
        } else if session.supportedFileTypes.contains(.mov) {
            session.outputFileType = .mov
            out = output.deletingPathExtension().appendingPathExtension("mov")
        } else {
            throw AppError("这个视频不支持导出为 MP4")
        }
        try? FileManager.default.removeItem(at: out)
        session.outputURL = out
        session.timeRange = range
        session.shouldOptimizeForNetworkUse = true

        let poller = Task {
            while !Task.isCancelled {
                progress(Double(session.progress))
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
        defer { poller.cancel() }

        await withTaskCancellationHandler {
            await session.export()
        } onCancel: {
            session.cancelExport()
        }

        switch session.status {
        case .completed:
            progress(1)
            return out
        case .cancelled:
            try? FileManager.default.removeItem(at: out)
            throw CancellationError()
        default:
            try? FileManager.default.removeItem(at: out)
            let reason = session.error?.localizedDescription ?? "未知错误"
            throw AppError("导出失败：\(reason)")
        }
    }

    // MARK: - 压缩视频

    /// 重新编码视频，可以调分辨率、帧率、码率。输出 MP4（HEVC 或 H.264 + AAC）。
    /// 分辨率和帧率只会降低、不会提高。
    static func compressVideo(source: URL, range: CMTimeRange, settings s: CompressSettings, output: URL,
                              progress: @escaping @Sendable (Double) -> Void) async throws -> URL {
        let asset = AVURLAsset(url: source)
        let videoTracks = try await asset.loadTracks(withMediaType: .video)
        guard let firstVideo = videoTracks.first else { throw AppError("这个文件没有画面") }
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        let sourceFPS = try await firstVideo.load(.nominalFrameRate)

        // 系统帮忙把方向处理好（竖拍的视频不会变成横的）
        let composition = try await AVMutableVideoComposition.videoComposition(withPropertiesOf: asset)
        let renderSize = composition.renderSize
        guard renderSize.width > 0, renderSize.height > 0 else { throw AppError("读不出视频的尺寸") }

        // 分辨率：按短边缩小，宽高取偶数
        let shortSource = min(renderSize.width, renderSize.height)
        let scale = s.shortSide > 0 ? min(1, CGFloat(s.shortSide) / shortSource) : 1
        let width = max(2, Int((renderSize.width * scale / 2).rounded()) * 2)
        let height = max(2, Int((renderSize.height * scale / 2).rounded()) * 2)

        // 帧率：不超过原视频
        let originalFPS = sourceFPS > 0 ? Int(sourceFPS.rounded()) : 30
        let fps = s.fps > 0 ? min(s.fps, originalFPS) : originalFPS
        composition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(fps))

        // HDR（iPhone 默认拍的）统一转成普通 SDR，到哪里播放颜色都正常
        composition.colorPrimaries = AVVideoColorPrimaries_ITU_R_709_2
        composition.colorTransferFunction = AVVideoTransferFunction_ITU_R_709_2
        composition.colorYCbCrMatrix = AVVideoYCbCrMatrix_ITU_R_709_2

        // ---- 读取 ----
        let reader = try AVAssetReader(asset: asset)
        reader.timeRange = range

        let videoOut = AVAssetReaderVideoCompositionOutput(videoTracks: videoTracks, videoSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
        ])
        videoOut.videoComposition = composition
        videoOut.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOut) else { throw AppError("无法读取这个视频的画面") }
        reader.add(videoOut)

        // ---- 写入 ----
        try? FileManager.default.removeItem(at: output)
        let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
        writer.shouldOptimizeForNetworkUse = true

        var compression: [String: Any] = [
            AVVideoAverageBitRateKey: Int(s.bitrateMbps * 1_000_000),
            AVVideoExpectedSourceFrameRateKey: fps,
            AVVideoMaxKeyFrameIntervalKey: fps * 2,
        ]
        if !s.hevc { compression[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel }

        let videoIn = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: s.hevc ? AVVideoCodecType.hevc : AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspect,
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
            ],
            AVVideoCompressionPropertiesKey: compression,
        ])
        videoIn.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoIn) else { throw AppError("这个设置没法编码，换个分辨率或编码试试") }
        writer.add(videoIn)

        // 声音：重新编码成 AAC（单声道保持单声道，其它混成立体声）
        var audioOut: AVAssetReaderAudioMixOutput?
        var audioIn: AVAssetWriterInput?
        if !audioTracks.isEmpty {
            var sourceChannels = 2
            var sourceRate = 44100.0
            if let desc = try await audioTracks[0].load(.formatDescriptions).first,
               let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc)?.pointee {
                sourceChannels = Int(asbd.mChannelsPerFrame)
                sourceRate = asbd.mSampleRate
            }
            let channels = sourceChannels == 1 ? 1 : 2
            let sampleRate = (sourceRate == 48000 || sourceRate == 44100) ? sourceRate : 44100
            var layout = AudioChannelLayout()
            layout.mChannelLayoutTag = channels == 1 ? kAudioChannelLayoutTag_Mono : kAudioChannelLayoutTag_Stereo
            let layoutData = Data(bytes: &layout, count: MemoryLayout<AudioChannelLayout>.size)

            let out = AVAssetReaderAudioMixOutput(audioTracks: audioTracks, audioSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: channels,
                AVChannelLayoutKey: layoutData,
            ])
            out.alwaysCopiesSampleData = false
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: channels,
                AVChannelLayoutKey: layoutData,
                AVEncoderBitRateKey: channels == 1 ? 96_000 : 128_000,
            ])
            input.expectsMediaDataInRealTime = false
            if reader.canAdd(out) && writer.canAdd(input) {
                reader.add(out)
                writer.add(input)
                audioOut = out
                audioIn = input
            }
        }

        guard reader.startReading() else {
            throw AppError("读取失败：\(reader.error?.localizedDescription ?? "未知错误")")
        }
        guard writer.startWriting() else {
            reader.cancelReading()
            throw AppError("无法写入：\(writer.error?.localizedDescription ?? "未知错误")")
        }
        writer.startSession(atSourceTime: range.start)

        // ---- 把画面和声音一边读一边写 ----
        let startSeconds = range.start.seconds
        let totalSeconds = max(range.duration.seconds, 0.001)

        await withTaskCancellationHandler {
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                let group = DispatchGroup()

                func pump(_ out: AVAssetReaderOutput, into input: AVAssetWriterInput,
                          label: String, reportsProgress: Bool) {
                    group.enter()
                    var finished = false
                    var lastReported = -1.0
                    func finish() {
                        finished = true
                        input.markAsFinished()
                        group.leave()
                    }
                    input.requestMediaDataWhenReady(on: DispatchQueue(label: label)) {
                        guard !finished else { return }
                        while input.isReadyForMoreMediaData {
                            guard let buffer = out.copyNextSampleBuffer() else { finish(); return }
                            if reportsProgress {
                                let pts = CMSampleBufferGetPresentationTimeStamp(buffer)
                                if pts.isNumeric {
                                    let fraction = min(max((pts.seconds - startSeconds) / totalSeconds, 0), 0.99)
                                    if fraction - lastReported >= 0.01 {
                                        lastReported = fraction
                                        progress(fraction)
                                    }
                                }
                            }
                            if !input.append(buffer) {
                                reader.cancelReading()
                                finish()
                                return
                            }
                        }
                    }
                }

                pump(videoOut, into: videoIn, label: "compress.video", reportsProgress: true)
                if let audioOut, let audioIn {
                    pump(audioOut, into: audioIn, label: "compress.audio", reportsProgress: false)
                }
                group.notify(queue: .global()) { done.resume() }
            }
        } onCancel: {
            reader.cancelReading()
        }

        if Task.isCancelled {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: output)
            throw CancellationError()
        }
        if reader.status == .failed || writer.status == .failed {
            let reason = writer.error?.localizedDescription ?? reader.error?.localizedDescription ?? "未知错误"
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: output)
            throw AppError("压缩失败：\(reason)")
        }

        await writer.finishWriting()
        guard writer.status == .completed else {
            let reason = writer.error?.localizedDescription ?? "未知错误"
            try? FileManager.default.removeItem(at: output)
            throw AppError("压缩失败：\(reason)")
        }
        progress(1)
        return output
    }

    // MARK: - 转成音频

    static func convertAudio(source: URL, range: CMTimeRange, format: OutputFormat, bitrate: Int,
                             output: URL, progress: @escaping @Sendable (Double) -> Void) async throws -> URL {
        let asset = AVURLAsset(url: source)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        guard !tracks.isEmpty else { throw AppError("这个文件里没有声音") }

        // 声道：单声道保持单声道，其它一律混成立体声
        var sourceChannels = 2
        var sourceRate = 44100.0
        if let desc = try await tracks[0].load(.formatDescriptions).first,
           let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc)?.pointee {
            sourceChannels = Int(asbd.mChannelsPerFrame)
            sourceRate = asbd.mSampleRate
        }
        let channels = sourceChannels == 1 ? 1 : 2
        let sampleRate = (sourceRate == 48000 || sourceRate == 44100) ? sourceRate : 44100

        var layout = AudioChannelLayout()
        layout.mChannelLayoutTag = channels == 1 ? kAudioChannelLayoutTag_Mono : kAudioChannelLayoutTag_Stereo
        let layoutData = Data(bytes: &layout, count: MemoryLayout<AudioChannelLayout>.size)

        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVChannelLayoutKey: layoutData,
        ]

        let reader = try AVAssetReader(asset: asset)
        reader.timeRange = range
        let readerOutput = AVAssetReaderAudioMixOutput(audioTracks: tracks, audioSettings: settings)
        readerOutput.alwaysCopiesSampleData = false
        guard reader.canAdd(readerOutput) else { throw AppError("无法读取这个文件的声音") }
        reader.add(readerOutput)

        try? FileManager.default.removeItem(at: output)
        let sink: PCMSink
        switch format {
        case .mp3:
            sink = try MP3Sink(url: output, sampleRate: Int(sampleRate), channels: channels, bitrate: bitrate)
        case .ogg:
            sink = try OggSink(url: output, sampleRate: Int(sampleRate), channels: channels,
                               quality: vorbisQuality(for: bitrate))
        case .m4a:
            sink = try AACSink(url: output, fileType: kAudioFileM4AType,
                               sampleRate: sampleRate, channels: channels, bitrate: bitrate)
        case .aac:
            sink = try AACSink(url: output, fileType: kAudioFileAAC_ADTSType,
                               sampleRate: sampleRate, channels: channels, bitrate: bitrate)
        case .video, .compressed:
            throw AppError("内部错误：格式不对")
        }

        guard reader.startReading() else {
            sink.abort()
            try? FileManager.default.removeItem(at: output)
            throw AppError("读取失败：\(reader.error?.localizedDescription ?? "未知错误")")
        }

        let totalSeconds = max(range.duration.seconds, 0.001)
        var scratch = [Float]()
        var lastReported = -1.0

        do {
            while let buffer = readerOutput.copyNextSampleBuffer() {
                try Task.checkCancellation()
                guard let block = CMSampleBufferGetDataBuffer(buffer) else { continue }
                let length = CMBlockBufferGetDataLength(block)
                let sampleCount = length / MemoryLayout<Float>.size
                let frames = sampleCount / channels
                guard frames > 0 else { continue }
                if scratch.count < sampleCount {
                    scratch = [Float](repeating: 0, count: sampleCount)
                }
                let status = scratch.withUnsafeMutableBytes { raw in
                    CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length,
                                               destination: raw.baseAddress!)
                }
                guard status == kCMBlockBufferNoErr else { throw AppError("读取声音数据失败") }
                try scratch.withUnsafeBufferPointer { ptr in
                    try sink.write(ptr.baseAddress!, frames: frames)
                }

                let pts = CMSampleBufferGetPresentationTimeStamp(buffer)
                if pts.isNumeric {
                    let fraction = min(max((pts.seconds - range.start.seconds) / totalSeconds, 0), 1)
                    if fraction - lastReported >= 0.01 {
                        lastReported = fraction
                        progress(fraction)
                    }
                }
            }
            try Task.checkCancellation()
            if reader.status == .failed {
                throw AppError("读取失败：\(reader.error?.localizedDescription ?? "未知错误")")
            }
            try sink.finish()
        } catch {
            reader.cancelReading()
            sink.abort()
            try? FileManager.default.removeItem(at: output)
            throw error
        }
        progress(1)
        return output
    }

    /// 码率 → Vorbis 质量参数（44.1kHz 立体声时大致对应）
    static func vorbisQuality(for bitrate: Int) -> Float {
        switch bitrate {
        case ..<112: return 0.2
        case ..<144: return 0.4
        case ..<176: return 0.5
        case ..<224: return 0.6
        case ..<288: return 0.8
        default: return 0.9
        }
    }
}

// MARK: - 编码器

/// 接收 32 位浮点、交错排列的 PCM
protocol PCMSink: AnyObject {
    func write(_ samples: UnsafePointer<Float>, frames: Int) throws
    func finish() throws
    func abort()
}

final class MP3Sink: PCMSink {
    private var encoder: OpaquePointer?

    init(url: URL, sampleRate: Int, channels: Int, bitrate: Int) throws {
        encoder = url.withUnsafeFileSystemRepresentation { (path) -> OpaquePointer? in
            guard let path else { return nil }
            return mp3enc_open(path, Int32(sampleRate), Int32(channels), Int32(bitrate))
        }
        if encoder == nil { throw AppError("无法创建 MP3 文件") }
    }

    func write(_ samples: UnsafePointer<Float>, frames: Int) throws {
        guard let encoder else { return }
        if mp3enc_write(encoder, samples, Int32(frames)) != 0 { throw AppError("MP3 编码失败") }
    }

    func finish() throws {
        guard let encoder else { return }
        self.encoder = nil
        if mp3enc_finish(encoder) != 0 { throw AppError("MP3 写入失败（空间不够？）") }
    }

    func abort() {
        guard let encoder else { return }
        self.encoder = nil
        mp3enc_abort(encoder)
    }

    deinit { abort() }
}

final class OggSink: PCMSink {
    private var encoder: OpaquePointer?

    init(url: URL, sampleRate: Int, channels: Int, quality: Float) throws {
        encoder = url.withUnsafeFileSystemRepresentation { (path) -> OpaquePointer? in
            guard let path else { return nil }
            return oggenc_open(path, Int32(sampleRate), Int32(channels), quality)
        }
        if encoder == nil { throw AppError("无法创建 OGG 文件") }
    }

    func write(_ samples: UnsafePointer<Float>, frames: Int) throws {
        guard let encoder else { return }
        if oggenc_write(encoder, samples, Int32(frames)) != 0 { throw AppError("OGG 编码失败") }
    }

    func finish() throws {
        guard let encoder else { return }
        self.encoder = nil
        if oggenc_finish(encoder) != 0 { throw AppError("OGG 写入失败（空间不够？）") }
    }

    func abort() {
        guard let encoder else { return }
        self.encoder = nil
        oggenc_abort(encoder)
    }

    deinit { abort() }
}

/// 用系统自带的 AAC 编码器写 .m4a 或 .aac（ADTS）
final class AACSink: PCMSink {
    private var file: ExtAudioFileRef?
    private let channels: Int

    init(url: URL, fileType: AudioFileTypeID, sampleRate: Double, channels: Int, bitrate: Int) throws {
        self.channels = channels

        var target = AudioStreamBasicDescription(
            mSampleRate: sampleRate, mFormatID: kAudioFormatMPEG4AAC, mFormatFlags: 0,
            mBytesPerPacket: 0, mFramesPerPacket: 1024, mBytesPerFrame: 0,
            mChannelsPerFrame: UInt32(channels), mBitsPerChannel: 0, mReserved: 0)
        var created: ExtAudioFileRef?
        var status = ExtAudioFileCreateWithURL(url as CFURL, fileType, &target, nil,
                                               AudioFileFlags.eraseFile.rawValue, &created)
        guard status == noErr, let created else {
            throw AppError("无法创建 AAC 文件（错误 \(status)）")
        }
        file = created

        // 用软件编码器，切到后台也不容易出错
        var codec = UInt32(kAppleSoftwareAudioCodecManufacturer)
        _ = ExtAudioFileSetProperty(created, kExtAudioFileProperty_CodecManufacturer,
                                    UInt32(MemoryLayout<UInt32>.size), &codec)

        let bytesPerFrame = UInt32(MemoryLayout<Float>.size * channels)
        var client = AudioStreamBasicDescription(
            mSampleRate: sampleRate, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: bytesPerFrame, mFramesPerPacket: 1, mBytesPerFrame: bytesPerFrame,
            mChannelsPerFrame: UInt32(channels), mBitsPerChannel: 32, mReserved: 0)
        status = ExtAudioFileSetProperty(created, kExtAudioFileProperty_ClientDataFormat,
                                         UInt32(MemoryLayout<AudioStreamBasicDescription>.size), &client)
        guard status == noErr else {
            abort()
            throw AppError("AAC 编码器设置失败（错误 \(status)）")
        }

        // 设置码率（不支持的码率就保持默认）
        var converter: AudioConverterRef?
        var size = UInt32(MemoryLayout<AudioConverterRef?>.size)
        if ExtAudioFileGetProperty(created, kExtAudioFileProperty_AudioConverter, &size, &converter) == noErr,
           let converter {
            var rate = UInt32(bitrate * 1000)
            if AudioConverterSetProperty(converter, kAudioConverterEncodeBitRate,
                                         UInt32(MemoryLayout<UInt32>.size), &rate) == noErr {
                var config: CFArray? = nil
                _ = ExtAudioFileSetProperty(created, kExtAudioFileProperty_ConverterConfig,
                                            UInt32(MemoryLayout<CFArray?>.size), &config)
            }
        }
    }

    func write(_ samples: UnsafePointer<Float>, frames: Int) throws {
        guard let file else { return }
        let bytes = UInt32(frames * channels * MemoryLayout<Float>.size)
        var list = AudioBufferList(
            mNumberBuffers: 1,
            mBuffers: AudioBuffer(mNumberChannels: UInt32(channels), mDataByteSize: bytes,
                                  mData: UnsafeMutableRawPointer(mutating: samples)))
        let status = ExtAudioFileWrite(file, UInt32(frames), &list)
        if status != noErr { throw AppError("AAC 编码失败（错误 \(status)）") }
    }

    func finish() throws {
        guard let file else { return }
        self.file = nil
        let status = ExtAudioFileDispose(file)
        if status != noErr { throw AppError("AAC 写入失败（错误 \(status)）") }
    }

    func abort() {
        guard let file else { return }
        self.file = nil
        _ = ExtAudioFileDispose(file)
    }

    deinit { abort() }
}
