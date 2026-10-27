import AVKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @StateObject private var model = EditorModel()
    @AppStorage(EditorModel.formatKey) private var format: OutputFormat = .video
    @AppStorage(EditorModel.bitrateKey) private var bitrate = 192
    @AppStorage(EditorModel.preciseKey) private var precise = false

    @State private var photoItem: PhotosPickerItem?
    @State private var showFileImporter = false
    @State private var showLibrary = false
    @State private var photoMessage: String?
    @FocusState private var focusedField: Field?

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
                    outputSection
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
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showLibrary = true
                    } label: {
                        Label("已导出", systemImage: "folder")
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成") { focusedField = nil }
                }
            }
            .navigationDestination(isPresented: $showLibrary) {
                LibraryView { url in
                    showLibrary = false
                    model.load(url)
                }
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
            if !model.hasVideo && format == .video { format = .mp3 }
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

    // MARK: - 输出格式

    private var availableFormats: [OutputFormat] {
        OutputFormat.allCases.filter { $0 != .video || model.hasVideo }
    }

    private var outputSection: some View {
        Section {
            Picker("格式", selection: $format) {
                ForEach(availableFormats) { f in
                    Text(f.label).tag(f)
                }
            }
            if format == .video {
                Toggle("精确剪切（重新编码）", isOn: $precise)
            } else {
                Picker("音质（码率）", selection: $bitrate) {
                    ForEach(Exporter.bitrates, id: \.self) { b in
                        Text("\(b) kbps").tag(b)
                    }
                }
            }
        } header: {
            Text("3. 输出")
        } footer: {
            if format == .video {
                Text(precise
                     ? "重新编码：切点精确到帧，但比较慢，画质会有一点点损失。"
                     : "不重新编码：很快、画质无损。开头可能会从最近的关键帧开始，有零点几秒的误差，介意的话打开「精确剪切」。")
            } else if format == .ogg {
                Text("OGG 用的是可变码率，选的数值是大概的平均码率。iPhone 自带的播放器不能播 OGG。")
            } else if format == .aac {
                Text(".aac 是不带封装的纯 AAC，兼容性比 .m4a 稍差；不确定就选 .m4a。")
            } else {
                Text("192 kbps 一般就够用了，听音乐想要更好可以选 256 或 320。")
            }
        }
        .disabled(model.isExporting)
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
                    photoMessage = nil
                    let f = availableFormats.contains(format) ? format : .mp3
                    model.export(format: f, bitrate: bitrate, precise: precise)
                } label: {
                    Label(format == .video ? "开始截取" : "开始转换",
                          systemImage: format == .video ? "scissors" : "waveform")
                        .frame(maxWidth: .infinity)
                }
                .disabled(model.rangeProblem != nil)
            }
        } footer: {
            if model.isExporting {
                Text("处理中请留在这个 App 里。")
            }
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
