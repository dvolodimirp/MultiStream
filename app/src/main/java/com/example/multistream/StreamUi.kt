package com.example.multistream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.ceil

@Composable
fun MultiStreamApp(vm: StreamsViewModel) {
    val context = LocalContext.current
    val focused = vm.streams.firstOrNull { it.id == vm.focusedId }
    var showAdd by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }

    ImmersiveMode(enabled = focused != null)
    BackHandler(enabled = focused != null) { vm.unfocus() }

    if (focused != null) {
        key(focused.id) { FullscreenPlayer(vm, focused) }
    } else {
        Scaffold(
            containerColor = Color.Black,
            floatingActionButton = {
                SmallFloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Додати стрім")
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (vm.streams.isEmpty()) {
                    EmptyState(onAdd = { showAdd = true }, onDemo = vm::addDemoStreams)
                } else {
                    StreamGrid(vm)
                }
                Text(
                    "v$version",
                    color = Color(0x88FFFFFF),
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                )
            }
        }
    }

    if (showAdd) {
        AddStreamDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, url, mjpeg, user, pass ->
                if (vm.add(name, url, mjpeg, user, pass)) {
                    showAdd = false
                } else {
                    Toast.makeText(
                        context,
                        "Максимум ${StreamsViewModel.MAX_STREAMS} стрімів",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, onDemo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Додайте посилання на стріми: HLS, DASH, MPEG-TS, RTSP, RTMP, UDP, MP4, YouTube, Twitch.\n" +
                "Тап по плитці — на весь екран, довгий тап — меню.",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) { Text("Додати стрім") }
        OutlinedButton(onClick = onDemo, modifier = Modifier.padding(top = 8.dp)) {
            Text("Додати тестові стріми")
        }
    }
}

