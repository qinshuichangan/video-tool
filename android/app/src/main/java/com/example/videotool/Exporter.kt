package com.example.videotool

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AppError(message: String) : Exception(message)

/** 要处理的片段（毫秒）。end 为 null = 到结尾 */
data class ClipRange(val startMs: Long, val endMs: Long?) {
    val isFull get() = startMs == 0L && endMs == null
}

/** 文件信息 */
data class MediaInfo(
    val durationSec: Double,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val video: SourceVideo?,
    val description: String,
)

object MediaProbe {
    fun probe(file: File): MediaInfo {
        var hasVideo = false
        var hasAudio = false
        var fps = 0.0
        var videoBitrate = 0
        var audioBitrate = 0
        var durationUs = 0L
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = max(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/") && !hasVideo) {
                    hasVideo = true
                    fps = f.number(MediaFormat.KEY_FRAME_RATE)
                    videoBitrate = f.number(MediaFormat.KEY_BIT_RATE).toInt()
                } else if (mime.startsWith("audio/") && !hasAudio) {
                    hasAudio = true
                    audioBitrate = f.number(MediaFormat.KEY_BIT_RATE).toInt()
                }
            }
        } catch (e: Exception) {
            // 交给下面的 MediaMetadataRetriever 再试
        } finally {
            extractor.release()
        }

        var width = 0
        var height = 0
        var totalBitrate = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun meta(key: Int) = retriever.extractMetadata(key)
            meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durationUs = max(durationUs, it * 1000) }
            if (meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") hasVideo = true
            if (meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") hasAudio = true
            width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = width; width = height; height = t }
            totalBitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
        } finally {
            retriever.release()
        }

        if (videoBitrate <= 0 && totalBitrate > 0) videoBitrate = max(0, totalBitrate - audioBitrate)
        val video = if (hasVideo && width > 0 && height > 0)
            SourceVideo(width, height, fps, videoBitrate / 1_000_000.0) else null
        val parts = mutableListOf<String>()
        if (video != null) {
            parts += "${width}×$height"
            if (fps > 0) parts += "${fps.toInt()} fps"
            if (videoBitrate > 0) parts += String.format("码率 %.1f Mbps", videoBitrate / 1_000_000.0)
            parts += Storage.sizeText(file)
        }
        return MediaInfo(durationUs / 1_000_000.0, hasVideo, hasAudio, video, parts.joinToString(" · "))
    }

    private fun MediaFormat.number(key: String): Double {
        if (!containsKey(key)) return 0.0
        return runCatching { getInteger(key).toDouble() }
            .recoverCatching { getFloat(key).toDouble() }
            .getOrDefault(0.0)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
object Exporter {

    // MARK: 视频截取

    /** precise = false：不重新编码（快、无损）；true：重新编码，切点精确 */
    suspend fun trimVideo(
        context: Context, input: File, range: ClipRange, precise: Boolean, output: File,
        progress: (Double) -> Unit,
    ) {
        val item = EditedMediaItem.Builder(mediaItem(input, range)).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .apply {
                // 要求 HDR→SDR 时一定会重新编码，用它来实现「精确剪切」
                if (precise) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            }
            .build()
        runTransformer(context, composition, output, progress) {
            if (!precise) experimentalSetTrimOptimizationEnabled(true)
        }
    }

    // MARK: 压缩视频

    suspend fun compressVideo(
        context: Context, input: File, range: ClipRange, s: CompressSettings, source: SourceVideo,
        output: File, progress: (Double) -> Unit,
    ) {
        val targetFps = if (s.fps > 0) min(s.fps, source.fpsInt) else source.fpsInt
        val videoEffects = mutableListOf<Effect>()
        if (targetFps < source.fpsInt) {
            videoEffects += FrameDropEffect.createDefaultFrameDropEffect(targetFps.toFloat())
        }
        val (w, h) = source.outputSize(s.shortSide)
        if (w != source.width || h != source.height) {
            videoEffects += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
        }

        val audioProcessors = mutableListOf<AudioProcessor>()
        if (s.monoAudio) {
            audioProcessors += ChannelMixingAudioProcessor().apply {
                for (inputChannels in 1..8) putChannelMixingMatrix(downmixMatrix(inputChannels, 1))
            }
        }
        if (s.speechAudio) {
            audioProcessors += SonicAudioProcessor().apply { setOutputSampleRateHz(22050) }
        }

        val item = EditedMediaItem.Builder(mediaItem(input, range))
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate((s.bitrateMbps * 1_000_000).toInt())
                    .setiFrameIntervalSeconds(s.keyframeSeconds.toFloat())
                    .build())
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder().setBitrate(s.effectiveAudioKbps * 1000).build())
            .setEnableFallback(true)
            .build()

        runTransformer(context, composition, output, progress) {
            setVideoMimeType(if (s.hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            setAudioMimeType(MimeTypes.AUDIO_AAC)
            // 告诉编码器真正的输出帧率（默认会按原视频的帧率分配码率，降帧后画质会很差）
            setEncoderFactory(FrameRateEncoderFactory(encoders, targetFps.toFloat()))
        }
    }

    /** 把编码器格式里的帧率改成实际输出的帧率 */
    private class FrameRateEncoderFactory(
        private val delegate: Codec.EncoderFactory,
        private val frameRate: Float,
    ) : Codec.EncoderFactory {
        override fun createForAudioEncoding(format: Format): Codec = delegate.createForAudioEncoding(format)
        override fun createForVideoEncoding(format: Format): Codec =
            delegate.createForVideoEncoding(format.buildUpon().setFrameRate(frameRate).build())
        override fun audioNeedsEncoding() = delegate.audioNeedsEncoding()
        override fun videoNeedsEncoding() = delegate.videoNeedsEncoding()
    }

    /** 任意声道数混成 outputChannels（1 或 2）：平均混合 */
    private fun downmixMatrix(inputChannels: Int, outputChannels: Int): ChannelMixingMatrix {
        if (inputChannels <= 2 && outputChannels <= 2) return ChannelMixingMatrix.create(inputChannels, outputChannels)
        val coefficients = FloatArray(inputChannels * outputChannels) { 1f / inputChannels }
        return ChannelMixingMatrix(inputChannels, outputChannels, coefficients)
    }

    // MARK: 转成音频

    suspend fun convertAudio(
        context: Context, input: File, range: ClipRange, format: OutputFormat, bitrate: Int, output: File,
        progress: (Double) -> Unit,
    ) {
        when (format) {
            OutputFormat.M4A -> exportM4a(context, input, range, bitrate, output, progress)
            OutputFormat.AAC -> {
                val temp = File(output.parentFile, output.nameWithoutExtension + ".tmp.m4a")
                try {
                    exportM4a(context, input, range, bitrate, temp) { progress(it * 0.95) }
                    withContext(Dispatchers.IO) { remuxToAdts(temp, output) }
                } finally {
                    temp.delete()
                }
            }
            OutputFormat.MP3, OutputFormat.OGG ->
                withContext(Dispatchers.IO) { encodeWithNative(input, range, format, bitrate, output, progress) }
            else -> throw AppError("内部错误：格式不对")
        }
        progress(1.0)
    }

    private suspend fun exportM4a(
        context: Context, input: File, range: ClipRange, bitrate: Int, output: File, progress: (Double) -> Unit,
    ) {
        val item = EditedMediaItem.Builder(mediaItem(input, range)).setRemoveVideo(true).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build()
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrate * 1000).build())
            .setEnableFallback(true)
            .build()
        runTransformer(context, composition, output, progress) {
            setAudioMimeType(MimeTypes.AUDIO_AAC)
            setEncoderFactory(object : Codec.EncoderFactory by encoders {
                // 同格式也强制重新编码，码率设置才会生效
                override fun audioNeedsEncoding() = true
            })
        }
    }

    /** .m4a 里的 AAC 原样取出，加上 ADTS 头写成 .aac */
    private fun remuxToAdts(m4a: File, output: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(m4a.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            } ?: throw AppError("找不到 AAC 声音")
            extractor.selectTrack(track)
            val f = extractor.getTrackFormat(track)
            val sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            val rateIndex = rates.indexOf(sampleRate).let { if (it < 0) 4 else it }
            val buffer = ByteBuffer.allocate(1 shl 16)
            FileOutputStream(output).use { out ->
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val frameLength = size + 7
                    val header = byteArrayOf(
                        0xFF.toByte(), 0xF1.toByte(),
                        ((1 shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte(),   // AAC LC
                        (((channels and 3) shl 6) or (frameLength shr 11)).toByte(),
                        ((frameLength shr 3) and 0xFF).toByte(),
                        (((frameLength and 7) shl 5) or 0x1F).toByte(),
                        0xFC.toByte(),
                    )
                    out.write(header)
                    val bytes = ByteArray(size)
                    buffer.position(0)
                    buffer.get(bytes, 0, size)
                    out.write(bytes)
                    buffer.clear()
                    extractor.advance()
                }
            }
        } catch (e: Exception) {
            output.delete()
            throw if (e is AppError) e else AppError("写 .aac 失败：${e.message}")
        } finally {
            extractor.release()
        }
    }

    /** 解码成 PCM，交给 LAME / Vorbis 编码 */
    private suspend fun encodeWithNative(
        input: File, range: ClipRange, format: OutputFormat, bitrate: Int, output: File,
        progress: (Double) -> Unit,
    ) {
        output.delete()
        var handle = 0L
        val isMp3 = format == OutputFormat.MP3
        try {
            PcmDecoder.decode(input, range, onFormat = { sampleRate, channels ->
                handle = if (isMp3) NativeEncoders.mp3Open(output.absolutePath, sampleRate, channels, bitrate)
                else NativeEncoders.oggOpen(output.absolutePath, sampleRate, channels, vorbisQuality(bitrate))
                if (handle == 0L) throw AppError(if (isMp3) "无法创建 MP3 文件" else "无法创建 OGG 文件")
            }, onSamples = { samples, frames, channels ->
                coroutineContext.ensureActive()
                val r = if (isMp3) NativeEncoders.mp3Write(handle, samples, frames, channels)
                else NativeEncoders.oggWrite(handle, samples, frames, channels)
                if (r != 0) throw AppError(if (isMp3) "MP3 编码失败" else "OGG 编码失败")
            }, progress = progress)
            val h = handle
            handle = 0L
            val r = if (isMp3) NativeEncoders.mp3Finish(h) else NativeEncoders.oggFinish(h)
            if (r != 0) throw AppError("写入失败（空间不够？）")
        } catch (e: Throwable) {
            if (handle != 0L) { if (isMp3) NativeEncoders.mp3Abort(handle) else NativeEncoders.oggAbort(handle) }
            output.delete()
            throw e
        }
    }

    private fun vorbisQuality(bitrate: Int) = when {
        bitrate < 112 -> 0.2f
        bitrate < 144 -> 0.4f
        bitrate < 176 -> 0.5f
        bitrate < 224 -> 0.6f
        bitrate < 288 -> 0.8f
        else -> 0.9f
    }

    // MARK: 共用

    private fun mediaItem(input: File, range: ClipRange): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.fromFile(input))
        if (!range.isFull) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.startMs)
                    .apply { range.endMs?.let { setEndPositionMs(it) } }
                    .build())
        }
        return builder.build()
    }

    /** Transformer 必须在主线程上用；用协程包起来，取消时停止导出 */
    private suspend fun runTransformer(
        context: Context, composition: Composition, output: File, progress: (Double) -> Unit,
        configure: Transformer.Builder.() -> Unit,
    ) = withContext(Dispatchers.Main) {
        output.delete()
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            var transformer: Transformer? = null
            val poll = object : Runnable {
                override fun run() {
                    val t = transformer ?: return
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        progress(min(holder.progress, 99) / 100.0)
                    }
                    handler.postDelayed(this, 300)
                }
            }
            val built = Transformer.Builder(context)
                .apply(configure)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition, exportResult: ExportResult, exportException: ExportException,
                    ) {
                        handler.removeCallbacks(poll)
                        output.delete()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                AppError("处理失败：${exportException.errorCodeName}（${exportException.message ?: ""}）"))
                        }
                    }
                })
                .build()
            transformer = built
            built.start(composition, output.absolutePath)
            handler.post(poll)
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    built.cancel()
                    output.delete()
                }
            }
        }
    }
}

