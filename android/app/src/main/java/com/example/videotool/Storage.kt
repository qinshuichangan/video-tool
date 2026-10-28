package com.example.videotool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object Storage {
    const val EDITOR_IMPORTS = "Imported"
    const val BATCH_IMPORTS = "BatchImported"

    val videoExtensions = setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")
    val audioExtensions = setOf("m4a", "aac", "mp3", "ogg", "wav", "flac", "opus")

    /** 导出的文件放在 App 自己的文件夹里（「已导出」列表就是这里） */
    fun outputDir(context: Context) = File(context.filesDir, "exports").apply { mkdirs() }

    private fun importRoot(context: Context, folder: String) = File(context.cacheDir, folder)

    /** 在临时文件夹里准备一个不重名的位置 */
    fun makeImportFile(context: Context, folder: String, name: String): File {
        val dir = File(importRoot(context, folder), UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, name.ifBlank { "video.mp4" }.replace('/', '_'))
    }

    /** 把别的 App 传来的文件（content:// 或 file://）复制进来 */
    fun importUri(context: Context, uri: Uri, folder: String, fallbackName: String? = null): File {
        val name = displayName(context, uri) ?: fallbackName ?: ("导入_" + System.currentTimeMillis())
        val withExt = if (name.contains('.')) name else {
            val ext = context.contentResolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "mp4"
            "$name.$ext"
        }
        val target = makeImportFile(context, folder, withExt)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "打不开这个文件" }
            target.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        return target
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    /** 删掉以前导入的临时文件（keep 所在的文件夹除外） */
    fun cleanImports(context: Context, folder: String, keep: File?) {
        val keepDir = keep?.parentFile?.absolutePath
        importRoot(context, folder).listFiles()?.forEach { dir ->
            if (dir.absolutePath != keepDir) dir.deleteRecursively()
        }
    }

    /** 删掉一个导入副本（连同它的随机文件夹） */
    fun deleteImport(file: File) {
        file.parentFile?.deleteRecursively()
    }

    /** 例：「我的视频_1m05s-2m30s.mp3」；整段就是「我的视频.mp3」；重名加 (2)、(3)… */
    fun outputFile(context: Context, name: String, range: ClipRange, endSec: Double, ext: String): File {
        var base = name.ifBlank { "输出" }
        if (!range.isFull) base += "_${TimeText.fileStamp(range.startMs / 1000.0)}-${TimeText.fileStamp(endSec)}"
        val dir = outputDir(context)
        var file = File(dir, "$base.$ext")
        var n = 2
        while (file.exists()) {
            file = File(dir, "$base ($n).$ext")
            n++
        }
        return file
    }

    fun isAudio(file: File) = file.extension.lowercase() in audioExtensions

    fun sizeText(file: File): String {
        val bytes = file.length().toDouble()
        return if (bytes >= 1024 * 1024 * 1024) String.format("%.2f GB", bytes / 1024 / 1024 / 1024)
        else String.format("%.1f MB", bytes / 1024 / 1024)
    }

    fun exportedFiles(context: Context): List<File> =
        outputDir(context).listFiles()
            ?.filter { it.isFile && (isAudio(it) || it.extension.lowercase() in videoExtensions) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    // MARK: 分享 / 剪贴板 / 相册

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: if (isAudio(file)) "audio/*" else "video/*"

    fun share(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 / 存到其他 App").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copyToClipboard(context: Context, file: File): Boolean = runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, file.name, uriFor(context, file)))
    }.isSuccess

    /** 视频存到「相册/影片/剪辑转换」，音频存到「音乐/剪辑转换」 */
    fun saveToGallery(context: Context, file: File): String = runCatching {
        val audio = isAudio(file)
        val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/剪辑转换")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("无法创建")
        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out)
            file.inputStream().use { it.copyTo(out, 1 shl 16) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        if (audio) "已存到「音乐/剪辑转换」" else "已存到相册（影片/剪辑转换）"
    }.getOrElse { "保存失败：${it.message}" }

    /** 剪贴板里的音频 / 视频 */
    fun clipboardMediaUri(context: Context): Uri? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return null
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = context.contentResolver.getType(uri) ?: ""
            if (type.startsWith("video/") || type.startsWith("audio/")) return uri
        }
        return null
    }
}
