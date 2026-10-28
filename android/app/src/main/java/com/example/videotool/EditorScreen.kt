package com.example.videotool

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

class EditorModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    var sourceFile by mutableStateOf<File?>(null); private set
    var sourceName by mutableStateOf(""); private set
    var info by mutableStateOf<MediaInfo?>(null); private set
    var isLoading by mutableStateOf(false); private set
    var startText by mutableStateOf("")
    var endText by mutableStateOf("")
    var currentTime by mutableStateOf(0.0); private set
    var errorMessage by mutableStateOf<String?>(null)
    var exportJobId by mutableStateOf<Long?>(null); private set
    private var previewEnd: Double? = null

    val duration get() = info?.durationSec ?: 0.0
    val hasVideo get() = info?.hasVideo == true
    val startSeconds get() = TimeText.parse(startText)
    val endSeconds get() = TimeText.parse(endText)

    /** 范围有问题时返回提示文字 */
    val rangeProblem: String?
        get() {
            if (sourceFile == null) return null
            val start = startSeconds ?: return "开始时间格式不对（例：1:23 或 83.5）"
            val end = endSeconds ?: return "结束时间格式不对（例：2:05 或 125）"
            if (start >= duration) return "开始时间超过了总长度 ${TimeText.format(duration)}"
            if (end > duration + 0.05) return "结束时间超过了总长度 ${TimeText.format(duration)}"
            if (end <= start) return "结束时间要比开始时间晚"
            return null
        }

    val selectedLength: Double?
        get() {
            if (rangeProblem != null) return null
            val s = startSeconds ?: return null
            val e = endSeconds ?: return null
            return min(e, duration) - s
        }

    private val isFullRange: Boolean
        get() {
            val s = startSeconds ?: return false
            val e = endSeconds ?: return false
            return s < 0.005 && e >= duration - 0.005
        }

    fun importUri(uri: Uri) {
        errorMessage = null
        isLoading = true
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { Storage.importUri(context, uri, Storage.EDITOR_IMPORTS) }
                load(file)
            } catch (e: Exception) {
                isLoading = false
                errorMessage = "读取文件失败：${e.message}"
            }
        }
    }

    fun pasteFromClipboard() {
        val uri = Storage.clipboardMediaUri(context)
        if (uri == null) errorMessage = "剪贴板里没有音频或视频" else importUri(uri)
    }

    /** 载入一个已经在 App 里的文件 */
    fun load(file: File) {
        player.pause()
        previewEnd = null
        isLoading = true
        errorMessage = null
        exportJobId?.let { id -> if (JobEngine.job(id)?.status !is JobStatus.Running) exportJobId = null }
        viewModelScope.launch {
            val probed = withContext(Dispatchers.IO) { MediaProbe.probe(file) }
            isLoading = false
            if (probed.durationSec <= 0) {
                errorMessage = "读不出这个文件的长度，可能不是视频或音频"; return@launch
            }
            if (!probed.hasVideo && !probed.hasAudio) {
                errorMessage = "这个文件里没有画面也没有声音"; return@launch
            }
            // 正在导出的单个任务还要用旧文件，等它结束了再清理
            val busy = JobEngine.jobs.value.any { it.kind == JobKind.SINGLE && it.status is JobStatus.Running }
            if (!busy) Storage.cleanImports(context, Storage.EDITOR_IMPORTS, file)
            sourceFile = file
            sourceName = file.nameWithoutExtension
            info = probed
            currentTime = 0.0
            resetRange()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
        }
    }

    fun tick() {
        currentTime = player.currentPosition / 1000.0
        previewEnd?.let { end ->
            if (currentTime >= end) { player.pause(); previewEnd = null }
        }
    }

    fun setStartToCurrent() { startText = TimeText.format(currentTime) }
    fun setEndToCurrent() { endText = TimeText.format(min(currentTime, duration)) }
    fun resetRange() { startText = TimeText.format(0.0); endText = TimeText.format(duration) }
    fun seek(seconds: Double) = player.seekTo((seconds * 1000).toLong())

    fun previewSelection() {
        val s = startSeconds ?: return
        val e = endSeconds ?: return
        if (rangeProblem != null) return
        previewEnd = min(e, duration)
        player.seekTo((s * 1000).toLong())
        player.play()
    }

    fun export() {
        val file = sourceFile ?: return
        rangeProblem?.let { errorMessage = it; return }
        val s = startSeconds ?: return
        val e = endSeconds ?: return
        player.pause()
        errorMessage = null
        val range = if (isFullRange) ClipRange(0, null)
        else ClipRange((s * 1000).toLong(), (min(e, duration) * 1000).toLong())
        exportJobId = JobEngine.exportSingle(context, file, sourceName, range)
    }

    override fun onCleared() {
        player.release()
    }
}

