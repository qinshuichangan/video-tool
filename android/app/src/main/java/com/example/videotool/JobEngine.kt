package com.example.videotool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class JobKind { SINGLE, BATCH }

sealed class JobStatus {
    data object Waiting : JobStatus()
    data class Running(val progress: Double) : JobStatus()
    data class Done(val file: File) : JobStatus()
    data class Failed(val message: String) : JobStatus()
}

data class Job(
    val id: Long,
    val kind: JobKind,
    val input: File,
    val name: String,
    val range: ClipRange,
    /** 开始处理时才确定（批量是按「开始」那一刻的设置） */
    val choice: OutputChoice? = null,
    /** true = 已经安排处理（批量要按「开始」才会安排） */
    val armed: Boolean = false,
    val status: JobStatus = JobStatus.Waiting,
)

/** 所有处理任务排成一个队列，一个一个做（单个导出也走这里） */
object JobEngine {
    private val ids = AtomicLong(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: kotlinx.coroutines.Job? = null
    private var currentJob: kotlinx.coroutines.Job? = null
    private var currentId: Long? = null
    private var batchStopRequested = false
    private lateinit var app: Context

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    /** 批量这一轮的进度：第几个 / 共几个 / 总进度 */
    data class BatchProgress(val index: Int, val total: Int, val overall: Double)
    private val _batch = MutableStateFlow<BatchProgress?>(null)
    val batch: StateFlow<BatchProgress?> = _batch.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val batchMessage: StateFlow<String?> = _message.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun job(id: Long?) = _jobs.value.firstOrNull { it.id == id }

    // MARK: 单个

    /** 单个导出：马上排进队列。返回任务 id */
    fun exportSingle(context: Context, input: File, name: String, range: ClipRange): Long {
        init(context)
        val job = Job(ids.getAndIncrement(), JobKind.SINGLE, input, name, range,
            choice = OutputChoice.load(context), armed = true)
        _jobs.update { list -> list.filterNot { it.kind == JobKind.SINGLE && it.status !is JobStatus.Running } + job }
        ensureRunning()
        return job.id
    }

    fun cancel(id: Long) {
        if (currentId == id) currentJob?.cancel()
        else _jobs.update { list -> list.filterNot { it.id == id && it.kind == JobKind.SINGLE } }
    }

    // MARK: 批量

    fun addBatch(files: List<File>) {
        val new = files.map {
            Job(ids.getAndIncrement(), JobKind.BATCH, it, it.nameWithoutExtension, ClipRange(0, null))
        }
        _jobs.update { it + new }
    }

    fun removeBatch(id: Long) {
        val job = job(id) ?: return
        if (job.status is JobStatus.Running) return
        if (job.status !is JobStatus.Done) Storage.deleteImport(job.input)
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinishedBatch() = _jobs.update { list ->
        list.filterNot { it.kind == JobKind.BATCH && it.status is JobStatus.Done }
    }

    fun retryFailedBatch() = _jobs.update { list ->
        list.map { if (it.kind == JobKind.BATCH && it.status is JobStatus.Failed) it.copy(status = JobStatus.Waiting) else it }
    }

    fun startBatch(context: Context) {
        init(context)
        val choice = OutputChoice.load(context)
        batchStopRequested = false
        _message.value = null
        _jobs.update { list ->
            list.map {
                if (it.kind == JobKind.BATCH && it.status == JobStatus.Waiting) it.copy(choice = choice, armed = true) else it
            }
        }
        val total = _jobs.value.count { it.kind == JobKind.BATCH && it.armed && it.status == JobStatus.Waiting }
        _batch.value = BatchProgress(0, total, 0.0)
        ensureRunning()
    }

    fun stopBatch() {
        batchStopRequested = true
        _jobs.update { list -> list.map { if (it.kind == JobKind.BATCH && it.status == JobStatus.Waiting) it.copy(armed = false) else it } }
        if (job(currentId)?.kind == JobKind.BATCH) currentJob?.cancel()
    }

    val batchRunning get() = _batch.value != null

    // MARK: 运行

    private fun ensureRunning() {
        if (loop?.isActive == true) return
        _working.value = true
        WorkService.start(app)
        loop = scope.launch {
            var batchDone = 0
            var batchFailed = 0
            while (true) {
                val next = _jobs.value.firstOrNull { it.armed && it.status == JobStatus.Waiting } ?: break
                if (next.kind == JobKind.BATCH) {
                    _batch.update { it?.copy(index = batchDone + batchFailed + 1) }
                }
                val result = runCatching { process(next) }
                when {
                    result.isSuccess -> {
                        setStatus(next.id, JobStatus.Done(result.getOrThrow()))
                        if (next.kind == JobKind.BATCH) { Storage.deleteImport(next.input); batchDone++ }
                    }
                    result.exceptionOrNull() is CancellationException -> {
                        if (next.kind == JobKind.BATCH) {
                            setStatus(next.id, JobStatus.Waiting)
                            disarm(next.id)
                        } else {
                            setStatus(next.id, JobStatus.Failed("已取消"))
                        }
                    }
                    else -> {
                        setStatus(next.id, JobStatus.Failed(result.exceptionOrNull()?.message ?: "未知错误"))
                        if (next.kind == JobKind.BATCH) batchFailed++
                    }
                }
                if (next.kind == JobKind.BATCH) {
                    _batch.update { b -> b?.copy(overall = if (b.total > 0) (batchDone + batchFailed).toDouble() / b.total else 1.0) }
                }
            }
            if (_batch.value != null) {
                _message.value = when {
                    batchStopRequested -> "已停止，剩下的还在队列里。"
                    batchFailed > 0 -> "完成了，有 $batchFailed 个失败。"
                    else -> "全部完成！"
                }
                _batch.value = null
            }
            _working.value = false
            WorkService.stop(app)
        }
    }

    private fun setStatus(id: Long, status: JobStatus) =
        _jobs.update { list -> list.map { if (it.id == id) it.copy(status = status) else it } }

    private fun disarm(id: Long) =
        _jobs.update { list -> list.map { if (it.id == id) it.copy(armed = false) else it } }

    private suspend fun process(job: Job): File {
        val context = app
        val choice = job.choice ?: OutputChoice.load(context)
        setStatus(job.id, JobStatus.Running(0.0))
        currentId = job.id
        try {
            return kotlinx.coroutines.coroutineScope {
                currentJob = coroutineContext[kotlinx.coroutines.Job]
                val info = withContext(Dispatchers.IO) { MediaProbe.probe(job.input) }
                if (info.durationSec <= 0) throw AppError("读不出长度，可能不是视频或音频")
                if (!info.hasVideo && !info.hasAudio) throw AppError("没有画面也没有声音")
                val format = choice.formatFor(info.hasVideo)
                if (format.isAudio && !info.hasAudio) throw AppError("这个文件里没有声音")

                val endSec = job.range.endMs?.let { it / 1000.0 } ?: info.durationSec
                val seconds = endSec - job.range.startMs / 1000.0
                val name = if (format == OutputFormat.COMPRESSED) job.name + "_压缩" else job.name
                val output = Storage.outputFile(context, name, job.range, endSec, format.ext)
                val report: (Double) -> Unit = { p -> onProgress(job, p) }

                when (format) {
                    OutputFormat.VIDEO ->
                        Exporter.trimVideo(context, job.input, job.range, choice.precise, output, report)
                    OutputFormat.COMPRESSED -> {
                        val source = info.video ?: throw AppError("读不出视频的分辨率")
                        Exporter.compressVideo(context, job.input, job.range,
                            choice.compressSettings(source, seconds), source, output, report)
                    }
                    else -> Exporter.convertAudio(context, job.input, job.range, format, choice.audioBitrate, output, report)
                }
                output
            }
        } finally {
            currentId = null
            currentJob = null
        }
    }

    private fun onProgress(job: Job, p: Double) {
        setStatus(job.id, JobStatus.Running(p))
        val b = _batch.value
        val text: String
        val overall: Double
        if (job.kind == JobKind.BATCH && b != null && b.total > 0) {
            overall = ((b.index - 1) + p) / b.total
            _batch.value = b.copy(overall = overall)
            text = "批量处理：第 ${b.index} / ${b.total} 个"
        } else {
            overall = p
            text = job.name
        }
        WorkService.update(app, text, overall)
    }
}

/** 前台服务：切到别的 App、锁屏后也继续处理，通知栏显示进度 */
class WorkService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 锁屏后 CPU 也不睡
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoTool:work")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, "准备中…", 0.0), type)
        instance = this
        // 任务在服务真正启动之前就做完了：这时候才能安全地停掉
        if (stopRequested) shutdown()
        return START_NOT_STICKY
    }

    private fun shutdown() {
        stopRequested = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "work"
        private const val NOTIFICATION_ID = 1
        private var lastUpdate = 0L
        private var instance: WorkService? = null
        private var stopRequested = false

        fun start(context: Context) {
            stopRequested = false
            createChannel(context)
            ContextCompat.startForegroundService(context, Intent(context, WorkService::class.java))
        }

        /** 服务还没来得及调用 startForeground 就停掉会让 App 崩溃，所以先记下来，等它启动后再停 */
        fun stop(context: Context) {
            val service = instance
            if (service != null) service.shutdown() else stopRequested = true
        }

        fun update(context: Context, text: String, progress: Double) {
            if (instance == null) return
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 1000 && progress < 1.0) return
            lastUpdate = now
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(context, text, progress))
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "处理进度", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun notification(context: Context, text: String, progress: Double): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("剪辑转换：处理中 ${(progress * 100).toInt()}%")
                .setContentText(text)
                .setProgress(100, (progress * 100).toInt(), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}
