package com.example.videotool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val Orange = Color(0xFFE08A00)
val Green = Color(0xFF2E9D4F)

/** 和 iPhone 版「Form 的一组」差不多的区块 */
@Composable
fun FormSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) {
            Text(header, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp))
        }
    }
}

@Composable
fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** 「标题 …… 当前值 ▾」的下拉选择 */
@Composable
fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }, enabled = enabled) {
                Text((options.firstOrNull { it.first == selected }?.second ?: "") + " ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
                }
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Caption(subtitle)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** 「处理时屏幕调到最暗」开关 */
@Composable
fun DimToggle(dim: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow("处理时屏幕调到最暗", "处理完之前不会自动锁屏，结束后恢复原来的亮度。切到别的 App 或锁屏也会继续处理。",
        dim, onChange = onChange)
}

/** 「处理完自动存到相册 / 音乐」开关 */
@Composable
fun AutoSaveToggle() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(OutputChoice.autoSaveEnabled(context)) }
    SwitchRow("处理完自动存到相册 / 音乐",
        "视频存到「影片/剪辑转换」，音频存到「音乐/剪辑转换」。卸载 App 后这些文件也还在。", on) {
        on = it
        OutputChoice.setAutoSaveEnabled(context, it)
    }
}

/** 「输出」设置（单个和批量共用） */
@Composable
fun OutputSettingsSection(
    header: String,
    splitHeader: String,
    choice: OutputChoice,
    allowVideo: Boolean,
    source: SourceVideo?,
    seconds: Double?,
    isBatch: Boolean,
    enabled: Boolean,
    onChange: (OutputChoice) -> Unit,
) {
    val context = LocalContext.current
    fun update(c: OutputChoice) { c.save(context); onChange(c) }
    val formats = OutputFormat.entries.filter { !it.isVideo || allowVideo }
    val format = if (choice.format in formats) choice.format else OutputFormat.MP3

    FormSection(header = header, footer = footerText(choice.copy(format = format), isBatch)) {
        ChoiceRow("格式", formats.map { it to it.label }, format, enabled) { update(choice.copy(format = it)) }
        when (format) {
            OutputFormat.VIDEO ->
                SwitchRow("精确剪切（重新编码）", checked = choice.precise, enabled = enabled) { update(choice.copy(precise = it)) }
            OutputFormat.COMPRESSED -> CompressControls(choice, source, seconds, isBatch, enabled, ::update)
            else -> ChoiceRow("音质（码率）", OutputChoice.audioBitrates.map { it to "$it kbps" }, choice.audioBitrate, enabled) {
                update(choice.copy(audioBitrate = it))
            }
        }
    }

    SplitSection(splitHeader, choice, format, source, seconds, isBatch, enabled, ::update)
}

/** 「分割」设置：处理完自动切成几段 */
@Composable
private fun SplitSection(
    header: String, choice: OutputChoice, format: OutputFormat, source: SourceVideo?, seconds: Double?,
    isBatch: Boolean, enabled: Boolean, update: (OutputChoice) -> Unit,
) {
    val split = choice.split
    fun set(s: SplitSettings) = update(choice.copy(split = s))
    val title = when (format) {
        OutputFormat.VIDEO -> "分割成几段"
        OutputFormat.COMPRESSED -> "压缩后自动分割"
        else -> "转换后自动分割"
    }
    val footer = when {
        !split.enabled -> "打开后，处理完会按设置切成几个文件，文件名后面加「_第1段」「_第2段」…，按名字排序就是顺序。"
        format == OutputFormat.VIDEO -> if (choice.precise) "每段都重新编码，切点精确到帧，比较慢。"
        else "不重新编码，很快、画质无损。每段开头从最近的关键帧开始，相邻两段可能会有一两秒重叠，不会漏掉内容；介意的话在上面打开「精确剪切」。"
        format == OutputFormat.COMPRESSED -> "按上面的压缩设置处理，再切成几段，每段都能单独播放。压缩设置按整段的长度来算，所以「目标大小」是所有段加起来的大小。"
        else -> "每段分别转成音频，可以一段一段发给 Claude 转写。"
    }

    FormSection(header = header, footer = footer) {
        SwitchRow(title, "切成几个小文件，方便一段一段上传或发送", split.enabled, enabled) { set(split.copy(enabled = it)) }
        if (split.enabled) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SplitMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = split.mode == mode,
                        onClick = { set(split.copy(mode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(i, SplitMode.entries.size),
                        enabled = enabled,
                    ) { Text(mode.label, maxLines = 1) }
                }
            }
            when (split.mode) {
                SplitMode.MINUTES -> {
                    var text by remember(split.minutes) { mutableStateOf(trimNumber(split.minutes)) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v ->
                            text = v
                            v.toDoubleOrNull()?.takeIf { it > 0 }?.let { set(split.copy(minutes = it)) }
                        },
                        label = { Text("每段长度（分钟）") },
                        singleLine = true,
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SplitMode.COUNT -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分成", Modifier.weight(1f))
                    TextButton(onClick = { set(split.copy(count = max(2, split.count - 1))) },
                        enabled = enabled && split.count > 2) { Text("－", style = MaterialTheme.typography.titleLarge) }
                    Text("${split.count} 段")
                    TextButton(onClick = { set(split.copy(count = min(SplitSettings.MAX_PARTS, split.count + 1))) },
                        enabled = enabled && split.count < SplitSettings.MAX_PARTS) { Text("＋", style = MaterialTheme.typography.titleLarge) }
                }
            }
            if (isBatch) {
                Caption(if (split.mode == SplitMode.MINUTES)
                    "每个文件分别按自己的长度来分，不够 ${TimeText.short(max(split.minutes, 0.1) * 60)} 的文件不分。"
                else "每个文件都平均分成 ${split.count} 段。")
            } else if (seconds != null && seconds > 0) {
                Caption(split.summary(seconds), MaterialTheme.colorScheme.onSurface)
                val parts = split.parts(0.0, seconds)
                val longest = parts.maxOf { it.length }
                if (parts.size > 1) {
                    val mb = when (format) {
                        OutputFormat.VIDEO -> null
                        OutputFormat.COMPRESSED -> choice.compressSettings(source, seconds).estimatedMB(longest)
                        else -> choice.audioBitrate * longest / 8 / 1000
                    }
                    mb?.let { Caption(String.format("每段预计最大约 %.1f MB", it)) }
                }
            }
        }
    }
}