/** MediaExtractor + MediaCodec 解码成 32 位浮点交错 PCM（超过 2 声道混成立体声） */
object PcmDecoder {
    suspend fun decode(
        input: File, range: ClipRange,
        onFormat: (sampleRate: Int, channels: Int) -> Unit,
        onSamples: suspend (FloatArray, Int, Int) -> Unit,
        progress: (Double) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AppError("这个文件里没有声音")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            val startUs = range.startMs * 1000
            val endUs = range.endMs?.let { it * 1000 } ?: Long.MAX_VALUE
            val totalUs = max(1L, min(endUs, if (durationUs > 0) durationUs else endUs) - startUs).toDouble()
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var channels = 0
            var outChannels = 0
            var sampleRate = 0
            var isFloat = false
            var lastReported = -1.0

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buf = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buf, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > endUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        outChannels = if (channels == 1) 1 else 2
                        onFormat(sampleRate, outChannels)
                    }
                    outIndex >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (info.size > 0 && channels > 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val samples = readSamples(buf.slice().order(ByteOrder.nativeOrder()), isFloat)
                            val frames = samples.size / channels
                            // 去掉开始时间之前、结束时间之后的部分
                            val firstUs = info.presentationTimeUs
                            val usPerFrame = 1_000_000.0 / sampleRate
                            var from = 0
                            var to = frames
                            if (firstUs < startUs) from = min(frames, ((startUs - firstUs) / usPerFrame).toInt())
                            val lastAllowed = ((endUs - firstUs) / usPerFrame)
                            if (lastAllowed < frames) to = max(from, lastAllowed.toInt())
                            if (to > from) {
                                val out = mix(samples, channels, outChannels, from, to)
                                onSamples(out, to - from, outChannels)
                            }
                            val fraction = ((firstUs - startUs) / totalUs).coerceIn(0.0, 0.99)
                            if (fraction - lastReported >= 0.01) { lastReported = fraction; progress(fraction) }
                            if (firstUs > endUs) outputDone = true
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
            if (sampleRate == 0) throw AppError("读不出声音数据")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun readSamples(buf: ByteBuffer, isFloat: Boolean): FloatArray {
        return if (isFloat) {
            val fb = buf.asFloatBuffer()
            FloatArray(fb.remaining()).also { fb.get(it) }
        } else {
            val sb = buf.asShortBuffer()
            val shorts = ShortArray(sb.remaining()).also { sb.get(it) }
            FloatArray(shorts.size) { shorts[it] / 32768f }
        }
    }

    private fun mix(samples: FloatArray, channels: Int, outChannels: Int, from: Int, to: Int): FloatArray {
        val frames = to - from
        if (channels == outChannels) return samples.copyOfRange(from * channels, to * channels)
        val out = FloatArray(frames * outChannels)
        for (i in 0 until frames) {
            val base = (from + i) * channels
            if (outChannels == 2) {
                // 多声道 → 立体声：左 = 0 号声道 + 中置一半，右 = 1 号声道 + 中置一半
                val center = if (channels > 2) samples[base + 2] * 0.5f else 0f
                out[i * 2] = clamp(samples[base] + center)
                out[i * 2 + 1] = clamp(samples[base + 1] + center)
            } else {
                var sum = 0f
                for (c in 0 until channels) sum += samples[base + c]
                out[i] = sum / channels
            }
        }
        return out
    }

    private fun clamp(v: Float) = if (abs(v) > 1f) (if (v > 0) 1f else -1f) else v
}

object NativeEncoders {
    init {
        System.loadLibrary("audioenc")
    }

    @JvmStatic external fun mp3Open(path: String, rate: Int, channels: Int, kbps: Int): Long
    @JvmStatic external fun mp3Write(handle: Long, samples: FloatArray, frames: Int, channels: Int): Int
    @JvmStatic external fun mp3Finish(handle: Long): Int
    @JvmStatic external fun mp3Abort(handle: Long)
    @JvmStatic external fun oggOpen(path: String, rate: Int, channels: Int, quality: Float): Long
    @JvmStatic external fun oggWrite(handle: Long, samples: FloatArray, frames: Int, channels: Int): Int
    @JvmStatic external fun oggFinish(handle: Long): Int
    @JvmStatic external fun oggAbort(handle: Long)
}
