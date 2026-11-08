package com.example.multistream

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

data class StreamItem(val id: Long, val name: String, val url: String)

/**
 * Тримає список стрімів і по одному ExoPlayer на кожен.
 * Плеєри живуть у ViewModel, тому розгортання на весь екран
 * використовує той самий плеєр без перезавантаження потоку.
 */
class StreamsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MAX_STREAMS = 9
        private const val PREFS = "multistream"
        private const val KEY = "streams_json"

        private val DEMO = listOf(
            "Demo HLS 1" to "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            "Demo HLS 2" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        )
    }

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val players = HashMap<Long, ExoPlayer>()
    private var foreground = true

    val streams = mutableStateListOf<StreamItem>()

    /** Id стріму, розгорнутого на весь екран (null — показується сітка). */
    var focusedId by mutableStateOf<Long?>(null)
        private set

    init {
        load()
    }

    fun playerFor(item: StreamItem): ExoPlayer = players.getOrPut(item.id) {
        ExoPlayer.Builder(getApplication<Application>()).build().apply {
            setMediaItem(MediaItem.fromUri(item.url))
            prepare()
            applyState(item.id, this)
        }
    }

    fun add(name: String, url: String): Boolean {
        if (streams.size >= MAX_STREAMS) return false
        val id = System.nanoTime()
        streams += StreamItem(id, name.ifBlank { "Стрім ${streams.size + 1}" }, url.trim())
        save()
        return true
    }

    fun addDemoStreams() {
        DEMO.forEach { (name, url) -> if (streams.none { it.url == url }) add(name, url) }
    }

    fun remove(id: Long) {
        players.remove(id)?.release()
        streams.removeAll { it.id == id }
        if (focusedId == id) focusedId = null
        applyAll()
        save()
    }

    fun restart(id: Long) {
        players[id]?.let {
            it.seekToDefaultPosition()
            it.prepare()
            applyState(id, it)
        }
    }

    fun focus(id: Long) {
        focusedId = id
        applyAll()
    }

    fun unfocus() {
        focusedId = null
        applyAll()
    }

    fun setForeground(value: Boolean) {
        foreground = value
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

    private fun save() {
        val arr = JSONArray()
        streams.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                streams += StreamItem(o.getLong("id"), o.getString("name"), o.getString("url"))
            }
        } catch (_: Exception) {
            streams.clear()
        }
    }

    override fun onCleared() {
        players.values.forEach { it.release() }
        players.clear()
    }
}