@Composable
fun EditorScreen(model: EditorModel, dim: Boolean, onDimChange: (Boolean) -> Unit, requestNotifications: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val jobs by JobEngine.jobs.collectAsStateCompat()
    val job = jobs.firstOrNull { it.id == model.exportJobId }
    val exporting = job != null && (job.status is JobStatus.Running || job.status == JobStatus.Waiting)
    var choice by remember { mutableStateOf(OutputChoice.load(context)) }
    var resultMessage by remember(job?.id) { mutableStateOf<String?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { model.importUri(it) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importUri(it) }
    }

    LaunchedEffect(model.sourceFile) {
        while (true) { model.tick(); delay(50) }
    }

    Column {
        FormSection(header = "1. 选择文件") {
            FilledTonalButton(
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                enabled = !exporting, modifier = Modifier.fillMaxWidth(),
            ) { Text("从相册选视频") }
            FilledTonalButton(
                onClick = { pickFile.launch(arrayOf("video/*", "audio/*")) },
                enabled = !exporting, modifier = Modifier.fillMaxWidth(),
            ) { Text("从「文件」选视频或音频") }
            OutlinedButton(onClick = { model.pasteFromClipboard() }, enabled = !exporting, modifier = Modifier.fillMaxWidth()) {
                Text("从剪贴板粘贴音频或视频")
            }
            if (model.sourceFile != null) {
                Text(model.sourceName, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Caption("总长度 ${TimeText.format(model.duration)}" + if (model.hasVideo) "" else "（只有声音）")
                model.info?.description?.takeIf { it.isNotEmpty() }?.let { Caption(it) }
            }
        }

        if (model.isLoading) {
            FormSection {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                    Text("正在读取…")
                }
            }
        }

        if (model.sourceFile != null) {
            FormSection {
                AndroidView(
                    factory = { PlayerView(it).apply { player = model.player } },
                    modifier = Modifier.fillMaxWidth().height(if (model.hasVideo) 220.dp else 100.dp),
                )
                Row {
                    Text("当前位置", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(TimeText.format(model.currentTime))
                }
            }

            FormSection(
                header = "2. 截取范围",
                footer = "可以直接输入时间：83.5、1:23、1:02:03.5、1分23秒 都行。点 ⌖ 把当前播放位置填进去，点 ↪ 跳到这个时间。",
            ) {
                TimeRow("开始", model.startText, { model.startText = it }, !exporting,
                    onCurrent = { focus.clearFocus(); model.setStartToCurrent() },
                    onJump = { focus.clearFocus(); model.startSeconds?.let(model::seek) })
                TimeRow("结束", model.endText, { model.endText = it }, !exporting,
                    onCurrent = { focus.clearFocus(); model.setEndToCurrent() },
                    onJump = { focus.clearFocus(); model.endSeconds?.let(model::seek) })
                val problem = model.rangeProblem
                if (problem != null) Caption(problem, MaterialTheme.colorScheme.error)
                else model.selectedLength?.let { Caption("截取长度 ${TimeText.format(it)}") }
                Row {
                    TextButton(onClick = { focus.clearFocus(); model.previewSelection() }, enabled = problem == null) {
                        Text("▶ 试播这一段")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { focus.clearFocus(); model.resetRange() }, enabled = !exporting) { Text("整段") }
                }
            }

            OutputSettingsSection(
                header = "3. 输出", choice = choice, allowVideo = model.hasVideo,
                source = model.info?.video, seconds = model.selectedLength, isBatch = false,
                enabled = !exporting, onChange = { choice = it },
            )

            FormSection(footer = when {
                job?.status == JobStatus.Waiting -> "批量处理正在进行，这个会排在它后面。"
                exporting -> "可以切到别的 App 或锁屏，会在后台继续处理，通知栏能看到进度。"
                else -> null
            }) {
                when (val status = job?.status) {
                    is JobStatus.Running -> {
                        LinearProgressIndicator(progress = { status.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Caption("正在处理… ${(status.progress * 100).toInt()}%")
                        OutlinedButton(onClick = { JobEngine.cancel(job.id) }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                    }
                    JobStatus.Waiting -> {
                        Caption("排队中…")
                        OutlinedButton(onClick = { JobEngine.cancel(job.id) }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                    }
                    else -> {
                        val f = choice.formatFor(model.hasVideo)
                        Button(
                            onClick = { focus.clearFocus(); requestNotifications(); model.export() },
                            enabled = model.rangeProblem == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(when (f) {
                                OutputFormat.VIDEO -> "✂ 开始截取"
                                OutputFormat.COMPRESSED -> "开始压缩"
                                else -> "开始转换"
                            })
                        }
                    }
                }
                DimToggle(dim, onDimChange)
                AutoSaveToggle()
            }
        }

        when (val status = job?.status) {
            is JobStatus.Done -> FormSection(header = "完成", footer = "文件也在右上角「已导出」里。") {
                Text("✓ ${status.file.name}", color = Green)
                Caption(Storage.sizeText(status.file))
                status.note?.let { Caption(it) }
                FileActions(status.file) { resultMessage = it }
                resultMessage?.let { Caption(it) }
            }
            is JobStatus.Failed -> FormSection { Text("⚠ ${status.message}", color = Orange) }
            else -> {}
        }

        model.errorMessage?.let { FormSection { Text("⚠ $it", color = Orange) } }

        Text(
            "版本 ${appVersion(context)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun TimeRow(
    title: String, text: String, onText: (String) -> Unit, enabled: Boolean,
    onCurrent: () -> Unit, onJump: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = onText, label = { Text(title) }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCurrent, enabled = enabled) { Text("⌖", style = MaterialTheme.typography.titleLarge) }
        TextButton(onClick = onJump) { Text("↪", style = MaterialTheme.typography.titleLarge) }
    }
}

/** 复制到剪贴板 / 分享 / 存到相册 */
@Composable
fun FileActions(file: File, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            onMessage(if (Storage.copyToClipboard(context, file)) "已复制到剪贴板，可以去别的 App 粘贴" else "复制失败")
        }, modifier = Modifier.fillMaxWidth()) { Text("复制到剪贴板") }
        OutlinedButton(onClick = { Storage.share(context, file) }, modifier = Modifier.fillMaxWidth()) {
            Text("分享 / 存到其他 App")
        }
        OutlinedButton(onClick = { onMessage(Storage.saveToGallery(context, file)) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (Storage.isAudio(file)) "存到「音乐」" else "存到相册")
        }
    }
}
