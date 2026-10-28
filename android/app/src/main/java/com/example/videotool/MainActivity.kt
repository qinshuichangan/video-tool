package com.example.videotool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class MainActivity : ComponentActivity() {
    /** 别的 App「分享」或「打开方式」传进来的文件 */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JobEngine.init(this)
        enableEdgeToEdge()
        incoming.value = incomingUri(intent)
        setContent {
            AppTheme {
                App(incoming.value, onIncomingHandled = { incoming.value = null }, onDim = ::applyDim)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUri(intent)?.let { incoming.value = it }
    }

    private fun incomingUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    /** 处理中：不自动锁屏，可选调到最暗；结束后恢复 */
    private fun applyDim(working: Boolean, dim: Boolean) {
        if (working) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attrs = window.attributes
        attrs.screenBrightness = if (working && dim) 0.01f
        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
    }
}

private enum class Screen { EDITOR, BATCH, LIBRARY, PLAYER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(incoming: Uri?, onIncomingHandled: () -> Unit, onDim: (Boolean, Boolean) -> Unit) {
    val context = LocalContext.current
    val editor: EditorModel = viewModel()
    var screen by remember { mutableStateOf(Screen.EDITOR) }
    var playing by remember { mutableStateOf<File?>(null) }
    var dim by remember { mutableStateOf(OutputChoice.dimEnabled(context)) }
    val working by JobEngine.working.collectAsStateCompat()

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val requestNotifications = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val setDim = { value: Boolean -> dim = value; OutputChoice.setDimEnabled(context, value) }

    LaunchedEffect(working, dim) { onDim(working, dim) }
    LaunchedEffect(incoming) {
        if (incoming != null) {
            screen = Screen.EDITOR
            editor.importUri(incoming)
            onIncomingHandled()
        }
    }
    BackHandler(enabled = screen != Screen.EDITOR) {
        screen = if (screen == Screen.PLAYER) Screen.LIBRARY else Screen.EDITOR
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (screen) {
                        Screen.EDITOR -> "剪辑转换"
                        Screen.BATCH -> "批量处理"
                        Screen.LIBRARY -> "已导出"
                        Screen.PLAYER -> playing?.name ?: ""
                    }, maxLines = 1)
                },
                navigationIcon = {
                    when (screen) {
                        Screen.EDITOR -> TextButton(onClick = { screen = Screen.BATCH }) { Text("批量") }
                        else -> TextButton(onClick = {
                            screen = if (screen == Screen.PLAYER) Screen.LIBRARY else Screen.EDITOR
                        }) { Text("‹ 返回") }
                    }
                },
                actions = {
                    if (screen == Screen.EDITOR) TextButton(onClick = { screen = Screen.LIBRARY }) { Text("已导出") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            when (screen) {
                Screen.EDITOR -> EditorScreen(editor, dim, setDim, requestNotifications)
                Screen.BATCH -> BatchScreen(dim, setDim, requestNotifications)
                Screen.LIBRARY -> LibraryScreen { playing = it; screen = Screen.PLAYER }
                Screen.PLAYER -> playing?.let { file ->
                    PlayerScreen(file,
                        onEdit = { editor.load(it); screen = Screen.EDITOR },
                        onDeleted = { screen = Screen.LIBRARY })
                }
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()

fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
