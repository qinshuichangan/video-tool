package com.example.videotool

import android.content.Context
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 输出格式（和 iPhone 版一样） */
enum class OutputFormat(val label: String, val ext: String) {
    VIDEO("视频（MP4）", "mp4"),
    COMPRESSED("压缩视频（调码率/帧率/分辨率）", "mp4"),
    M4A("AAC 音频（.m4a）", "m4a"),
    AAC("AAC 音频（.aac）", "aac"),
    MP3("MP3 音频", "mp3"),
    OGG("OGG 音频（Vorbis）", "ogg");

    val isVideo get() = this == VIDEO || this == COMPRESSED
    val isAudio get() = !isVideo
}

enum class CompressMode(val label: String) {
    LECTURE("网课(给AI)"),
    TARGET("目标大小"),
    MANUAL("手动"),
}

/** 原视频的基本信息（宽高是转正方向后的） */
data class SourceVideo(val width: Int, val height: Int, val fps: Double, val bitrateMbps: Double) {
    val shortSide get() = min(width, height)
    val fpsInt get() = if (fps > 0) fps.roundToInt() else 30

    /** 按短边缩小后的宽高（只缩小不放大，取偶数） */
    fun outputSize(targetShortSide: Int): Pair<Int, Int> {
        val scale = if (targetShortSide > 0) min(1.0, targetShortSide.toDouble() / max(shortSide, 1)) else 1.0
        val w = max(2, (width * scale / 2).roundToInt() * 2)
        val h = max(2, (height * scale / 2).roundToInt() * 2)
        return w to h
    }
}

/** 压缩设置 */
data class CompressSettings(
    val shortSide: Int,          // 分辨率（按短边算），0 = 原始
    val fps: Int,                // 帧率，0 = 原始
    val bitrateMbps: Double,     // 视频码率
    val hevc: Boolean,           // true = HEVC（更小），false = H.264（兼容性好）
    val audioKbps: Int = 0,      // 0 = 自动（立体声 128 / 单声道 96）
    val monoAudio: Boolean = false,
    val keyframeSeconds: Int = 2,
    val speechAudio: Boolean = false, // 22.05 kHz 人声
) {
    val effectiveAudioKbps get() = if (audioKbps > 0) audioKbps else if (monoAudio) 96 else 128

    fun estimatedMB(seconds: Double) = (bitrateMbps + effectiveAudioKbps / 1000.0) * seconds / 8

    fun summary(source: SourceVideo?): String {
        val parts = mutableListOf<String>()
        if (source != null) {
            val (w, h) = source.outputSize(shortSide)
            parts += "${min(w, h)}p"
            parts += "${if (fps > 0) min(fps, source.fpsInt) else source.fpsInt} fps"
        } else {
            parts += if (shortSide > 0) "${shortSide}p" else "原始分辨率"
            parts += if (fps > 0) "$fps fps" else "原始帧率"
        }
        parts += String.format("%.2f Mbps", bitrateMbps)
        parts += if (hevc) "HEVC" else "H.264"
        parts += "${if (monoAudio) "单声道" else "立体声"} $effectiveAudioKbps kbps"
        return parts.joinToString(" · ")
    }

    companion object {
        val resolutions = listOf(0 to "原始", 1080 to "1080p", 720 to "720p", 540 to "540p", 480 to "480p", 360 to "360p")
        val frameRates = listOf(0 to "原始", 60 to "60 fps", 30 to "30 fps", 25 to "25 fps", 24 to "24 fps", 15 to "15 fps")
        const val SPEECH_AUDIO_KBPS = 32

        /** 网课(给AI)：480p、1 fps、约 0.1 Mbps、单声道 32 kbps */
        fun lecture(source: SourceVideo): CompressSettings {
            val side = min(480, source.shortSide)
            val fps = min(1, source.fpsInt)
            val (w, h) = source.outputSize(side)
            var mbps = 0.1 * (w * h).toDouble() / (854 * 480)
            mbps = mbps.coerceIn(0.05, 0.15)
            if (source.bitrateMbps > 0) mbps = min(mbps, source.bitrateMbps)
            return CompressSettings(side, fps, (mbps * 100).roundToInt() / 100.0, true,
                SPEECH_AUDIO_KBPS, true, 10, true)
        }

        /** 指定目标大小：算出码率，再挑这个码率下字还看得清的分辨率和帧率 */
        fun forTarget(megabytes: Double, seconds: Double, source: SourceVideo): Pair<CompressSettings, String?> {
            val audio = SPEECH_AUDIO_KBPS.toDouble()
            val totalKbps = megabytes * 8 * 1000 / max(seconds, 1.0) * 0.95
            var videoKbps = totalKbps - audio
            if (source.bitrateMbps > 0) videoKbps = min(videoKbps, source.bitrateMbps * 1000)

            val ladder = listOf(1080 to 15, 720 to 15, 720 to 10, 540 to 10, 480 to 8, 360 to 6, 240 to 5)
            var choice = ladder.last()
            for (step in ladder) {
                val side = min(step.first, source.shortSide)
                val fps = min(step.second, source.fpsInt)
                val (w, h) = source.outputSize(side)
                val bpp = videoKbps * 1000 / (w.toDouble() * h * fps)
                if (bpp >= 0.04) { choice = step; break }
            }
            val side = min(choice.first, source.shortSide)
            val fps = min(choice.second, source.fpsInt)
            val minVideoKbps = 40.0
            var warning: String? = null
            if (videoKbps < minVideoKbps) {
                val minMB = (minVideoKbps + audio) * max(seconds, 1.0) / 8 / 1000 / 0.95
                warning = "目标太小了，画面会非常糊。这段长度建议至少 ${kotlin.math.ceil(minMB).toInt()} MB。"
                videoKbps = minVideoKbps
            }
            return CompressSettings(side, fps, (videoKbps / 10).roundToInt() / 100.0, true,
                SPEECH_AUDIO_KBPS, true, 10, true) to warning
        }
    }
}