/** Сітка, що заповнює весь екран без прокрутки (до 9 плиток). */
@Composable
private fun StreamGrid(vm: StreamsViewModel) {
    val items = vm.streams
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val n = items.size
        val cols = if (landscape) {
            when {
                n <= 1 -> 1
                n <= 4 -> 2
                else -> 3
            }
        } else {
            when {
                n <= 2 -> 1
                n <= 6 -> 2
                else -> 3
            }
        }
        val rows = ceil(n / cols.toDouble()).toInt()

        Column(Modifier.fillMaxSize()) {
            repeat(rows) { r ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(cols) { c ->
                        val item = items.getOrNull(r * cols + c)
                        Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp)) {
                            if (item != null) {
                                key(item.id) { StreamTile(vm, item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamTile(vm: StreamsViewModel, item: StreamItem) {
    val player = if (item.isWeb || item.isMjpeg) null else vm.playerFor(item)
    var menu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
        when {
            player != null -> PlayerSurface(player, controller = false)
            item.isMjpeg -> MjpegSurface(vm.mjpegFor(item))
            else -> WebSurface(vm.webViewFor(item))
        }

        // Шар для жестів: тап — на весь екран, довгий тап — меню.
        Box(
            Modifier.matchParentSize().combinedClickable(
                onClick = { vm.focus(item.id) },
                onLongClick = { menu = true },
            ),
        )

        Text(
            item.name,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        if (player != null) PlaybackOverlay(player, onRetry = { vm.restart(item.id) })

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Розгорнути") },
                onClick = { menu = false; vm.focus(item.id) },
            )
            DropdownMenuItem(
                text = { Text("Перезапустити") },
                onClick = { menu = false; vm.restart(item.id) },
            )
            DropdownMenuItem(
                text = { Text("Перемістити раніше") },
                onClick = { menu = false; vm.move(item.id, -1) },
            )
            DropdownMenuItem(
                text = { Text("Перемістити пізніше") },
                onClick = { menu = false; vm.move(item.id, +1) },
            )
            DropdownMenuItem(
                text = { Text("Видалити") },
                onClick = { menu = false; vm.remove(item.id) },
            )
        }
    }
}

@Composable
private fun FullscreenPlayer(vm: StreamsViewModel, item: StreamItem) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (item.isMjpeg) {
            MjpegSurface(vm.mjpegFor(item))
        } else if (item.isWeb) {
            WebSurface(vm.webViewFor(item))
        } else {
            val player = vm.playerFor(item)
            PlayerSurface(player, controller = true)
            PlaybackOverlay(player, onRetry = { vm.restart(item.id) })
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x66000000))
                .safeDrawingPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.streams.size > 1) {
                TextButton(onClick = { vm.cycleFocus(-1) }) {
                    Text("‹", color = Color.White, fontSize = 28.sp)
                }
            }
            Text(
                item.name,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            if (vm.streams.size > 1) {
                TextButton(onClick = { vm.cycleFocus(+1) }) {
                    Text("›", color = Color.White, fontSize = 28.sp)
                }
            }
            IconButton(onClick = vm::unfocus) {
                Icon(Icons.Default.Close, contentDescription = "Закрити", tint = Color.White)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(player: ExoPlayer, controller: Boolean) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = controller
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                if (controller) controllerShowTimeoutMs = 2500
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}

/** WebView живе у ViewModel, тому при розгортанні перекидається в нове місце без перезавантаження. */
@Composable
private fun WebSurface(webView: WebView) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
    )
}

/** Кадри MJPEG + текст стану/помилки (наприклад «HTTP 401», «Таймаут: камера не відповідає»). */
@Composable
private fun BoxScope.MjpegSurface(source: MjpegSource) {
    val frame = source.frame
    val status = source.status
    if (frame != null) {
        Image(
            bitmap = frame,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (status.isNotEmpty()) {
        Text(
            status,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(if (frame == null) Alignment.Center else Alignment.BottomCenter)
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

private data class PlayUi(val state: Int, val error: String?)

private fun PlaybackException.brief(): String {
    val reason = cause?.message?.take(90)
    return if (reason.isNullOrBlank()) errorCodeName else "$errorCodeName: $reason"
}

@Composable
private fun rememberPlayUi(player: ExoPlayer): State<PlayUi> {
    val state = remember(player) {
        mutableStateOf(PlayUi(player.playbackState, player.playerError?.brief()))
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.value = PlayUi(playbackState, player.playerError?.brief())
            }

            override fun onPlayerError(error: PlaybackException) {
                state.value = PlayUi(player.playbackState, error.brief())
            }
        }
        player.addListener(listener)
        state.value = PlayUi(player.playbackState, player.playerError?.brief())
        onDispose { player.removeListener(listener) }
    }
    return state
}

@Composable
private fun BoxScope.PlaybackOverlay(player: ExoPlayer, onRetry: () -> Unit) {
    val ui by rememberPlayUi(player)
    val error = ui.error
    if (error != null) {
        Column(
            Modifier.align(Alignment.Center).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Помилка відтворення", color = Color.White, fontSize = 12.sp)
            Text(
                error,
                color = Color(0xFFFFB4AB),
                fontSize = 10.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) { Text("Повторити") }
        }
    } else if (ui.state == Player.STATE_BUFFERING) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center).size(28.dp),
            strokeWidth = 3.dp,
            color = Color.White,
        )
    }
}

@Composable
private fun AddStreamDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, mjpeg: Boolean, user: String, pass: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var mjpeg by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новий стрім") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Назва (необов'язково)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = false },
                    label = { Text("URL стріму") },
                    singleLine = true,
                    isError = error,
                    supportingText = {
                        Text(
                            if (error) "Потрібне посилання http(s)://, rtsp://, rtmp:// або udp://"
                            else "HLS, DASH, MPEG-TS, RTSP, RTMP, UDP, MP4, YouTube, Twitch",
                        )
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mjpeg, onCheckedChange = { mjpeg = it; error = false })
                    Text("MJPEG (IP-камера, mjpg-streamer)")
                }
                if (mjpeg) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("Логін (необов'язково)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Пароль (необов'язково)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isValidUrl(url, mjpeg)) {
                    onAdd(name.trim(), url.trim(), mjpeg, if (mjpeg) user.trim() else "", if (mjpeg) pass else "")
                } else {
                    error = true
                }
            }) { Text("Додати") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

private fun isValidUrl(s: String, mjpeg: Boolean): Boolean {
    val uri = Uri.parse(s.trim())
    val scheme = uri.scheme?.lowercase() ?: return false
    val allowed = if (mjpeg) setOf("http", "https") else setOf("http", "https", "rtsp", "rtmp", "udp")
    return scheme in allowed && !uri.host.isNullOrBlank()
}

/** Ховає системні панелі у повноекранному режимі (свайп від краю показує їх тимчасово). */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            if (enabled) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
