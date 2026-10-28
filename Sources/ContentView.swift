import AVKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @StateObject private var model = EditorModel()
    @AppStorage(EditorModel.formatKey) private var format: OutputFormat = .video
    @StateObject private var batch = BatchModel()

    @State private var photoItem: PhotosPickerItem?
    @State private var showFileImporter = false
    @State private var showLibrary = false
    @State private var showBatch = false
    @State private var photoMessage: String?
    @FocusState private var focusedField: Field?
    @FocusState private var targetFocused: Bool

    enum Field { case start, end }

    var body: some View {
        NavigationStack {
            Form {
                pickSection

                if model.isLoading {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("正在读取…").foregroundStyle(.secondary)
                        }
                    }
                }

                if model.sourceURL != nil {
                    playerSection
                    rangeSection
                    OutputSettingsSection(header: "3. 输出", allowVideo: model.hasVideo,
                                          source: model.sourceVideo, seconds: model.selectedLength,
                                          targetFocus: $targetFocused)
                        .disabled(model.isExporting)
                    exportSection
                }

                if let url = model.resultURL {
                    resultSection(url)
                }

                if let message = model.errorMessage {
                    Section {
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                    }
                }

                Section {
                    Text("版本 \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?")")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                }
                .listRowBackground(Color.clear)
            }
            .navigationTitle("剪辑转换")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        showBatch = true
                    } label: {
                        Label("批量", systemImage: "list.bullet.rectangle")
                            .labelStyle(.titleAndIcon)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showLibrary = true
                    } label: {
                        Label("已导出", systemImage: "folder")
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成") {
                        focusedField = nil
                        targetFocused = false
                    }
                }
            }
            .navigationDestination(isPresented: $showLibrary) {
                LibraryView { url in
                    showLibrary = false
                    model.load(url)
                }
            }
            .navigationDestination(isPresented: $showBatch) {
                BatchView(batch: batch, editorBusy: model.isExporting)
            }
        }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.audiovisualContent]) { result in
            switch result {
            case .success(let url): model.loadExternal(url)
            case .failure(let error): model.errorMessage = "选择文件失败：\(error.localizedDescription)"
            }
        }
        .onChange(of: photoItem) { item in
            guard let item else { return }
            Task {
                do {
                    if let movie = try await item.loadTransferable(type: PickedMovie.self) {
                        model.loadPhotoItem(movie)
                    } else {
                        model.errorMessage = "读取相册视频失败"
                    }
                } catch {
                    model.errorMessage = "读取相册视频失败：\(error.localizedDescription)"
                }
                photoItem = nil
            }
        }
        .onOpenURL { url in
            showLibrary = false
            model.loadExternal(url)
        }
        .onChange(of: model.sourceURL) { _ in
            // 只有声音的文件不能输出视频
            if !model.hasVideo && format.isVideo { format = .mp3 }
        }
    }

    // MARK: - 选择文件

    private var pickSection: some View {
        Section {
            PhotosPicker(selection: $photoItem, matching: .videos, preferredItemEncoding: .current) {
                Label("从相册选视频", systemImage: "photo.on.rectangle")
            }
            Button {
                showFileImporter = true
            } label: {
                Label("从「文件」选视频或音频", systemImage: "folder")
            }
            HStack {
                Label("从剪贴板粘贴音频或视频", systemImage: "doc.on.clipboard")
                    .foregroundStyle(.secondary)
                Spacer()
                PasteButton(supportedContentTypes: [.audiovisualContent]) { providers in
                    Task { @MainActor in model.loadPasted(providers) }
                }
                .labelStyle(.titleOnly)
                .buttonBorderShape(.capsule)
            }
            if model.sourceURL != nil {
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.sourceName).font(.callout).lineLimit(2)
                    Text("总长度 \(TimeText.format(model.duration))"
                         + (model.hasVideo ? "" : "（只有声音）"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if !model.videoInfo.isEmpty {
                        Text(model.videoInfo)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        } header: {
            Text("1. 选择文件")
        }
        .disabled(model.isExporting)
    }

    // MARK: - 播放

    private var playerSection: some View {
        Section {
            VideoPlayer(player: model.player)
                .frame(height: model.hasVideo ? 220 : 60)
                .listRowInsets(EdgeInsets())
            HStack {
                Text("当前位置")
                    .foregroundStyle(.secondary)
                Spacer()
                Text(TimeText.format(model.currentTime))
                    .monospacedDigit()
            }
            .font(.callout)
        }
    }

    // MARK: - 截取范围

    private var rangeSection: some View {
        Section {
            timeRow(title: "开始", text: $model.startText, field: .start,
                    setCurrent: model.setStartToCurrent, jump: model.startSeconds)
            timeRow(title: "结束", text: $model.endText, field: .end,
                    setCurrent: model.setEndToCurrent, jump: model.endSeconds)

            if let problem = model.rangeProblem {
                Text(problem).font(.caption).foregroundStyle(.red)
            } else if let length = model.selectedLength {
                Text("截取长度 \(TimeText.format(length))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack {
                Button {
                    focusedField = nil
                    model.previewSelection()
                } label: {
                    Label("试播这一段", systemImage: "play.circle")
                }
                .disabled(model.rangeProblem != nil)
                Spacer()
                Button("整段") {
                    focusedField = nil
                    model.resetRange()
                }
            }
            .buttonStyle(.borderless)
        } header: {
            Text("2. 截取范围")
        } footer: {
            Text("可以直接输入时间：83.5、1:23、1:02:03.5、1分23秒 都行。点 ⌖ 把当前播放位置填进去，点 ↪ 跳到这个时间。")
        }
        .disabled(model.isExporting)
    }

    private func timeRow(title: String, text: Binding<String>, field: Field,
                         setCurrent: @escaping @MainActor () -> Void, jump: Double?) -> some View {
        HStack(spacing: 12) {
            Text(title)
            TextField("0:00.00", text: text)
                .keyboardType(.numbersAndPunctuation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
                .focused($focusedField, equals: field)
                .submitLabel(.done)
            Button {
                focusedField = nil
                setCurrent()
            } label: {
                Image(systemName: "scope")
            }
            .accessibilityLabel("用当前播放位置")
            Button {
                focusedField = nil
                if let jump { model.seek(to: jump) }
            } label: {
                Image(systemName: "arrow.uturn.right")
            }
            .accessibilityLabel("跳到这个时间")
            .disabled(jump == nil)
        }
        .buttonStyle(.borderless)
    }

    // MARK: - 导出

    private var exportSection: some View {
        Section {
            if model.isExporting {
                VStack(alignment: .leading, spacing: 8) {
                    ProgressView(value: model.progress)
                    Text("正在处理… \(Int(model.progress * 100))%")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button("取消", role: .destructive) {
                    model.cancelExport()
                }
            } else {
                Button {
                    focusedField = nil
                    targetFocused = false
                    photoMessage = nil
                    let choice = OutputChoice.current()
                    model.export(format: choice.format(hasVideo: model.hasVideo),
                                 bitrate: choice.audioBitrate, precise: choice.precise,
                                 compress: choice.compressSettings(source: model.sourceVideo,
                                                                   seconds: model.selectedLength))
                } label: {
                    Label(exportTitle, systemImage: exportIcon)
                        .frame(maxWidth: .infinity)
                }
                .disabled(model.rangeProblem != nil || batch.isRunning)
            }
            DimToggle()
        } footer: {
            if batch.isRunning {
                Text("批量处理正在进行，等它结束后再导出。")
            } else if model.isExporting {
                Text("可以切到别的 App：系统允许的话会在后台继续，锁屏和灵动岛上能看到进度。万一被系统中断，回来重新开始就行。")
            }
        }
    }

    private var exportTitle: String {
        switch format {
        case .video: return "开始截取"
        case .compressed: return "开始压缩"
        default: return "开始转换"
        }
    }

    private var exportIcon: String {
        switch format {
        case .video: return "scissors"
        case .compressed: return "arrow.down.right.and.arrow.up.left"
        default: return "waveform"
        }
    }

    private func resultSection(_ url: URL) -> some View {
        Section {
            Label(url.lastPathComponent, systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
            Text(LibraryView.sizeText(url)).font(.caption).foregroundStyle(.secondary)
            Button {
                photoMessage = Clipboard.copyFile(url) ? "已复制到剪贴板，可以去别的 App 粘贴" : "复制失败"
            } label: {
                Label("复制到剪贴板", systemImage: "doc.on.doc")
            }
            ShareLink(item: url) {
                Label("分享 / 存到其他 App", systemImage: "square.and.arrow.up")
            }
            if !LibraryView.isAudio(url) {
                Button {
                    Task { photoMessage = await PhotoSaver.save(url) }
                } label: {
                    Label("存到相册", systemImage: "photo.badge.plus")
                }
            }
            if let photoMessage {
                Text(photoMessage).font(.caption)
            }
        } header: {
            Text("完成")
        } footer: {
            Text("文件也在「文件」App →「我的 iPhone」→「剪辑转换」里。")
        }
    }
}
