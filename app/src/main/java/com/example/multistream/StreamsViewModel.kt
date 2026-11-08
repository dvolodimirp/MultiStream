package com.example.multistream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import org.json.JSONArray
import org.json.JSONObject

data class StreamItem(
    val id: Long,
    val name: String,
    val url: String,
    val mjpeg: Boolean = false,
    val user: String = "",
    val pass: String = "",
) {
    /** MJPEG, YouTube і Twitch відтворюються через WebView, решта — через ExoPlayer. */
    val isWeb: Boolean get() = mjpeg || Embeds.isWeb(url)

    /** Логін/пароль з окремих полів або з адреси виду http://user:pass@host/. */
    fun credentials(): Pair<String, String> {
        if (user.isNotEmpty()) return user to pass
        val info = Uri.parse(url).userInfo ?: return "" to ""
        val i = info.indexOf(':')
        return if (i < 0) info to "" else info.substring(0, i) to info.substring(i + 1)
    }
}

/**
 * Тримає список стрімів, по одному ExoPlayer (або WebView) на кожен.
 * Плеєри живуть у ViewModel, тому розгортання на весь екран
 * використовує той самий плеєр без перезавантаження потоку.
 */
@OptIn(UnstableApi::class)
class StreamsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MAX_STREAMS = 9
        private const val PREFS = "multistream"
        private const val KEY = "streams_json"

        private val DEMO = listOf(
            "Demo HLS 1" to "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            "Demo HLS 2" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        )

        /** Якщо формат не розпізнано, послідовно пробуємо примусові типи (для http/https). */
        private val ATTEMPTS = listOf<String?>(
            null,
            MimeTypes.APPLICATION_M3U8,
            MimeTypes.APPLICATION_MPD,
            MimeTypes.VIDEO_MP2T,
        )

        private val RECOVERABLE = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    }

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val players = HashMap<Long, ExoPlayer>()
    private val webViews = HashMap<Long, WebView>()
    private val attemptIdx = HashMap<Long, Int>()
    private val authTries = HashMap<Long, Int>()
    private var foreground = true
    private var multicastLock: WifiManager.MulticastLock? = null

    val streams = mutableStateListOf<StreamItem>()

    /** Id стріму, розгорнутого на весь екран (null — показується сітка). */
    var focusedId by mutableStateOf<Long?>(null)
        private set

    init {
        load()
        ensureMulticastLock()
    }

    // ---------- ExoPlayer ----------

    private fun mediaItem(url: String, mime: String?): MediaItem =
        MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()

    private fun buildPlayer(): ExoPlayer {
        val app = getApplication<Application>()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent("Mozilla/5.0 (Linux; Android) MultiStream/1.0")
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
            .setConstantBitrateSeekingEnabled(true)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http), extractors)
        return ExoPlayer.Builder(app).setMediaSourceFactory(sources).build()
    }

    fun playerFor(item: StreamItem): ExoPlayer = players.getOrPut(item.id) {
        val player = buildPlayer()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode !in RECOVERABLE) return
                val scheme = Uri.parse(item.url).scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return
                val next = (attemptIdx[item.id] ?: 0) + 1
                if (next >= ATTEMPTS.size) return
                attemptIdx[item.id] = next
                player.setMediaItem(mediaItem(item.url, ATTEMPTS[next]))
                player.prepare()
            }
        })
        player.setMediaItem(mediaItem(item.url, ATTEMPTS[0]))
        player.prepare()
        applyState(item.id, player)
        player
    }

    // ---------- WebView (YouTube / Twitch) ----------

    @SuppressLint("SetJavaScriptEnabled")
    fun webViewFor(item: StreamItem): WebView = webViews.getOrPut(item.id) {
        WebView(getApplication<Application>()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(Color.BLACK)
            webChromeClient = WebChromeClient()
            if (item.mjpeg) {
                // Basic/Digest-авторизація камери: відповідаємо логіном і паролем (до 3 спроб, щоб не зациклитись).
                webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpAuthRequest(
                        view: WebView?,
                        handler: HttpAuthHandler,
                        host: String?,
                        realm: String?,
                    ) {
                        val (u, p) = item.credentials()
                        val tries = authTries[item.id] ?: 0
                        if (u.isNotEmpty() && tries < 3) {
                            authTries[item.id] = tries + 1
                            handler.proceed(u, p)
                        } else {
                            handler.cancel()
                        }
                    }
                }
            }
            loadEmbed(this, item)
        }
    }

    private fun loadEmbed(wv: WebView, item: StreamItem) {
        authTries[item.id] = 0
        if (item.mjpeg) {
            wv.loadDataWithBaseURL(
                Embeds.mjpegOrigin(item.url), Embeds.mjpegHtml(item.url), "text/html", "utf-8", null,
            )
        } else {
            wv.loadDataWithBaseURL(Embeds.BASE_URL, Embeds.html(item.url), "text/html", "utf-8", null)
        }
    }

    // ---------- Керування списком ----------

    fun add(
        name: String,
        url: String,
        mjpeg: Boolean = false,
        user: String = "",
        pass: String = "",
    ): Boolean {
        if (streams.size >= MAX_STREAMS) return false
        val id = System.nanoTime()
        streams += StreamItem(
            id, name.ifBlank { "Стрім ${streams.size + 1}" }, url.trim(), mjpeg, user, pass,
        )
        save()
        ensureMulticastLock()
        return true
    }

    fun addDemoStreams() {
        DEMO.forEach { (name, url) -> if (streams.none { it.url == url }) add(name, url) }
    }

    fun remove(id: Long) {
        players.remove(id)?.release()
        webViews.remove(id)?.let { destroyWebView(it) }
        attemptIdx.remove(id)
        authTries.remove(id)
        streams.removeAll { it.id == id }
        if (focusedId == id) focusedId = null
        applyAll()
        save()
    }

    /** Зсунути стрім у списку: delta = -1 (раніше) або +1 (пізніше). */
    fun move(id: Long, delta: Int) {
        val i = streams.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in streams.indices) return
        val item = streams.removeAt(i)
        streams.add(j, item)
        save()
    }

    fun restart(id: Long) {
        val item = streams.firstOrNull { it.id == id } ?: return
        if (item.isWeb) {
            webViews[id]?.let { loadEmbed(it, item) }
            return
        }
        players[id]?.let {
            attemptIdx[id] = 0
            it.setMediaItem(mediaItem(item.url, ATTEMPTS[0]))
            it.prepare()
            applyState(id, it)
        }
    }

    // ---------- Фокус / повний екран ----------

    fun focus(id: Long) {
        focusedId = id
        applyAll()
    }

    fun unfocus() {
        focusedId = null
        applyAll()
    }

    /** Перемкнутись на наступний (+1) або попередній (-1) стрім у повноекранному режимі. */
    fun cycleFocus(delta: Int) {
        val cur = streams.indexOfFirst { it.id == focusedId }
        if (cur < 0 || streams.size < 2) return
        focus(streams[(cur + delta + streams.size) % streams.size].id)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        webViews.values.forEach { if (value) it.onResume() else it.onPause() }
        applyAll()
    }

    /** У фокусі грає тільки обраний стрім і зі звуком; решта на паузі (економія трафіку). */
    private fun applyState(id: Long, p: ExoPlayer) {
        val shouldPlay = foreground && (focusedId == null || focusedId == id)
        if (shouldPlay && !p.playWhenReady && p.isCurrentMediaItemLive) {
            p.seekToDefaultPosition() // повернутись до live-краю після паузи
        }
        p.playWhenReady = shouldPlay
        p.volume = if (focusedId == id) 1f else 0f
    }

    private fun applyAll() = players.forEach { (id, p) -> applyState(id, p) }

    // ---------- Збереження ----------

    private fun save() {
        val arr = JSONArray()
        streams.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("url", it.url)
                    .put("mjpeg", it.mjpeg).put("user", it.user).put("pass", it.pass),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                streams += StreamItem(
                    o.getLong("id"), o.getString("name"), o.getString("url"),
                    o.optBoolean("mjpeg"), o.optString("user"), o.optString("pass"),
                )
            }
        } catch (_: Exception) {
            streams.clear()
        }
    }

    // ---------- Службове ----------

    /** Для udp:// multicast Wi-Fi-чіп має пропускати multicast-пакети. */
    private fun ensureMulticastLock() {
        if (multicastLock != null) return
        if (streams.none { it.url.startsWith("udp://", ignoreCase = true) }) return
        val wifi = getApplication<Application>().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("multistream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun destroyWebView(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
    }

    override fun onCleared() {
        players.values.forEach { it.release() }
        players.clear()
        webViews.values.forEach { destroyWebView(it) }
        webViews.clear()
        multicastLock?.takeIf { it.isHeld }?.release()
    }
}