@Composable
private fun CompressControls(
    choice: OutputChoice, source: SourceVideo?, seconds: Double?, isBatch: Boolean, enabled: Boolean,
    update: (OutputChoice) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        CompressMode.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = choice.compressMode == mode,
                onClick = { update(choice.copy(compressMode = mode)) },
                shape = SegmentedButtonDefaults.itemShape(i, CompressMode.entries.size),
                enabled = enabled,
            ) { Text(mode.label, maxLines = 1) }
        }
    }
    val settings = choice.compressSettings(source, seconds)

    @Composable
    fun estimate() {
        if (seconds != null && seconds > 0) Caption(String.format("预计大小约 %.1f MB", settings.estimatedMB(seconds)))
    }

    @Composable
    fun summary() {
        when {
            isBatch -> Caption("每个视频分别按自己的分辨率和长度自动计算。")
            source == null -> Caption("读不出原视频的信息，自动模式用不了，请用「手动」。", Orange)
            else -> {
                Caption("自动设置：${settings.summary(source)}", MaterialTheme.colorScheme.onSurface)
                estimate()
            }
        }
    }

    when (choice.compressMode) {
        CompressMode.LECTURE -> summary()
        CompressMode.TARGET -> {
            var text by remember(choice.targetMB) { mutableStateOf(trimNumber(choice.targetMB)) }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v
                    v.toDoubleOrNull()?.takeIf { it > 0 }?.let { update(choice.copy(targetMB = it)) }
                },
                label = { Text(if (isBatch) "每个视频的目标大小（MB）" else "目标大小（MB）") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            summary()
            choice.targetPlan(source, seconds)?.second?.let { Caption(it, MaterialTheme.colorScheme.error) }
        }
        CompressMode.MANUAL -> {
            ChoiceRow("分辨率", CompressSettings.resolutions, choice.manualSide, enabled) { update(choice.copy(manualSide = it)) }
            ChoiceRow("帧率", CompressSettings.frameRates, choice.manualFps, enabled) { update(choice.copy(manualFps = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("码率", Modifier.weight(1f))
                Text(String.format("%.1f Mbps", choice.manualBitrate), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = choice.manualBitrate.toFloat(),
                onValueChange = { update(choice.copy(manualBitrate = (it * 10).roundToInt() / 10.0)) },
                valueRange = 0.3f..20f,
                enabled = enabled,
            )
            estimate()
            SwitchRow("HEVC 编码（体积更小）", checked = choice.manualHevc, enabled = enabled) { update(choice.copy(manualHevc = it)) }
        }
    }
}

private fun trimNumber(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

private fun footerText(choice: OutputChoice, isBatch: Boolean): String = when (choice.format) {
    OutputFormat.VIDEO -> when {
        isBatch -> "批量时整段不剪，只是转成 MP4。想变小请选「压缩视频」。"
        choice.precise -> "重新编码：切点精确到帧，但比较慢，画质会有一点点损失。"
        else -> "不重新编码：很快、画质无损。开头可能会从最近的关键帧开始，有零点几秒的误差，介意的话打开「精确剪切」。"
    }
    OutputFormat.COMPRESSED -> when (choice.compressMode) {
        CompressMode.LECTURE -> "专门压给 Claude 看的网课：480p、每秒 1 帧、单声道 32 kbps 人声，一小时大约 45～60 MB。画面会一卡一卡的，但幻灯片和板书的字、老师讲的话都够 Claude 看清听清。字很小或者黑板拍得很远的话，换「目标大小」给多一点空间。"
        CompressMode.TARGET -> "输入想要的文件大小，自动算码率，再挑这个码率下最清楚的分辨率和帧率。只压缩选中的那一段时，按那一段的长度算。实际大小可能有一点点误差。"
        CompressMode.MANUAL -> "分辨率和帧率只会降低、不会比原视频高。码率参考：1080p 约 4–8 Mbps，720p 约 2–4 Mbps，480p 约 1–2 Mbps，太低会有马赛克。HEVC 体积更小；要发给旧手机或电脑就关掉，用 H.264。HDR 视频会转成普通画面。"
    }
    OutputFormat.OGG -> "OGG 用的是可变码率，选的数值是大概的平均码率。"
    OutputFormat.AAC -> ".aac 是不带封装的纯 AAC，兼容性比 .m4a 稍差；不确定就选 .m4a。"
    else -> "192 kbps 一般就够用了，听音乐想要更好可以选 256 或 320。"
}