enum class SplitMode(val label: String) {
    MINUTES("每 X 分钟一段"),
    COUNT("平均分成 X 段"),
}

/** 处理完自动分割成几段（和 iPhone 版一样） */
data class SplitSettings(
    val enabled: Boolean = false,
    val mode: SplitMode = SplitMode.MINUTES,
    val minutes: Double = 15.0,
    val count: Int = 3,
) {
    data class Part(val start: Double, val end: Double) {
        val length get() = end - start
    }

    /** 按时长分时，不限段数的话会分成几段 */
    private fun rawMinuteCount(total: Double): Int {
        val step = max(minutes, 0.1) * 60
        var n = kotlin.math.ceil(total / step).toInt()
        if (n > 1 && total - (n - 1) * step < MIN_TAIL) n--
        return max(n, 1)
    }

    /** 把 start…end 分成几段（没打开分割、或者不够分时只有一段） */
    fun parts(start: Double, end: Double): List<Part> {
        val total = end - start
        if (!enabled || total <= 1) return listOf(Part(start, end))
        var n: Int
        when (mode) {
            SplitMode.MINUTES -> {
                n = rawMinuteCount(total)
                if (n <= MAX_PARTS) {
                    val step = max(minutes, 0.1) * 60
                    return (0 until n).map { i ->
                        Part(start + i * step, if (i == n - 1) end else start + (i + 1) * step)
                    }
                }
                n = MAX_PARTS  // 太多了：改成平均分 99 段
            }
            SplitMode.COUNT -> n = count
        }
        n = minOf(max(n, 1), MAX_PARTS, max(total.toInt(), 1))  // 每段至少 1 秒
        val step = total / n
        return (0 until n).map { i -> Part(start + step * i, if (i == n - 1) end else start + step * (i + 1)) }
    }

    /** 例：「会分成 3 段：每段 15:00，最后一段 12:31」 */
    fun summary(total: Double): String {
        val list = parts(0.0, total)
        if (list.size <= 1) return "这段不够分，会照常输出一个文件。"
        val first = list.first().length
        val last = list.last().length
        var text = if (kotlin.math.abs(first - last) < 1) "会分成 ${list.size} 段，每段约 ${TimeText.short(first)}"
        else "会分成 ${list.size} 段：每段 ${TimeText.short(first)}，最后一段 ${TimeText.short(last)}"
        if (mode == SplitMode.MINUTES && rawMinuteCount(total) > MAX_PARTS) text += "（最多 $MAX_PARTS 段，所以改成了平均分）"
        return text
    }

    companion object {
        const val MAX_PARTS = 99
        /** 按时长分时，最后剩下不到这么多秒就并到前一段 */
        const val MIN_TAIL = 10.0

        /** 「我的视频_第1段」；10 段以上补 0（第01段），按名字排序就是顺序 */
        fun partName(base: String, index: Int, count: Int): String =
            base + "_第" + (index + 1).toString().padStart(count.toString().length, '0') + "段"
    }
}

