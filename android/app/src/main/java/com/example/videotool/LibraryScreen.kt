package com.example.videotool

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/** 「已导出」列表 */
@Composable
fun LibraryScreen(onOpen: (File) -> Unit) {
    val context = LocalContext.current
    val jobs by JobEngine.jobs.collectAsStateCompat()   // 有新导出时刷新
    val files = remember(jobs) { Storage.exportedFiles(context) }
    Column {
        FormSection(footer = "点一个文件可以播放、分享、存到相册或再剪一次。") {
            if (files.isEmpty()) Text("还没有导出的文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            files.forEachIndexed { i, file ->
                if (i > 0) HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(file) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (Storage.isAudio(file)) "♪" else "▶", Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 2)
                        Caption(Storage.sizeText(file))
                    }
                }
            }
        }
    }
}

/** 播放一个导出的文件 */
@Composable
fun PlayerScreen(file: File, onEdit: (File) -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Column {
        FormSection {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxWidth().height(if (Storage.isAudio(file)) 100.dp else 260.dp),
            )
            Text(file.name)
            Caption(Storage.sizeText(file))
        }
        FormSection {
            FileActions(file) { message = it }
            OutlinedButton(onClick = { onEdit(file) }, modifier = Modifier.fillMaxWidth()) { Text("再剪一次") }
            OutlinedButton(onClick = {
                player.release()
                file.delete()
                onDeleted()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
            message?.let { Caption(it) }
        }
    }
}
