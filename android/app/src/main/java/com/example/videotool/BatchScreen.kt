package com.example.videotool

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BatchScreen(dim: Boolean, onDimChange: (Boolean) -> Unit, requestNotifications: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by JobEngine.jobs.collectAsStateCompat()
    val batch by JobEngine.batch.collectAsStateCompat()
    val message by JobEngine.batchMessage.collectAsStateCompat()
    val items = jobs.filter { it.kind == JobKind.BATCH }
    val running = batch != null
    var importing by remember { mutableIntStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf(OutputChoice.load(context)) }

    fun import(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        importing += uris.size
        scope.launch {
            for (uri in uris) {
                val file: File? = withContext(Dispatchers.IO) {
                    runCatching { Storage.importUri(context, uri, Storage.BATCH_IMPORTS) }
                        .onFailure { importError = "读取文件失败：${it.message}" }
                        .getOrNull()
                }
                if (file != null) JobEngine.addBatch(listOf(file))
                importing--
            }
        }
    }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { import(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { import(it) }

    val waiting = items.count { it.status == JobStatus.Waiting }
    val done = items.count { it.status is JobStatus.Done }
    val failed = items.count { it.status is JobStatus.Failed }

    Column {
        FormSection(header = "1. 添加文件", footer = "批量处理是整段转换，不剪。处理完的原文件副本会自动删掉，不占空间。") {
            FilledTonalButton(
                onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                enabled = !running, modifier = Modifier.fillMaxWidth(),
            ) { Text("从相册多选视频") }
            FilledTonalButton(
                onClick = { pickFiles.launch(arrayOf("video/*", "audio/*")) },
                enabled = !running, modifier = Modifier.fillMaxWidth(),
            ) { Text("从「文件」多选视频或音频") }
            if (importing > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.height(20.dp))
                    Text("正在导入 $importing 个…")
                }
            }
            importError?.let { Caption(it, MaterialTheme.colorScheme.error) }
        }

        FormSection(header = "2. 队列（${items.size} 个）", footer = if (items.isEmpty()) null else "点「删除」可以从队列里拿掉。") {
            if (items.isEmpty()) Text("队列是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider()
                BatchRow(item)
            }
            if (!running && (done > 0 || failed > 0)) {
                Row {
                    if (done > 0) TextButton(onClick = { JobEngine.clearFinishedBatch() }) { Text("清除已完成") }
                    Spacer(Modifier.weight(1f))
                    if (failed > 0) TextButton(onClick = { JobEngine.retryFailedBatch() }) { Text("重试失败的") }
                }
            }
        }

        OutputSettingsSection(
            header = "3. 输出（所有文件共用）", choice = choice, allowVideo = true, source = null, seconds = null,
            isBatch = true, enabled = !running, onChange = { choice = it },
        )

        FormSection(footer = "可以切到别的 App 或锁屏，会在后台继续处理，通知栏能看到进度。结果都在右上角「已导出」里。") {
            val b = batch
            if (b != null) {
                LinearProgressIndicator(progress = { b.overall.toFloat() }, modifier = Modifier.fillMaxWidth())
                Caption("第 ${b.index} / ${b.total} 个 · 总进度 ${(b.overall * 100).toInt()}%")
                OutlinedButton(onClick = { JobEngine.stopBatch() }, modifier = Modifier.fillMaxWidth()) { Text("停止") }
            } else {
                Button(
                    onClick = { requestNotifications(); JobEngine.startBatch(context) },
                    enabled = waiting > 0 && importing == 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("▶ 开始批量处理（$waiting 个）") }
            }
            DimToggle(dim, onDimChange)
            AutoSaveToggle()
        }

        message?.let { FormSection { Text(it) } }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BatchRow(item: Job) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, Modifier.weight(1f), maxLines = 2)
            if (item.status !is JobStatus.Running) {
                TextButton(onClick = { JobEngine.removeBatch(item.id) }) { Text("删除") }
            }
        }
        when (val s = item.status) {
            JobStatus.Waiting -> Caption("等待中")
            is JobStatus.Running -> {
                LinearProgressIndicator(progress = { s.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                Caption("处理中 ${(s.progress * 100).toInt()}%")
            }
            is JobStatus.Done -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ 完成 · ${Storage.sizeText(s.file)}", color = Green, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { Storage.share(context, s.file) }) { Text("分享") }
                }
                s.note?.let { Caption(it) }
            }
            is JobStatus.Failed -> Caption("失败：${s.message}", MaterialTheme.colorScheme.error)
        }
    }
}