/** 输出设置（单个和批量共用，存在 SharedPreferences 里） */
data class OutputChoice(
    val format: OutputFormat = OutputFormat.VIDEO,
    val audioBitrate: Int = 192,
    val precise: Boolean = false,
    val compressMode: CompressMode = CompressMode.LECTURE,
    val manualSide: Int = 720,
    val manualFps: Int = 30,
    val manualBitrate: Double = 3.0,
    val manualHevc: Boolean = true,
    val targetMB: Double = 30.0,
    val split: SplitSettings = SplitSettings(),
) {
    val manual get() = CompressSettings(manualSide, manualFps, manualBitrate, manualHevc)

    /** 只有声音的文件没法输出视频，自动换成 MP3 */
    fun formatFor(hasVideo: Boolean) = if (format.isVideo && !hasVideo) OutputFormat.MP3 else format

    fun targetPlan(source: SourceVideo?, seconds: Double?): Pair<CompressSettings, String?>? {
        if (source == null || seconds == null || seconds <= 0) return null
        return CompressSettings.forTarget(max(targetMB, 1.0), seconds, source)
    }

    fun compressSettings(source: SourceVideo?, seconds: Double?): CompressSettings = when (compressMode) {
        CompressMode.LECTURE -> source?.let { CompressSettings.lecture(it) } ?: manual
        CompressMode.TARGET -> targetPlan(source, seconds)?.first ?: manual
        CompressMode.MANUAL -> manual
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putString("format", format.name)
            .putInt("audioBitrate", audioBitrate)
            .putBoolean("precise", precise)
            .putString("compressMode", compressMode.name)
            .putInt("manualSide", manualSide)
            .putInt("manualFps", manualFps)
            .putFloat("manualBitrate", manualBitrate.toFloat())
            .putBoolean("manualHevc", manualHevc)
            .putFloat("targetMB", targetMB.toFloat())
            .putBoolean("splitEnabled", split.enabled)
            .putString("splitMode", split.mode.name)
            .putFloat("splitMinutes", split.minutes.toFloat())
            .putInt("splitCount", split.count)
            .apply()
    }

    companion object {
        val audioBitrates = listOf(96, 128, 160, 192, 256, 320)

        fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun load(context: Context): OutputChoice {
            val p = prefs(context)
            val d = OutputChoice()
            return OutputChoice(
                format = runCatching { OutputFormat.valueOf(p.getString("format", null)!!) }.getOrDefault(d.format),
                audioBitrate = p.getInt("audioBitrate", d.audioBitrate),
                precise = p.getBoolean("precise", d.precise),
                compressMode = runCatching { CompressMode.valueOf(p.getString("compressMode", null)!!) }
                    .getOrDefault(d.compressMode),
                manualSide = p.getInt("manualSide", d.manualSide),
                manualFps = p.getInt("manualFps", d.manualFps),
                manualBitrate = p.getFloat("manualBitrate", d.manualBitrate.toFloat()).toDouble(),
                manualHevc = p.getBoolean("manualHevc", d.manualHevc),
                targetMB = p.getFloat("targetMB", d.targetMB.toFloat()).toDouble(),
                split = SplitSettings(
                    enabled = p.getBoolean("splitEnabled", d.split.enabled),
                    mode = runCatching { SplitMode.valueOf(p.getString("splitMode", null)!!) }.getOrDefault(d.split.mode),
                    minutes = p.getFloat("splitMinutes", d.split.minutes.toFloat()).toDouble(),
                    count = p.getInt("splitCount", d.split.count),
                ),
            )
        }

        fun autoSaveEnabled(context: Context) = prefs(context).getBoolean("autoSaveToGallery", true)
        fun setAutoSaveEnabled(context: Context, value: Boolean) =
            prefs(context).edit().putBoolean("autoSaveToGallery", value).apply()

        fun dimEnabled(context: Context) = prefs(context).getBoolean("dimWhileWorking", true)
        fun setDimEnabled(context: Context, value: Boolean) =
            prefs(context).edit().putBoolean("dimWhileWorking", value).apply()
    }
}

/** 时间文字 ⇄ 秒数（和 iPhone 版写法一样） */
object TimeText {
    fun parse(text: String): Double? {
        var t = text.trim()
        val replacements = listOf(
            "：" to ":", "，" to ".", "," to ".", "。" to ".", "．" to ".",
            "小时" to ":", "时" to ":", "分钟" to ":", "分" to ":", "秒" to "", " " to "",
        )
        for ((from, to) in replacements) t = t.replace(from, to)
        t = t.map { ch -> if (ch in '０'..'９') '0' + (ch - '０') else ch }.joinToString("")
        if (t.endsWith(":")) t += "0"
        if (t.isEmpty()) return null
        val parts = t.split(":")
        if (parts.size > 3) return null
        var total = 0.0
        parts.forEachIndexed { index, part ->
            if (index < parts.size - 1 && part.contains(".")) return null
            val value = part.toDoubleOrNull() ?: return null
            if (value < 0 || !value.isFinite()) return null
            total = total * 60 + value
        }
        return total
    }

    fun format(seconds: Double): String {
        if (!seconds.isFinite()) return "0:00.00"
        val cs = (max(0.0, seconds) * 100).roundToInt()
        val h = cs / 360_000
        val m = (cs / 6000) % 60
        val s = (cs / 100) % 60
        val f = cs % 100
        return if (h > 0) String.format("%d:%02d:%02d.%02d", h, m, s, f)
        else String.format("%d:%02d.%02d", m, s, f)
    }

    /** 「15:00」或「1:02:03」（四舍五入到秒，用在说明文字里） */
    fun short(seconds: Double): String {
        val total = max(0.0, seconds).roundToInt()
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    fun fileStamp(seconds: Double): String {
        val total = max(0.0, seconds).toInt()
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) String.format("%dh%02dm%02ds", h, m, s) else String.format("%dm%02ds", m, s)
    }
}
